package br.ufmg.sistole.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/** Estatísticas de variabilidade da FC (Task Force ESC/NASPE, 1996) a partir de intervalos RR em ms. */
data class HrvStats(
    val count: Int,
    val meanRr: Double,
    val meanHr: Double,
    val sdnn: Double,
    val rmssd: Double,
    val pnn50: Double,
    val sd1: Double,
    val sd2: Double,
)

object Hrv {
    /** RR plausíveis e sem batimentos ectópicos (variação < 30 % da média local). */
    fun normalRr(rr: List<Double>): List<Double> {
        val plaus = rr.filter { it in 300.0..2000.0 }
        if (plaus.size < 3) return plaus
        val out = ArrayList<Double>(plaus.size)
        for (i in plaus.indices) {
            val lo = maxOf(0, i - 4)
            val hi = minOf(plaus.size - 1, i + 4)
            var s = 0.0
            var c = 0
            for (j in lo..hi) if (j != i) { s += plaus[j]; c++ }
            val local = s / c
            if (abs(plaus[i] - local) < 0.3 * local) out += plaus[i]
        }
        return out
    }

    fun stats(rrAll: List<Double>): HrvStats? {
        val rr = normalRr(rrAll)
        if (rr.size < 2) return null
        val mean = rr.average()
        val sdnn = sqrt(rr.sumOf { (it - mean) * (it - mean) } / (rr.size - 1))
        val diffs = rr.zipWithNext { a, b -> b - a }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val pnn50 = diffs.count { abs(it) > 50 } * 100.0 / diffs.size
        val dMean = diffs.average()
        val varDiff = diffs.sumOf { (it - dMean) * (it - dMean) } / maxOf(1, diffs.size - 1)
        val sd1 = sqrt(varDiff / 2)
        val sd2 = sqrt(maxOf(0.0, 2 * sdnn * sdnn - sd1 * sd1))
        return HrvStats(rr.size, mean, 60000.0 / mean, sdnn, rmssd, pnn50, sd1, sd2)
    }

    fun rmssd(rr: List<Double>): Double? {
        val n = normalRr(rr)
        if (n.size < 3) return null
        val d = n.zipWithNext { a, b -> b - a }
        return sqrt(d.sumOf { it * it } / d.size)
    }
}
