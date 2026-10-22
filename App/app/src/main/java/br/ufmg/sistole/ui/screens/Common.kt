package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import br.ufmg.sistole.SistoleApp
import br.ufmg.sistole.data.SessionRecorder
import br.ufmg.sistole.data.Transport
import br.ufmg.sistole.source.LinkState
import br.ufmg.sistole.source.LinkStatus
import br.ufmg.sistole.ui.components.ChipTone
import br.ufmg.sistole.ui.components.SChip
import br.ufmg.sistole.ui.components.SIconButton
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlinx.coroutines.delay

val app: SistoleApp get() = SistoleApp.instance

/** Chip que diz como o app está recebendo o sinal. Toque abre Conectar. */
@Composable
fun ConnectionChip(status: LinkStatus, onClick: () -> Unit, rssi: Int? = null) {
    val icon = when (status.transport) {
        Transport.USB -> SIcons.usb
        Transport.DEMO -> SIcons.demo
        else -> SIcons.bt
    }
    val sub = when (status.transport) {
        Transport.BLE -> "· BLE"
        Transport.USB -> status.detail?.let { "· $it" } ?: "· USB"
        Transport.DEMO -> "· sintético"
        null -> null
    }
    when (status.state) {
        LinkState.CONNECTED -> SChip(status.deviceName ?: "Conectado", tone = ChipTone.OK, icon = icon, sub = if (rssi != null && status.transport == Transport.BLE) "· $rssi dBm" else sub, onClick = onClick)
        LinkState.CONNECTING -> SChip("Conectando…", tone = ChipTone.SCAN, sub = status.deviceName, onClick = onClick)
        LinkState.RECONNECTING -> SChip("Reconectando…", tone = ChipTone.WARN, dot = true, sub = status.deviceName, onClick = onClick)
        LinkState.DISCONNECTED -> SChip("Desconectado", tone = ChipTone.ALERT, dot = true, onClick = onClick)
        LinkState.IDLE -> SChip("Nenhum aparelho", tone = ChipTone.IDLE, onClick = onClick)
    }
}

@Composable
fun RecChip(recorder: SessionRecorder) {
    val elapsed by produceState(0.0, recorder) {
        while (true) { value = recorder.elapsedS(); delay(500) }
    }
    SChip(Fmt.clock(elapsed), tone = ChipTone.REC)
}

/** Sobreposições nos cantos do traçado (eyebrow mono). */
@Composable
fun BoxScope.TraceCorner(align: Alignment, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.align(align).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun OverlayText(text: String, strong: Boolean = false) {
    Text(text.uppercase(), style = SType.overlay.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium), color = if (strong) S.c.ink else S.c.inkMuted, maxLines = 1)
}

@Composable
fun OverlayTag(text: String) {
    val c = S.c
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { OverlayText(text, strong = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = S.c
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.lineStrong))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SType.title, color = S.c.ink, modifier = Modifier.weight(1f))
        SIconButton(SIcons.close, "Fechar", onClose, flat = true)
    }
}

@Composable
fun SField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, numeric: Boolean = false, singleLine: Boolean = true) {
    val c = S.c
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = SType.label) },
        singleLine = singleLine,
        textStyle = if (numeric) SType.data.copy(color = c.ink) else SType.body.copy(color = c.ink),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.brand, unfocusedBorderColor = c.lineStrong,
            focusedContainerColor = c.surfaceRaised, unfocusedContainerColor = c.surfaceRaised,
            focusedLabelColor = c.inkMuted, unfocusedLabelColor = c.inkMuted, cursorColor = c.brand,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ChipRow(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o -> SChip(o, tone = if (o == selected) ChipTone.SEL else ChipTone.PLAIN, height = 34.dp, onClick = { onSelect(o) }) }
    }
}

/** Relógio que avança sozinho a cada [periodMs], para textos "há N s". */
@Composable
fun ticker(periodMs: Long = 1000): Long {
    val t by produceState(System.currentTimeMillis()) {
        while (true) { delay(periodMs); value = System.currentTimeMillis() }
    }
    return t
}

@Composable
fun OnceEffect(block: suspend () -> Unit) = LaunchedEffect(Unit) { block() }
