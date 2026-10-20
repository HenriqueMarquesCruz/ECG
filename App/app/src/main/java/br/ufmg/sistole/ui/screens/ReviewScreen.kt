package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import br.ufmg.sistole.data.SessionAnalysis
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.engine.EventType
import br.ufmg.sistole.export.ExportFormat
import br.ufmg.sistole.export.ExportRequest
import br.ufmg.sistole.export.Exporters
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.BannerLevel
import br.ufmg.sistole.ui.components.EcgStrip
import br.ufmg.sistole.ui.components.Eyebrow
import br.ufmg.sistole.ui.components.LineChart
import br.ufmg.sistole.ui.components.OptionCard
import br.ufmg.sistole.ui.components.PoincarePlot
import br.ufmg.sistole.ui.components.RowDivider
import br.ufmg.sistole.ui.components.SBanner
import br.ufmg.sistole.ui.components.SButton
import br.ufmg.sistole.ui.components.SCard
import br.ufmg.sistole.ui.components.SIconButton
import br.ufmg.sistole.ui.components.SList
import br.ufmg.sistole.ui.components.SRow
import br.ufmg.sistole.ui.components.SSegmented
import br.ufmg.sistole.ui.components.SSwitch
import br.ufmg.sistole.ui.components.SectionLabel
import br.ufmg.sistole.ui.components.StatTile
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.components.TraceBox
import br.ufmg.sistole.ui.components.rememberPxPerMm
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface Loaded {
    data object Loading : Loaded
    data class Error(val msg: String) : Loaded
    data class Ok(val a: SessionAnalysis) : Loaded
}

@Composable
fun ReviewScreen(nav: NavHostController, id: String) {
    val meta = remember(id) { app.sessions.get(id) }
    var filterMode by remember { mutableStateOf(0) }
    var samples by remember { mutableStateOf<ShortArray?>(null) }
    val state by produceState<Loaded>(Loaded.Loading, id, filterMode) {
        value = Loaded.Loading
        value = if (meta == null) Loaded.Error("Sessão não encontrada.") else runCatching {
            withContext(Dispatchers.Default) {
                val adc = samples ?: app.sessions.loadSamples(id).also { samples = it }
                val filters = when (filterMode) {
                    1 -> FilterConfig(0.5, 40.0, true, enabled = false)
                    2 -> FilterConfig(0.5, 40.0, true)
                    3 -> FilterConfig(0.05, 150.0, true)
                    else -> meta.filters
                }
                SessionAnalysis(meta, adc, filters)
            }
        }.fold({ Loaded.Ok(it) }, { Loaded.Error(it.message ?: "Falha ao ler a gravação.") })
    }
    var exportOpen by remember { mutableStateOf(false) }
    var windowStart by remember { mutableDoubleStateOf(0.0) }
    var windowLen by remember { mutableDoubleStateOf(4.0) }

    Column(Modifier.fillMaxSize()) {
        AppBar(
            title = meta?.title ?: "Sessão", small = true,
            subtitle = meta?.let { "${Fmt.dayAndTime(it.startedAt)} · ${Fmt.clock(it.durationS)} · ${it.lead}" },
            onBack = { nav.popBackStack() },
        ) {
            if (state is Loaded.Ok) SIconButton(SIcons.share, "Exportar", { exportOpen = true })
        }
        when (val s = state) {
            Loaded.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = S.c.brand)
                    Sub("Reprocessando o sinal…", Modifier.padding(top = 12.dp))
                }
            }
            is Loaded.Error -> Box(Modifier.padding(16.dp)) { SBanner(BannerLevel.ALERT, s.msg, title = "Não foi possível abrir") }
            is Loaded.Ok -> ReviewBody(s.a, windowStart, { windowStart = it }, { windowLen = it }, filterMode) { filterMode = it }
        }
    }
    val ok = state as? Loaded.Ok
    if (exportOpen && ok != null) ExportSheet(ok.a, windowStart, windowStart + windowLen) { exportOpen = false }
}

