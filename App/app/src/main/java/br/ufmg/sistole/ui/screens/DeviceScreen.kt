package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.Routes
import br.ufmg.sistole.data.Electrode
import br.ufmg.sistole.data.ElectrodeStandard
import br.ufmg.sistole.data.Lead
import br.ufmg.sistole.data.Transport
import br.ufmg.sistole.dsp.Spectrum
import br.ufmg.sistole.navigateTab
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.BannerLevel
import br.ufmg.sistole.ui.components.BtnVariant
import br.ufmg.sistole.ui.components.ChipTone
import br.ufmg.sistole.ui.components.ElectrodeBadge
import br.ufmg.sistole.ui.components.Eyebrow
import br.ufmg.sistole.ui.components.RawAdcChart
import br.ufmg.sistole.ui.components.RowDivider
import br.ufmg.sistole.ui.components.SBanner
import br.ufmg.sistole.ui.components.SButton
import br.ufmg.sistole.ui.components.SCard
import br.ufmg.sistole.ui.components.SChip
import br.ufmg.sistole.ui.components.SList
import br.ufmg.sistole.ui.components.SRow
import br.ufmg.sistole.ui.components.SSegmented
import br.ufmg.sistole.ui.components.SectionLabel
import br.ufmg.sistole.ui.components.SpectrumChart
import br.ufmg.sistole.ui.components.StatTile
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.components.Tab
import br.ufmg.sistole.ui.components.TraceBox
import br.ufmg.sistole.ui.components.electrodeColors
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeviceScreen(nav: NavHostController) {
    val status by app.connection.status.collectAsStateWithLifecycle()
    val diag by app.engine.diag.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        AppBar(eyebrow = "Dispositivo", title = if (page == 0) "Eletrodos" else "Diagnóstico") {
            ConnectionChip(status, onClick = { nav.navigate(Routes.CONNECT) }, rssi = diag.rssi)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SSegmented(listOf("Eletrodos", "Diagnóstico"), page, { page = it }, icons = listOf(SIcons.person, SIcons.terminal))
            if (page == 0) ElectrodesPane(nav) else DiagnosticsPane()
        }
    }
}

// ------------------------------------------------------------------ Eletrodos

private enum class Contact { OK, OFF, UNKNOWN, UNUSED, REFERENCE, NODATA }

