package br.ufmg.sistole.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.ufmg.sistole.engine.AcquisitionEngine
import br.ufmg.sistole.engine.EventType
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SistoleColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Pixels por milímetro físico da tela. Usa o DPI real; se ele parecer inválido, usa a densidade. */
@Composable
fun rememberPxPerMm(): Float {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    return remember(ctx, density) {
        val dm = ctx.resources.displayMetrics
        val nominal = density * 160f / 25.4f
        val real = (dm.xdpi + dm.ydpi) / 2f / 25.4f
        if (real.isFinite() && real > nominal * 0.7f && real < nominal * 1.3f) real else nominal
    }
}

fun DrawScope.drawEcgGrid(ppm: Float, c: SistoleColors) {
    drawRect(c.paper)
    if (ppm <= 0.5f) return
    val w = size.width
    val h = size.height
    if (ppm >= 2.4f) {
        var x = 0f
        while (x <= w) { drawLine(c.gridMinor, Offset(x, 0f), Offset(x, h), 1f); x += ppm }
        var y = h
        while (y >= 0) { drawLine(c.gridMinor, Offset(0f, y), Offset(w, y), 1f); y -= ppm }
    }
    var x = 0f
    while (x <= w) { drawLine(c.gridMajor, Offset(x, 0f), Offset(x, h), 1f); x += ppm * 5 }
    var y = h
    while (y >= 0) { drawLine(c.gridMajor, Offset(0f, y), Offset(w, y), 1f); y -= ppm * 5 }
}

/** Contêiner do traçado: papel, borda, cantos arredondados e véu vermelho quando o sinal é inválido. */
@Composable
fun TraceBox(modifier: Modifier = Modifier, lost: Boolean = false, radius: Dp = 20.dp, content: @Composable BoxScope.() -> Unit) {
    val c = S.c
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(radius)),
    ) {
        content()
        if (lost) Box(Modifier.fillMaxSize().background(c.alertSoft))
    }
}

/** Janela congelada: índice absoluto da primeira amostra e escala horizontal, para mapear toques. */
data class FrozenWindow(val from: Long, val count: Int, val pxPerSample: Float, val fs: Double)

/**
 * Traçado ao vivo em varredura, a 25 mm/s reais: a caneta escreve da esquerda para a direita
 * e apaga uma lacuna de 16 dp à frente. Congelado, vira uma tira estática da última janela.
 */
