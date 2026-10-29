package br.ufmg.sistole.source

import br.ufmg.sistole.data.Transport
import br.ufmg.sistole.dsp.Calibration
import br.ufmg.sistole.dsp.SyntheticEcg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

enum class LinkState { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

data class LinkStatus(
    val state: LinkState = LinkState.IDLE,
    val transport: Transport? = null,
    val deviceName: String? = null,
    val detail: String? = null,
    val message: String? = null,
) {
    val isLive get() = state == LinkState.CONNECTED
}

/** Uma fonte de amostras ativa. */
interface EcgSource {
    val transport: Transport
    fun stop()
    /** Envia um comando de texto ao firmware, se o transporte permitir. */
    fun command(text: String) {}
}

/** Sinal sintético para aula e desenvolvimento sem o hardware. */
class DemoSource(
    private val sink: SampleSink,
    scope: CoroutineScope,
    private val calibration: () -> Calibration,
    bpm: Int,
    mains: Boolean,
    private val fs: Int = 500,
) : EcgSource {
    override val transport = Transport.DEMO
    private val ecg = SyntheticEcg(bpm = bpm.toDouble(), mainsMv = if (mains) 0.25 else 0.0)
    @Volatile var leadOff = false
    private val job: Job

    fun setBpm(bpm: Int) { ecg.bpm = bpm.toDouble() }
    fun setMains(on: Boolean) { ecg.mainsMv = if (on) 0.25 else 0.0 }

    init {
        job = scope.launch(Dispatchers.Default) {
            sink.onMeta(mapOf("fs" to fs.toString(), "bits" to "12", "vref" to "3.3", "fw" to "demo (sinal sintético)", "lo" to "1", "bat" to "1"))
            val start = System.nanoTime()
            var n = 0L
            val chunk = IntArray(64)
            var lastBat = 0L
            while (isActive) {
                delay(20)
                val target = ((System.nanoTime() - start) / 1e9 * fs).toLong()
                while (n < target) {
                    val k = minOf(chunk.size.toLong(), target - n).toInt()
                    val seq = (n and 0xFFFF).toInt()
                    val lo = if (leadOff) SistoleProtocol.FLAG_LO_PLUS else 0
                    val cal = calibration()
                    for (i in 0 until k) {
                        val t = (n + i).toDouble() / fs
                        val mv = if (leadOff) 0.8 * sin(2 * PI * 60 * t) + 2.0 else ecg.value(t)
                        chunk[i] = cal.toAdc(mv)
                    }
                    sink.onPacket(4 + 2 * k)
                    sink.onSamples(seq, chunk, k, lo)
                    if (n / (fs / 4) != (n + k) / (fs / 4)) sink.onConsole("seq=$seq n=$k adc=${chunk[0]} lo=$lo")
                    n += k
                }
                if (n - lastBat > fs * 2) {
                    lastBat = n
                    sink.onBattery(3900 - (n / fs / 60).toInt().coerceAtMost(400))
                }
            }
        }
    }

    override fun stop() { job.cancel() }
}
