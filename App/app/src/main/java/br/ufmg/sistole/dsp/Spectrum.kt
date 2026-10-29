package br.ufmg.sistole.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** FFT radix-2 in-place e densidade espectral por Welch simplificado (janelas de Hann com 50 % de sobreposição). */
object Spectrum {
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Espectro de potência em dB (relativo a 1 mV²) de [x] com [n] amostras.
     * Retorna [segment]/2 + 1 pontos de 0 a fs/2.
     */
    fun welchDb(x: FloatArray, n: Int, segment: Int = 512): FloatArray {
        val seg = segment
        val out = DoubleArray(seg / 2 + 1)
        val window = DoubleArray(seg) { 0.5 - 0.5 * cos(2 * PI * it / (seg - 1)) }
        val wPow = window.sumOf { it * it }
        var count = 0
        var start = 0
        val re = DoubleArray(seg)
        val im = DoubleArray(seg)
        while (start + seg <= n) {
            var mean = 0.0
            for (i in 0 until seg) mean += x[start + i]
            mean /= seg
            for (i in 0 until seg) { re[i] = (x[start + i] - mean) * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in out.indices) out[k] += (re[k] * re[k] + im[k] * im[k]) / wPow
            count++
            start += seg / 2
        }
        return FloatArray(out.size) { k ->
            val p = if (count == 0) 0.0 else out[k] / count
            (10 * log10(max(p, 1e-12))).toFloat()
        }
    }
}
