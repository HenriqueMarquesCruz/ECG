package br.ufmg.sistole.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * ECG sintético em mV: soma de gaussianas P, Q, R, S, T por batimento, com variabilidade
 * (arritmia sinusal respiratória), deriva de linha de base, ruído e interferência de 60 Hz opcionais.
 * Mesmo modelo dos previews do design system.
 */
class SyntheticEcg(
    var bpm: Double = 72.0,
    private val hrv: Double = 0.05,
    var noise: Double = 0.012,
    var mainsMv: Double = 0.0,
    var wanderMv: Double = 0.035,
    seed: Int = 7,
) {
    private val rnd = Random(seed)
    private val beats = ArrayList<Double>().apply { add(0.3) }

    private fun g(x: Double, a: Double, m: Double, s: Double): Double {
        val d = (x - m) / s
        return a * exp(-0.5 * d * d)
    }

    private fun beat(dt: Double): Double =
        g(dt, 0.12, -0.2, 0.026) + g(dt, -0.11, -0.034, 0.010) + g(dt, 1.15, 0.0, 0.011) +
            g(dt, -0.26, 0.032, 0.012) + g(dt, 0.30, 0.25, 0.048)

    private fun nextRr(i: Int): Double {
        val b = 60.0 / bpm
        return b * (1 + hrv * sin(i * 0.83) + (rnd.nextDouble() - 0.5) * hrv)
    }

    private fun ensure(t: Double) {
        while (beats.last() < t + 2) beats.add(beats.last() + nextRr(beats.size))
        // descarta batimentos antigos para não crescer sem limite
        if (beats.size > 64 && beats[16] < t - 4) repeat(16) { beats.removeAt(0) }
    }

    /** Instantes dos picos R já gerados (s). */
    fun beatTimes(): List<Double> = beats.toList()

    fun value(t: Double): Double {
        ensure(t)
        var lo = 0
        var hi = beats.size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (beats[m] <= t) lo = m else hi = m
        }
        var y = 0.0
        for (k in maxOf(0, lo - 1)..minOf(beats.size - 1, lo + 2)) y += beat(t - beats[k])
        y += wanderMv * sin(t * 0.55)
        y += noise * (rnd.nextDouble() - 0.5) * 2
        if (mainsMv > 0) y += mainsMv * sin(2 * PI * 60.0 * t)
        return y
    }
}
