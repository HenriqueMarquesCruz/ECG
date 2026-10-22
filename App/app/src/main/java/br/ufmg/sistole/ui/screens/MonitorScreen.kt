package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.Routes
import br.ufmg.sistole.data.SessionMeta
import br.ufmg.sistole.data.SessionStore
import br.ufmg.sistole.data.Transport
import br.ufmg.sistole.engine.AlarmKind
import br.ufmg.sistole.engine.Vitals
import br.ufmg.sistole.engine.qualityWord
import br.ufmg.sistole.source.LinkState
import br.ufmg.sistole.ui.components.ActionButton
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.BannerLevel
import br.ufmg.sistole.ui.components.BeatingHeart
import br.ufmg.sistole.ui.components.BtnVariant
import br.ufmg.sistole.ui.components.EcgSweep
import br.ufmg.sistole.ui.components.Eyebrow
import br.ufmg.sistole.ui.components.LineChart
import br.ufmg.sistole.ui.components.QualityBars
import br.ufmg.sistole.ui.components.RecButton
import br.ufmg.sistole.ui.components.SBanner
import br.ufmg.sistole.ui.components.SButton
import br.ufmg.sistole.ui.components.SCard
import br.ufmg.sistole.ui.components.SIconButton
import br.ufmg.sistole.ui.components.StatTile
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.components.Tab
import br.ufmg.sistole.ui.components.TraceBox
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(nav: NavHostController) {
    val c = S.c
    val engine = app.engine
    val status by app.connection.status.collectAsStateWithLifecycle()
    val vitals by engine.vitals.collectAsStateWithLifecycle()
    val alarm by engine.alarm.collectAsStateWithLifecycle()
    val recorder by engine.recording.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val diag by engine.diag.collectAsStateWithLifecycle()
    var frozen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var recordSheet by remember { mutableStateOf(false) }
    var markSheet by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val hasData = vitals.receiving || engine.total > 0
    val invalid = vitals.receiving && (vitals.loFlags != 0 || vitals.quality == 1)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppBar {
                ConnectionChip(status, onClick = { nav.navigate(Routes.CONNECT) }, rssi = diag.rssi)
                recorder?.let { RecChip(it) }
                Spacer(Modifier.weight(1f))
                Box {
                    SIconButton(SIcons.more, "Mais opções", { menu = true }, flat = true)
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                        DropdownMenuItem(text = { Text("Conectar", style = SType.body) }, onClick = { menu = false; nav.navigate(Routes.CONNECT) })
                        if (status.state != LinkState.IDLE) {
                            DropdownMenuItem(text = { Text("Desconectar", style = SType.body) }, onClick = { menu = false; app.connection.disconnect() })
                        }
                        DropdownMenuItem(text = { Text("Sobre e aviso de uso", style = SType.body) }, onClick = { menu = false; nav.navigate(Routes.ABOUT) })
                    }
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Um banner por vez: alarme sólido, depois problema de conexão.
                val a = alarm
                if (a != null && vitals.receiving) {
                    val silencedLeft = if (a.silenced()) ((a.silencedUntil - ticker()) / 1000).coerceAtLeast(0) else 0
                    SBanner(
                        BannerLevel.SOLID, a.text, title = a.title,
                        action = when {
                            a.kind == AlarmKind.LEAD_OFF -> "Ver"
                            !a.physiological -> null
                            silencedLeft > 0 -> "Silenciado ${Fmt.clock(silencedLeft.toDouble())}"
                            else -> "Silenciar"
                        },
                        onAction = {
                            if (a.kind == AlarmKind.LEAD_OFF) nav.navigate(Tab.DISPOSITIVO.route) else app.engine.silence(a.kind)
                        },
                    )
                } else if (status.state == LinkState.RECONNECTING) {
                    SBanner(BannerLevel.ALERT, "Reconectando a ${status.deviceName ?: "o aparelho"}. Mantenha o aparelho ligado e perto.", title = "Conexão perdida")
                } else if (status.state == LinkState.DISCONNECTED) {
                    SBanner(BannerLevel.ALERT, status.message ?: "O aparelho foi desconectado.", title = "Desconectado", action = "Conectar", onAction = { nav.navigate(Routes.CONNECT) })
                }

                TraceBox(Modifier.fillMaxWidth().height(250.dp), lost = invalid) {
                    if (hasData) {
                        EcgSweep(engine, settings.speedMmS, settings.gainMmMv, frozen = frozen, dim = invalid, base = 0.62f)
                    } else {
                        EmptyTrace(onConnect = { nav.navigate(Routes.CONNECT) }, onDemo = { app.connection.startDemo() })
                    }
                    if (hasData) {
                        TraceCorner(Alignment.TopStart) {
                            OverlayTag(settings.lead.label)
                            OverlayText("${Fmt.decTrim(settings.speedMmS.toDouble(), 1)} mm/s · ${Fmt.decTrim(settings.gainMmMv.toDouble())} mm/mV")
                        }
                        if (frozen) TraceCorner(Alignment.TopEnd) { OverlayTag("Congelado") }
                        else if (status.transport == Transport.DEMO) TraceCorner(Alignment.TopEnd) { OverlayTag("Demo") }
                        TraceCorner(Alignment.BottomStart) { OverlayText(engine.filterConfig.label()) }
                        TraceCorner(Alignment.BottomEnd) {
                            QualityBars(vitals.quality)
                            OverlayText(qualityWord(vitals.quality))
                        }
                    }
                }

                VitalsRow(vitals)

                SCard(padding = 12.dp) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eyebrow("Tendência · 5 min")
                        Eyebrow("faixa ${settings.alarmLow}–${settings.alarmHigh}")
                    }
                    val lo = minOf(40, settings.alarmLow - 10).toFloat()
                    val hi = maxOf(130, settings.alarmHigh + 10).toFloat()
                    LineChart(
                        vitals.trend, lo, hi, Modifier.fillMaxWidth().height(54.dp),
                        band = settings.alarmLow.toFloat() to settings.alarmHigh.toFloat(),
                        markers = vitals.trendMarkers.map { it to c.brand },
                        endDot = true,
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(SIcons.flag, "Marcar", {
                        app.engine.markEvent("Marcado por você")
                        markSheet = true
                    }, Modifier.weight(1f), enabled = vitals.receiving)
                    ActionButton(if (frozen) SIcons.play else SIcons.pause, if (frozen) "Retomar" else "Congelar", { frozen = !frozen }, Modifier.weight(1f), on = frozen, enabled = hasData)
                    Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
                        RecButton(recording = recorder != null, enabled = vitals.receiving || recorder != null, onClick = {
                            if (recorder == null) recordSheet = true
                            else scope.launch {
                                val saved = app.finishRecording()
                                if (saved != null) {
                                    val r = snack.showSnackbar("Sessão salva · ${Fmt.clock(saved.durationS)}", actionLabel = "Revisar", duration = SnackbarDuration.Long)
                                    if (r == SnackbarResult.ActionPerformed) nav.navigate(Routes.review(saved.id))
                                }
                            }
                        })
                    }
                    ActionButton(SIcons.caliper, "Medir", { nav.navigate(Routes.fullscreen(caliper = true)) }, Modifier.weight(1f), enabled = hasData)
                    ActionButton(SIcons.expand, "Tela cheia", { nav.navigate(Routes.fullscreen(caliper = false)) }, Modifier.weight(1f), enabled = hasData)
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
            Snackbar(
                data, shape = RoundedCornerShape(14.dp),
                containerColor = c.surfaceRaised, contentColor = c.ink, actionColor = c.brand,
            )
        }
    }

    if (recordSheet) RecordSheet(onDismiss = { recordSheet = false })
    if (markSheet) MarkSheet(onDismiss = { markSheet = false })
}

