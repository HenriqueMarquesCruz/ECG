package br.ufmg.sistole.data

import android.content.Context
import br.ufmg.sistole.dsp.Calibration
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.engine.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionEvent(val t: Double, val type: EventType, val label: String, val detail: String = "")

data class SessionSummary(
    val hrMean: Int,
    val hrMin: Int,
    val hrMax: Int,
    val beats: Int,
    val sdnn: Double?,
    val rmssd: Double?,
    val pnn50: Double?,
    val qualityPct: Int,
)

data class SessionMeta(
    val id: String,
    val title: String,
    val volunteer: String,
    val lead: String,
    val startedAt: Long,
    val durationS: Double,
    val fs: Double,
    val calibration: Calibration,
    val filters: FilterConfig,
    val transport: String,
    val device: String,
    val sampleCount: Long,
    val events: List<SessionEvent>,
    val summary: SessionSummary? = null,
    val preview: FloatArray? = null,
    val sizeBytes: Long = 0,
    val goodSeconds: Int = 0,
    val totalSeconds: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", id); put("title", title); put("volunteer", volunteer); put("lead", lead)
        put("startedAt", startedAt); put("durationS", durationS); put("fs", fs)
        put("gain", calibration.gain); put("offsetV", calibration.offsetV); put("vref", calibration.vref); put("bits", calibration.bits)
        put("hp", filters.highPassHz); put("lp", filters.lowPassHz); put("notch", filters.notch); put("filt", filters.enabled)
        put("transport", transport); put("device", device); put("sampleCount", sampleCount)
        put("goodSeconds", goodSeconds); put("totalSeconds", totalSeconds)
        put("events", JSONArray().apply {
            events.forEach { e -> put(JSONObject().put("t", e.t).put("type", e.type.name).put("label", e.label).put("detail", e.detail)) }
        })
        summary?.let { s ->
            put("summary", JSONObject().apply {
                put("hrMean", s.hrMean); put("hrMin", s.hrMin); put("hrMax", s.hrMax); put("beats", s.beats)
                s.sdnn?.let { put("sdnn", it) }; s.rmssd?.let { put("rmssd", it) }; s.pnn50?.let { put("pnn50", it) }
                put("qualityPct", s.qualityPct)
            })
        }
        preview?.let { p -> put("preview", JSONArray().apply { p.forEach { put(Math.round(it * 1000) / 1000.0) } }) }
    }

    companion object {
        fun fromJson(o: JSONObject, sizeBytes: Long): SessionMeta {
            val ev = o.optJSONArray("events") ?: JSONArray()
            val s = o.optJSONObject("summary")
            val pv = o.optJSONArray("preview")
            return SessionMeta(
                id = o.getString("id"),
                title = o.optString("title", "Sessão"),
                volunteer = o.optString("volunteer", ""),
                lead = o.optString("lead", "DII"),
                startedAt = o.getLong("startedAt"),
                durationS = o.optDouble("durationS", 0.0),
                fs = o.optDouble("fs", 500.0),
                calibration = Calibration(o.optDouble("gain", 1000.0), o.optDouble("offsetV", 1.65), o.optDouble("vref", 3.3), o.optInt("bits", 12)),
                filters = FilterConfig(o.optDouble("hp", 0.5), o.optDouble("lp", 40.0), o.optBoolean("notch", true), o.optBoolean("filt", true)),
                transport = o.optString("transport", ""),
                device = o.optString("device", ""),
                sampleCount = o.optLong("sampleCount", 0),
                goodSeconds = o.optInt("goodSeconds", 0),
                totalSeconds = o.optInt("totalSeconds", 0),
                events = (0 until ev.length()).map { i ->
                    val e = ev.getJSONObject(i)
                    SessionEvent(e.getDouble("t"), runCatching { EventType.valueOf(e.getString("type")) }.getOrDefault(EventType.USER), e.optString("label"), e.optString("detail"))
                },
                summary = s?.let {
                    SessionSummary(
                        it.optInt("hrMean"), it.optInt("hrMin"), it.optInt("hrMax"), it.optInt("beats"),
                        if (it.has("sdnn")) it.getDouble("sdnn") else null,
                        if (it.has("rmssd")) it.getDouble("rmssd") else null,
                        if (it.has("pnn50")) it.getDouble("pnn50") else null,
                        it.optInt("qualityPct"),
                    )
                },
                preview = pv?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
                sizeBytes = sizeBytes,
            )
        }
    }
}

/** Grava as amostras brutas do ADC (int16 little-endian) enquanto a sessão está ativa. Chamado na thread de aquisição. */
class SessionRecorder(val dir: File, val base: SessionMeta, val startSample: Long) {
    private val out = BufferedOutputStream(FileOutputStream(File(dir, SessionStore.SAMPLES)), 1 shl 16)
    private val events = mutableListOf<SessionEvent>()
    var count = 0L
        private set
    var goodSeconds = 0
    var totalSeconds = 0

    fun write(adc: Int) {
        out.write(adc and 0xFF)
        out.write((adc shr 8) and 0xFF)
        count++
    }

    fun addEvent(sampleIndex: Long, type: EventType, label: String, detail: String) {
        synchronized(events) { events += SessionEvent((sampleIndex - startSample).coerceAtLeast(0) / base.fs, type, label, detail) }
    }

    fun relabelLastUser(label: String) = synchronized(events) {
        val i = events.indexOfLast { it.type == EventType.USER }
        if (i >= 0) events[i] = events[i].copy(label = label)
    }

    fun elapsedS() = count / base.fs

    fun finish(): SessionMeta {
        out.flush(); out.close()
        return base.copy(
            durationS = count / base.fs,
            sampleCount = count,
            events = synchronized(events) { events.toList() },
            goodSeconds = goodSeconds,
            totalSeconds = totalSeconds,
        )
    }
}

class SessionStore(context: Context) {
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }
    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions.asStateFlow()

    val exportDir: File = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _sessions.value = root.listFiles()?.mapNotNull { d -> readMeta(d) }?.sortedByDescending { it.startedAt } ?: emptyList()
    }

    private fun readMeta(d: File): SessionMeta? = runCatching {
        val f = File(d, META)
        val size = d.listFiles()?.sumOf { it.length() } ?: 0
        SessionMeta.fromJson(JSONObject(f.readText()), size)
    }.getOrNull()

    fun get(id: String): SessionMeta? = _sessions.value.firstOrNull { it.id == id } ?: readMeta(File(root, id))

    fun newRecorder(meta: SessionMeta, startSample: Long): SessionRecorder {
        val dir = File(root, meta.id).apply { mkdirs() }
        return SessionRecorder(dir, meta, startSample)
    }

    suspend fun save(meta: SessionMeta) = withContext(Dispatchers.IO) {
        val dir = File(root, meta.id).apply { mkdirs() }
        File(dir, META).writeText(meta.toJson().toString())
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }

    suspend fun rename(id: String, title: String) {
        val m = get(id) ?: return
        save(m.copy(title = title))
    }

    /** Lê as amostras brutas do ADC. */
    suspend fun loadSamples(id: String): ShortArray = withContext(Dispatchers.IO) {
        val bytes = File(File(root, id), SAMPLES).readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        out
    }

    companion object {
        const val META = "meta.json"
        const val SAMPLES = "samples.bin"

        fun newId(now: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
    }
}
