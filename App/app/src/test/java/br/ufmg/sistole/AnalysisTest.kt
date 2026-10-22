package br.ufmg.sistole

import br.ufmg.sistole.data.SessionAnalysis
import br.ufmg.sistole.data.SessionEvent
import br.ufmg.sistole.data.SessionMeta
import br.ufmg.sistole.dsp.Calibration
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.dsp.SyntheticEcg
import br.ufmg.sistole.engine.EventType
import br.ufmg.sistole.export.ExportFormat
import br.ufmg.sistole.export.ExportRequest
import br.ufmg.sistole.export.Exporters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnalysisTest {
    private val cal = Calibration(1000.0, 1.65, 3.3, 12)

    private fun session(seconds: Double = 60.0, bpm: Double = 75.0): SessionAnalysis {
        val fs = 500.0
        val ecg = SyntheticEcg(bpm = bpm, noise = 0.02)
        val n = (seconds * fs).toInt()
        val adc = ShortArray(n) { cal.toAdc(ecg.value(it / fs)).toShort() }
        val meta = SessionMeta(
            id = "20260926-120000", title = "Repouso sentado", volunteer = "Teste", lead = "DII",
            startedAt = 1_790_000_000_000, durationS = seconds, fs = fs, calibration = cal,
            filters = FilterConfig(0.5, 40.0, true), transport = "DEMO", device = "Demo", sampleCount = n.toLong(),
            events = listOf(SessionEvent(12.5, EventType.USER, "Tosse"), SessionEvent(30.2, EventType.AUTO, "Subida de FC")),
            goodSeconds = 55, totalSeconds = 60,
        )
        return SessionAnalysis(meta, adc)
    }

    @Test
    fun analysisFindsHrAndHrv() {
        val a = session()
        val s = a.summary(55, 60)
        assertTrue("FC média ${s.hrMean}", s.hrMean in 73..77)
        assertTrue("batimentos ${s.beats}", s.beats in 70..76)
        assertNotNull(a.stats)
        assertEquals(92, s.qualityPct)
    }

    @Test
    fun averageBeatHasRPeakAtAlignment() {
        val a = session()
        val avg = a.avgBeat!!
        val rIdx = (a.avgBeatPreS * a.fs).toInt()
        val peak = avg.indices.maxBy { avg[it] }
        assertTrue("pico em $peak, esperado ~$rIdx", kotlin.math.abs(peak - rIdx) <= 5)
        assertTrue("amplitude ${avg[peak]}", avg[peak] in 0.8f..1.4f)
        assertTrue(a.avgBeatCount > 50)
    }

    @Test
    fun previewHasPoints() {
        val p = session(10.0).preview()
        assertEquals(600, p.size)
    }

    @Test
    fun edfHeaderAndRecordSizes() {
        val a = session(10.0)
        val dir = Files.createTempDirectory("edf").toFile()
        val f = Exporters.export(a, ExportRequest(ExportFormat.EDF, 0.0, 10.0, includeRaw = true), dir)
        val bytes = f.readBytes()
        val header = String(bytes, 0, 256, Charsets.US_ASCII)
        assertEquals("0", header.substring(0, 8).trim())
        val ns = header.substring(252, 256).trim().toInt()
        assertEquals(3, ns)
        val hdrLen = header.substring(184, 192).trim().toInt()
        assertEquals(256 * (ns + 1), hdrLen)
        assertEquals("EDF+C", header.substring(192, 197))
        val records = header.substring(236, 244).trim().toInt()
        assertEquals(10, records)
        // cada registro: 500 + 500 amostras int16 + 120 bytes de anotação
        assertEquals(hdrLen + records * (500 * 2 + 500 * 2 + 120), bytes.size)
        dir.deleteRecursively()
    }

    @Test
    fun csvHasHeaderAndRows() {
        val a = session(4.0)
        val dir = Files.createTempDirectory("csv").toFile()
        val f: File = Exporters.export(a, ExportRequest(ExportFormat.CSV, 1.0, 3.0, includeRaw = false), dir)
        val lines = f.readLines()
        assertTrue(lines.first().startsWith("# Sístole"))
        val header = lines.indexOf("tempo_s,mv_filtrado")
        assertTrue(header > 0)
        assertEquals(1000, lines.size - header - 1)
        assertTrue(lines[header + 1].startsWith("1.0000,"))
        dir.deleteRecursively()
    }
}