@Composable
private fun EmptyTrace(onConnect: () -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nenhum aparelho conectado", style = SType.title, color = S.c.ink)
        Sub("Conecte o ESP32 por Bluetooth ou cabo USB. Sem o hardware, use o modo demonstração.", Modifier.padding(top = 4.dp, bottom = 14.dp), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SButton("Conectar", onConnect, small = true, icon = SIcons.bt)
            SButton("Modo demonstração", onDemo, small = true, variant = BtnVariant.SECONDARY, icon = SIcons.demo)
        }
    }
}

@Composable
fun VitalsRow(v: Vitals) {
    val c = S.c
    val invalid = v.hr == null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SCard(Modifier.weight(1.25f), padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (invalid) Icon(SIcons.heart, null, Modifier.size(14.dp), tint = c.inkMuted) else BeatingHeart(v.beatTick)
                Eyebrow("Frequência")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(v.hr?.toString() ?: "– –", style = SType.vitalXl, color = if (invalid) c.inkFaint else c.ink, maxLines = 1, modifier = Modifier.alignByBaseline())
                Text("bpm", style = SType.data, color = c.inkMuted, modifier = Modifier.alignByBaseline())
            }
            val ctx = when {
                !invalid && v.hrMin != null -> "mín ${v.hrMin}   máx ${v.hrMax}   5 min"
                v.lastValidHr != null && v.lastValidAgoS != null -> "última válida ${v.lastValidHr}\nhá ${Fmt.clock(v.lastValidAgoS.toDouble())}"
                v.receiving -> "procurando batimentos"
                else -> "sem sinal"
            }
            Text(ctx, style = SType.dataSm, color = c.inkMuted, maxLines = 2)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Intervalo RR", v.rrMs?.toString() ?: "– –", Modifier.fillMaxWidth(), unit = "ms", valueColor = if (v.rrMs == null) c.inkFaint else c.ink)
            if (v.receiving && v.quality <= 1) {
                StatTile("Qualidade", v.qualityReason, Modifier.fillMaxWidth(), valueColor = c.statusAlert, valueStyle = SType.title.copy(fontSize = 18.sp, lineHeight = 30.sp))
            } else {
                StatTile("Variab. RMSSD", v.rmssd?.toString() ?: "– –", Modifier.fillMaxWidth(), unit = "ms", valueColor = if (v.rmssd == null) c.inkFaint else c.ink)
            }
        }
    }
}

