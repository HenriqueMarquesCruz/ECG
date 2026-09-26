package br.ufmg.sistole.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.data.Transport
import br.ufmg.sistole.source.BleDevice
import br.ufmg.sistole.source.LinkState
import br.ufmg.sistole.source.UsbSerialDevices
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.BannerLevel
import br.ufmg.sistole.ui.components.BtnVariant
import br.ufmg.sistole.ui.components.ChipTone
import br.ufmg.sistole.ui.components.KeyValue
import br.ufmg.sistole.ui.components.LeadIcon
import br.ufmg.sistole.ui.components.RowDivider
import br.ufmg.sistole.ui.components.RssiBars
import br.ufmg.sistole.ui.components.SBanner
import br.ufmg.sistole.ui.components.SButton
import br.ufmg.sistole.ui.components.SCard
import br.ufmg.sistole.ui.components.SChip
import br.ufmg.sistole.ui.components.SList
import br.ufmg.sistole.ui.components.SRow
import br.ufmg.sistole.ui.components.SSegmented
import br.ufmg.sistole.ui.components.SSwitch
import br.ufmg.sistole.ui.components.SectionLabel
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasAll(ctx: Context, perms: Array<String>) =
    perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun ConnectScreen(nav: NavHostController) {
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val status by app.connection.status.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(settings.lastTransport.ordinal) }

    // Acabou de conectar (não estava conectado ao abrir a tela): volta ao Monitor.
    var sawNotConnected by remember { mutableStateOf(status.state != LinkState.CONNECTED) }
    LaunchedEffect(status.state, status.transport) {
        if (status.state != LinkState.CONNECTED) sawNotConnected = true
        else if (sawNotConnected && status.transport != Transport.DEMO && nav.previousBackStackEntry != null) nav.popBackStack()
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Conectar", onBack = { nav.popBackStack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status.state == LinkState.CONNECTED || status.state == LinkState.RECONNECTING) {
                SCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(status.deviceName ?: "Conectado", style = SType.title, color = S.c.ink)
                            Sub(if (status.state == LinkState.RECONNECTING) "Reconectando…" else "Recebendo sinal")
                        }
                        SButton("Desconectar", { app.connection.disconnect() }, small = true, variant = BtnVariant.SECONDARY)
                    }
                }
            }
            SSegmented(
                listOf("Bluetooth", "USB", "Demo"), tab, { tab = it },
                icons = listOf(SIcons.bt, SIcons.usb, SIcons.demo),
            )
            when (tab) {
                0 -> BlePane()
                1 -> UsbPane()
                else -> DemoPane(onStarted = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun BlePane() {
    val ctx = LocalContext.current
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val status by app.connection.status.collectAsStateWithLifecycle()
    val scanner = app.connection.scanner
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    val devices by scanner.devices.collectAsStateWithLifecycle()
    val adapter = app.connection.bluetoothAdapter
    var granted by remember { mutableStateOf(hasAll(ctx, blePermissions())) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = adapter?.isEnabled == true
    }

    LaunchedEffect(granted, enabled, settings.onlySistole) {
        if (granted && enabled) scanner.start(settings.onlySistole)
    }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    when {
        adapter == null -> SBanner(BannerLevel.ALERT, "Este celular não tem Bluetooth. Use o cabo USB ou o modo demonstração.", title = "Sem Bluetooth")
        !granted -> {
            SCard {
                Text("Permitir Bluetooth", style = SType.title, color = S.c.ink)
                Sub("O app precisa procurar e conectar aparelhos Bluetooth por perto. Não usamos sua localização.", Modifier.padding(top = 4.dp, bottom = 12.dp), maxLines = 3)
                SButton("Permitir", { permLauncher.launch(blePermissions()) }, Modifier.fillMaxWidth())
            }
        }
        !enabled -> {
            SCard {
                Text("Bluetooth desligado", style = SType.title, color = S.c.ink)
                Sub("Ligue o Bluetooth para procurar o Sístole.", Modifier.padding(top = 4.dp, bottom = 12.dp))
                SButton("Ligar Bluetooth", {
                    runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                }, Modifier.fillMaxWidth())
            }
        }
        else -> {
            Radar(scanning)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (scanning) SChip("Procurando dispositivos", tone = ChipTone.SCAN)
                else SChip("Busca pausada", tone = ChipTone.IDLE, onClick = { scanner.start(settings.onlySistole) })
                Box(Modifier.weight(1f))
                Text("Só Sístole", style = SType.caption, color = S.c.inkMuted, modifier = Modifier.padding(end = 8.dp))
                SSwitch(settings.onlySistole, { v -> app.settings.update { it.copy(onlySistole = v) } }, "Mostrar só dispositivos Sístole")
            }
            SectionLabel("Encontrados")
            val sorted = devices.sortedWith(compareByDescending<BleDevice> { it.address == settings.lastBleAddress }.thenByDescending { it.isSistole }.thenByDescending { it.rssi })
            if (sorted.isEmpty()) {
                SList { SRow(if (scanning) "Procurando…" else "Nada encontrado", detail = if (settings.onlySistole) "Desligue “Só Sístole” para ver outros aparelhos BLE" else null) }
            } else {
                SList {
                    sorted.forEachIndexed { i, d ->
                        if (i > 0) RowDivider()
                        val connecting = status.state == LinkState.CONNECTING && status.deviceName == d.name
                        SRow(
                            d.name,
                            detail = buildString {
                                if (d.address == settings.lastBleAddress) append("usado por último · ")
                                append("${d.rssi} dBm")
                                if (!d.isSistole) append(" · sem serviço Sístole")
                            },
                            leadContent = { LeadIcon(SIcons.heart, bg = if (d.isSistole) S.c.brandSoft else S.c.surfaceRaised) },
                        ) {
                            RssiBars(d.rssi)
                            SButton(
                                if (connecting) "Conectando…" else "Conectar",
                                { app.connection.connectBle(d.address, d.name) },
                                small = true, variant = if (i == 0) BtnVariant.PRIMARY else BtnVariant.SECONDARY, enabled = !connecting,
                            )
                        }
                    }
                }
            }
            if (status.state == LinkState.DISCONNECTED && status.transport == Transport.BLE && status.message != null) {
                SBanner(BannerLevel.ALERT, status.message!!, title = "Não conectou")
            }
            SBanner(BannerLevel.INFO, "Não aparece? Ligue o aparelho e mantenha a até 2 m. O LED azul pisca quando está anunciando.")
            if (Build.VERSION.SDK_INT < 31) Sub("No Android 11 ou anterior, a localização do celular precisa estar ligada para a busca Bluetooth funcionar.", maxLines = 3)
        }
    }
}

@Composable
private fun Radar(active: Boolean) {
    val c = S.c
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        if (active) {
            listOf(0f, 1 / 3f, 2 / 3f).forEach { off ->
                val p = (phase + off) % 1f
                Box(Modifier.size(60.dp).scale(1f + 1.6f * p).alpha(0.8f * (1 - p)).clip(CircleShape).border(1.5.dp, c.brand, CircleShape))
            }
        }
        Box(Modifier.size(60.dp).clip(CircleShape).background(c.brand), contentAlignment = Alignment.Center) {
            Icon(SIcons.bt, null, Modifier.size(26.dp), tint = c.onBrand)
        }
    }
}

