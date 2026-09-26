package br.ufmg.sistole

import br.ufmg.sistole.dsp.Biquad
import br.ufmg.sistole.dsp.Calibration
import br.ufmg.sistole.dsp.FilterChain
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.dsp.Hrv
import br.ufmg.sistole.dsp.QrsDetector
import br.ufmg.sistole.dsp.Spectrum
import br.ufmg.sistole.dsp.SyntheticEcg
import br.ufmg.sistole.dsp.goertzelPower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {
    private fun detect(bpm: Double, fs: Double, seconds: Double, noise: Double = 0.012, mains: Double = 0.0, scale: Double = 1.0): Pair<List<Long>, SyntheticEcg> {
        val ecg = SyntheticEcg(bpm = bpm, noise = noise, mainsMv = mains)
        val beats = mutableListOf<Long>()
        val det = QrsDetector(fs) { beats += it }
        val n = (seconds * fs).toInt()
        for (i in 0 until n) det.process(ecg.value(i / fs) * scale + 0.2)
        return beats to ecg
    }

    @Test
    fun qrsDetectsEveryBeatAt72bpm() {
        val fs = 500.0
        val (beats, _) = detect(72.0, fs, 60.0)
        // 2 s de aprendizado: espera ~69 batimentos depois disso
        assertTrue("batimentos=${beats.size}", beats.size in 66..71)
        val rr = beats.zipWithNext { a, b -> (b - a) / fs * 1000 }
        val hr = 60000 / rr.average()
        assertEquals(72.0, hr, 2.0)
    }

    @Test
    fun qrsPeakLandsOnR() {
        val fs = 500.0
        val ecg = SyntheticEcg(bpm = 60.0, noise = 0.0)
        val beats = mutableListOf<Long>()
        val det = QrsDetector(fs) { beats += it }
        for (i in 0 until (20 * fs).toInt()) det.process(ecg.value(i / fs))
        val truth = ecg.beatTimes()
        for (b in beats) {
            val t = b / fs
            val nearest = truth.minBy { abs(it - t) }
            assertTrue("erro ${(t - nearest) * 1000} ms", abs(t - nearest) < 0.02)
        }
        assertTrue(beats.size >= 17)
    }

    @Test
    fun qrsWorksWithNoiseMainsAndSmallAmplitude() {
        for ((bpm, scale) in listOf(50.0 to 0.3, 140.0 to 1.0, 100.0 to 2.5)) {
            val fs = 500.0
            val (beats, _) = detect(bpm, fs, 40.0, noise = 0.05, mains = 0.2, scale = scale)
            val expected = (38.0 * bpm / 60).toInt()
            assertTrue("bpm=$bpm scale=$scale: ${beats.size} vs $expected", abs(beats.size - expected) <= 3)
        }
    }

    @Test
    fun qrsAt250Hz() {
        val (beats, _) = detect(80.0, 250.0, 30.0)
        assertTrue("${beats.size}", abs(beats.size - 37) <= 2)
    }

    @Test
    fun notchRemoves60Hz() {
        val fs = 500.0
        val f = Biquad.notch(fs, 60.0)
        val n = 5000
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = f.process(sin(2 * PI * 60 * i / fs)).toFloat()
        val tail = out.copyOfRange(2000, n)
        assertTrue(goertzelPower(tail, tail.size, fs, 60.0) < 1e-3)
    }

    @Test
    fun highPassRemovesDcWithoutTransient() {
        val chain = FilterChain(500.0, FilterConfig(0.5, 40.0, true))
        var maxAbs = 0.0
        for (i in 0 until 2000) maxAbs = maxOf(maxAbs, abs(chain.process(3.0)))
        assertTrue("max=$maxAbs", maxAbs < 1e-6)
    }

    @Test
    fun calibrationRoundTrip() {
        val c = Calibration(1000.0, 1.65, 3.3, 12)
        val adc = c.toAdc(1.0)
        assertEquals(1.0, c.toMv(adc), 0.002)
        assertEquals(1240.9, c.countsPerMv, 0.1)
    }

    @Test
    fun hrvOfConstantRrIsZero() {
        val s = Hrv.stats(List(50) { 800.0 })!!
        assertEquals(75.0, s.meanHr, 1e-6)
        assertEquals(0.0, s.rmssd, 1e-9)
        assertEquals(0.0, s.sdnn, 1e-9)
    }

    @Test
    fun hrvRejectsEctopic() {
        val rr = MutableList(20) { 800.0 }
        rr[10] = 400.0
        assertEquals(19, Hrv.normalRr(rr).size)
    }

    @Test
    fun spectrumFindsPeak() {
        val fs = 500.0
        val n = 4096
        val x = FloatArray(n) { (sin(2 * PI * 60 * it / fs)).toFloat() }
        val db = Spectrum.welchDb(x, n, 512)
        val peak = db.indices.maxBy { db[it] }
        val hz = peak * fs / 512
        assertEquals(60.0, hz, fs / 512)
    }
}