// ------------------------------------------------------------------ Folhas

@Composable
private fun RecordSheet(onDismiss: () -> Unit) {
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val status by app.connection.status.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    var title by remember { mutableStateOf("Sessão ${Fmt.time(now)}") }
    var volunteer by remember { mutableStateOf(settings.lastProfile ?: "") }
    val presets = listOf("Repouso sentado", "Repouso deitado", "Pós-exercício", "Aula prática", "Respiração profunda")
    SSheet(onDismiss) {
        SheetHeader("Nova gravação", onDismiss)
        SField(title, { title = it }, "Título")
        ChipRow(presets, title) { title = it }
        SField(volunteer, { volunteer = it }, "Voluntário")
        if (settings.profiles.isNotEmpty()) ChipRow(settings.profiles, volunteer) { volunteer = it }
        Sub("Derivação ${settings.lead.label} · ${app.engine.filterConfig.label()} · o sinal bruto é gravado e pode ser refiltrado depois.", maxLines = 3)
        SButton("Gravar", {
            val v = volunteer.trim()
            app.settings.update { s -> s.copy(lastProfile = v.ifBlank { null }, profiles = if (v.isNotBlank() && v !in s.profiles) s.profiles + v else s.profiles) }
            val t = System.currentTimeMillis()
            app.engine.startRecording(
                meta = { fs, cal, filters ->
                    SessionMeta(
                        id = SessionStore.newId(t), title = title.trim().ifBlank { "Sessão" }, volunteer = v,
                        lead = settings.lead.label, startedAt = t, durationS = 0.0, fs = fs, calibration = cal, filters = filters,
                        transport = status.transport?.name ?: "", device = status.deviceName ?: "", sampleCount = 0, events = emptyList(),
                    )
                },
                create = { m, start -> app.sessions.newRecorder(m, start) },
            )
            onDismiss()
        }, Modifier.fillMaxWidth(), icon = null)
    }
}

@Composable
private fun MarkSheet(onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    val labels = listOf("Tosse", "Movimento", "Respiração profunda", "Fala", "Levantou", "Exercício", "Ajuste de eletrodo")
    SSheet(onDismiss) {
        SheetHeader("Evento marcado", onDismiss)
        Sub("O instante já foi marcado. Escolha o que aconteceu ou feche para manter “Marcado por você”.", maxLines = 3)
        ChipRow(labels, null) { app.engine.relabelLastUserEvent(it); onDismiss() }
        SField(custom, { custom = it }, "Outro")
        SButton("Salvar", {
            if (custom.isNotBlank()) app.engine.relabelLastUserEvent(custom.trim())
            onDismiss()
        }, Modifier.fillMaxWidth(), variant = BtnVariant.SECONDARY)
    }
}
