package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.Routes
import br.ufmg.sistole.data.SessionMeta
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.ChipTone
import br.ufmg.sistole.ui.components.EcgStrip
import br.ufmg.sistole.ui.components.SChip
import br.ufmg.sistole.ui.components.SIconButton
import br.ufmg.sistole.ui.components.SectionLabel
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.fmt.Fmt
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType
import kotlinx.coroutines.launch
import java.util.Calendar

private enum class SessionFilter(val label: String) { ALL("Todas"), EVENTS("Com eventos"), WEEK("Esta semana"), ALARMS("FC fora da faixa") }

@Composable
fun SessionsScreen(nav: NavHostController) {
    val sessions by app.sessions.sessions.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(SessionFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<SessionMeta?>(null) }
    var renaming by remember { mutableStateOf<SessionMeta?>(null) }
    var deleting by remember { mutableStateOf<SessionMeta?>(null) }
    val scope = rememberCoroutineScope()

    val weekAgo = remember { Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis }
    val shown = sessions.filter { s ->
        when (filter) {
            SessionFilter.ALL -> true
            SessionFilter.EVENTS -> s.events.isNotEmpty()
            SessionFilter.WEEK -> s.startedAt >= weekAgo
            SessionFilter.ALARMS -> s.summary?.let { it.hrMax > settings.alarmHigh || (it.hrMin in 1 until settings.alarmLow) } == true
        }
    }.filter { s ->
        query.isBlank() || s.title.contains(query, true) || s.volunteer.contains(query, true)
    }
    val totalBytes = sessions.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Sessões", subtitle = "${sessions.size} ${if (sessions.size == 1) "gravação" else "gravações"} · ${Fmt.bytes(totalBytes)} no aparelho") {
            SIconButton(if (searching) SIcons.close else SIcons.search, if (searching) "Fechar busca" else "Buscar", { searching = !searching; if (!searching) query = "" })
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searching) item { SField(query, { query = it }, "Buscar por título ou voluntário") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionFilter.entries.forEach { f -> SChip(f.label, tone = if (f == filter) ChipTone.SEL else ChipTone.PLAIN, onClick = { filter = f }) }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (sessions.isEmpty()) "Nenhuma gravação ainda" else "Nada neste filtro", style = SType.title, color = S.c.ink)
                        Sub(if (sessions.isEmpty()) "Grave no Monitor pelo botão vermelho." else "Troque o filtro ou a busca.", Modifier.padding(top = 4.dp))
                    }
                }
            }
            val groups = shown.groupBy { Fmt.dayLabel(it.startedAt) }
            groups.forEach { (day, list) ->
                item(key = "h-$day") { SectionLabel(day) }
                items(list, key = { it.id }) { s ->
                    SessionCard(s, settings.alarmHigh, onClick = { nav.navigate(Routes.review(s.id)) }, onLong = { menuFor = s })
                }
            }
        }
    }

    menuFor?.let { s ->
        SSheet({ menuFor = null }) {
            SheetHeader(s.title, { menuFor = null })
            SessionAction(SIcons.edit, "Renomear") { renaming = s; menuFor = null }
            SessionAction(SIcons.share, "Revisar e exportar") { nav.navigate(Routes.review(s.id)); menuFor = null }
            SessionAction(SIcons.trash, "Excluir", danger = true) { deleting = s; menuFor = null }
        }
    }
    renaming?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = S.c.surface,
            title = { Text("Renomear sessão", style = SType.title, color = S.c.ink) },
            text = { SField(name, { name = it }, "Título") },
            confirmButton = { TextButton({ scope.launch { app.sessions.rename(s.id, name.trim().ifBlank { s.title }) }; renaming = null }) { Text("Salvar", color = S.c.brand) } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancelar", color = S.c.inkMuted) } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = S.c.surface,
            title = { Text("Excluir “${s.title}”?", style = SType.title, color = S.c.ink) },
            text = { Text("A gravação de ${Fmt.clock(s.durationS)} some do aparelho. Exporte antes se quiser guardar.", style = SType.body, color = S.c.inkMuted) },
            confirmButton = { TextButton({ scope.launch { app.sessions.delete(s.id) }; deleting = null }) { Text("Excluir", color = S.c.statusAlert) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancelar", color = S.c.inkMuted) } },
        )
    }
}

@Composable
private fun SessionAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = S.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (danger) c.statusAlert else c.ink)
        Text(label, style = SType.rowTitle, color = if (danger) c.statusAlert else c.ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: SessionMeta, alarmHigh: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val c = S.c
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.title, style = SType.title, color = c.ink, maxLines = 1)
                Sub(listOf(Fmt.time(s.startedAt), s.volunteer.ifBlank { null }, s.lead).filterNotNull().joinToString(" · "))
            }
            val sum = s.summary
            when {
                sum != null && sum.hrMax > alarmHigh -> SChip("FC alta", tone = ChipTone.ALERT, icon = SIcons.alert, height = 26.dp)
                s.events.isNotEmpty() -> SChip("${s.events.size} ${if (s.events.size == 1) "evento" else "eventos"}", tone = ChipTone.WARN, icon = SIcons.flag, height = 26.dp)
                else -> SChip("Sem eventos", tone = ChipTone.OK, icon = SIcons.check, height = 26.dp)
            }
        }
        s.preview?.let { p ->
            Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))) {
                EcgStrip(p, p.size / 3.0, gainMmMv = 5f, base = 0.66f, lineWidth = 1.3.dp, scaleToWidth = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SIcons.clock, null, Modifier.size(13.dp), tint = c.inkMuted)
            MetaText(Fmt.clock(s.durationS), bold = true)
            s.summary?.let {
                Row { MetaText("FC "); MetaText("${it.hrMean}", bold = true); MetaText(" bpm") }
                MetaText("${it.hrMin}–${it.hrMax}")
            }
        }
    }
}

@Composable
private fun MetaText(t: String, bold: Boolean = false) =
    Text(t, style = SType.dataSm.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium), color = if (bold) S.c.ink else S.c.inkMuted)
