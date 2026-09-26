package br.ufmg.sistole.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Detector de QRS em tempo real inspirado em Pan & Tompkins (1985):
 * passa-faixa 5–15 Hz → derivada de 5 pontos → quadrado → integração em janela de 150 ms
 * → limiares adaptativos (SPKI/NPKI), período refratário e busca retroativa.
 *
 * Recebe amostras em mV (sem filtro) e chama [onBeat] com o índice absoluto da amostra do pico R.
 * A decisão sai com ~200 ms de atraso, o pico é localizado olhando para trás.
 */
class QrsDetector(private val fs: Double, private val onBeat: (rIndex: Long) -> Unit) {
    private val hp = Biquad.highPass(fs, 5.0)
    private val lp = Biquad.lowPass(fs, 15.0)
    private val shape = FilterChain(fs, FilterConfig(0.5, 40.0, notch = true))

    private val winLen = max(1, (0.150 * fs).roundToInt())
    private val win = DoubleArray(winLen)
    private var winSum = 0.0
    private var winPos = 0

    private val der = DoubleArray(5)
    private val histLen = (4.0 * fs).toInt()
    private val hist = FloatArray(histLen)

    private var n = 0L
    private val refractory = (0.20 * fs).toLong()
    private val learnLen = (2.0 * fs).toLong()

    private var spki = 0.0
    private var npki = 0.0
    private var learnMax = 0.0
    private var learnSum = 0.0
    private var learning = true

    private var inPulse = false
    private var candVal = 0.0
    private var candIdx = 0L

    private var prev1 = 0.0
    private var prev2 = 0.0

    private var lastQrs = -1_000_000L
    private var rrAvg = 0.8 * fs
    private var noiseMax = 0.0
    private var noiseMaxIdx = 0L

    private val thr1 get() = npki + 0.25 * (spki - npki)

    fun process(mv: Double) {
        val shaped = shape.process(mv)
        hist[(n % histLen).toInt()] = shaped.toFloat()

        val bp = lp.process(hp.process(mv))
        der[4] = der[3]; der[3] = der[2]; der[2] = der[1]; der[1] = der[0]; der[0] = bp
        val d = (2 * der[0] + der[1] - der[3] - 2 * der[4]) * fs / 8.0
        val sq = d * d
        winSum += sq - win[winPos]
        win[winPos] = sq
        winPos = (winPos + 1) % winLen
        val mwi = winSum / winLen

        if (learning) {
            if (n > (0.3 * fs)) { learnMax = max(learnMax, mwi); learnSum += mwi }
            if (n >= learnLen) {
                spki = 0.25 * learnMax
                npki = 0.5 * learnSum / (learnLen - 0.3 * fs)
                learning = false
            }
        } else {
            step(mwi)
        }
        prev2 = prev1; prev1 = mwi
        n++
    }

    private fun step(mwi: Double) {
        if (!inPulse) {
            if (mwi > thr1 && n - lastQrs > refractory) {
                inPulse = true; candVal = mwi; candIdx = n
            } else if (prev1 > prev2 && prev1 >= mwi) {
                // pico que não passou no limiar: ruído
                npki = 0.125 * prev1 + 0.875 * npki
                if (prev1 > noiseMax && n - lastQrs > refractory) { noiseMax = prev1; noiseMaxIdx = n - 1 }
            }
            // busca retroativa: nenhum QRS há 1,66 × RR médio
            if (lastQrs > 0 && n - lastQrs > 1.66 * rrAvg && noiseMax > 0.5 * thr1) {
                spki = 0.25 * noiseMax + 0.75 * spki
                accept(noiseMaxIdx)
            }
            // sem batimentos há 3 s: amplitude mudou, reduz o limiar
            if (n - lastQrs > 3 * fs && n % (fs.toLong()) == 0L) spki *= 0.5
        } else {
            if (mwi > candVal) { candVal = mwi; candIdx = n }
            if (mwi < 0.5 * candVal) {
                spki = 0.125 * candVal + 0.875 * spki
                inPulse = false
                accept(candIdx)
            }
        }
    }

    private fun accept(mwiIdx: Long) {
        // O pico R fica ~50–250 ms antes do pico da integral. Procura o maior desvio no sinal filtrado.
        val from = max(mwiIdx - (0.25 * fs).toLong(), max(0L, n - histLen + 1))
        val to = mwiIdx.coerceAtMost(n)
        var best = from
        var bestV = -1.0
        var mean = 0.0
        var cnt = 0
        for (i in from..to) { mean += hist[(i % histLen).toInt()]; cnt++ }
        mean /= max(1, cnt)
        for (i in from..to) {
            val v = abs(hist[(i % histLen).toInt()] - mean)
            if (v > bestV) { bestV = v; best = i }
        }
        noiseMax = 0.0
        if (to < from || (lastQrs > 0 && best - lastQrs < refractory)) return
        if (lastQrs > 0) {
            val rr = (best - lastQrs).toDouble()
            if (rr in 0.25 * fs..2.0 * fs) rrAvg = 0.875 * rrAvg + 0.125 * rr
        }
        lastQrs = best
        onBeat(best)
    }
}
