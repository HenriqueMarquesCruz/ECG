package br.ufmg.sistole.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.ui.components.BeatingHeart
import br.ufmg.sistole.ui.components.ChipTone
import br.ufmg.sistole.ui.components.EcgSweep
import br.ufmg.sistole.ui.components.Eyebrow
import br.ufmg.sistole.ui.components.FrozenWindow
import br.ufmg.sistole.ui.components.SChip
import br.ufmg.sistole.ui.components.SIconButton
import br.ufmg.sistole.ui.components.TraceBox
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlin.math.abs
import kotlin.math.roundToInt

private data class Zoom(val speed: Float, val gain: Float, val label: String)

@Composable
fun FullscreenScreen(nav: NavHostController, startWithCaliper: Boolean) {
    val c = S.c
    val engine = app.engine
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val vitals by engine.vitals.collectAsStateWithLifecycle()
    var frozen by rememberSaveable { mutableStateOf(startWithCaliper) }
    var caliperOn by rememberSaveable { mutableStateOf(startWithCaliper) }
    var zoom by rememberSaveable { mutableIntStateOf(0) }
    val window = remember { mutableStateOf<FrozenWindow?>(null) }
    var calipers by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var frozenAt by remember { mutableStateOf(0.0) }
    var widthPx by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current

    ImmersiveLandscape()

    val zooms = listOf(
        Zoom(settings.speedMmS, settings.gainMmMv, "${Fmt.decTrim(settings.speedMmS.toDouble(), 1)} mm/s"),
        Zoom(50f, 20f, "50 mm/s · 20 mm/mV"),
        Zoom(12.5f, settings.gainMmMv, "12,5 mm/s"),
    )
    val z = zooms[zoom % zooms.size]

    // Ao congelar com o compasso ligado, encaixa em dois picos R do meio da janela (longe do HUD).
    val w = window.value
    LaunchedEffect(w?.from, caliperOn, frozen) {
        if (!frozen || !caliperOn || w == null) return@LaunchedEffect
        if (calipers != null && calipers!!.first >= w.from && calipers!!.second < w.from + w.count) return@LaunchedEffect
        val beats = engine.beatsIn(w.from + w.count / 4, w.from + w.count * 3 / 4)
        calipers = when {
            beats.size >= 2 -> beats[0] to beats[1]
            else -> (w.from + w.count / 3) to (w.from + w.count / 3 + (0.8 * w.fs).toLong())
        }
    }
    LaunchedEffect(frozen) { if (frozen) frozenAt = engine.total / engine.fs else calipers = null }

    Row(Modifier.fillMaxSize().background(c.bg)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { widthPx = it.width }
                .pointerInput(frozen, caliperOn) {
                    if (!frozen || !caliperOn) return@pointerInput
                    var which = -1
                    detectDragGestures(
                        onDragStart = { p ->
                            val fw = window.value ?: return@detectDragGestures
                            val cal = calipers ?: return@detectDragGestures
                            val x1 = (cal.first - fw.from) * fw.pxPerSample
                            val x2 = (cal.second - fw.from) * fw.pxPerSample
                            which = if (abs(p.x - x1) < abs(p.x - x2)) 0 else 1
                        },
                        onDrag = { change, _ ->
                            val fw = window.value ?: return@detectDragGestures
                            val cal = calipers ?: return@detectDragGestures
                            val idx = fw.from + (change.position.x / fw.pxPerSample).toLong().coerceIn(0, fw.count.toLong() - 1)
                            calipers = if (which == 0) idx to cal.second else cal.first to idx
                        },
                        onDragEnd = {
                            // encaixa no pico R mais próximo (até 40 ms)
                            val fw = window.value ?: return@detectDragGestures
                            val cal = calipers ?: return@detectDragGestures
                            val beats = engine.beatsIn(fw.from, fw.from + fw.count)
                            fun snap(i: Long): Long = beats.minByOrNull { abs(it - i) }?.takeIf { abs(it - i) < 0.04 * fw.fs } ?: i
                            calipers = snap(cal.first) to snap(cal.second)
                        },
                    )
                }
                .pointerInput(frozen) {
                    if (frozen) return@pointerInput
                    detectTapGestures(onDoubleTap = { frozen = true })
                },
        ) {
            TraceBox(Modifier.fillMaxSize(), lost = vitals.receiving && (vitals.loFlags != 0 || vitals.quality == 1), radius = 0.dp) {
                EcgSweep(
                    engine, z.speed, z.gain, frozen = frozen, dim = vitals.loFlags != 0,
                    base = 0.6f, showCal = frozen, calipers = if (caliperOn) calipers else null, frozenWindow = window,
                )
            }
            TraceCorner(Alignment.TopStart) {
                if (frozen) SChip("Congelado", tone = ChipTone.WARN, icon = SIcons.pause, height = 24.dp)
                OverlayTag(settings.lead.label)
                OverlayText("${z.label} · ${Fmt.decTrim(z.gain.toDouble())} mm/mV")
            }
            if (frozen) TraceCorner(Alignment.BottomStart) { OverlayText("1 mV ⊓ calibração") }
            else TraceCorner(Alignment.BottomStart) { OverlayText("Toque duas vezes para congelar") }

            // HUD de vitais
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BeatingHeart(vitals.beatTick, 13.dp); Eyebrow("FC")
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(vitals.hr?.toString() ?: "– –", style = SType.vitalMd, color = if (vitals.hr == null) c.inkFaint else c.ink)
                        Text(" bpm", style = SType.dataSm, color = c.inkMuted, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                Column {
                    Eyebrow("Instante")
                    val t = if (frozen) frozenAt else vitals.elapsedS
                    Text(Fmt.clockTenths(t), style = SType.vitalMd, color = c.ink)
                }
            }

            // Leitura do compasso
            val cal = calipers
            val fw = window.value
            if (frozen && caliperOn && cal != null && fw != null) {
                val dtMs = abs(cal.second - cal.first) / fw.fs * 1000
                val midPx = ((cal.first + cal.second) / 2 - fw.from) * fw.pxPerSample
                val text = "Δt ${dtMs.roundToInt()} ms · ${if (dtMs > 0) (60000 / dtMs).roundToInt() else 0} bpm"
                val labelW = with(density) { 170.dp.toPx() }
                val x = (midPx - labelW / 2).coerceIn(0f, widthPx - labelW)
                Box(
                    Modifier.offset { IntOffset(x.roundToInt(), with(density) { 110.dp.roundToPx() }) }
                        .width(170.dp).clip(RoundedCornerShape(10.dp)).background(c.brand).padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(text, style = SType.data, color = c.onBrand, maxLines = 1) }
            }
        }
        Column(
            Modifier.width(76.dp).fillMaxHeight().background(c.surface).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            SIconButton(if (frozen) SIcons.play else SIcons.pause, if (frozen) "Retomar" else "Congelar", { frozen = !frozen })
            SIconButton(SIcons.caliper, "Compasso", {
                caliperOn = !caliperOn
                if (caliperOn) frozen = true
            }, on = caliperOn)
            SIconButton(SIcons.zoom, "Zoom", { zoom = (zoom + 1) % zooms.size; calipers = null }, on = zoom != 0)
            SIconButton(SIcons.flag, "Marcar evento", { engine.markEvent("Marcado por você") })
            SIconButton(SIcons.shrink, "Sair da tela cheia", { nav.popBackStack() }, flat = true)
        }
    }
}

@Composable
private fun ImmersiveLandscape() {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = WindowCompat.getInsetsController(activity.window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
