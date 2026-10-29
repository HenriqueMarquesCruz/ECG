package br.ufmg.sistole.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.ufmg.sistole.ui.theme.ElectrodeColors
import br.ufmg.sistole.ui.theme.PlexMono
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType

// ------------------------------------------------------------------ Tipografia auxiliar

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = S.c.inkMuted) =
    Text(text.uppercase(), modifier = modifier, style = SType.eyebrow, color = color, maxLines = 1)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Eyebrow(text, modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))

@Composable
fun Sub(text: String, modifier: Modifier = Modifier, color: Color = S.c.inkMuted, maxLines: Int = 2) =
    Text(text, modifier = modifier, style = SType.caption, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

// ------------------------------------------------------------------ Superfícies

@Composable
fun SCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(S.c.surface)
            .border(1.dp, S.c.line, RoundedCornerShape(20.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(S.c.surface)
            .border(1.dp, S.c.line, RoundedCornerShape(18.dp)),
        content = content,
    )
}

@Composable
fun RowDivider() = HorizontalDivider(thickness = 1.dp, color = S.c.line)

@Composable
fun SRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    lead: ImageVector? = null,
    leadContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadContent != null) leadContent()
        else if (lead != null) LeadIcon(lead)
        Column(Modifier.weight(1f)) {
            Text(title, style = SType.rowTitle, color = S.c.ink)
            if (detail != null) Sub(detail)
        }
        trailing()
    }
}

@Composable
fun LeadIcon(icon: ImageVector, bg: Color = S.c.surfaceRaised, tint: Color = S.c.ink) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = S.c.ink) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = SType.label.copy(fontWeight = FontWeight.Normal), color = S.c.inkMuted)
        Text(value, style = SType.data.copy(fontSize = 14.sp), color = valueColor)
    }
}

// ------------------------------------------------------------------ Botões

enum class BtnVariant { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun SButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.PRIMARY,
    small: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = S.c
    val (bg, fg, border) = when (variant) {
        BtnVariant.PRIMARY -> Triple(c.brand, c.onBrand, Color.Transparent)
        BtnVariant.SECONDARY -> Triple(c.surfaceRaised, c.ink, c.line)
        BtnVariant.GHOST -> Triple(Color.Transparent, c.ink, Color.Transparent)
        BtnVariant.DANGER -> Triple(c.statusAlert, c.onAlert, Color.Transparent)
    }
    val shape = RoundedCornerShape(if (small) 10.dp else 14.dp)
    Row(
        modifier
            .heightIn(min = if (small) 36.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(if (small) 16.dp else 18.dp), tint = fg)
        Text(text, style = if (small) SType.buttonSm else SType.button, color = fg, maxLines = 1)
    }
}

@Composable
fun SIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    on: Boolean = false,
    tint: Color = S.c.ink,
    size: Dp = 44.dp,
) {
    val c = S.c
    val bg = when { on -> c.brandSoft; flat -> Color.Transparent; else -> c.surfaceRaised }
    val border = when { on -> c.brand; flat -> Color.Transparent; else -> c.line }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
}

/** Ação da barra do Monitor: anel de 44 dp com rótulo embaixo. */
@Composable
fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true) {
    val c = S.c
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (on) c.brandSoft else c.surfaceRaised)
                .border(1.dp, if (on) c.brand else c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = c.ink) }
        Text(label, style = SType.chip.copy(fontSize = 11.sp), color = c.inkMuted, maxLines = 1)
    }
}

/** Botão circular de gravação: círculo = pronto para gravar, quadrado = gravando. */
@Composable
fun RecButton(recording: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val c = S.c
    val inner by animateFloatAsState(if (recording) 28f else 56f, tween(200), label = "rec")
    val radius by animateFloatAsState(if (recording) 7f else 28f, tween(200), label = "recr")
    Box(
        Modifier.size(76.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).border(3.dp, c.rec, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (recording) "Parar gravação" else "Gravar" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner.dp).clip(RoundedCornerShape(radius.dp)).background(c.rec))
    }
}

// ------------------------------------------------------------------ Chips

enum class ChipTone { PLAIN, OK, WARN, ALERT, IDLE, SCAN, REC, SEL }

