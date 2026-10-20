package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.Routes
import br.ufmg.sistole.data.AppSettings
import br.ufmg.sistole.data.Lead
import br.ufmg.sistole.data.ThemeMode
import br.ufmg.sistole.dsp.Calibration
import br.ufmg.sistole.dsp.FilterChain
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.dsp.SyntheticEcg
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.BannerLevel
import br.ufmg.sistole.ui.components.EcgStrip
import br.ufmg.sistole.ui.components.EcgSweep
import br.ufmg.sistole.ui.components.Eyebrow
import br.ufmg.sistole.ui.components.RowDivider
import br.ufmg.sistole.ui.components.SBanner
import br.ufmg.sistole.ui.components.SCard
import br.ufmg.sistole.ui.components.SList
import br.ufmg.sistole.ui.components.SRow
import br.ufmg.sistole.ui.components.SSegmented
import br.ufmg.sistole.ui.components.SSwitch
import br.ufmg.sistole.ui.components.SectionLabel
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.components.TraceBox
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(nav: NavHostController) {
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val vitals by app.engine.vitals.collectAsStateWithLifecycle()
    fun set(block: (AppSettings) -> AppSettings) = app.settings.update(block)
    var editing by remember { mutableStateOf<NumField?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Ajustes")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TraceBox(Modifier.fillMaxWidth().height(112.dp)) {
                if (vitals.receiving) {
                    EcgSweep(app.engine, s.speedMmS, s.gainMmMv, base = 0.74f)
                    TraceCorner(Alignment.TopStart) { OverlayTag("Prévia ao vivo") }
                } else {
                    val cfg = FilterConfig(s.highPassHz, s.lowPassHz, s.notch, s.digitalFilters)
                    val preview = remember(cfg) { filteredDemo(cfg) }
                    EcgStrip(preview, 500.0, speedMmS = s.speedMmS, gainMmMv = s.gainMmMv, base = 0.7f)
                    TraceCorner(Alignment.TopStart) { OverlayTag("Prévia"); OverlayText("sinal ruidoso simulado") }
                }
                TraceCorner(Alignment.BottomEnd) { OverlayText(FilterConfig(s.highPassHz, s.lowPassHz, s.notch, s.digitalFilters).label()) }
            }

            SectionLabel("Exibição")
            SCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Labeled("Velocidade") {
                        val v = listOf(12.5f, 25f, 50f)
                        SSegmented(v.map { if (it == s.speedMmS) "${Fmt.decTrim(it.toDouble(), 1)} mm/s" else Fmt.decTrim(it.toDouble(), 1) }, v.indexOf(s.speedMmS).coerceAtLeast(1), { i -> set { it.copy(speedMmS = v[i]) } }, mono = true)
                    }
                    Labeled("Ganho") {
                        val v = listOf(5f, 10f, 20f)
                        SSegmented(v.map { if (it == s.gainMmMv) "${it.roundToInt()} mm/mV" else "${it.roundToInt()}" }, v.indexOf(s.gainMmMv).coerceAtLeast(1), { i -> set { it.copy(gainMmMv = v[i]) } }, mono = true)
                    }
                    Labeled("Derivação medida") {
                        SSegmented(Lead.entries.map { it.label }, s.lead.ordinal, { i -> set { it.copy(lead = Lead.entries[i]) } }, mono = true)
                    }
                }
            }

            SectionLabel("Filtros digitais")
            SList {
                SRow(
                    "Filtrar no app",
                    detail = if (s.digitalFilters) "Somam-se aos filtros analógicos do circuito" else "Desligado: o traçado é o sinal do ADC como chega do circuito",
                    lead = SIcons.filter,
                ) { SSwitch(s.digitalFilters, { v -> set { it.copy(digitalFilters = v) } }, "Filtrar no app") }
            }
            if (s.digitalFilters) {
                SCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Labeled("Passa-altas", "Remove a deriva da linha de base") {
                            val v = listOf(0.0, 0.05, 0.5)
                            SSegmented(listOf("Desligado", "0,05 Hz", "0,5 Hz"), v.indexOf(s.highPassHz).coerceAtLeast(0), { i -> set { it.copy(highPassHz = v[i]) } }, mono = true)
                        }
                        Labeled("Passa-baixas", "Corta ruído muscular e de alta frequência") {
                            val v = listOf(0.0, 40.0, 150.0)
                            SSegmented(listOf("Desligado", "40 Hz", "150 Hz"), v.indexOf(s.lowPassHz).coerceAtLeast(0), { i -> set { it.copy(lowPassHz = v[i]) } }, mono = true)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Notch 60 Hz", style = SType.rowTitle, color = S.c.ink)
                                Sub(if (s.lowPassHz == 0.0 || s.lowPassHz > 100) "Rede elétrica, e 120 Hz junto" else "Interferência da rede elétrica")
                            }
                            SSwitch(s.notch, { v -> set { it.copy(notch = v) } }, "Notch 60 Hz")
                        }
                    }
                }
                SBanner(BannerLevel.INFO, "0,05–150 Hz preserva o segmento ST (modo diagnóstico). 0,5–40 Hz deixa o traçado mais limpo para monitorar. A gravação guarda sempre o sinal bruto: dá para refiltrar depois.")
            }
            if (!s.digitalFilters || s.highPassHz == 0.0) {
                SBanner(
                    BannerLevel.WARN,
                    "Sem o passa-altas do app, a posição da linha de base depende do offset calibrado. Se o traço subir ou descer demais, use “Medir offset agora” em Calibração com os eletrodos em repouso.",
                    title = "Linha de base pelo circuito",
                )
            }

            SectionLabel("Alarme de frequência")
            SCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Faixa aceitável")
                    Text("${s.alarmLow} – ${s.alarmHigh} bpm", style = SType.data.copy(fontSize = SType.body.fontSize), color = S.c.ink)
                }
                RangeSlider(s.alarmLow, s.alarmHigh, 30, 250) { lo, hi -> set { it.copy(alarmLow = lo, alarmHigh = hi) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("30", "140", "250").forEach { Text(it, style = SType.dataSm, color = S.c.inkMuted) }
                }
            }
            SList {
                SRow("Som", lead = SIcons.bell) { SSwitch(s.alarmSound, { v -> set { it.copy(alarmSound = v) } }, "Som do alarme") }
                RowDivider()
                SRow("Bipe a cada batimento", lead = SIcons.pulse) { SSwitch(s.beatBeep, { v -> set { it.copy(beatBeep = v) } }, "Bipe a cada batimento") }
                RowDivider()
                SRow("Avisar eletrodo solto", lead = SIcons.warn) { SSwitch(s.leadOffWarn, { v -> set { it.copy(leadOffWarn = v) } }, "Avisar eletrodo solto") }
            }

            SectionLabel("Calibração do front-end")
            val cal = Calibration(s.frontEndGain, s.adcOffsetV, s.vref, s.adcBits)
            SList {
                SRow("Ganho total", detail = "INA122 + estágio de ganho (V/V)", onClick = { editing = NumField.GAIN }) { Value(Fmt.decTrim(s.frontEndGain, 1)) }
                RowDivider()
                SRow("Offset na entrada do ADC", detail = "Tensão com 0 mV no eletrodo", onClick = { editing = NumField.OFFSET }) { Value("${Fmt.decTrim(s.adcOffsetV, 3)} V") }
                RowDivider()
                SRow(
                    "Medir offset agora",
                    detail = if (vitals.receiving) "Média do ADC nos últimos 2 s, com o voluntário parado" else "Conecte o aparelho para medir",
                    lead = SIcons.refresh,
                    onClick = if (vitals.receiving) ({ app.engine.measureOffsetV()?.let { v -> set { it.copy(adcOffsetV = Math.round(v * 1000) / 1000.0) } } }) else null,
                )
                RowDivider()
                SRow("Referência do ADC", detail = "Fundo de escala", onClick = { editing = NumField.VREF }) { Value("${Fmt.decTrim(s.vref, 2)} V") }
                RowDivider()
                SRow("Taxa padrão", detail = "Usada quando o firmware não informa", onClick = { editing = NumField.FS }) { Value("${s.defaultFs} Hz") }
            }
            Sub(
                "1 mV no eletrodo = ${Fmt.dec(s.frontEndGain / 1000, 3)} V no ADC = ${Fmt.dec(cal.countsPerMv, 0)} contagens. " +
                    "Faixa útil: ${Fmt.dec(cal.toMv(0), 2)} a ${Fmt.dec(cal.toMv(cal.fullScale), 2)} mV.",
                Modifier.padding(horizontal = 4.dp), maxLines = 3,
            )

            SectionLabel("Aparência")
            SSegmented(listOf("Automático", "Monitor", "Papel"), s.theme.ordinal, { i -> set { it.copy(theme = ThemeMode.entries[i]) } }, icons = listOf(null, SIcons.moon, null))
            SList {
                SRow("Manter tela ligada", detail = "Durante a aquisição", lead = SIcons.expand) { SSwitch(s.keepScreenOn, { v -> set { it.copy(keepScreenOn = v) } }, "Manter tela ligada") }
                RowDivider()
                SRow("Reconectar sozinho", detail = "Ao último aparelho BLE ou cabo USB", lead = SIcons.refresh) { SSwitch(s.autoReconnect, { v -> set { it.copy(autoReconnect = v) } }, "Reconectar sozinho") }
                RowDivider()
                SRow("Perfis de voluntários", detail = "${s.profiles.size} ${if (s.profiles.size == 1) "perfil" else "perfis"}", lead = SIcons.person, onClick = { nav.navigate(Routes.PROFILES) }) { Chevron() }
                RowDivider()
                SRow("Sobre e aviso de uso", detail = "Uso didático. Não é equipamento médico.", lead = SIcons.info, onClick = { nav.navigate(Routes.ABOUT) }) { Chevron() }
            }
        }
    }

    editing?.let { f -> NumberDialog(f, s, onDismiss = { editing = null }) }
}