@Composable
private fun ElectrodesPane(nav: NavHostController) {
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val vitals by app.engine.vitals.collectAsStateWithLifecycle()
    val lead = settings.lead
    val aha = settings.electrodeStandard == ElectrodeStandard.AHA

    fun contact(e: Electrode): Contact = when {
        e == Electrode.N -> Contact.REFERENCE
        e != lead.plus && e != lead.minus -> Contact.UNUSED
        !vitals.receiving -> Contact.NODATA
        !vitals.loReported -> Contact.UNKNOWN
        e == lead.plus && vitals.loFlags and 1 != 0 -> Contact.OFF
        e == lead.minus && vitals.loFlags and 2 != 0 -> Contact.OFF
        else -> Contact.OK
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SSegmented(Lead.entries.map { it.label }, lead.ordinal, { i -> app.settings.update { it.copy(lead = Lead.entries[i]) } }, Modifier.weight(1.4f), mono = true)
        SSegmented(listOf("IEC", "AHA"), if (aha) 1 else 0, { i -> app.settings.update { it.copy(electrodeStandard = ElectrodeStandard.entries[i]) } }, Modifier.weight(1f), mono = true)
    }

    SCard(padding = 8.dp) {
        Einthoven(lead, aha) { contact(it) }
    }

    SList {
        Electrode.entries.forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            val role = when (e) {
                lead.plus -> "IN+ · ${e.hint}"
                lead.minus -> "IN− · ${e.hint}"
                Electrode.N -> "Referência (perna direita / RLD)"
                else -> "Não usado em ${lead.label}"
            }
            SRow(e.limb, detail = role, leadContent = { ElectrodeBadge(e.letter, aha) }) {
                when (contact(e)) {
                    Contact.OK -> SChip("Contato", tone = ChipTone.OK, icon = SIcons.check)
                    Contact.OFF -> SChip("Solto", tone = ChipTone.ALERT, icon = SIcons.close)
                    Contact.UNKNOWN -> SChip("Não informado", tone = ChipTone.IDLE)
                    Contact.NODATA -> SChip("Sem dados", tone = ChipTone.IDLE)
                    Contact.UNUSED -> SChip("Não usado", tone = ChipTone.PLAIN)
                    Contact.REFERENCE -> SChip("Referência", tone = ChipTone.PLAIN)
                }
            }
        }
    }
    if (vitals.receiving && !vitals.loReported) {
        SBanner(BannerLevel.INFO, "O firmware não informa lead-off (LO+/LO−). O app usa a saturação do ADC e o sinal plano como indícios de eletrodo solto.")
    }
    SBanner(BannerLevel.INFO, "Limpe a pele com álcool e espere secar. Gel velho aumenta o ruído: troque o eletrodo se ele não colar firme.")
    val ready = vitals.receiving && vitals.loFlags == 0
    SButton("Ir para o monitor", { nav.navigateTab(Tab.MONITOR) }, Modifier.fillMaxWidth(), enabled = ready)
    if (!vitals.receiving) Sub("Conecte o aparelho para checar o contato.", Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Einthoven(lead: Lead, aha: Boolean, contact: (Electrode) -> Contact) {
    val c = S.c
    val body = remember {
        PathParser().parsePathString(
            "M170 18c18 0 30 13 30 30s-12 30-30 30-30-13-30-30 12-30 30-30zM118 92h104c20 0 34 14 36 34l10 70h-26l-8-58-8 118h-38l-10-86-10 86h-38l-8-118-8 58H72l10-70c2-20 16-34 36-34z",
        ).toPath()
    }
    val pos = mapOf(Electrode.R to Offset(70f, 104f), Electrode.L to Offset(270f, 104f), Electrode.F to Offset(186f, 252f), Electrode.N to Offset(152f, 252f))
    val edges = listOf(Triple(Lead.DI, Electrode.R, Electrode.L), Triple(Lead.DII, Electrode.R, Electrode.F), Triple(Lead.DIII, Electrode.L, Electrode.F))
    Canvas(Modifier.fillMaxWidth().aspectRatio(340f / 270f)) {
        val s = size.width / 340f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(body, c.surfaceRaised)
            drawPath(body, c.line, style = Stroke(1f))
            edges.forEach { (l, a, b) ->
                val measured = l == lead
                val ok = measured && contact(a) != Contact.OFF && contact(b) != Contact.OFF
                val col = when { !measured -> c.lineStrong; ok -> c.statusOk; else -> c.statusAlert }
                drawLine(
                    col, pos[a]!!, pos[b]!!, strokeWidth = if (measured) 3f else 1.5f,
                    pathEffect = if (measured && !ok) PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) else if (!measured) PathEffect.dashPathEffect(floatArrayOf(2f, 4f)) else null,
                )
            }
            Electrode.entries.forEach { e ->
                val (fill, _) = electrodeColors(e.letter, aha)
                val p = pos[e]!!
                val r = if (e == Electrode.N) 10f else 15f
                drawCircle(fill, r, p)
                val ct = contact(e)
                val ring = when (ct) { Contact.OK -> c.statusOk; Contact.OFF -> c.statusAlert; else -> c.lineStrong }
                drawCircle(ring, r, p, style = Stroke(if (ct == Contact.OK || ct == Contact.OFF) 3f else 2f, pathEffect = if (ct == Contact.OFF) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null))
            }
        }
        // Rótulos em texto nativo, na escala da tela
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            isFakeBoldText = true
        }
        fun text(t: String, x: Float, y: Float, color: Color, sizePx: Float, align: android.graphics.Paint.Align = android.graphics.Paint.Align.CENTER) {
            paint.color = color.toArgb(); paint.textSize = sizePx * s; paint.textAlign = align
            drawContext.canvas.nativeCanvas.drawText(t, x * s, y * s, paint)
        }
        text("DI", 170f, 96f, if (lead == Lead.DI) c.ink else c.inkMuted, 13f)
        text("DII", 106f, 190f, if (lead == Lead.DII) c.ink else c.inkMuted, 13f)
        text("DIII", 248f, 190f, if (lead == Lead.DIII) c.ink else c.inkMuted, 13f)
        Electrode.entries.filter { it != Electrode.N }.forEach { e ->
            val (_, fg) = electrodeColors(e.letter, aha)
            text(e.letter, pos[e]!!.x, pos[e]!!.y + 5f, fg, 13f)
        }
        text("N ref.", 130f, 256f, c.inkMuted, 12f, android.graphics.Paint.Align.RIGHT)
    }
}

// ------------------------------------------------------------------ Diagnóstico