@Composable
fun EcgSweep(
    engine: AcquisitionEngine,
    speedMmS: Float,
    gainMmMv: Float,
    modifier: Modifier = Modifier,
    frozen: Boolean = false,
    dim: Boolean = false,
    base: Float = 0.62f,
    color: Color = S.c.trace,
    lineWidth: Dp = 1.8.dp,
    showCal: Boolean = false,
    calipers: Pair<Long, Long>? = null,
    frozenWindow: MutableState<FrozenWindow?>? = null,
) {
    val c = S.c
    val ppm = rememberPxPerMm()
    val density = LocalDensity.current
    val gapPx = with(density) { 16.dp.toPx() }
    val lw = with(density) { lineWidth.toPx() }
    val frame = remember { mutableLongStateOf(0L) }
    val state = remember { SweepState() }
    val isFrozen by rememberUpdatedState(frozen)

    LaunchedEffect(engine) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.0 else (t - last) / 1e9
                last = t
                state.advance(engine, dt, isFrozen)
                frame.longValue = t
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        frame.longValue // redesenha a cada quadro
        drawEcgGrid(ppm, c)
        val fs = engine.fs
        val pxPerSample = (speedMmS * ppm / fs).toFloat()
        val n = ceil(size.width / pxPerSample).toInt().coerceAtLeast(2)
        state.ensure(n)
        val head = floor(state.head).toLong()
        val from = head - n
        engine.copyFiltered(from, n, state.buf, state.flags)
        val y0 = size.height * base
        val k = gainMmMv * ppm
        val alpha = if (dim) 0.35f else 1f
        val effect = if (dim) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null
        val path = Path()

        if (frozen) {
            frozenWindow?.value = FrozenWindow(from, n, pxPerSample, fs)
            var x0 = 0f
            if (showCal) {
                val cw = 5 * ppm
                path.moveTo(0f, y0); path.lineTo(ppm, y0); path.lineTo(ppm, y0 - k); path.lineTo(ppm + cw, y0 - k)
                path.lineTo(ppm + cw, y0); path.lineTo(2 * ppm + cw, y0)
                x0 = 2 * ppm + cw
            }
            var pen = showCal
            for (i in 0 until n) {
                val x = i * pxPerSample
                if (x < x0) continue
                val v = state.buf[i]
                if (v.isNaN()) { pen = false; continue }
                val y = (y0 - v * k).coerceIn(0f, size.height)
                if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
            }
            drawPath(path, color, alpha = alpha, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
            drawEvents(engine, from, head, c) { idx -> (idx - from) * pxPerSample }
            if (calipers != null) drawCalipers(calipers, from, pxPerSample, c.brand)
        } else {
            frozenWindow?.value = null
            val period = size.width
            fun xOf(idx: Long) = ((idx / fs * speedMmS * ppm) % period).toFloat()
            val skip = ceil(gapPx / pxPerSample).toInt()
            var pen = false
            var prevX = -1f
            for (i in skip until n) {
                val v = state.buf[i]
                if (v.isNaN()) { pen = false; continue }
                val x = xOf(from + i)
                if (x < prevX) pen = false
                prevX = x
                val y = (y0 - v * k).coerceIn(0f, size.height)
                if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
            }
            drawPath(path, color, alpha = alpha, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
            drawEvents(engine, from + skip, head, c) { idx -> xOf(idx) }
            val last = state.buf[n - 1]
            if (!last.isNaN()) drawCircle(color, 3.dp.toPx(), Offset(xOf(head - 1), (y0 - last * k).coerceIn(0f, size.height)), alpha = alpha)
        }
    }
}

private fun DrawScope.drawEvents(engine: AcquisitionEngine, from: Long, to: Long, c: SistoleColors, xOf: (Long) -> Float) {
    engine.eventsIn(from, to).forEach { e ->
        drawMarker(xOf(e.sampleIndex), if (e.type == EventType.USER) c.brand else c.statusWarn)
    }
}

fun DrawScope.drawMarker(x: Float, col: Color) {
    drawLine(col, Offset(x, 14.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    val p = Path().apply {
        moveTo(x - 5.dp.toPx(), 2.dp.toPx()); lineTo(x + 5.dp.toPx(), 2.dp.toPx()); lineTo(x, 12.dp.toPx()); close()
    }
    drawPath(p, col)
}

private fun DrawScope.drawCalipers(cal: Pair<Long, Long>, from: Long, pxPerSample: Float, brand: Color) {
    val x1 = (cal.first - from) * pxPerSample
    val x2 = (cal.second - from) * pxPerSample
    val sw = 1.5.dp.toPx()
    listOf(x1, x2).forEach { x ->
        drawLine(brand, Offset(x, 0f), Offset(x, size.height), sw)
        drawCircle(brand, 7.dp.toPx(), Offset(x, size.height - 16.dp.toPx()))
    }
    val ya = 104.dp.toPx()
    drawLine(brand, Offset(x1, ya), Offset(x2, ya), sw)
}

private class SweepState {
    var head = 0.0
    var generation = -1
    var buf = FloatArray(0)
    var flags = ByteArray(0)

    fun ensure(n: Int) {
        if (buf.size != n) { buf = FloatArray(n); flags = ByteArray(n) }
    }

    /** Relógio de reprodução: anda a fs amostras/s ~100 ms atrás da última amostra, suavizando os pacotes em rajada. */
    fun advance(engine: AcquisitionEngine, dt: Double, frozen: Boolean) {
        val latest = engine.total.toDouble()
        val fs = engine.fs
        if (engine.generation != generation) { generation = engine.generation; head = latest }
        if (frozen) return
        val target = latest - 0.1 * fs
        head += dt * fs
        val err = target - head
        if (abs(err) > 0.5 * fs) head = target else head += err * 0.05
        head = min(head, latest)
        head = max(head, 0.0)
    }
}

/** Tira estática de ECG em escala real, a partir de um vetor em mV. */
@Composable
fun EcgStrip(
    data: FloatArray,
    fs: Double,
    modifier: Modifier = Modifier,
    startSample: Int = 0,
    speedMmS: Float = 25f,
    gainMmMv: Float = 10f,
    base: Float = 0.62f,
    lineWidth: Dp = 1.6.dp,
    cal: Boolean = false,
    markers: List<Pair<Int, Color>> = emptyList(),
    selection: Pair<Int, Int>? = null,
    scaleToWidth: Boolean = false,
    grid: Boolean = true,
    color: Color = S.c.trace,
) {
    val c = S.c
    val ppm = rememberPxPerMm()
    Canvas(modifier.fillMaxSize()) {
        val pxPerSample: Float
        val gridPpm: Float
        if (scaleToWidth) {
            // miniaturas: a tira inteira na largura disponível
            pxPerSample = size.width / max(1, data.size)
            gridPpm = (pxPerSample * fs / speedMmS).toFloat()
        } else {
            pxPerSample = (speedMmS * ppm / fs).toFloat()
            gridPpm = ppm
        }
        if (grid) drawEcgGrid(gridPpm, c) else drawRect(c.paper)
        val y0 = size.height * base
        val k = gainMmMv * gridPpm
        if (selection != null) {
            val a = (selection.first - startSample) * pxPerSample
            val b = (selection.second - startSample) * pxPerSample
            drawRect(c.brandSoft, Offset(a, 0f), Size(b - a, size.height))
        }
        val path = Path()
        var x0 = 0f
        if (cal) {
            val cw = 5 * gridPpm
            path.moveTo(0f, y0); path.lineTo(gridPpm, y0); path.lineTo(gridPpm, y0 - k); path.lineTo(gridPpm + cw, y0 - k)
            path.lineTo(gridPpm + cw, y0); path.lineTo(2 * gridPpm + cw, y0)
            x0 = 2 * gridPpm + cw
        }
        var pen = cal
        val count = min(data.size - startSample, ceil(size.width / pxPerSample).toInt() + 1)
        for (i in 0 until count) {
            val x = i * pxPerSample
            if (x < x0) continue
            val v = data[startSample + i]
            if (v.isNaN()) { pen = false; continue }
            val y = (y0 - v * k).coerceIn(0f, size.height)
            if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        markers.forEach { (idx, col) -> drawMarker((idx - startSample) * pxPerSample, col) }
    }
}

/** Gráfico de linha simples (tendência de FC). NaN interrompe a linha. */
@Composable
fun LineChart(
    data: FloatArray,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    color: Color = S.c.brand,
    band: Pair<Float, Float>? = null,
    guides: List<Float> = emptyList(),
    markers: List<Pair<Int, Color>> = emptyList(),
    fill: Boolean = false,
    endDot: Boolean = false,
    lineWidth: Dp = 2.dp,
    cursor: Float? = null,
) {
    val c = S.c
    Canvas(modifier.fillMaxSize()) {
        val pad = 6.dp.toPx()
        val n = data.size
        if (n < 2) return@Canvas
        fun x(i: Int) = pad + (size.width - 2 * pad) * i / (n - 1)
        fun y(v: Float) = pad + (size.height - 2 * pad) * (1 - (v.coerceIn(min, max) - min) / (max - min))
        if (band != null) drawRect(c.okSoft, Offset(0f, y(band.second)), Size(size.width, y(band.first) - y(band.second)))
        guides.forEach { g -> drawLine(c.line, Offset(0f, y(g)), Offset(size.width, y(g)), 1f) }
        val path = Path()
        val area = Path()
        var pen = false
        var lastI = -1
        for (i in 0 until n) {
            val v = data[i]
            if (v.isNaN()) {
                if (pen && fill) { area.lineTo(x(lastI), size.height); area.close() }
                pen = false; continue
            }
            if (!pen) {
                path.moveTo(x(i), y(v))
                if (fill) { area.moveTo(x(i), size.height); area.lineTo(x(i), y(v)) }
                pen = true
            } else {
                path.lineTo(x(i), y(v))
                if (fill) area.lineTo(x(i), y(v))
            }
            lastI = i
        }
        if (pen && fill) { area.lineTo(x(lastI), size.height); area.close() }
        if (fill) drawPath(area, color, alpha = 0.14f)
        drawPath(path, color, style = Stroke(lineWidth.toPx(), join = StrokeJoin.Round))
        markers.forEach { (i, col) ->
            if (i !in 0 until n) return@forEach
            val v = data[i].takeIf { !it.isNaN() } ?: ((min + max) / 2)
            drawCircle(col, 4.5.dp.toPx(), Offset(x(i), y(v)))
            drawCircle(c.surface, 4.5.dp.toPx(), Offset(x(i), y(v)), style = Stroke(2.dp.toPx()))
        }
        if (endDot && lastI >= 0) drawCircle(color, 3.5.dp.toPx(), Offset(x(lastI), y(data[lastI])))
        if (cursor != null) {
            val cx = pad + (size.width - 2 * pad) * cursor
            drawLine(c.ink, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx(), alpha = 0.5f)
        }
    }
}

/** Poincaré: RRₙ × RRₙ₊₁ (ms). */
@Composable
fun PoincarePlot(points: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    val c = S.c
    Canvas(modifier.fillMaxSize()) {
        if (points.isEmpty()) return@Canvas
        val all = points.flatMap { listOf(it.first, it.second) }
        val center = all.average().toFloat()
        val spread = max(150f, (all.maxOrNull()!! - all.minOrNull()!!) * 0.6f + 40f)
        val lo = center - spread
        val hi = center + spread
        val side = min(size.width, size.height)
        val ox = (size.width - side) / 2
        fun px(v: Float) = ox + side * (v - lo) / (hi - lo)
        fun py(v: Float) = size.height - side * (v - lo) / (hi - lo) - (size.height - side) / 2
        drawRect(c.line, Offset(ox, (size.height - side) / 2), Size(side, side), style = Stroke(1f))
        drawLine(c.line, Offset(px(lo), py(lo)), Offset(px(hi), py(hi)), 1f)
        points.forEach { (a, b) -> drawCircle(c.brand, 2.dp.toPx(), Offset(px(a), py(b)), alpha = 0.7f) }
    }
}

/** Espectro em dB de 0 a fs/2, com marcas em 60 e 120 Hz. */
@Composable
fun SpectrumChart(db: FloatArray, fs: Double, modifier: Modifier = Modifier, second: FloatArray? = null, floorDb: Float = -100f, topDb: Float = 0f) {
    val c = S.c
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.paper)
        if (db.isEmpty()) return@Canvas
        val nyq = fs / 2
        fun x(hz: Double) = (hz / nyq * size.width).toFloat()
        fun y(v: Float) = size.height * (1 - (v.coerceIn(floorDb, topDb) - floorDb) / (topDb - floorDb))
        var g = floorDb
        while (g <= topDb) { drawLine(c.gridMajor, Offset(0f, y(g)), Offset(size.width, y(g)), 1f); g += 20f }
        var hz = 0.0
        while (hz <= nyq) { drawLine(c.gridMinor, Offset(x(hz), 0f), Offset(x(hz), size.height), 1f); hz += 25.0 }
        listOf(60.0, 120.0, 180.0).filter { it < nyq }.forEach { m ->
            drawLine(c.statusWarn, Offset(x(m), 0f), Offset(x(m), size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)), alpha = 0.8f)
        }
        fun line(d: FloatArray, col: Color) {
            val p = Path()
            for (k in d.indices) {
                val xx = x(k * nyq / (d.size - 1))
                val yy = y(d[k])
                if (k == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
            }
            drawPath(p, col, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        }
        line(db, c.statusOk)
        if (second != null && second.isNotEmpty()) line(second, c.brand)
    }
}

/** ADC bruto em escala de contagens, sem filtro. */
@Composable
fun RawAdcChart(values: FloatArray, count: Int, full: Int, modifier: Modifier = Modifier) {
    val c = S.c
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.paper)
        for (q in 1..3) { val yy = size.height * q / 4f; drawLine(c.gridMajor, Offset(0f, yy), Offset(size.width, yy), 1f) }
        if (count < 2) return@Canvas
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until count) { mn = min(mn, values[i]); mx = max(mx, values[i]) }
        // zoom automático em torno do sinal, sem passar dos limites do ADC
        val span = max(64f, (mx - mn) * 1.3f)
        val mid = (mx + mn) / 2
        val lo = max(0f, mid - span / 2)
        val hi = min(full.toFloat(), lo + span)
        val p = Path()
        for (i in 0 until count) {
            val xx = size.width * i / (count - 1)
            val yy = size.height * (1 - (values[i] - lo) / (hi - lo))
            if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
        }
        drawPath(p, c.statusOk, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
    }
}
