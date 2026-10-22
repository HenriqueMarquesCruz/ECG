package br.ufmg.sistole.engine

import android.os.SystemClock
import br.ufmg.sistole.data.AppSettings
import br.ufmg.sistole.data.SessionMeta
import br.ufmg.sistole.data.SessionRecorder
import br.ufmg.sistole.data.SettingsRepository
import br.ufmg.sistole.dsp.Calibration
import br.ufmg.sistole.dsp.FilterChain
import br.ufmg.sistole.dsp.FilterConfig
import br.ufmg.sistole.dsp.Hrv
import br.ufmg.sistole.dsp.QrsDetector
import br.ufmg.sistole.dsp.goertzelPower
import br.ufmg.sistole.dsp.rms
import br.ufmg.sistole.source.SampleSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recebe as amostras de qualquer fonte, converte, filtra, detecta QRS, avalia a qualidade,
 * dispara alarmes e alimenta a gravação. Os anéis guardam os últimos ~65 s para o traçado.
 *
 * As fontes chamam [onSamples] das suas próprias threads; tudo que mexe nos anéis roda sob [lock].
 */
class AcquisitionEngine(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val beeper: Beeper,
) : SampleSink {
    val lock = Any()

    private val filtered = FloatArray(CAP)
    private val rawMv = FloatArray(CAP)
    private val rawAdc = ShortArray(CAP)
    private val flags = ByteArray(CAP)

    /** Total de amostras recebidas desde o último [reset]. O índice absoluto da próxima amostra. */
    @Volatile var total = 0L
        private set

    @Volatile var fs = 500.0
        private set

    private var cfg: AppSettings = settings.value
    @Volatile var calibration = calibrationOf(cfg)
        private set
    private var filterCfg = filterOf(cfg)
    private var chain = FilterChain(fs, filterCfg)
    private var qrs = newQrs()

    // Batimentos
    private val beats = LongArray(BEATS_CAP)
    private var beatCount = 0L
    private var lastR = -1L
    private val rrRecent = ArrayDeque<Double>()
    private var hrSmoothed: Double? = null
    private var lastValidHr: Int? = null
    private var lastValidAt = 0L
    private var beatTick = 0L
    private var lastBeatWall = 0L

    // Sequência e perdas
    private var lastSeq = -1
    private var lostSamples = 0L
    private var lastAdcValue = 2048

    // Lead-off
    private var loNow = 0
    private var loSince = 0L
    @Volatile private var loReported = false

    // Pacotes e medições
    private var lastPacketWall = 0L
    private var packetIntervalEma = 0.0
    private var packets = 0L
    private val fsHistory = ArrayDeque<Pair<Long, Long>>()
    private var fsMeasured = 0.0
    private var batteryMv: Int? = null
    @Volatile var rssi: Int? = null
    private var meta: Map<String, String> = emptyMap()
    private val console = ArrayDeque<String>()
    private var lastSampleWall = 0L

    // Tendência (1 ponto/s, 5 min)
    private val trend = FloatArray(TREND_LEN) { Float.NaN }
    private var trendHead = 0L
    private val liveEvents = ArrayDeque<LiveEvent>()
    private var lastJumpEvent = 0L

    // Qualidade
    private var quality = 0
    private var qualityReason = "Sem dados"
    private var saturationPct = 0.0

    // Gravação
    @Volatile var recorder: SessionRecorder? = null
        private set
    private val _recording = MutableStateFlow<SessionRecorder?>(null)
    val recording: StateFlow<SessionRecorder?> = _recording.asStateFlow()

    // Alarmes
    private val _alarm = MutableStateFlow<ActiveAlarm?>(null)
    val alarm: StateFlow<ActiveAlarm?> = _alarm.asStateFlow()
    private var hrOutSince = 0L
    private var lastAlarmSound = 0L
    private val silenced = mutableMapOf<AlarmKind, Long>()

    private val _vitals = MutableStateFlow(Vitals())
    val vitals: StateFlow<Vitals> = _vitals.asStateFlow()
    private val _diag = MutableStateFlow(Diagnostics())
    val diag: StateFlow<Diagnostics> = _diag.asStateFlow()

    /** Número de reinícios; a UI usa para recomeçar a varredura. */
    @Volatile var generation = 0
        private set

    init {
        scope.launch { settings.flow.collect { applySettings(it) } }
        scope.launch {
            var tick = 0
            while (true) {
                delay(250)
                tick++
                runCatching { periodic(tick) }
            }
        }
    }

    private fun calibrationOf(s: AppSettings) = Calibration(s.frontEndGain, s.adcOffsetV, s.vref, s.adcBits)
    private fun filterOf(s: AppSettings) = FilterConfig(s.highPassHz, s.lowPassHz, s.notch, s.digitalFilters)
    private fun newQrs() = QrsDetector(fs) { onBeat(it) }

    private fun applySettings(s: AppSettings) = synchronized(lock) {
        val old = cfg
        cfg = s
        if (old.frontEndGain != s.frontEndGain || old.adcOffsetV != s.adcOffsetV || old.vref != s.vref ||
            (old.adcBits != s.adcBits && meta["bits"] == null)
        ) {
            calibration = Calibration(s.frontEndGain, s.adcOffsetV, s.vref, meta["bits"]?.toIntOrNull() ?: s.adcBits)
        }
        val f = filterOf(s)
        if (f != filterCfg) {
            filterCfg = f
            chain = FilterChain(fs, f)
        }
        if (old.defaultFs != s.defaultFs && meta["fs"] == null && total == 0L) fs = s.defaultFs.toDouble()
    }

    val filterConfig: FilterConfig get() = filterCfg

    /** Começa do zero (nova conexão). */
    fun reset(fsHint: Double? = null) = synchronized(lock) {
        fs = fsHint ?: cfg.defaultFs.toDouble()
        meta = emptyMap()
        calibration = calibrationOf(cfg)
        chain = FilterChain(fs, filterCfg)
        qrs = newQrs()
        total = 0; lastSeq = -1; lostSamples = 0
        beatCount = 0; lastR = -1; rrRecent.clear(); hrSmoothed = null; lastValidHr = null
        loNow = 0; loReported = false; packets = 0; packetIntervalEma = 0.0; fsHistory.clear(); fsMeasured = 0.0
        batteryMv = null; rssi = null; console.clear(); trend.fill(Float.NaN); liveEvents.clear()
        quality = 0; qualityReason = "Sem dados"; lastSampleWall = 0
        silenced.clear(); _alarm.value = null
        generation++
        _vitals.value = Vitals()
        _diag.value = Diagnostics(fsTarget = fs)
    }

    // ---------------------------------------------------------------- SampleSink

    override fun onSamples(seq: Int, adc: IntArray, count: Int, loFlags: Int) {
        synchronized(lock) {
            if (seq >= 0) {
                if (lastSeq >= 0) {
                    val expected = (lastSeq + 1) and 0xFFFF
                    val gap = (seq - expected) and 0xFFFF
                    if (gap in 1..(2 * fs).toInt()) {
                        lostSamples += gap
                        // mantém o eixo do tempo: repete a última amostra, marcada como perdida
                        repeat(gap) { push(lastAdcValue, FLAG_LOST) }
                    }
                }
                lastSeq = (seq + count - 1) and 0xFFFF
            }
            if (loFlags != 0) loReported = true
            if (loFlags != loNow) { loNow = loFlags; loSince = SystemClock.elapsedRealtime() }
            for (i in 0 until count) push(adc[i], loFlags)
            lastSampleWall = SystemClock.elapsedRealtime()
        }
    }

    private fun push(adcValue: Int, lo: Int) {
        val mv = calibration.toMv(adcValue)
        val f = chain.process(mv)
        val i = (total % CAP).toInt()
        filtered[i] = f.toFloat()
        rawMv[i] = mv.toFloat()
        rawAdc[i] = adcValue.toShort()
        flags[i] = lo.toByte()
        total++
        lastAdcValue = adcValue
        qrs.process(mv)
        recorder?.write(adcValue)
    }

    override fun onMeta(meta: Map<String, String>) {
        synchronized(lock) {
            this.meta = this.meta + meta
            meta["lo"]?.let { if (it == "1") loReported = true }
            meta["bits"]?.toIntOrNull()?.let { b ->
                if (b in 8..24 && b != calibration.bits) calibration = calibration.copy(bits = b)
            }
            meta["vref"]?.replace(',', '.')?.toDoubleOrNull()?.let { v ->
                if (v in 0.5..5.5 && v != calibration.vref) calibration = calibration.copy(vref = v)
            }
            meta["fs"]?.toDoubleOrNull()?.let { f ->
                if (f in 50.0..4000.0 && f != fs) {
                    fs = f
                    chain = FilterChain(fs, filterCfg)
                    qrs = newQrs()
                    lastR = -1; rrRecent.clear()
                    generation++
                }
            }
        }
    }

    override fun onBattery(mv: Int) { batteryMv = mv }

    override fun onConsole(line: String) {
        synchronized(console) {
            console.addLast(line)
            while (console.size > CONSOLE_CAP) console.removeFirst()
        }
    }

    override fun onPacket(bytes: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastPacketWall > 0) {
            val dt = (now - lastPacketWall).toDouble()
            packetIntervalEma = if (packetIntervalEma == 0.0) dt else packetIntervalEma * 0.95 + dt * 0.05
        }
        lastPacketWall = now
        packets++
    }

    // ---------------------------------------------------------------- Batimentos

    private fun onBeat(r: Long) {
        // chamado dentro de push(), sob lock
        if (loNow != 0) return
        val now = SystemClock.elapsedRealtime()
        if (lastR >= 0) {
            val rr = (r - lastR) / fs * 1000.0
            if (rr in 250.0..2000.0) {
                rrRecent.addLast(rr)
                while (rrRecent.size > RR_KEEP) rrRecent.removeFirst()
                val last8 = rrRecent.takeLast(8)
                hrSmoothed = 60000.0 / last8.average()
                lastValidHr = hrSmoothed!!.roundToInt()
                lastValidAt = now
            }
        }
        lastR = r
        beats[(beatCount % BEATS_CAP).toInt()] = r
        beatCount++
        beatTick++
        lastBeatWall = now
        if (cfg.beatBeep) beeper.beat()
    }

    /** Picos R (índices absolutos) dentro de [from, to). */
    fun beatsIn(from: Long, to: Long): LongArray = synchronized(lock) {
        val out = ArrayList<Long>()
        val n = min(beatCount, BEATS_CAP.toLong())
        for (k in 0 until n) {
            val b = beats[((beatCount - 1 - k) % BEATS_CAP).toInt()]
            if (b < from) break
            if (b < to) out += b
        }
        out.reverse()
        out.toLongArray()
    }

    // ---------------------------------------------------------------- Leitura para a UI

    /**
     * Copia as amostras filtradas de [from] até [from]+[n] em [out] (NaN onde não houver dado)
     * e as marcações em [outFlags]. Retorna [total] no momento da cópia.
     */
    fun copyFiltered(from: Long, n: Int, out: FloatArray, outFlags: ByteArray? = null): Long = synchronized(lock) {
        val oldest = max(0L, total - CAP + 1)
        for (k in 0 until n) {
            val idx = from + k
            if (idx < oldest || idx >= total) {
                out[k] = Float.NaN
                outFlags?.set(k, 0)
            } else {
                val i = (idx % CAP).toInt()
                out[k] = filtered[i]
                outFlags?.set(k, flags[i])
            }
        }
        total
    }

    fun copyRawAdc(n: Int, out: FloatArray): Int = synchronized(lock) {
        val m = min(n.toLong(), min(total, CAP.toLong())).toInt()
        for (k in 0 until m) out[k] = rawAdc[((total - m + k) % CAP).toInt()].toFloat()
        m
    }

    fun copyRawMv(n: Int, out: FloatArray): Int = synchronized(lock) {
        val m = min(n.toLong(), min(total, CAP.toLong())).toInt()
        for (k in 0 until m) out[k] = rawMv[((total - m + k) % CAP).toInt()]
        m
    }

    fun copyFilteredTail(n: Int, out: FloatArray): Int = synchronized(lock) {
        val m = min(n.toLong(), min(total, CAP.toLong())).toInt()
        for (k in 0 until m) out[k] = filtered[((total - m + k) % CAP).toInt()]
        m
    }

    fun eventsIn(from: Long, to: Long): List<LiveEvent> = synchronized(liveEvents) {
        liveEvents.filter { it.sampleIndex in from until to }
    }

    fun consoleLines(): List<String> = synchronized(console) { console.toList() }

    // ---------------------------------------------------------------- Ações

    fun markEvent(label: String, type: EventType = EventType.USER, detail: String = "") {
        val idx = total
        synchronized(liveEvents) {
            liveEvents.addLast(LiveEvent(idx, type, label, detail))
            while (liveEvents.size > 100) liveEvents.removeFirst()
        }
        recorder?.addEvent(idx, type, label, detail)
    }

    /** Troca o rótulo do último evento do usuário (depois de escolher na folha de marcação). */
    fun relabelLastUserEvent(label: String) {
        synchronized(liveEvents) {
            val i = liveEvents.indexOfLast { it.type == EventType.USER }
            if (i >= 0) liveEvents[i] = liveEvents[i].copy(label = label)
        }
        recorder?.relabelLastUser(label)
    }

    fun startRecording(meta: (fs: Double, calibration: Calibration, filters: FilterConfig) -> SessionMeta, create: (SessionMeta, Long) -> SessionRecorder) {
        synchronized(lock) {
            if (recorder != null) return
            val m = meta(fs, calibration, filterCfg)
            val r = create(m, total)
            recorder = r
            _recording.value = r
        }
    }

    fun stopRecording(): SessionMeta? = synchronized(lock) {
        val r = recorder ?: return null
        recorder = null
        _recording.value = null
        r.finish()
    }

    fun silence(kind: AlarmKind) {
        silenced[kind] = System.currentTimeMillis() + SILENCE_MS
        _alarm.value = _alarm.value?.let { if (it.kind == kind) it.copy(silencedUntil = silenced[kind]!!) else it }
    }

    // ---------------------------------------------------------------- Periódico (4 Hz)

    private fun periodic(tick: Int) {
        val now = SystemClock.elapsedRealtime()
        val receiving = lastSampleWall > 0 && now - lastSampleWall < 1500
        synchronized(lock) {
            // taxa medida
            fsHistory.addLast(now to total)
            while (fsHistory.size > 21) fsHistory.removeFirst()
            if (fsHistory.size > 4) {
                val (t0, n0) = fsHistory.first()
                val (t1, n1) = fsHistory.last()
                if (t1 > t0) fsMeasured = (n1 - n0 - 0.0) * 1000.0 / (t1 - t0)
            }
            if (tick % 2 == 0) computeQuality(now, receiving)

            // FC some se não houver batimento recente ou sinal inválido
            val stale = now - lastBeatWall > 4000 || loNow != 0 || quality <= 1
            if (stale) hrSmoothed = null

            if (tick % 4 == 0) {
                trend[(trendHead % TREND_LEN).toInt()] = hrSmoothed?.toFloat() ?: Float.NaN
                trendHead++
                recorder?.let { r ->
                    r.totalSeconds++
                    if (quality >= 3) r.goodSeconds++
                }
                checkHrJump()
            }
        }
        updateAlarm(now, receiving)
        publish(now, receiving)
    }

    private fun computeQuality(now: Long, receiving: Boolean) {
        val n = min(total, (2 * fs).toLong()).toInt()
        if (!receiving || n < fs / 2) {
            quality = 0; qualityReason = "Sem dados"; saturationPct = 0.0
            return
        }
        val raw = FloatArray(n)
        var sat = 0
        val full = calibration.fullScale
        for (k in 0 until n) {
            val i = ((total - n + k) % CAP).toInt()
            raw[k] = rawMv[i]
            val a = rawAdc[i].toInt()
            if (a <= 2 || a >= full - 2) sat++
        }
        saturationPct = sat * 100.0 / n
        val sd = rms(raw, n)
        val sinceBeat = now - lastBeatWall
        val rr = rrRecent.takeLast(8)
        val cv = if (rr.size >= 4) {
            val m = rr.average(); sqrt(rr.sumOf { (it - m) * (it - m) } / rr.size) / m
        } else 1.0
        val mainsRatio = if (sd > 0) goertzelPower(raw, n, fs, 60.0) / (sd * sd) else 0.0
        val (q, why) = when {
            loNow != 0 -> 1 to "Eletrodo solto"
            saturationPct > 5 -> 1 to "ADC saturado"
            sd < 0.005 -> 1 to "Sinal plano"
            total > 3 * fs && sinceBeat > 3000 -> 1 to "Sem batimentos"
            mainsRatio > 0.25 -> 2 to "Ruído de 60 Hz"
            cv > 0.2 -> 2 to "Ritmo irregular ou ruído"
            mainsRatio < 0.05 && cv < 0.1 -> 4 to "Boa"
            else -> 3 to "Aceitável"
        }
        quality = q; qualityReason = why
    }

    private fun checkHrJump() {
        if (recorder == null) return
        val hr = hrSmoothed ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastJumpEvent < 60_000) return
        var minHr = Double.MAX_VALUE
        var minAgo = 0
        for (k in 1..30) {
            val v = trend[((trendHead - 1 - k + TREND_LEN * 10) % TREND_LEN).toInt()]
            if (!v.isNaN() && v < minHr) { minHr = v.toDouble(); minAgo = k }
        }
        if (minHr != Double.MAX_VALUE && hr - minHr > 20) {
            lastJumpEvent = now
            markEvent(
                "Subida de FC: ${minHr.roundToInt()} → ${hr.roundToInt()} bpm em $minAgo s",
                EventType.AUTO, "Detecção automática. Não é diagnóstico.",
            )
        }
    }

    private fun updateAlarm(now: Long, receiving: Boolean) {
        val s = cfg
        val wall = System.currentTimeMillis()
        val hr = hrSmoothed
        val leadOff = receiving && loNow != 0 && now - loSince > 300
        val noSignal = receiving && !leadOff && quality == 1 && total > 3 * fs
        val out = hr != null && quality >= 2 && (hr > s.alarmHigh || hr < s.alarmLow)
        if (out) { if (hrOutSince == 0L) hrOutSince = now } else hrOutSince = 0
        val hrAlarm = out && now - hrOutSince >= 3000

        val lead = s.lead
        val next: ActiveAlarm? = when {
            leadOff && s.leadOffWarn -> {
                val who = buildList {
                    if (loNow and 1 != 0) add(lead.plus)
                    if (loNow and 2 != 0) add(lead.minus)
                }
                val title = if (who.size == 1) "Eletrodo ${who[0].letter} sem contato" else "Eletrodos ${who.joinToString(" e ") { it.letter }} sem contato"
                ActiveAlarm(AlarmKind.LEAD_OFF, title, "Traçado inválido. A FC não é calculada até o contato voltar.", physiological = false)
            }
            hrAlarm && hr > s.alarmHigh -> ActiveAlarm(
                AlarmKind.HR_HIGH, "FC alta: ${hr.roundToInt()} bpm",
                "Acima de ${s.alarmHigh} bpm, o limite configurado. Verifique o voluntário.", physiological = true,
            )
            hrAlarm -> ActiveAlarm(
                AlarmKind.HR_LOW, "FC baixa: ${hr.roundToInt()} bpm",
                "Abaixo de ${s.alarmLow} bpm, o limite configurado. Verifique o voluntário e os eletrodos.", physiological = true,
            )
            noSignal -> ActiveAlarm(
                AlarmKind.NO_SIGNAL, "Sem sinal válido",
                "$qualityReason. Confira os eletrodos, o ganho e a calibração do front-end.", physiological = false,
            )
            else -> null
        }
        val prev = _alarm.value
        val withSilence = next?.copy(silencedUntil = silenced[next.kind] ?: 0)
        if (withSilence?.kind != prev?.kind || withSilence?.title != prev?.title || withSilence?.silencedUntil != prev?.silencedUntil) {
            if (withSilence != null && withSilence.kind != prev?.kind && (withSilence.physiological || withSilence.kind == AlarmKind.LEAD_OFF)) {
                markEvent(withSilence.title, EventType.ALARM)
            }
            _alarm.value = withSilence
        }
        val a = _alarm.value
        if (a != null && s.alarmSound && !a.silenced(wall) && (a.physiological || a.kind == AlarmKind.LEAD_OFF) && now - lastAlarmSound > 3000) {
            lastAlarmSound = now
            beeper.alarm()
        }
    }

    private fun publish(now: Long, receiving: Boolean) {
        val vitals: Vitals
        val diag: Diagnostics
        synchronized(lock) {
            val trendCopy = FloatArray(TREND_LEN) { k -> trend[((trendHead - TREND_LEN + k + TREND_LEN * 10) % TREND_LEN).toInt()] }
            val valid = trendCopy.filter { !it.isNaN() } + listOfNotNull(hrSmoothed?.toFloat())
            val eventMarkers = synchronized(liveEvents) {
                liveEvents.mapNotNull { e ->
                    val agoS = (total - e.sampleIndex) / fs
                    val idx = TREND_LEN - 1 - agoS.roundToInt()
                    if (idx in 0 until TREND_LEN) idx else null
                }
            }
            val rr = rrRecent.lastOrNull()
            vitals = Vitals(
                hr = hrSmoothed?.roundToInt(),
                rrMs = if (hrSmoothed != null) rr?.roundToInt() else null,
                rmssd = if (hrSmoothed != null) Hrv.rmssd(rrRecent.takeLast(30))?.roundToInt() else null,
                hrMin = valid.minOrNull()?.roundToInt(),
                hrMax = valid.maxOrNull()?.roundToInt(),
                lastValidHr = lastValidHr,
                lastValidAgoS = if (lastValidAt > 0) ((now - lastValidAt) / 1000).toInt() else null,
                quality = quality,
                qualityReason = qualityReason,
                loFlags = loNow,
                loReported = loReported,
                beatTick = beatTick,
                receiving = receiving,
                trend = trendCopy,
                trendMarkers = eventMarkers,
                elapsedS = total / fs,
            )
            diag = Diagnostics(
                fsTarget = fs,
                fsMeasured = fsMeasured,
                totalSamples = total,
                lostSamples = lostSamples,
                packetIntervalMs = packetIntervalEma,
                packetsPerSecond = if (packetIntervalEma > 0) 1000.0 / packetIntervalEma else 0.0,
                batteryMv = batteryMv,
                rssi = rssi,
                firmware = meta["fw"],
                meta = meta,
                loFlags = loNow,
                loReported = loReported,
                saturationPct = saturationPct,
                lastAdc = lastAdcValue,
                console = synchronized(console) { console.takeLast(12) },
            )
        }
        _vitals.value = vitals
        _diag.value = diag
    }

    /**
     * Tensão média na entrada do ADC nos últimos 2 s. Com os eletrodos em repouso ela é o offset
     * do front-end: usada para calibrar a linha de base quando o passa-altas do app está desligado.
     */
    fun measureOffsetV(): Double? = synchronized(lock) {
        val n = min(total, (2 * fs).toLong()).toInt()
        if (n < fs) return null
        var acc = 0.0
        for (k in 0 until n) acc += rawAdc[((total - n + k) % CAP).toInt()]
        acc / n / calibration.fullScale * calibration.vref
    }

    /** Potência de 60 Hz (dB re 1 mV²) antes e depois dos filtros digitais, nos últimos 2 s. */
    fun mainsDb(): Pair<Double, Double>? {
        val n = min(total, (2 * fs).toLong()).toInt()
        if (n < fs) return null
        val raw = FloatArray(n)
        val flt = FloatArray(n)
        copyRawMv(n, raw)
        copyFilteredTail(n, flt)
        val a = goertzelPower(raw, n, fs, 60.0)
        val b = goertzelPower(flt, n, fs, 60.0)
        fun db(p: Double) = 10 * kotlin.math.log10(max(p, 1e-12))
        return db(a) to db(b)
    }

    val isLeadOffReported: Boolean get() = loReported

    companion object {
        const val CAP = 1 shl 16
        const val BEATS_CAP = 1024
        const val RR_KEEP = 300
        const val TREND_LEN = 300
        const val CONSOLE_CAP = 2000
        const val FLAG_LOST = 0x10
        const val SILENCE_MS = 120_000L

        fun isLost(flag: Byte) = flag.toInt() and FLAG_LOST != 0
        fun isLeadOff(flag: Byte) = flag.toInt() and 3 != 0
    }
}

private fun <T> ArrayDeque<T>.takeLast(n: Int): List<T> {
    val from = max(0, size - n)
    return subList(from, size).toList()
}
