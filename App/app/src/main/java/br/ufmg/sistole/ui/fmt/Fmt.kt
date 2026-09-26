package br.ufmg.sistole.ui.fmt

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Números e datas no padrão brasileiro: vírgula decimal, ponto de milhar. */
object Fmt {
    val BR: Locale = Locale.forLanguageTag("pt-BR")

    fun dec(v: Double, digits: Int): String {
        val nf = NumberFormat.getNumberInstance(BR)
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        return nf.format(v)
    }

    fun decTrim(v: Double, maxDigits: Int = 2): String {
        val nf = NumberFormat.getNumberInstance(BR)
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = maxDigits
        return nf.format(v)
    }

    /** Valor para campo de edição: vírgula decimal, sem separador de milhar. */
    fun input(v: Double, maxDigits: Int): String {
        val nf = NumberFormat.getNumberInstance(BR)
        nf.isGroupingUsed = false
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = maxDigits
        return nf.format(v)
    }

    /** Aceita "1,65", "1.65", "1.005,5" ou "1005". */
    fun parse(text: String): Double? {
        var t = text.trim().replace(" ", "")
        if (t.contains(',')) t = t.replace(".", "").replace(',', '.')
        else if (t.count { it == '.' } > 1) t = t.replace(".", "")
        return t.toDoubleOrNull()
    }

    fun int(v: Long): String = NumberFormat.getIntegerInstance(BR).format(v)

    /** mm:ss */
    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
    }

    /** mm:ss,d */
    fun clockTenths(seconds: Double): String {
        val t = (seconds.coerceAtLeast(0.0) * 10).roundToLong()
        return "%02d:%02d,%d".format(t / 600, (t / 10) % 60, t % 10)
    }

    fun duration(seconds: Double) = clock(seconds)

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", BR).format(Date(ms))

    fun dayLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
        val a = Calendar.getInstance().apply { timeInMillis = ms }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        val dayDiff = if (sameYear) b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR) else 99
        return when (dayDiff) {
            0 -> "Hoje"
            1 -> "Ontem"
            else -> SimpleDateFormat(if (sameYear) "d 'de' MMMM" else "d 'de' MMMM 'de' yyyy", BR).format(Date(ms))
        }
    }

    fun dayAndTime(ms: Long): String = "${dayLabel(ms)}, ${time(ms)}"

    fun bytes(b: Long): String = when {
        b >= 1_000_000 -> "${dec(b / 1e6, if (b >= 100_000_000) 0 else 1)} MB"
        b >= 1_000 -> "${(b / 1e3).roundToLong()} kB"
        else -> "$b B"
    }
}