@Composable
private fun UsbPane() {
    val c = S.c
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val status by app.connection.status.collectAsStateWithLifecycle()
    val drivers by app.connection.usbDrivers.collectAsStateWithLifecycle()
    var permTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { app.connection.refreshUsb() }
    val d = drivers.firstOrNull()
    val hasPerm = remember(d, permTick) { d != null && app.connection.hasUsbPermission(d) }

    UsbCable(active = d != null)
    SCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (d != null) "Adaptador detectado" else "Nenhum adaptador USB", style = SType.title, color = c.ink, modifier = Modifier.weight(1f))
            when {
                d == null -> SChip("Aguardando", tone = ChipTone.IDLE)
                hasPerm -> SChip("Pronto", tone = ChipTone.OK, icon = SIcons.check)
                else -> SChip("Sem permissão", tone = ChipTone.WARN, icon = SIcons.warn)
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d != null) {
                KeyValue("Chip", UsbSerialDevices.chipName(d))
                KeyValue("VID : PID", UsbSerialDevices.vidPid(d))
                KeyValue("Permissão USB", if (hasPerm) "concedida" else "pendente", if (hasPerm) c.statusOk else c.statusWarn)
            } else {
                Sub("Ligue o ESP32 no celular com um cabo OTG (USB-C ou micro-USB). Conversores CP2102, CH340, FTDI e o USB nativo do ESP32-S3 funcionam.", maxLines = 4)
            }
        }
    }
    SectionLabel("Taxa de transmissão")
    val bauds = listOf(115200, 230400, 921600)
    SSegmented(bauds.map { it.toString() }, bauds.indexOf(settings.baudRate).coerceAtLeast(0), { i -> app.settings.update { it.copy(baudRate = bauds[i]) } }, mono = true)
    SectionLabel("Formato dos dados")
    SSegmented(listOf("Texto (CSV)", "Binário"), if (settings.serialBinary) 1 else 0, { i -> app.settings.update { it.copy(serialBinary = i == 1) } })
    SBanner(
        BannerLevel.WARN,
        "Com eletrodos em alguém, use o celular só na bateria. Sem isolamento, o cabo cria um caminho entre a pessoa e a rede elétrica.",
        title = "Tire o celular da tomada", icon = SIcons.bolt,
    )
    if (status.state == LinkState.DISCONNECTED && status.transport == Transport.USB && status.message != null) {
        SBanner(BannerLevel.ALERT, status.message!!, title = "Não conectou")
    }
    SButton(
        if (d != null && !hasPerm) "Permitir e abrir porta serial" else "Abrir porta serial",
        {
            val drv = d ?: return@SButton
            app.connection.requestUsbPermission(drv) { ok ->
                permTick++
                if (ok) app.connection.connectUsb(drv)
            }
        },
        Modifier.fillMaxWidth(), enabled = d != null,
    )
    if (d == null) SButton("Procurar de novo", { app.connection.refreshUsb() }, Modifier.fillMaxWidth(), variant = BtnVariant.SECONDARY, icon = SIcons.refresh)
}