@Composable
private fun DiagnosticsPane() {
    val c = S.c
    val ctx = LocalContext.current
    val engine = app.engine
    val d by engine.diag.collectAsStateWithLifecycle()
    val status by app.connection.status.collectAsStateWithLifecycle()
    val raw = remember { FloatArray(1000) }
    var rawCount by remember { mutableStateOf(0) }
    var spec by remember { mutableStateOf<Pair<FloatArray, FloatArray>?>(null) }
    var mains by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val fs = engine.fs
            rawCount = engine.copyRawAdc((2 * fs).toInt().coerceAtMost(raw.size), raw)
            withContext(Dispatchers.Default) {
                val n = 4096
                val a = FloatArray(n)
                val b = FloatArray(n)
                val m = engine.copyRawMv(n, a)
                engine.copyFilteredTail(n, b)
                spec = if (m >= 1024) Spectrum.welchDb(a, m, 512) to Spectrum.welchDb(b, m, 512) else null
                mains = engine.mainsDb()
            }
            delay(1000)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val fsOk = d.fsTarget > 0 && d.fsMeasured > 0 && kotlin.math.abs(d.fsMeasured - d.fsTarget) / d.fsTarget < 0.02
        StatTile("Taxa medida", if (d.fsMeasured > 0) Fmt.dec(d.fsMeasured, 1) else "– –", Modifier.weight(1f), unit = "Hz", sub = "alvo ${Fmt.dec(d.fsTarget, 0)} Hz", valueColor = if (d.fsMeasured > 0 && !fsOk) c.statusWarn else c.ink)
        val lostPct = if (d.totalSamples > 0) d.lostSamples * 100.0 / d.totalSamples else 0.0
        StatTile("Amostras perdidas", Fmt.dec(lostPct, 2), Modifier.weight(1f), unit = "%", sub = "${Fmt.int(d.lostSamples)} de ${Fmt.int(d.totalSamples)}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Entre pacotes", if (d.packetIntervalMs > 0) Fmt.dec(d.packetIntervalMs, 1) else "– –", Modifier.weight(1f), unit = "ms", sub = if (d.packetsPerSecond > 0) "${Fmt.dec(d.packetsPerSecond, 0)} pacotes/s" else "sem pacotes")
        StatTile("Bateria ESP", d.batteryMv?.let { Fmt.dec(it / 1000.0, 2) } ?: "– –", Modifier.weight(1f), unit = "V", sub = if (d.batteryMv == null) "não informada" else null) {
            d.batteryMv?.let { mv ->
                val frac = ((mv - 3300) / 900f).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceRaised)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (frac < 0.2f) c.statusWarn else c.statusOk))
                }
            }
        }
    }

    SCard(padding = 12.dp) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("ADC bruto · ${engine.calibration.bits} bits")
            Eyebrow("0 – ${engine.calibration.fullScale}")
        }
        TraceBox(Modifier.fillMaxWidth().height(96.dp), radius = 12.dp) {
            RawAdcChart(raw, rawCount, engine.calibration.fullScale)
            TraceCorner(Alignment.BottomEnd) { OverlayText("sem filtro · saturação ${Fmt.dec(d.saturationPct, 0)}%") }
            TraceCorner(Alignment.TopStart) { OverlayText("último ${d.lastAdc}") }
        }
    }

    SCard(padding = 12.dp) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("Espectro · 0–${Fmt.dec(engine.fs / 2, 0)} Hz")
            Eyebrow("dB re 1 mV²")
        }
        TraceBox(Modifier.fillMaxWidth().height(130.dp), radius = 12.dp) {
            SpectrumChart(spec?.first ?: FloatArray(0), engine.fs, second = spec?.second)
            if (spec == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Sub("Aguardando 2 s de sinal") }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(c.statusOk, "Bruto (após o front-end)")
            Legend(c.brand, "Após filtros do app")
        }
        mains?.let { (a, b) ->
            Text(
                "60 Hz: ${Fmt.dec(a, 0)} dB bruto · ${Fmt.dec(b, 0)} dB filtrado · atenuação ${Fmt.dec(a - b, 0)} dB",
                style = SType.dataSm, color = c.inkMuted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Sub("Picos em 60 e 120 Hz mostram quanto ruído de rede passa pelos notches analógicos.", Modifier.padding(top = 4.dp))
    }

    SList {
        SRow("Lead-off LO+ / LO−") {
            Text(if (d.loReported) "${d.loFlags and 1} / ${(d.loFlags shr 1) and 1}" else "não informado", style = SType.data, color = c.inkMuted)
        }
        RowDivider()
        SRow("Firmware") { Text(d.firmware ?: "– –", style = SType.data, color = c.inkMuted) }
        RowDivider()
        SRow("Protocolo") {
            val proto = when (status.transport) {
                Transport.BLE -> "BLE notify · MTU ${app.connection.bleMtu ?: "–"}"
                Transport.USB -> "Serial ${app.settings.value.baudRate} · ${if (app.settings.value.serialBinary) "binário" else "CSV"}"
                Transport.DEMO -> "Sintético no app"
                null -> "– –"
            }
            Text(proto, style = SType.data, color = c.inkMuted)
        }
        RowDivider()
        SRow("Conversão") {
            val cal = engine.calibration
            Text("1 mV = ${Fmt.dec(cal.countsPerMv, 0)} contagens", style = SType.data, color = c.inkMuted)
        }
        if (d.rssi != null) {
            RowDivider()
            SRow("RSSI") { Text("${d.rssi} dBm", style = SType.data, color = c.inkMuted) }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("Console", Modifier.weight(1f))
        SButton("Salvar log", {
            val lines = engine.consoleLines()
            val dir = app.sessions.exportDir
            val f = File(dir, "sistole-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            f.writeText(lines.joinToString("\n"))
            shareFile(ctx, f, "text/plain", "Log do Sístole")
        }, small = true, variant = BtnVariant.GHOST, icon = SIcons.download)
    }
    Column(
        Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(14.dp)).background(c.paper)
            .padding(12.dp),
    ) {
        if (d.console.isEmpty()) Text("Nada recebido ainda.", style = SType.console, color = c.inkFaint)
        d.console.takeLast(9).forEach { l ->
            Text(l, style = SType.console, color = if (l.startsWith("#") || l.startsWith("info")) c.trace else c.inkMuted, maxLines = 1)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.height(3.dp).aspectRatio(5f)) { drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height) }
        Text(label, style = SType.caption, color = S.c.inkMuted)
    }
}
