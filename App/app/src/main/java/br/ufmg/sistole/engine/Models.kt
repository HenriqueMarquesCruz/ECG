package br.ufmg.sistole.engine

/** Leitura fisiológica ao vivo exibida no Monitor. */
data class Vitals(
    val hr: Int? = null,
    val rrMs: Int? = null,
    val rmssd: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val lastValidHr: Int? = null,
    val lastValidAgoS: Int? = null,
    val quality: Int = 0,
    val qualityReason: String = "Sem dados",
    val loFlags: Int = 0,
    val loReported: Boolean = false,
    val beatTick: Long = 0,
    val receiving: Boolean = false,
    val trend: FloatArray = FloatArray(0),
    val trendMarkers: List<Int> = emptyList(),
    val elapsedS: Double = 0.0,
)

fun qualityWord(q: Int) = when (q) {
    4 -> "Boa"
    3 -> "Aceitável"
    2 -> "Ruidosa"
    1 -> "Sem sinal"
    else -> "Sem dados"
}

enum class AlarmKind { LEAD_OFF, NO_SIGNAL, HR_HIGH, HR_LOW }

data class ActiveAlarm(
    val kind: AlarmKind,
    val title: String,
    val text: String,
    val physiological: Boolean,
    val silencedUntil: Long = 0,
) {
    fun silenced(now: Long = System.currentTimeMillis()) = silencedUntil > now
}

enum class EventType { USER, AUTO, ALARM }

/** Evento na linha do tempo ao vivo; [sampleIndex] é o índice absoluto da amostra. */
data class LiveEvent(val sampleIndex: Long, val type: EventType, val label: String, val detail: String = "")

data class Diagnostics(
    val fsTarget: Double = 0.0,
    val fsMeasured: Double = 0.0,
    val totalSamples: Long = 0,
    val lostSamples: Long = 0,
    val packetIntervalMs: Double = 0.0,
    val packetsPerSecond: Double = 0.0,
    val batteryMv: Int? = null,
    val rssi: Int? = null,
    val firmware: String? = null,
    val meta: Map<String, String> = emptyMap(),
    val loFlags: Int = 0,
    val loReported: Boolean = false,
    val saturationPct: Double = 0.0,
    val lastAdc: Int = 0,
    val console: List<String> = emptyList(),
)
