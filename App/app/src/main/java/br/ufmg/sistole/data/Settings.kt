package br.ufmg.sistole.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { AUTO, MONITOR, PAPEL }

enum class Transport { BLE, USB, DEMO }

enum class ElectrodeStandard { IEC, AHA }

/** Derivação bipolar de membros medida pelo canal único. `plus`/`minus` são os eletrodos em IN+ / IN−. */
enum class Lead(val label: String, val plus: Electrode, val minus: Electrode) {
    DI("DI", Electrode.L, Electrode.R),
    DII("DII", Electrode.F, Electrode.R),
    DIII("DIII", Electrode.F, Electrode.L),
}

enum class Electrode(val letter: String, val limb: String, val hint: String) {
    R("R", "Braço direito", "Punho ou abaixo da clavícula direita"),
    L("L", "Braço esquerdo", "Espelhado ao R"),
    F("F", "Perna esquerda", "Tornozelo ou abdome inferior esquerdo"),
    N("N", "Perna direita", "Referência do circuito"),
}

data class AppSettings(
    // Exibição
    val speedMmS: Float = 25f,
    val gainMmMv: Float = 10f,
    val lead: Lead = Lead.DII,
    // Filtros digitais
    val highPassHz: Double = 0.5,
    val lowPassHz: Double = 40.0,
    val notch: Boolean = true,
    // Alarmes
    val alarmLow: Int = 50,
    val alarmHigh: Int = 120,
    val alarmSound: Boolean = true,
    val beatBeep: Boolean = false,
    val leadOffWarn: Boolean = true,
    // Aparência
    val theme: ThemeMode = ThemeMode.MONITOR,
    val keepScreenOn: Boolean = true,
    val electrodeStandard: ElectrodeStandard = ElectrodeStandard.IEC,
    // Calibração do front-end (ADC → mV no eletrodo)
    val frontEndGain: Double = 1000.0,
    val adcOffsetV: Double = 1.65,
    val vref: Double = 3.3,
    val adcBits: Int = 12,
    val defaultFs: Int = 500,
    // Conexão
    val lastTransport: Transport = Transport.BLE,
    val baudRate: Int = 230400,
    val serialBinary: Boolean = false,
    val onlySistole: Boolean = true,
    val lastBleAddress: String? = null,
    val lastBleName: String? = null,
    val autoReconnect: Boolean = true,
    // Demo
    val demoBpm: Int = 72,
    val demoMains: Boolean = false,
    // App
    val disclaimerAccepted: Boolean = false,
    val profiles: List<String> = emptyList(),
    val lastProfile: String? = null,
)

/** Preferências persistidas em SharedPreferences, expostas como StateFlow. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sistole", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val value: AppSettings get() = _flow.value

    fun update(block: (AppSettings) -> AppSettings) {
        synchronized(this) {
            val next = block(_flow.value)
            if (next == _flow.value) return
            _flow.value = next
            save(next)
        }
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        with(prefs) {
            return AppSettings(
                speedMmS = getFloat("speed", d.speedMmS),
                gainMmMv = getFloat("gain", d.gainMmMv),
                lead = enumOr("lead", d.lead),
                highPassHz = getFloat("hp", d.highPassHz.toFloat()).toDouble(),
                lowPassHz = getFloat("lp", d.lowPassHz.toFloat()).toDouble(),
                notch = getBoolean("notch", d.notch),
                alarmLow = getInt("alarmLow", d.alarmLow),
                alarmHigh = getInt("alarmHigh", d.alarmHigh),
                alarmSound = getBoolean("alarmSound", d.alarmSound),
                beatBeep = getBoolean("beatBeep", d.beatBeep),
                leadOffWarn = getBoolean("leadOffWarn", d.leadOffWarn),
                theme = enumOr("theme", d.theme),
                keepScreenOn = getBoolean("keepScreenOn", d.keepScreenOn),
                electrodeStandard = enumOr("electrodeStd", d.electrodeStandard),
                frontEndGain = getFloat("feGain", d.frontEndGain.toFloat()).toDouble(),
                adcOffsetV = getFloat("adcOffset", d.adcOffsetV.toFloat()).toDouble(),
                vref = getFloat("vref", d.vref.toFloat()).toDouble(),
                adcBits = getInt("adcBits", d.adcBits),
                defaultFs = getInt("defaultFs", d.defaultFs),
                lastTransport = enumOr("lastTransport", d.lastTransport),
                baudRate = getInt("baud", d.baudRate),
                serialBinary = getBoolean("serialBinary", d.serialBinary),
                onlySistole = getBoolean("onlySistole", d.onlySistole),
                lastBleAddress = getString("lastBleAddress", null),
                lastBleName = getString("lastBleName", null),
                autoReconnect = getBoolean("autoReconnect", d.autoReconnect),
                demoBpm = getInt("demoBpm", d.demoBpm),
                demoMains = getBoolean("demoMains", d.demoMains),
                disclaimerAccepted = getBoolean("disclaimer", d.disclaimerAccepted),
                profiles = getString("profiles", "")!!.split('\n').filter { it.isNotBlank() },
                lastProfile = getString("lastProfile", null),
            )
        }
    }

    private fun save(s: AppSettings) = prefs.edit {
        putFloat("speed", s.speedMmS)
        putFloat("gain", s.gainMmMv)
        putString("lead", s.lead.name)
        putFloat("hp", s.highPassHz.toFloat())
        putFloat("lp", s.lowPassHz.toFloat())
        putBoolean("notch", s.notch)
        putInt("alarmLow", s.alarmLow)
        putInt("alarmHigh", s.alarmHigh)
        putBoolean("alarmSound", s.alarmSound)
        putBoolean("beatBeep", s.beatBeep)
        putBoolean("leadOffWarn", s.leadOffWarn)
        putString("theme", s.theme.name)
        putBoolean("keepScreenOn", s.keepScreenOn)
        putString("electrodeStd", s.electrodeStandard.name)
        putFloat("feGain", s.frontEndGain.toFloat())
        putFloat("adcOffset", s.adcOffsetV.toFloat())
        putFloat("vref", s.vref.toFloat())
        putInt("adcBits", s.adcBits)
        putInt("defaultFs", s.defaultFs)
        putString("lastTransport", s.lastTransport.name)
        putInt("baud", s.baudRate)
        putBoolean("serialBinary", s.serialBinary)
        putBoolean("onlySistole", s.onlySistole)
        putString("lastBleAddress", s.lastBleAddress)
        putString("lastBleName", s.lastBleName)
        putBoolean("autoReconnect", s.autoReconnect)
        putInt("demoBpm", s.demoBpm)
        putBoolean("demoMains", s.demoMains)
        putBoolean("disclaimer", s.disclaimerAccepted)
        putString("profiles", s.profiles.joinToString("\n"))
        putString("lastProfile", s.lastProfile)
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enumOr(key: String, default: E): E =
        getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
