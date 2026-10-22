package br.ufmg.sistole.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ícones de linha do Sístole (bundle.js): grade 24, traço 1,8, pontas arredondadas.
 * A cor vem do `tint` do Icon.
 */
object SIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
        val iw = w - 2 * rx
        val ih = h - 2 * rx
        return "M${x + rx},${y}h${iw}a$rx,$rx 0 0 1 $rx,$rx" +
            "v${ih}a$rx,$rx 0 0 1 ${-rx},$rx" +
            "h${-iw}a$rx,$rx 0 0 1 ${-rx},${-rx}" +
            "v${-ih}a$rx,$rx 0 0 1 $rx,${-rx}z"
    }

    private fun icon(name: String, vararg strokes: String, fills: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        strokes.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        fills.forEach { d -> b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        return b.build()
    }

    val bt = icon("bt", "M7 7l10 10-5 5V2l5 5L7 17")
    val usb = icon(
        "usb", "M12 3v13", "M9.5 5.5L12 3l2.5 2.5", "M12 12L8 9.5V7", "M12 14l4-2.5V9",
        rect(6.8f, 5f, 2.4f, 2.4f, .4f), circle(16f, 8f, 1.2f), circle(12f, 18.5f, 2.5f),
    )
    val demo = icon("demo", "M3 12h3l2-6 3 12 3-9 2 3h5", "M19 4l1 1M20 4l-1 1")
    val heart = icon("heart", "M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7a4.3 4.3 0 0 1 7.5 2.8C19.5 15.4 12 20 12 20z")
    val pulse = icon("pulse", "M2.5 12h4l2.2-5.5 4.3 11 2.3-5.5h6.2")
    val list = icon("list", "M8 6h12M8 12h12M8 18h12", circle(4f, 6f, .6f), circle(4f, 12f, .6f), circle(4f, 18f, .6f))
    val chip = icon(
        "chip", rect(6f, 6f, 12f, 12f, 2f), rect(9.5f, 9.5f, 5f, 5f, 1f),
        "M9 2.5V6M15 2.5V6M9 18v3.5M15 18v3.5M2.5 9H6M2.5 15H6M18 9h3.5M18 15h3.5",
    )
    val sliders = icon("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    val flag = icon("flag", "M5 21V4", "M5 4.5h12l-2.5 4 2.5 4H5")
    val pause = icon("pause", "M8.5 5v14M15.5 5v14")
    val play = icon("play", "M7.5 5l11 7-11 7z")
    val stop = icon("stop", rect(6.5f, 6.5f, 11f, 11f, 2f))
    val expand = icon("expand", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val shrink = icon("shrink", "M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5")
    val back = icon("back", "M15 5l-7 7 7 7")
    val chev = icon("chev", "M9 6l6 6-6 6")
    val down = icon("down", "M6 9l6 6 6-6")
    val search = icon("search", circle(11f, 11f, 6.5f), "M16 16l4.5 4.5")
    val refresh = icon("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4v4.5H15")
    val share = icon("share", "M12 15V3.5", "M7.5 8L12 3.5 16.5 8", "M5 12v7.5h14V12")
    val download = icon("download", "M12 4v11", "M7.5 10.5L12 15l4.5-4.5", "M5 19.5h14")
    val warn = icon("warn", "M12 3.5l9.5 16.5h-19z", "M12 10v4.5", "M12 17.3v.2")
    val alert = icon("alert", circle(12f, 12f, 9f), "M12 7.5v5.5", "M12 16.3v.2")
    val info = icon("info", circle(12f, 12f, 9f), "M12 11v5.5", "M12 7.7v.2")
    val check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val close = icon("close", "M6 6l12 12M18 6L6 18")
    val plus = icon("plus", "M12 5v14M5 12h14")
    val caliper = icon("caliper", "M6 3v18M18 3v18", "M6 12h12", "M9 9.5L6 12l3 2.5M15 9.5l3 2.5-3 2.5")
    val zoom = icon("zoom", circle(11f, 11f, 6.5f), "M16 16l4.5 4.5M8.5 11h5M11 8.5v5")
    val filter = icon("filter", "M3.5 5h17l-6.5 8v6l-4-2v-4z")
    val clock = icon("clock", circle(12f, 12f, 8.5f), "M12 7.5V12l3 2")
    val battery = icon("battery", rect(2.5f, 7f, 17f, 10f, 2.5f), "M22 10.5v3", fills = listOf(rect(5f, 9.5f, 10f, 5f, 1f)))
    val terminal = icon("terminal", rect(3f, 4.5f, 18f, 15f, 2.5f), "M7 10l3 2.5L7 15M12.5 15.5H17")
    val bolt = icon("bolt", "M13 2.5L5 13.5h6l-1 8 8-11h-6z")
    val person = icon("person", circle(12f, 7f, 3.5f), "M5 20.5c.8-4 3.6-6 7-6s6.2 2 7 6")
    val more = icon("more", circle(5.5f, 12f, 1f), circle(12f, 12f, 1f), circle(18.5f, 12f, 1f))
    val moon = icon("moon", "M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z")
    val bell = icon("bell", "M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5h4")
    val file = icon("file", "M6 3h8l4 4v14H6z", "M14 3v4h4")
    val trash = icon("trash", "M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13")
    val spectrum = icon("spectrum", "M3 20h18", "M5 20v-4M8.5 20V9M12 20v-7M15.5 20V5M19 20v-9")
    val edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
}
