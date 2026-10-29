package br.ufmg.sistole.source

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import br.ufmg.sistole.data.SettingsRepository
import br.ufmg.sistole.data.Transport
import br.ufmg.sistole.engine.AcquisitionEngine
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mantém a fonte ativa (BLE, USB ou Demo) e o estado do link. Só uma fonte por vez. */
class ConnectionManager(
    private val context: Context,
    private val engine: AcquisitionEngine,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val btManager = context.getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter get() = btManager?.adapter
    val scanner = BleScanner(bluetoothAdapter)

    private val usb = context.getSystemService(UsbManager::class.java)
    private val _usbDrivers = MutableStateFlow<List<UsbSerialDriver>>(emptyList())
    val usbDrivers: StateFlow<List<UsbSerialDriver>> = _usbDrivers.asStateFlow()

    private var source: EcgSource? = null
    val demo: DemoSource? get() = source as? DemoSource
    val bleMtu: Int? get() = (source as? BleSource)?.mtu

    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    refreshUsb()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    pendingPermission?.invoke(granted)
                    pendingPermission = null
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onUsbAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshUsb()
                    if (source is UsbSerialSource && _usbDrivers.value.isEmpty()) {
                        stopSource()
                        _status.value = LinkStatus(LinkState.DISCONNECTED, Transport.USB, _status.value.deviceName, message = "Cabo USB desconectado")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshUsb()
    }

    fun refreshUsb() {
        _usbDrivers.value = runCatching { UsbSerialDevices.find(usb) }.getOrDefault(emptyList())
    }

    fun hasUsbPermission(d: UsbSerialDriver) = usb.hasPermission(d.device)

    fun requestUsbPermission(d: UsbSerialDriver, onResult: (Boolean) -> Unit) {
        if (usb.hasPermission(d.device)) { onResult(true); return }
        pendingPermission = onResult
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        usb.requestPermission(d.device, pi)
    }

    /** Cabo plugado: se o último transporte foi USB, conecta sozinho. */
    fun onUsbAttached() {
        refreshUsb()
        val s = settings.value
        val d = _usbDrivers.value.firstOrNull() ?: return
        if (s.autoReconnect && s.lastTransport == Transport.USB && (source == null || _status.value.state != LinkState.CONNECTED) && usb.hasPermission(d.device)) {
            connectUsb(d)
        }
    }

    fun connectUsb(d: UsbSerialDriver) {
        stopSource()
        val s = settings.value
        engine.reset()
        _status.value = LinkStatus(LinkState.CONNECTING, Transport.USB, UsbSerialDevices.chipName(d))
        runCatching {
            UsbSerialSource(usb, d, s.baudRate, s.serialBinary, engine) { msg ->
                _status.value = LinkStatus(LinkState.DISCONNECTED, Transport.USB, UsbSerialDevices.chipName(d), message = msg)
            }
        }.onSuccess { src ->
            source = src
            settings.update { it.copy(lastTransport = Transport.USB) }
            _status.value = LinkStatus(LinkState.CONNECTED, Transport.USB, UsbSerialDevices.chipName(d), detail = "${s.baudRate}")
        }.onFailure { e ->
            _status.value = LinkStatus(LinkState.DISCONNECTED, Transport.USB, UsbSerialDevices.chipName(d), message = e.message ?: "Não foi possível abrir a porta")
        }
    }

    fun connectBle(address: String, name: String) {
        val adapter = bluetoothAdapter ?: return
        scanner.stop()
        stopSource()
        engine.reset()
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        settings.update { it.copy(lastTransport = Transport.BLE, lastBleAddress = address, lastBleName = name) }
        _status.value = LinkStatus(LinkState.CONNECTING, Transport.BLE, name, detail = "BLE")
        source = BleSource(
            context, device, engine,
            onState = { st, msg ->
                _status.value = LinkStatus(st, Transport.BLE, name, detail = "BLE", message = msg)
            },
            onRssi = { engine.rssi = it },
            autoReconnect = settings.value.autoReconnect,
        )
    }

    fun startDemo() {
        stopSource()
        val s = settings.value
        engine.reset(500.0)
        source = DemoSource(engine, scope, { engine.calibration }, s.demoBpm, s.demoMains)
        settings.update { it.copy(lastTransport = Transport.DEMO) }
        _status.value = LinkStatus(LinkState.CONNECTED, Transport.DEMO, "Demonstração", detail = "sintético")
    }

    fun command(text: String) = source?.command(text)

    fun disconnect() {
        stopSource()
        _status.value = LinkStatus(LinkState.IDLE)
    }

    private fun stopSource() {
        source?.stop()
        source = null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "br.ufmg.sistole.USB_PERMISSION"
    }
}