@Composable
fun SChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.PLAIN,
    icon: ImageVector? = null,
    sub: String? = null,
    dot: Boolean = false,
    height: Dp = 30.dp,
    onClick: (() -> Unit)? = null,
) {
    val c = S.c
    val bg = when (tone) {
        ChipTone.OK -> c.okSoft
        ChipTone.WARN -> c.warnSoft
        ChipTone.ALERT, ChipTone.REC -> c.alertSoft
        ChipTone.SEL -> c.brandSoft
        else -> c.surfaceRaised
    }
    val border = when (tone) {
        ChipTone.PLAIN, ChipTone.IDLE, ChipTone.SCAN -> c.line
        ChipTone.SEL -> c.brand
        else -> Color.Transparent
    }
    val iconTint = when (tone) {
        ChipTone.OK -> c.statusOk
        ChipTone.WARN -> c.statusWarn
        ChipTone.ALERT -> c.statusAlert
        else -> c.ink
    }
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot || tone == ChipTone.SCAN || tone == ChipTone.REC || tone == ChipTone.IDLE) {
            val dotColor = when (tone) {
                ChipTone.SCAN -> c.brand
                ChipTone.REC, ChipTone.ALERT -> c.rec
                ChipTone.WARN -> c.statusWarn
                ChipTone.IDLE -> Color.Transparent
                else -> c.statusOk
            }
            val blink = tone == ChipTone.SCAN || tone == ChipTone.REC
            val a = if (blink) {
                val t = rememberInfiniteTransition(label = "blink")
                t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(if (tone == ChipTone.REC) 700 else 550), RepeatMode.Reverse), label = "a").value
            } else 1f
            Box(
                Modifier.size(8.dp).alpha(a).clip(CircleShape).background(dotColor)
                    .then(if (tone == ChipTone.IDLE) Modifier.border(1.5.dp, c.lineStrong, CircleShape) else Modifier),
            )
        }
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = iconTint)
        Text(
            text, maxLines = 1,
            style = if (tone == ChipTone.REC) SType.chip.copy(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold) else SType.chip,
            color = c.ink,
        )
        if (sub != null) Text(sub, style = SType.chip.copy(fontWeight = FontWeight.Normal), color = c.inkMuted, maxLines = 1)
    }
}

// ------------------------------------------------------------------ Controles

@Composable
fun SSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    icons: List<ImageVector?>? = null,
) {
    val c = S.c
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceRaised)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.surface else Color.Transparent, tween(150), label = "seg")
            Row(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(9.dp), ambientColor = c.shadow, spotColor = c.shadow) else Modifier)
                    .clip(RoundedCornerShape(9.dp))
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                icons?.getOrNull(i)?.let { Icon(it, null, Modifier.size(16.dp), tint = if (on) c.ink else c.inkMuted) }
                Text(
                    label, maxLines = 1,
                    style = if (mono) SType.dataSm else SType.label.copy(fontWeight = FontWeight.Medium),
                    color = if (on) c.ink else c.inkMuted,
                )
            }
        }
    }
}

@Composable
fun SSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String) {
    val c = S.c
    val x by animateFloatAsState(if (checked) 18f else 0f, tween(150), label = "sw")
    Box(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) c.brand else Color.Transparent)
            .border(2.dp, if (checked) c.brand else c.lineStrong, RoundedCornerShape(14.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics {
                contentDescription = description
                stateDescription = if (checked) "Ligado" else "Desligado"
            },
    ) {
        Box(
            Modifier.padding(3.dp).offset(x = x.dp).size(18.dp).clip(CircleShape)
                .background(if (checked) c.onBrand else c.lineStrong),
        )
    }
}

// ------------------------------------------------------------------ Banner

enum class BannerLevel { INFO, WARN, ALERT, SOLID }

@Composable
fun SBanner(
    level: BannerLevel,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = when (level) {
        BannerLevel.INFO -> SIcons.info
        BannerLevel.WARN -> SIcons.warn
        else -> SIcons.alert
    },
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = S.c
    val (bg, border, iconTint, fg) = when (level) {
        BannerLevel.INFO -> Quad(c.okSoft, Color.Transparent, c.statusOk, c.ink)
        BannerLevel.WARN -> Quad(c.warnSoft, c.statusWarn, c.statusWarn, c.ink)
        BannerLevel.ALERT -> Quad(c.alertSoft, c.statusAlert, c.statusAlert, c.ink)
        BannerLevel.SOLID -> Quad(c.statusAlert, Color.Transparent, c.onAlert, c.onAlert)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { if (level == BannerLevel.SOLID) contentDescription = listOfNotNull(title, text).joinToString(". ") },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(if (level == BannerLevel.SOLID) 22.dp else 20.dp), tint = iconTint)
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = SType.bannerTitle, color = fg, modifier = Modifier.padding(bottom = 2.dp))
            Text(text, style = SType.banner, color = fg)
        }
        if (action != null && onAction != null) {
            val btnBg = if (level == BannerLevel.SOLID) c.onAlert else c.surfaceRaised
            val btnFg = if (level == BannerLevel.SOLID) c.statusAlert else c.ink
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(btnBg)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, style = SType.buttonSm, color = btnFg, maxLines = 1) }
        }
    }
}

private data class Quad(val a: Color, val b: Color, val c: Color, val d: Color)

// ------------------------------------------------------------------ Vitais

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, sub: String? = null, valueColor: Color = S.c.ink, valueStyle: TextStyle = SType.stat, extra: @Composable ColumnScope.() -> Unit = {}) {
    val c = S.c
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = SType.eyebrow.copy(letterSpacing = SType.overlay.letterSpacing), color = c.inkMuted, maxLines = 1)
        Row {
            Text(value, style = valueStyle, color = valueColor, maxLines = 1, modifier = Modifier.alignByBaseline())
            if (unit != null) Text(unit, style = SType.dataSm, color = c.inkMuted, modifier = Modifier.alignByBaseline().padding(start = 3.dp))
        }
        if (sub != null) Sub(sub)
        extra()
    }
}

