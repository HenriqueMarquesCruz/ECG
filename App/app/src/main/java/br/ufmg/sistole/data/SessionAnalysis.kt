package br.ufmg.sistole.data

import br.ufmg.sistole.dsp.FilterChain
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.dsp.Hrv
import br.ufmg.sistole.dsp.HrvStats
import br.ufmg.sistole.dsp.QrsDetector
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reprocessa uma sessão gravada: filtra o bruto, detecta os batimentos e calcula as estatísticas. */
class SessionAnalysis(val meta: SessionMeta, val adc: ShortArray, val filters: FilterConfig = meta.filters) {
    val fs = meta.fs
    val n = adc.size
    val mv = FloatArray(n)
    val filtered = FloatArray(n)
    val beats: LongArray
    val rr: List<Double>
    val hrPerSecond: FloatArray
    val stats: HrvStats?
    val avgBeat: FloatArray?
    val avgBeatCount: Int
    val avgBeatPreS = 0.30
    val avgBeatPostS = 0.45

    val durationS get() = n / fs

    init {
        val cal = meta.calibration
        val chain = FilterChain(fs, filters)
        val found = ArrayList<Long>()
        val qrs = QrsDetector(fs) { found += it }
        for (i in 0 until n) {
            val a = adc[i].toInt() and 0xFFFF
            val v = cal.toMv(a)
            mv[i] = v.toFloat()
            filtered[i] = chain.process(v).toFloat()
            qrs.process(v)
        }
        beats = found.toLongArray()
        rr = beats.toList().zipWithNext { a, b -> (b - a) / fs * 1000.0 }
        stats = Hrv.stats(rr)

        val secs = ceil(durationS).toInt().coerceAtLeast(1)
        hrPerSecond = FloatArray(secs) { Float.NaN }
        for (s in 0 until secs) {
            val to = ((s + 1) * fs).toLong()
            val from = to - (5 * fs).toLong()
            val local = ArrayList<Double>()
            for (k in 1 until beats.size) if (beats[k] in from until to) {
                val r = (beats[k] - beats[k - 1]) / fs * 1000.0
                if (r in 300.0..2000.0) local += r
            }
            if (local.isNotEmpty()) hrPerSecond[s] = (60000.0 / local.average()).toFloat()
        }

        // Batimento médio: janela −250 ms a +450 ms em torno do R, só batimentos com RR normal.
        val pre = (avgBeatPreS * fs).toInt()
        val post = (avgBeatPostS * fs).toInt()
        val acc = DoubleArray(pre + post)
        var count = 0
        val normal = Hrv.normalRr(rr).toHashSet()
        for (k in 1 until beats.size - 1) {
            val rPrev = (beats[k] - beats[k - 1]) / fs * 1000.0
            if (rPrev !in normal) continue
            val c = beats[k].toInt()
            if (c - pre < 0 || c + post > n) continue
            // linha de base no segmento PR (−90 a −60 ms), entre o fim da P e o início do QRS
            var base = 0.0
            val b0 = (0.09 * fs).toInt()
            val b1 = max(1, (0.06 * fs).toInt())
            for (j in c - b0 until c - b1) base += filtered[j]
            base /= max(1, b0 - b1)
            for (j in 0 until pre + post) acc[j] += filtered[c - pre + j] - base
            count++
        }
        avgBeatCount = count
        avgBeat = if (count >= 3) FloatArray(pre + post) { (acc[it] / count).toFloat() } else null
    }

    fun summary(goodSeconds: Int, totalSeconds: Int): SessionSummary {
        val valid = hrPerSecond.filter { !it.isNaN() }
        return SessionSummary(
            hrMean = stats?.meanHr?.roundToInt() ?: valid.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0,
            hrMin = valid.minOrNull()?.roundToInt() ?: 0,
            hrMax = valid.maxOrNull()?.roundToInt() ?: 0,
            beats = beats.size,
            sdnn = stats?.sdnn,
            rmssd = stats?.rmssd,
            pnn50 = stats?.pnn50,
            qualityPct = if (totalSeconds > 0) (goodSeconds * 100.0 / totalSeconds).roundToInt() else 0,
        )
    }

    /** 3 s do traçado filtrado, a partir de um trecho com batimentos regulares, reduzido para a lista de sessões. */
    fun preview(points: Int = 600): FloatArray {
        val len = min(n, (3 * fs).toInt())
        if (len <= 0) return FloatArray(0)
        var start = 0
        if (beats.size > 4) start = (beats[min(2, beats.size - 1)] - (0.4 * fs).toLong()).toInt().coerceIn(0, n - len)
        val out = FloatArray(min(points, len))
        val step = len.toDouble() / out.size
        for (k in out.indices) {
            val a = start + (k * step).toInt()
            val b = min(start + ((k + 1) * step).toInt(), n)
            var best = filtered[a]
            for (i in a until b) if (abs(filtered[i]) > abs(best)) best = filtered[i]
            out[k] = best
        }
        return out
    }

    /** Pares (RRₙ, RRₙ₊₁) normais para o gráfico de Poincaré. */
    fun poincare(): List<Pair<Float, Float>> {
        val nr = Hrv.normalRr(rr)
        return nr.zipWithNext { a, b -> a.toFloat() to b.toFloat() }
    }

    fun beatsIn(fromS: Double, toS: Double): List<Long> {
        val a = (fromS * fs).toLong()
        val b = (toS * fs).toLong()
        return beats.filter { it in a until b }
    }

    /** Desvio-padrão residual do batimento médio no trecho isoelétrico inicial, em µV. */
    fun avgBeatNoiseUv(): Double? {
        val b = avgBeat ?: return null
        val m = (0.06 * fs).toInt().coerceAtMost(b.size)
        if (m < 3) return null
        val mean = (0 until m).sumOf { b[it].toDouble() } / m
        return sqrt((0 until m).sumOf { (b[it] - mean) * (b[it] - mean) } / m) * 1000
    }
}
