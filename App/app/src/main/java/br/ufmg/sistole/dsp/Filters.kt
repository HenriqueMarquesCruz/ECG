package br.ufmg.sistole.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Biquad IIR em forma direta I, coeficientes do "Audio EQ Cookbook" (R. Bristow-Johnson). */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    /** Ganho em DC, para iniciar o filtro já em regime com a primeira amostra (evita o transitório). */
    val dcGain: Double get() = (b0 + b1 + b2) / (1 + a1 + a2)

    fun reset(x0: Double) {
        x1 = x0; x2 = x0
        val y = x0 * dcGain
        y1 = y; y2 = y
    }

    companion object {
        private const val BUTTERWORTH_Q = 0.7071067811865476

        fun lowPass(fs: Double, f0: Double, q: Double = BUTTERWORTH_Q): Biquad {
            val w = 2 * PI * f0 / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(fs: Double, f0: Double, q: Double = BUTTERWORTH_Q): Biquad {
            val w = 2 * PI * f0 / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun notch(fs: Double, f0: Double, q: Double = 30.0): Biquad {
            val w = 2 * PI * f0 / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(1 / a0, -2 * c / a0, 1 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * Configuração dos filtros digitais do app. [enabled] = false (ou todos desligados) entrega o sinal
 * exatamente como sai do ADC, para quem já filtra no circuito analógico. Frequência 0 desliga o filtro.
 */
data class FilterConfig(val highPassHz: Double, val lowPassHz: Double, val notch: Boolean, val enabled: Boolean = true) {
    val hasHighPass get() = enabled && highPassHz > 0
    val hasLowPass get() = enabled && lowPassHz > 0
    val hasNotch get() = enabled && notch
    val isBypass get() = !hasHighPass && !hasLowPass && !hasNotch

    fun label(): String {
        if (isBypass) return "Sem filtro digital"
        val band = when {
            hasHighPass && hasLowPass -> "${fmtHz(highPassHz)}–${fmtHz(lowPassHz)} Hz"
            hasHighPass -> "PA ${fmtHz(highPassHz)} Hz"
            hasLowPass -> "PB ${fmtHz(lowPassHz)} Hz"
            else -> null
        }
        return listOfNotNull(band, if (hasNotch) "Notch 60" else null).joinToString(" · ")
    }

    /** Campo "prefiltering" do EDF. */
    fun edfLabel(): String = if (isBypass) "none (app)" else listOfNotNull(
        if (hasHighPass) "HP:${highPassHz}Hz" else null,
        if (hasLowPass) "LP:${lowPassHz}Hz" else null,
        if (hasNotch) "N:60Hz" else null,
    ).joinToString(" ")

    private fun fmtHz(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString().replace('.', ',')
}

/**
 * Cadeia causal: passa-altas (deriva da linha de base) → passa-baixas → notch 60 Hz
 * (e 120 Hz quando o passa-baixas deixa passar essa harmônica).
 */
class FilterChain(fs: Double, cfg: FilterConfig) {
    private val stages: List<Biquad>
    private var primed = false

    init {
        val nyq = fs / 2
        val list = mutableListOf<Biquad>()
        if (cfg.hasHighPass) list += Biquad.highPass(fs, cfg.highPassHz)
        if (cfg.hasLowPass) list += Biquad.lowPass(fs, min(cfg.lowPassHz, nyq * 0.9))
        if (cfg.hasNotch && 60.0 < nyq * 0.95) list += Biquad.notch(fs, 60.0)
        // 120 Hz junto quando nada abaixo de 100 Hz corta essa harmônica
        if (cfg.hasNotch && !(cfg.hasLowPass && cfg.lowPassHz <= 100) && 120.0 < nyq * 0.95) list += Biquad.notch(fs, 120.0)
        stages = list
    }

    fun process(x: Double): Double {
        if (!primed) {
            var v = x
            for (s in stages) { s.reset(v); v *= s.dcGain }
            primed = true
        }
        var y = x
        for (s in stages) y = s.process(y)
        return y
    }
}

/** Conversão ADC → mV no eletrodo a partir do ganho total e do offset do front-end. */
data class Calibration(val gain: Double, val offsetV: Double, val vref: Double, val bits: Int) {
    val fullScale: Int get() = (1 shl bits) - 1

    fun toMv(adc: Int): Double = ((adc.toDouble() / fullScale) * vref - offsetV) / gain * 1000.0

    fun toAdc(mv: Double): Int {
        val v = mv / 1000.0 * gain + offsetV
        return (v / vref * fullScale).toInt().coerceIn(0, fullScale)
    }

    /** Contagens do ADC equivalentes a 1 mV no eletrodo. */
    val countsPerMv: Double get() = gain / 1000.0 / vref * fullScale
}

/** Algoritmo de Goertzel: potência de uma única frequência em um bloco de amostras. */
fun goertzelPower(x: FloatArray, n: Int, fs: Double, f: Double): Double {
    val k = 2 * cos(2 * PI * f / fs)
    var s1 = 0.0
    var s2 = 0.0
    var mean = 0.0
    for (i in 0 until n) mean += x[i]
    mean /= n
    for (i in 0 until n) {
        val s = (x[i] - mean) + k * s1 - s2
        s2 = s1; s1 = s
    }
    val p = s1 * s1 + s2 * s2 - k * s1 * s2
    return p / (n.toDouble() * n) * 2
}

fun rms(x: FloatArray, n: Int): Double {
    if (n == 0) return 0.0
    var mean = 0.0
    for (i in 0 until n) mean += x[i]
    mean /= n
    var acc = 0.0
    for (i in 0 until n) { val d = x[i] - mean; acc += d * d }
    return sqrt(acc / n)
}
