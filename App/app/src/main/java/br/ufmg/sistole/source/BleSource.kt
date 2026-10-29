package br.ufmg.sistole.source

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import br.ufmg.sistole.data.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isSistole: Boolean,
    val lastSeen: Long,
)

/** Varredura BLE. As permissões são pedidas pela tela Conectar antes de chamar [start]. */
@SuppressLint("MissingPermission")
class BleScanner(private val adapter: BluetoothAdapter?) {
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, BleDevice>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }
        override fun onScanFailed(errorCode: Int) { _scanning.value = false }
    }

    private fun add(r: ScanResult) {
        val uuids = r.scanRecord?.serviceUuids.orEmpty()
        val isSistole = uuids.any { it.uuid == SistoleProtocol.SERVICE }
        val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: return
        synchronized(found) {
            found[r.device.address] = BleDevice(r.device.address, name, r.rssi, isSistole, System.currentTimeMillis())
            _devices.value = found.values.toList()
        }
    }

    fun start(onlySistole: Boolean, durationMs: Long = 15_000) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stop()
        synchronized(found) { found.clear(); _devices.value = emptyList() }
        val filters = if (onlySistole) listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SistoleProtocol.SERVICE)).build()) else emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(filters, settings, callback) }.onSuccess {
            _scanning.value = true
            handler.postDelayed({ stop() }, durationMs)
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (_scanning.value) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }
}

/**
 * Conexão GATT com o Sístole: MTU 247 → prioridade alta → descobre serviços → lê Info → liga notify.
 * Em queda inesperada tenta reconectar sozinho (autoConnect).
 */
@SuppressLint("MissingPermission")
class BleSource(
    private val context: Context,
    private val device: BluetoothDevice,
    private val sink: SampleSink,
    private val onState: (LinkState, String?) -> Unit,
    private val onRssi: (Int) -> Unit,
    private val autoReconnect: Boolean,
) : EcgSource {
    override val transport = Transport.BLE
    private val decoder = PacketDecoder(sink)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var stopped = false
    @Volatile private var ready = false
    var mtu = 23
        private set

    private val rssiPoll = object : Runnable {
        override fun run() {
            if (stopped) return
            runCatching { gatt?.readRemoteRssi() }
            handler.postDelayed(this, 2000)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (stopped) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState(LinkState.CONNECTING, "Negociando MTU")
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                g.close()
                gatt = null
                if (autoReconnect) {
                    onState(LinkState.RECONNECTING, "Conexão perdida (código $status)")
                    handler.postDelayed({ if (!stopped) connect(autoConnect = true) }, 1000)
                } else {
                    onState(LinkState.DISCONNECTED, "Conexão perdida (código $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@BleSource.mtu = mtu
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SistoleProtocol.SERVICE)
            if (svc == null) {
                onState(LinkState.DISCONNECTED, "Este aparelho não tem o serviço Sístole")
                stop()
                return
            }
            val info = svc.getCharacteristic(SistoleProtocol.INFO)
            if (info == null || !g.readCharacteristic(info)) enableNotify(g)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(g, c, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, c, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(c, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == SistoleProtocol.CCCD) {
                ready = true
                onState(LinkState.CONNECTED, "MTU $mtu")
                handler.post(rssiPoll)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onRssi(rssi)
        }
    }

    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid == SistoleProtocol.INFO) {
            val text = value.toString(Charsets.UTF_8)
            sink.onConsole("info: $text")
            val meta = SistoleProtocol.parseMeta(text)
            if (meta.isNotEmpty()) sink.onMeta(meta)
            enableNotify(g)
        }
    }

    private fun handleNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid != SistoleProtocol.STREAM) return
        if (!decoder.decode(value)) sink.onConsole("pacote inválido (${value.size} B)")
    }

    private fun enableNotify(g: BluetoothGatt) {
        val ch = g.getService(SistoleProtocol.SERVICE)?.getCharacteristic(SistoleProtocol.STREAM) ?: return
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(SistoleProtocol.CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v)
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun connect(autoConnect: Boolean) {
        onState(if (autoConnect) LinkState.RECONNECTING else LinkState.CONNECTING, null)
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    init { connect(autoConnect = false) }

    override fun command(text: String) {
        val g = gatt ?: return
        val ch = g.getService(SistoleProtocol.SERVICE)?.getCharacteristic(SistoleProtocol.CONTROL) ?: return
        val bytes = text.toByteArray()
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    override fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