/** Coração que pulsa (320 ms, escala 1,28) a cada pico R. */
@Composable
fun BeatingHeart(beatTick: Long, size: Dp = 14.dp) {
    val anim = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        anim.snapTo(1f)
        anim.animateTo(1.28f, tween(64))
        anim.animateTo(1f, tween(256))
    }
    Icon(SIcons.heart, null, Modifier.size(size).scale(anim.value), tint = S.c.statusAlert)
}

// ------------------------------------------------------------------ Qualidade e RSSI

@Composable
fun QualityBars(level: Int, modifier: Modifier = Modifier) {
    val c = S.c
    val color = when (level) { 4, 3 -> c.statusOk; 2 -> c.statusWarn; else -> c.statusAlert }
    Row(
        modifier.height(18.dp).semantics { contentDescription = "Qualidade ${br.ufmg.sistole.engine.qualityWord(level)}" },
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(6, 10, 14, 18).forEachIndexed { i, h ->
            val filled = i < level
            Box(
                Modifier.width(5.dp).height(h.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(if (filled) color else c.surfaceRaised)
                    .border(1.dp, if (filled) color else c.lineStrong, RoundedCornerShape(1.5.dp)),
            )
        }
    }
}

fun rssiLevel(rssi: Int) = when { rssi >= -60 -> 4; rssi >= -70 -> 3; rssi >= -80 -> 2; else -> 1 }

@Composable
fun RssiBars(rssi: Int) {
    val c = S.c
    val level = rssiLevel(rssi)
    Row(
        Modifier.height(14.dp).semantics { contentDescription = "Sinal $rssi dBm" },
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(4, 7, 10, 14).forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.dp)).background(if (i < level) c.ink else c.lineStrong))
        }
    }
}

@Composable
fun ElectrodeBadge(letter: String, aha: Boolean = false, size: Dp = 30.dp) {
    val (bg, fg) = electrodeColors(letter, aha)
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).border(2.dp, S.c.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(letter, style = SType.dataSm.copy(fontWeight = FontWeight.Bold), color = fg) }
}

fun electrodeColors(letter: String, aha: Boolean): Pair<Color, Color> = if (!aha) when (letter) {
    "R" -> ElectrodeColors.R to Color(0xFF1A0506)
    "L" -> ElectrodeColors.L to Color(0xFF1F1A00)
    "F" -> ElectrodeColors.F to Color(0xFF03140C)
    else -> ElectrodeColors.N to Color(0xFFEAF1ED)
} else when (letter) {
    "R" -> ElectrodeColors.R_AHA to Color(0xFF191B1A)
    "L" -> ElectrodeColors.L_AHA to Color(0xFFEAF1ED)
    "F" -> ElectrodeColors.F_AHA to Color(0xFF1A0506)
    else -> ElectrodeColors.N_AHA to Color(0xFF03140C)
}

// ------------------------------------------------------------------ Barra superior e abas

@Composable
fun AppBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    small: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) SIconButton(SIcons.back, "Voltar", onBack, flat = true)
        if (eyebrow != null || title != null || subtitle != null) {
            Column(Modifier.weight(1f)) {
                if (eyebrow != null) Text(eyebrow.uppercase(), style = SType.eyebrow, color = S.c.inkMuted)
                if (title != null) Text(title, style = if (small) SType.title else SType.titleLg, color = S.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Sub(subtitle, maxLines = 1)
            }
        }
        actions()
    }
}

enum class Tab(val route: String, val label: String) {
    MONITOR("monitor", "Monitor"),
    SESSOES("sessoes", "Sessões"),
    DISPOSITIVO("dispositivo", "Dispositivo"),
    AJUSTES("ajustes", "Ajustes"),
}

@Composable
fun TabBar(active: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = S.c
    Column(modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Tab.entries.forEach { t ->
                val on = t == active
                val icon = when (t) {
                    Tab.MONITOR -> SIcons.pulse
                    Tab.SESSOES -> SIcons.list
                    Tab.DISPOSITIVO -> SIcons.chip
                    Tab.AJUSTES -> SIcons.sliders
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(t) }
                        .semantics { selected = on }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(width = 60.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (on) c.brandSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(22.dp), tint = if (on) c.ink else c.inkMuted) }
                    Text(t.label, style = SType.tab.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.ink else c.inkMuted)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Opção de exportação (radio)

@Composable
fun OptionCard(ext: String, title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = S.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.brandSoft else c.surface)
            .border(BorderStroke(1.5.dp, if (selected) c.brand else c.line), RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            Text(ext, style = SType.dataSm.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = SType.button, color = c.ink)
            Sub(detail)
        }
        Box(Modifier.size(20.dp).clip(CircleShape).border(if (selected) 6.dp else 2.dp, if (selected) c.brand else c.lineStrong, CircleShape))
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

@Composable
fun rememberNoRipple() = remember { MutableInteractionSource() }
