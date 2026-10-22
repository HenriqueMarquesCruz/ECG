package br.ufmg.sistole.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import br.ufmg.sistole.data.SessionAnalysis
import br.ufmg.sistole.data.SessionMeta
import br.ufmg.sistole.engine.EventType
import br.ufmg.sistole.ui.fmt.Fmt
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    EDF("edf", "application/octet-stream"),
}

data class ExportRequest(
    val format: ExportFormat,
    val fromS: Double,
    val toS: Double,
    val includeRaw: Boolean,
)

object Exporters {
    fun fileName(meta: SessionMeta, f: ExportFormat): String {
        val base = meta.title.lowercase(Fmt.BR)
            .replace(Regex("[^a-z0-9áàâãéêíóôõúç]+"), "-").trim('-').ifEmpty { "sessao" }
        return "sistole-${meta.id}-$base.${f.ext}"
    }

    fun export(a: SessionAnalysis, req: ExportRequest, dir: File): File {
        dir.mkdirs()
        val out = File(dir, fileName(a.meta, req.format))
        when (req.format) {
            ExportFormat.CSV -> csv(a, req, out)
            ExportFormat.EDF -> edf(a, req, out)
            ExportFormat.PDF -> pdf(a, req, out)
        }
        return out
    }

    private fun range(a: SessionAnalysis, req: ExportRequest): IntRange {
        val from = (req.fromS * a.fs).toInt().coerceIn(0, a.n)
        val to = (req.toS * a.fs).toInt().coerceIn(from, a.n)
        return from until to
    }

    // ------------------------------------------------------------------ CSV