@Composable
private fun ReviewBody(
    a: SessionAnalysis,
    windowStart: Double,
    setStart: (Double) -> Unit,
    setLen: (Double) -> Unit,
    filterMode: Int,
    setFilterMode: (Int) -> Unit,
) {
    val c = S.c
    val m = a.meta
    val ppm = rememberPxPerMm()
    var stripWidthPx by remember { mutableStateOf(1) }
    val visibleS = stripWidthPx / (25.0 * ppm)
    LaunchedEffect(visibleS) { setLen(visibleS) }
    val maxStart = (a.durationS - visibleS).coerceAtLeast(0.0)
    var playing by remember { mutableStateOf(false) }
    val start = windowStart.coerceIn(0.0, maxStart)
    val startNow by rememberUpdatedState(start)

    LaunchedEffect(playing) {
        var last = System.nanoTime()
        var pos = startNow
        while (playing) {
            delay(16)
            val now = System.nanoTime()
            pos += (now - last) / 1e9
            last = now
            if (pos >= maxStart) { setStart(maxStart); playing = false } else setStart(pos)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SCard(padding = 12.dp) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("FC ao longo da sessão"); Eyebrow("bpm")
            }
            val valid = a.hrPerSecond.filter { !it.isNaN() }
            val lo = ((valid.minOrNull() ?: 60f) - 10f).coerceAtLeast(30f)
            val hi = (valid.maxOrNull() ?: 100f) + 10f
            Box(
                Modifier.fillMaxWidth().height(92.dp).pointerInput(a) {
                    detectTapGestures { p -> setStart((p.x / size.width * a.durationS - visibleS / 2).coerceIn(0.0, maxStart)) }
                },
            ) {
                LineChart(
                    a.hrPerSecond, lo, hi, Modifier.fillMaxSize(),
                    guides = listOf(60f, 80f, 100f, 120f).filter { it in lo..hi }, fill = true,
                    markers = m.events.map { e -> e.t.toInt().coerceIn(0, a.hrPerSecond.size - 1) to (if (e.type == EventType.USER) c.brand else c.statusWarn) },
                    cursor = if (a.durationS > 0) ((start + visibleS / 2) / a.durationS).toFloat().coerceIn(0f, 1f) else null,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                (0..3).forEach { k -> Text(Fmt.clock(a.durationS * k / 3), style = SType.overlay, color = c.inkMuted) }
            }
        }

        val from = (start * a.fs).toInt()
        val to = ((start + visibleS) * a.fs).toInt()
        val inWindow = m.events.filter { (it.t * a.fs).toInt() in from until to }
        TraceBox(
            Modifier.fillMaxWidth().height(132.dp).onSizeChanged { stripWidthPx = it.width }
                .pointerInput(a, visibleS) {
                    detectHorizontalDragGestures { _, dx ->
                        setStart((startNow - dx / (25.0 * ppm)).coerceIn(0.0, maxStart))
                    }
                },
        ) {
            EcgStrip(
                a.filtered, a.fs, startSample = from,
                markers = inWindow.map { e -> (e.t * a.fs).toInt() to (if (e.type == EventType.USER) c.brand else c.statusWarn) },
            )
            TraceCorner(Alignment.TopStart) {
                OverlayTag(Fmt.clockTenths(start))
                inWindow.firstOrNull()?.let { OverlayText(it.label.take(28)) }
            }
            TraceCorner(Alignment.BottomEnd) { OverlayText("25 mm/s · 10 mm/mV") }
        }

        // Linha do tempo com janela
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SIconButton(if (playing) SIcons.pause else SIcons.play, if (playing) "Pausar" else "Reproduzir", { playing = !playing })
            BoxWithConstraints(
                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceRaised)
                    .pointerInput(a) {
                        detectTapGestures { p -> setStart((p.x / size.width * a.durationS - visibleS / 2).coerceIn(0.0, maxStart)) }
                    }
                    .pointerInput(a, visibleS) {
                        detectHorizontalDragGestures { ch, _ -> setStart((ch.position.x / size.width * a.durationS - visibleS / 2).coerceIn(0.0, maxStart)) }
                    },
            ) {
                val w = maxWidth
                val dur = a.durationS.coerceAtLeast(0.001)
                m.events.forEach { e ->
                    Box(
                        Modifier.offset(x = w * (e.t / dur).toFloat()).padding(vertical = 6.dp).width(2.dp).fillMaxHeight()
                            .background(if (e.type == EventType.USER) c.brand else c.statusWarn),
                    )
                }
                Box(
                    Modifier.offset(x = w * (start / dur).toFloat()).width((w * (visibleS / dur).toFloat()).coerceAtLeast(6.dp)).fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp)).background(c.brandSoft).border(2.dp, c.brand, RoundedCornerShape(8.dp)),
                )
            }
        }

        val sum = a.summary(m.goodSeconds, m.totalSeconds)
        val st = a.stats
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Média", "${sum.hrMean}", Modifier.weight(1f), unit = "bpm")
            StatTile("Mín · máx", "${sum.hrMin}–${sum.hrMax}", Modifier.weight(1f), valueStyle = SType.stat.copy(fontSize = SType.stat.fontSize * 0.8f))
            StatTile("Batimentos", Fmt.int(sum.beats.toLong()), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("SDNN", st?.sdnn?.roundToInt()?.toString() ?: "– –", Modifier.weight(1f), unit = "ms")
            StatTile("RMSSD", st?.rmssd?.roundToInt()?.toString() ?: "– –", Modifier.weight(1f), unit = "ms")
            StatTile("Qualidade", "${sum.qualityPct}%", Modifier.weight(1f), valueStyle = SType.stat.copy(fontSize = SType.stat.fontSize * 0.8f))
        }

        SectionLabel("Eventos")
        if (m.events.isEmpty()) {
            SList { SRow("Nenhum evento", detail = "Use “Marcar” no Monitor durante a gravação.") }
        } else {
            SList {
                m.events.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    EventRow(e.t, e.type, e.label, e.detail) { setStart((e.t - visibleS / 2).coerceIn(0.0, maxStart)) }
                }
            }
        }

        SectionLabel("Batimento médio")
        SCard(padding = 12.dp) {
            val avg = a.avgBeat
            if (avg == null) {
                Sub("São necessários pelo menos 3 batimentos regulares.")
            } else {
                TraceBox(Modifier.fillMaxWidth().height(150.dp), radius = 12.dp) {
                    EcgStrip(avg, a.fs, speedMmS = 50f, gainMmMv = 10f, base = 0.72f, lineWidth = 2.dp)
                    TraceCorner(Alignment.TopStart) { OverlayTag("R"); OverlayText("em ${(a.avgBeatPreS * 1000).roundToInt()} ms") }
                    TraceCorner(Alignment.BottomEnd) { OverlayText("50 mm/s · 10 mm/mV") }
                }
                val noise = a.avgBeatNoiseUv()
                Sub(
                    "Média de ${a.avgBeatCount} batimentos alinhados no pico R. O ruído aleatório cai √N ≈ ${Fmt.dec(kotlin.math.sqrt(a.avgBeatCount.toDouble()), 0)} vezes" +
                        (noise?.let { " (resíduo ≈ ${Fmt.dec(it, 0)} µV)" } ?: "") + " e as ondas P, QRS e T ficam nítidas. Não é diagnóstico.",
                    Modifier.padding(top = 8.dp), maxLines = 4,
                )
            }
        }

        SectionLabel("Poincaré (RRₙ × RRₙ₊₁)")
        SCard(padding = 12.dp) {
            val pts = remember(a) { a.poincare() }
            Box(Modifier.fillMaxWidth().height(170.dp)) { PoincarePlot(pts, Modifier.fillMaxSize()) }
            if (st != null) {
                Text(
                    "SD1 ${Fmt.dec(st.sd1, 0)} ms · SD2 ${Fmt.dec(st.sd2, 0)} ms · pNN50 ${Fmt.dec(st.pnn50, 1)} %",
                    style = SType.dataSm, color = c.inkMuted, modifier = Modifier.padding(top = 8.dp),
                )
                Sub("SD1 reflete a variação batimento a batimento (parassimpática); SD2, a variação lenta.", Modifier.padding(top = 2.dp))
            }
        }
        SectionLabel("Refiltrar o sinal bruto")
        SSegmented(listOf("Gravação", "Sem filtro", "0,5–40", "0,05–150"), filterMode, setFilterMode, mono = true)
        Sub("Filtros desta análise: ${a.filters.label()} · calibração ganho ${Fmt.dec(m.calibration.gain, 0)} V/V · fs ${Fmt.dec(a.fs, 0)} Hz. A exportação usa estes filtros.", Modifier.padding(horizontal = 4.dp), maxLines = 3)
    }
}