@Composable
private fun Labeled(label: String, detail: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(label, style = SType.rowTitle, color = S.c.ink)
            if (detail != null) Sub(detail)
        }
        content()
    }
}

@Composable
private fun Value(t: String) = Text(t, style = SType.data, color = S.c.inkMuted)

@Composable
private fun Chevron() = Icon(SIcons.chev, null, Modifier.size(18.dp), tint = S.c.inkMuted)

private fun filteredDemo(cfg: FilterConfig): FloatArray {
    val fs = 500.0
    val ecg = SyntheticEcg(bpm = 72.0, noise = 0.06, mainsMv = 0.25, wanderMv = 0.35, seed = 3)
    val chain = FilterChain(fs, cfg)
    val n = (6 * fs).toInt()
    val out = FloatArray(n)
    for (i in 0 until n) out[i] = chain.process(ecg.value(i / fs) + 0.3).toFloat()
    // descarta os 2 primeiros segundos (regime do passa-altas de 0,05 Hz)
    return out.copyOfRange((2 * fs).toInt(), n)
}

/** Faixa com dois controles (mínimo e máximo), passo de 5 bpm. */
@Composable
private fun RangeSlider(low: Int, high: Int, min: Int, max: Int, onChange: (Int, Int) -> Unit) {
    val c = S.c
    val lowNow by rememberUpdatedState(low)
    val highNow by rememberUpdatedState(high)
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 11.dp)) {
        val w = maxWidth
        fun frac(v: Int) = (v - min).toFloat() / (max - min)
        Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(2.dp)).background(c.surfaceRaised))
        Box(
            Modifier.offset(x = w * frac(low)).width(w * (frac(high) - frac(low))).height(4.dp).align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp)).background(c.brand),
        )
        var dragging by remember { mutableStateOf(-1) }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        val v = min + (p.x / size.width * (max - min))
                        dragging = if (kotlin.math.abs(v - lowNow) < kotlin.math.abs(v - highNow)) 0 else 1
                    },
                    onDrag = { ch, _ ->
                        val raw = min + (ch.position.x / size.width * (max - min))
                        val v = ((raw / 5).roundToInt() * 5).coerceIn(min, max)
                        if (dragging == 0) onChange(v.coerceAtMost(highNow - 10), highNow) else onChange(lowNow, v.coerceAtLeast(lowNow + 10))
                    },
                    onDragEnd = { dragging = -1 },
                )
            },
        )
        listOf(low to "Mínimo $low bpm", high to "Máximo $high bpm").forEach { (v, d) ->
            Box(
                Modifier.offset(x = w * frac(v) - 11.dp).size(22.dp).align(Alignment.CenterStart).clip(CircleShape)
                    .background(c.surface).border(2.dp, c.brand, CircleShape).semantics { contentDescription = d },
            )
        }
    }
}