@Composable
private fun UsbCable(active: Boolean) {
    val c = S.c
    val t = rememberInfiniteTransition(label = "wire")
    val shift by t.animateFloat(0f, 28f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "s")
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CableNode { Icon(SIcons.file, null, Modifier.size(26.dp), tint = c.ink) }
        Canvas(Modifier.width(120.dp).height(2.dp)) {
            drawLine(
                if (active) c.brand else c.lineStrong, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), if (active) -shift * 2 else 0f),
            )
        }
        CableNode { Icon(SIcons.chip, null, Modifier.size(26.dp), tint = c.ink) }
    }
}

@Composable
private fun CableNode(content: @Composable () -> Unit) {
    val c = S.c
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(c.surfaceRaised).border(1.dp, c.line, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DemoPane(onStarted: () -> Unit) {
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val status by app.connection.status.collectAsStateWithLifecycle()
    val running = status.transport == Transport.DEMO && status.state == LinkState.CONNECTED
    var leadOff by remember { mutableStateOf(app.connection.demo?.leadOff == true) }
    SCard {
        Text("Sinal sintético", style = SType.title, color = S.c.ink)
        Sub(
            "Um ECG gerado pelo app (ondas P, QRS e T com variabilidade e ruído) passa pelo mesmo caminho do sinal real: calibração, filtros, detecção e gravação. Serve para aula e para testar sem o hardware.",
            Modifier.padding(top = 4.dp), maxLines = 5,
        )
    }
    SectionLabel("Frequência simulada")
    val bpms = listOf(48, 72, 100, 140)
    SSegmented(bpms.map { "$it" + if (it == settings.demoBpm) " bpm" else "" }, bpms.indexOf(settings.demoBpm).coerceAtLeast(1), { i ->
        app.settings.update { it.copy(demoBpm = bpms[i]) }
        app.connection.demo?.setBpm(bpms[i])
    }, mono = true)
    SList {
        SRow("Ruído de 60 Hz", detail = "Interferência da rede, para ver o notch agir") {
            SSwitch(settings.demoMains, { v -> app.settings.update { it.copy(demoMains = v) }; app.connection.demo?.setMains(v) }, "Ruído de 60 Hz")
        }
        RowDivider()
        SRow("Simular eletrodo solto", detail = if (running) "Dispara o alarme de lead-off" else "Disponível com a demonstração rodando") {
            SSwitch(leadOff, { v -> leadOff = v; app.connection.demo?.leadOff = v }, "Simular eletrodo solto")
        }
    }
    if (running) {
        SButton("Parar demonstração", { app.connection.disconnect() }, Modifier.fillMaxWidth(), variant = BtnVariant.SECONDARY)
    } else {
        SButton("Iniciar demonstração", { app.connection.startDemo(); onStarted() }, Modifier.fillMaxWidth(), icon = SIcons.demo)
    }
}