@Composable
private fun EventRow(t: Double, type: EventType, label: String, detail: String, onClick: () -> Unit) {
    val c = S.c
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        val (bg, fg, icon) = when (type) {
            EventType.USER -> Triple(c.brandSoft, c.ink, SIcons.flag)
            EventType.AUTO -> Triple(c.warnSoft, c.statusWarn, SIcons.warn)
            EventType.ALARM -> Triple(c.alertSoft, c.statusAlert, SIcons.alert)
        }
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(15.dp), tint = fg)
        }
        Text(Fmt.clock(t), style = SType.data.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink, modifier = Modifier.width(50.dp))
        Column(Modifier.weight(1f)) {
            val who = when (type) { EventType.USER -> "Marcado por você"; EventType.AUTO -> label; EventType.ALARM -> label }
            Text(who, style = SType.label.copy(fontSize = SType.body.fontSize), color = c.ink)
            val sub = when (type) {
                EventType.USER -> if (label != "Marcado por você") "“$label”" else null
                EventType.AUTO -> detail.ifBlank { "Detecção automática. Não é diagnóstico." }
                EventType.ALARM -> "Alarme disparado durante a gravação"
            }
            if (sub != null) Sub(sub)
        }
    }
}

@Composable
private fun ExportSheet(a: SessionAnalysis, selFrom: Double, selTo: Double, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var whole by remember { mutableStateOf(true) }
    var raw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SSheet(onDismiss) {
        SheetHeader("Exportar sessão", onDismiss)
        OptionCard("PDF", "Relatório", "Traçado a 25 mm/s, estatísticas e eventos", format == ExportFormat.PDF) { format = ExportFormat.PDF }
        OptionCard("CSV", "Amostras", "tempo, mV, filtrado. Para MATLAB / Python", format == ExportFormat.CSV) { format = ExportFormat.CSV }
        OptionCard("EDF+", "Formato de pesquisa", "Abre em EDFbrowser, MNE, PhysioNet", format == ExportFormat.EDF) { format = ExportFormat.EDF }
        SectionLabel("Trecho")
        SSegmented(listOf("Sessão inteira", "Seleção ${Fmt.clock(selFrom)}–${Fmt.clock(selTo)}"), if (whole) 0 else 1, { whole = it == 0 })
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Incluir sinal bruto (sem filtro)", style = SType.rowTitle, color = S.c.ink, modifier = Modifier.weight(1f))
            SSwitch(raw, { raw = it }, "Incluir sinal bruto")
        }
        error?.let { SBanner(BannerLevel.ALERT, it, title = "Não foi possível exportar") }
        SButton(if (busy) "Gerando…" else "Compartilhar", {
            busy = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val req = ExportRequest(format, if (whole) 0.0 else selFrom, if (whole) a.durationS else selTo, raw)
                        Exporters.export(a, req, app.sessions.exportDir)
                    }
                }.onSuccess { f ->
                    shareFile(ctx, f, format.mime, a.meta.title)
                    onDismiss()
                }.onFailure { error = it.message ?: it.javaClass.simpleName }
                busy = false
            }
        }, Modifier.fillMaxWidth(), icon = SIcons.share, enabled = !busy)
    }
}
