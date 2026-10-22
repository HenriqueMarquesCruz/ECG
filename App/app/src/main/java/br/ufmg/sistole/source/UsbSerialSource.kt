package br.ufmg.sistole.source

import android.hardware.usb.UsbManager
import br.ufmg.sistole.data.Transport
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

object UsbSerialDevices {
    /** Adiciona IDs comuns em placas ESP32 que o prober padrão pode não conhecer. */
    private val extra = UsbSerialProber(
        ProbeTable().apply {
            addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java) // ESP32-S3/C3 USB nativo
            addProduct(0x303A, 0x4001, CdcAcmSerialDriver::class.java)
            addProduct(0x1A86, 0x55D4, CdcAcmSerialDriver::class.java) // CH9102
        },
    )

    fun find(usb: UsbManager): List<UsbSerialDriver> {
        val found = UsbSerialProber.getDefaultProber().findAllDrivers(usb).toMutableList()
        extra.findAllDrivers(usb).forEach { d -> if (found.none { it.device.deviceId == d.device.deviceId }) found += d }
        return found
    }

    fun chipName(d: UsbSerialDriver): String = when (d) {
        is Cp21xxSerialDriver -> "CP210x · Silicon Labs"
        is Ch34xSerialDriver -> "CH340 · WCH"
        is FtdiSerialDriver -> "FT232 · FTDI"
        is ProlificSerialDriver -> "PL2303 · Prolific"
        is CdcAcmSerialDriver -> if (d.device.vendorId == 0x303A) "USB nativo · Espressif" else "USB CDC-ACM"
        else -> d.javaClass.simpleName
    }

    fun vidPid(d: UsbSerialDriver) = "%04X : %04X".format(d.device.vendorId, d.device.productId)
}

/** Porta serial USB (OTG). Lê em thread própria da biblioteca e entrega ao parser. */
class UsbSerialSource(
    usb: UsbManager,
    driver: UsbSerialDriver,
    baud: Int,
    binary: Boolean,
    sink: SampleSink,
    private val onLost: (String) -> Unit,
) : EcgSource {
    override val transport = Transport.USB
    private val port: UsbSerialPort = driver.ports[0]
    private val io: SerialInputOutputManager
    @Volatile private var stopped = false

    init {
        val conn = usb.openDevice(driver.device) ?: error("Sem permissão para abrir o dispositivo USB")
        port.open(conn)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // Placas com conversor usam DTR/RTS para resetar o ESP32: deixa os dois soltos.
        // O USB nativo (CDC) só envia com DTR ativo.
        val cdc = driver is CdcAcmSerialDriver
        runCatching { port.dtr = cdc; port.rts = false }
        val csv = CsvLineParser(sink)
        val bin = BinaryFrameParser(sink)
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (binary) bin.feed(data) else csv.feed(data)
            }

            override fun onRunError(e: Exception) {
                if (!stopped) onLost(e.message ?: "Cabo USB desconectado")
            }
        })
        io.start()
    }

    override fun command(text: String) {
        runCatching { port.write((text + "\n").toByteArray(), 200) }
    }

    override fun stop() {
        stopped = true
        runCatching { io.stop() }
        runCatching { port.close() }
    }
}