private enum class NumField(val title: String, val hint: String) {
    GAIN("Ganho total do front-end", "Produto dos ganhos: INA122 (5 + 200 kΩ/RG) × estágio seguinte. Ex.: 1005 com RG = 200 Ω."),
    OFFSET("Offset na entrada do ADC", "Tensão que o ADC vê com 0 mV no eletrodo. Com o divisor para 0–3,3 V, costuma ser 1,65 V."),
    VREF("Referência do ADC", "Fundo de escala do ADC. No ESP32 com atenuação de 11 dB, use cerca de 3,3 V."),
    FS("Taxa de amostragem padrão", "Usada só quando o firmware não envia fs=… nos metadados."),
}

@Composable
private fun NumberDialog(f: NumField, s: AppSettings, onDismiss: () -> Unit) {
    val initial = when (f) {
        NumField.GAIN -> Fmt.input(s.frontEndGain, 2)
        NumField.OFFSET -> Fmt.input(s.adcOffsetV, 3)
        NumField.VREF -> Fmt.input(s.vref, 3)
        NumField.FS -> s.defaultFs.toString()
    }
    var text by remember { mutableStateOf(initial) }
    val v = Fmt.parse(text)
    val valid = v != null && when (f) {
        NumField.GAIN -> v in 1.0..100000.0
        NumField.OFFSET -> v in 0.0..5.0
        NumField.VREF -> v in 0.5..5.5
        NumField.FS -> v in 50.0..4000.0
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = S.c.surface,
        title = { Text(f.title, style = SType.title, color = S.c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SField(text, { text = it }, "Valor", numeric = true)
                Sub(f.hint, maxLines = 4)
            }
        },
        confirmButton = {
            TextButton({
                val x = v ?: return@TextButton
                app.settings.update {
                    when (f) {
                        NumField.GAIN -> it.copy(frontEndGain = x)
                        NumField.OFFSET -> it.copy(adcOffsetV = x)
                        NumField.VREF -> it.copy(vref = x)
                        NumField.FS -> it.copy(defaultFs = x.roundToInt())
                    }
                }
                onDismiss()
            }, enabled = valid) { Text("Salvar", color = if (valid) S.c.brand else S.c.inkFaint) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar", color = S.c.inkMuted) } },
    )
}