    private fun csv(a: SessionAnalysis, req: ExportRequest, out: File) {
        val r = range(a, req)
        val m = a.meta
        BufferedWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8), 1 shl 16).use { w ->
            w.write("# Sístole · ${m.title}\n")
            w.write("# inicio=${iso(m.startedAt)} fs=${m.fs} derivacao=${m.lead} voluntario=${m.volunteer}\n")
            w.write("# ganho_frontend=${m.calibration.gain} offset_V=${m.calibration.offsetV} vref=${m.calibration.vref} bits=${m.calibration.bits}\n")
            w.write("# filtro_app=${a.filters.label()} · Uso didático. Não é equipamento médico.\n")
            w.write(if (req.includeRaw) "tempo_s,adc,mv_bruto,mv_filtrado\n" else "tempo_s,mv_filtrado\n")
            val sb = StringBuilder(64)
            for (i in r) {
                sb.setLength(0)
                sb.append(String.format(Locale.US, "%.4f", i / a.fs)).append(',')
                if (req.includeRaw) {
                    sb.append(a.adc[i].toInt() and 0xFFFF).append(',')
                    sb.append(String.format(Locale.US, "%.5f", a.mv[i])).append(',')
                }
                sb.append(String.format(Locale.US, "%.5f", a.filtered[i])).append('\n')
                w.write(sb.toString())
            }
        }
    }

    // ------------------------------------------------------------------ EDF+

    /**
     * EDF+C com registros de 1 s: sinal filtrado (µV de resolução), opcionalmente o bruto do ADC,
     * e o canal "EDF Annotations" com os eventos.
     */
    private fun edf(a: SessionAnalysis, req: ExportRequest, out: File) {
        val r = range(a, req)
        val m = a.meta
        val fs = a.fs.roundToInt()
        val records = max(1, (r.last - r.first + 1 + fs - 1) / fs)
        val annBytes = 120
        val signals = mutableListOf<EdfSignal>()
        signals += EdfSignal("ECG ${m.lead}", "AgAgCl electrode", "mV", -10.0, 10.0, -32768, 32767, a.filters.edfLabel(), fs)
        if (req.includeRaw) {
            val cal = m.calibration
            signals += EdfSignal("ECG ${m.lead} bruto", "AgAgCl electrode", "mV", cal.toMv(0), cal.toMv(cal.fullScale), 0, cal.fullScale, "HP:DC LP:160Hz N:60Hz (analogico)", fs)
        }
        signals += EdfSignal("EDF Annotations", "", "", -1.0, 1.0, -32768, 32767, "", annBytes / 2)

        val ns = signals.size
        val start = Date(m.startedAt + (req.fromS * 1000).toLong())
        val hdr = StringBuilder()
        fun f(s: String, len: Int) { hdr.append(s.take(len).padEnd(len)) }
        f("0", 8)
        val volunteer = m.volunteer.ifBlank { "X" }.replace(' ', '_')
        f("X X X $volunteer", 80)
        f("Startdate ${SimpleDateFormat("dd-MMM-yyyy", Locale.US).format(start).uppercase()} X X Sistole", 80)
        f(SimpleDateFormat("dd.MM.yy", Locale.US).format(start), 8)
        f(SimpleDateFormat("HH.mm.ss", Locale.US).format(start), 8)
        f(((ns + 1) * 256).toString(), 8)
        f("EDF+C", 44)
        f(records.toString(), 8)
        f("1", 8)
        f(ns.toString(), 4)
        signals.forEach { f(it.label, 16) }
        signals.forEach { f(it.transducer, 80) }
        signals.forEach { f(it.dim, 8) }
        signals.forEach { f(num(it.physMin), 8) }
        signals.forEach { f(num(it.physMax), 8) }
        signals.forEach { f(it.digMin.toString(), 8) }
        signals.forEach { f(it.digMax.toString(), 8) }
        signals.forEach { f(it.prefilter, 80) }
        signals.forEach { f(it.samplesPerRecord.toString(), 8) }
        signals.forEach { f("", 32) }

        val events = m.events.filter { it.t >= req.fromS && it.t < req.toS }
        FileOutputStream(out).buffered(1 shl 16).use { os ->
            os.write(hdr.toString().toByteArray(Charsets.US_ASCII))
            val rec = ByteBuffer.allocate(signals.sumOf { it.samplesPerRecord } * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until records) {
                rec.clear()
                for (j in 0 until fs) {
                    val i = min(r.first + k * fs + j, max(r.first, r.last))
                    val v = if (a.n == 0) 0f else a.filtered[i]
                    rec.putShort((v / 10.0 * 32767).roundToInt().coerceIn(-32768, 32767).toShort())
                }
                if (req.includeRaw) {
                    for (j in 0 until fs) {
                        val i = min(r.first + k * fs + j, max(r.first, r.last))
                        rec.putShort(if (a.n == 0) 0 else a.adc[i])
                    }
                }
                val tal = StringBuilder("+$k\u0014\u0014\u0000")
                events.filter { ((it.t - req.fromS).toInt()) == k }.forEach { e ->
                    val t = String.format(Locale.US, "+%.3f", e.t - req.fromS)
                    val candidate = "$t\u0014${e.label.replace('\u0014', ' ')}\u0014\u0000"
                    if (tal.toString().toByteArray(Charsets.UTF_8).size + candidate.toByteArray(Charsets.UTF_8).size <= annBytes) tal.append(candidate)
                }
                val bytes = tal.toString().toByteArray(Charsets.UTF_8).copyOf(annBytes)
                rec.put(bytes)
                os.write(rec.array(), 0, rec.position())
            }
        }
    }

    private data class EdfSignal(
        val label: String, val transducer: String, val dim: String,
        val physMin: Double, val physMax: Double, val digMin: Int, val digMax: Int,
        val prefilter: String, val samplesPerRecord: Int,
    )

    private fun num(v: Double): String {
        var s = String.format(Locale.US, "%.4f", v)
        while (s.length > 8 && s.contains('.')) s = s.dropLast(1)
        return s.trimEnd('.')
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(t))

    // ------------------------------------------------------------------ PDF

    private const val MM = 72f / 25.4f
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    private fun pdf(a: SessionAnalysis, req: ExportRequest, out: File) {
        val doc = PdfDocument()
        val m = a.meta
        val left = 15 * MM
        val width = 180 * MM
        val stripSeconds = 180.0 / 25.0 // 7,2 s por tira a 25 mm/s
        val stripH = 30 * MM

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = Color.BLACK }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.SANS_SERIF; color = Color.rgb(40, 40, 40) }
        val small = Paint(body).apply { textSize = 7.5f; color = Color.rgb(90, 90, 90) }
        val mono = Paint(body).apply { typeface = Typeface.MONOSPACE; textSize = 8f }
        val big = Paint(title).apply { textSize = 16f }

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var c = page.canvas
        var y = 15 * MM

        c.drawText(m.title, left, y + 14, title); y += 30
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Fmt.BR).format(Date(m.startedAt))
        c.drawText("$date · ${Fmt.duration(a.durationS)} · ${m.lead}" + (if (m.volunteer.isNotBlank()) " · ${m.volunteer}" else "") + " · ${m.device}", left, y, body); y += 12
        c.drawText("Uso didático. Não é equipamento médico. As detecções automáticas não são diagnóstico.", left, y, small); y += 16

        // Estatísticas
        val s = a.summary(m.goodSeconds, m.totalSeconds)
        val st = a.stats
        val cells = listOf(
            "FC média" to "${s.hrMean} bpm",
            "Mín · máx" to "${s.hrMin}–${s.hrMax}",
            "Batimentos" to Fmt.int(s.beats.toLong()),
            "SDNN" to (st?.sdnn?.let { "${it.roundToInt()} ms" } ?: "– –"),
            "RMSSD" to (st?.rmssd?.let { "${it.roundToInt()} ms" } ?: "– –"),
            "pNN50" to (st?.pnn50?.let { "${Fmt.dec(it, 1)} %" } ?: "– –"),
            "Qualidade" to "${s.qualityPct} %",
        )
        val cw = width / cells.size
        cells.forEachIndexed { i, (k, v) ->
            c.drawText(k.uppercase(), left + i * cw, y, small)
            c.drawText(v, left + i * cw, y + 18, big)
        }
        y += 30
        c.drawText("Filtros digitais: ${a.filters.label()} · Calibração: ganho ${Fmt.dec(m.calibration.gain, 0)} V/V, offset ${Fmt.dec(m.calibration.offsetV, 2)} V, fs ${Fmt.dec(m.fs, 0)} Hz · 25 mm/s · 10 mm/mV", left, y, small)
        y += 14

        // Tendência de FC
        c.drawText("FC AO LONGO DA SESSÃO (bpm)", left, y, small); y += 4
        drawTrend(c, a, left, y, width, 32 * MM, m.events.map { it.t })
        y += 32 * MM + 12

        // Tiras contínuas a partir do início do trecho
        c.drawText("TRAÇADO · 25 MM/S · 10 MM/MV", left, y, small); y += 6
        var t = req.fromS
        repeat(3) {
            if (t >= req.toS) return@repeat
            drawStrip(c, a, t, stripSeconds, left, y, width, stripH, cal = it == 0, events = m.events)
            c.drawText(Fmt.clock(t), left + 2, y + stripH - 3, mono)
            y += stripH + 6
            t += stripSeconds
        }
        y += 4

        // Eventos
        if (m.events.isNotEmpty()) {
            c.drawText("EVENTOS", left, y, small); y += 12
            for (e in m.events.take(10)) {
                val who = when (e.type) { EventType.USER -> "Marcado"; EventType.AUTO -> "Automático"; EventType.ALARM -> "Alarme" }
                c.drawText("${Fmt.clock(e.t)}  $who · ${e.label}" + if (e.detail.isNotBlank()) " · ${e.detail}" else "", left, y, body)
                y += 11
                if (y > PAGE_H - 20 * MM) break
            }
        }
        footer(c, pageNo)
        doc.finishPage(page)

        // Páginas com os trechos de cada evento
        val evs = m.events.filter { it.t in req.fromS..req.toS }.take(12)
        var i = 0
        while (i < evs.size && pageNo < 4) {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            c = page.canvas
            y = 15 * MM
            c.drawText("Trechos dos eventos", left, y + 12, big); y += 26
            while (i < evs.size && y + stripH + 20 < PAGE_H - 20 * MM) {
                val e = evs[i]
                c.drawText("${Fmt.clock(e.t)} · ${e.label}", left, y, body); y += 5
                val t0 = (e.t - stripSeconds / 2).coerceIn(0.0, max(0.0, a.durationS - stripSeconds))
                drawStrip(c, a, t0, stripSeconds, left, y, width, stripH, cal = false, events = m.events)
                y += stripH + 14
                i++
            }
            footer(c, pageNo)
            doc.finishPage(page)
        }

        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    private fun footer(c: Canvas, page: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f; color = Color.rgb(120, 120, 120) }
        c.drawText("Sístole · EEE025 Instrumentação Biomédica · UFMG · página $page", 15 * MM, PAGE_H - 10 * MM, p)
    }

    private fun drawGrid(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val bg = Paint().apply { color = Color.rgb(255, 250, 244) }
        c.drawRect(x, y, x + w, y + h, bg)
        val minor = Paint().apply { color = Color.rgb(246, 225, 218); strokeWidth = 0.3f }
        val major = Paint().apply { color = Color.rgb(233, 184, 171); strokeWidth = 0.6f }
        var k = 0
        var xx = x
        while (xx <= x + w + 0.01f) { c.drawLine(xx, y, xx, y + h, if (k % 5 == 0) major else minor); xx += MM; k++ }
        k = 0
        var yy = y + h
        while (yy >= y - 0.01f) { c.drawLine(x, yy, x + w, yy, if (k % 5 == 0) major else minor); yy -= MM; k++ }
    }

    private fun drawStrip(c: Canvas, a: SessionAnalysis, t0: Double, secs: Double, x: Float, y: Float, w: Float, h: Float, cal: Boolean, events: List<br.ufmg.sistole.data.SessionEvent>) {
        drawGrid(c, x, y, w, h)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 26, 26); strokeWidth = 0.7f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        val y0 = y + h * 0.6f
        val k = 10 * MM // 10 mm/mV
        val path = Path()
        var x0 = x
        if (cal) {
            path.moveTo(x, y0); path.lineTo(x + MM, y0); path.lineTo(x + MM, y0 - k); path.lineTo(x + 6 * MM, y0 - k); path.lineTo(x + 6 * MM, y0); path.lineTo(x + 7 * MM, y0)
            x0 = x + 7 * MM
        }
        val from = (t0 * a.fs).toInt()
        val pxPerSample = (25 * MM / a.fs).toFloat()
        var started = cal
        var i = from + ((x0 - x) / pxPerSample).toInt()
        while (i < a.n) {
            val px = x + (i - from) * pxPerSample
            if (px > x + w) break
            val py = (y0 - a.filtered[i] * k).coerceIn(y, y + h)
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            i++
        }
        c.drawPath(path, ink)
        val ev = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138, 90, 0); strokeWidth = 0.8f; pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f) }
        for (e in events) {
            if (e.t < t0 || e.t > t0 + secs) continue
            val px = x + ((e.t - t0) * 25 * MM).toFloat()
            c.drawLine(px, y, px, y + h, ev)
        }
    }

    private fun drawTrend(c: Canvas, a: SessionAnalysis, x: Float, y: Float, w: Float, h: Float, markers: List<Double>) {
        val frame = Paint().apply { color = Color.rgb(220, 210, 195); style = Paint.Style.STROKE; strokeWidth = 0.5f }
        c.drawRect(x, y, x + w, y + h, frame)
        val hr = a.hrPerSecond
        val valid = hr.filter { !it.isNaN() }
        if (valid.isEmpty()) return
        val lo = (valid.min() - 10).coerceAtLeast(30f)
        val hi = valid.max() + 10
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 6.5f; color = Color.rgb(90, 90, 90) }
        c.drawText("${hi.roundToInt()}", x + 2, y + 8, label)
        c.drawText("${lo.roundToInt()}", x + 2, y + h - 2, label)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 122, 79); strokeWidth = 1.1f; style = Paint.Style.STROKE }
        val p = Path()
        var pen = false
        for (i in hr.indices) {
            val v = hr[i]
            if (v.isNaN()) { pen = false; continue }
            val px = x + w * i / max(1, hr.size - 1)
            val py = y + h * (1 - (v - lo) / (hi - lo))
            if (!pen) { p.moveTo(px, py); pen = true } else p.lineTo(px, py)
        }
        c.drawPath(p, line)
        val mk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138, 90, 0) }
        markers.forEach { t ->
            val px = x + (w * t / max(1.0, a.durationS)).toFloat()
            c.drawCircle(px, y + 3, 1.8f, mk)
        }
    }
}
