package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.CellEvent
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class LogFilter(val label: String) { All("Tous"), Tech("Changements de techno"), Nr("5G"), Lte("4G") }

private fun CellEvent.matches(f: LogFilter) = when (f) {
    LogFilter.All -> true
    LogFilter.Tech -> kind == "up" || kind == "down"
    LogFilter.Nr -> tech == "NR"
    LogFilter.Lte -> tech == "LTE"
}

private val CellEvent.icon get() = when (kind) {
    "up" -> Sym.ArrowUpward
    "down" -> Sym.ArrowDownward
    "lost" -> Sym.CellNoData
    "back" -> Sym.CellTower
    else -> Sym.SwapHoriz
}

private val CellEvent.techChange get() = kind == "up" || kind == "down"

/** "Aujourd'hui", "Hier" or the full date. */
fun dayLabel(atMs: Long, now: Long = System.currentTimeMillis()): String {
    val a = Calendar.getInstance().apply { timeInMillis = atMs }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val days = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 366 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR)
    return when (days) {
        0 -> "Aujourd'hui"
        1 -> "Hier"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(atMs)).replaceFirstChar { it.uppercase() }
    }
}

private fun csv(events: List<CellEvent>): String = buildString {
    appendLine("date;type;titre;detail;note;techno;niveau_dbm")
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
    events.forEach { e -> appendLine(listOf(f.format(Date(e.atMs)), e.kind, e.title, e.detail, e.note, e.tech.orEmpty(), e.level?.toString().orEmpty()).joinToString(";") { it.replace(';', ',') }) }
}

private fun json(events: List<CellEvent>): String = org.json.JSONArray(
    events.map { e ->
        org.json.JSONObject().put("at", e.atMs).put("kind", e.kind).put("title", e.title).put("detail", e.detail).put("note", e.note).put("tech", e.tech).put("level", e.level)
    },
).toString(2)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CellLogTool(vm: MainViewModel) {
    val events by vm.history.cellEvents.collectAsStateWithLifecycle()
    val active by vm.recording.active.collectAsStateWithLifecycle()
    val recording = com.allnetworktools.service.RecKind.CellLog in active
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    var filter by rememberSaveable { mutableStateOf(LogFilter.All) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val shown = events.filter { it.matches(filter) }
    val time = SimpleDateFormat("HH:mm", Locale.FRANCE)
    PageColumn {
        com.allnetworktools.ui.pages.RecordingCard(
            vm, com.allnetworktools.service.RecKind.CellLog,
            idleText = "Lancez-le pour consigner les changements de cellule, même application fermée.",
            activeText = "Continue en arrière-plan · notification pour l'arrêter",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilter.entries.forEach { f -> AntFilterChip(f.label, filter == f, { filter = f }) }
            }
            IconCircleButton(Sym.IosShare, { if (events.isEmpty()) actions.toast("Le journal est vide") else sheet = true }, size = 40.dp, tint = cs.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stockés sur cet appareil", Modifier.weight(1f), style = rf(13, 18, 500), color = cs.onSurfaceVariant)
            Text("${shown.size} ${plural(shown.size, "événement")}", style = rf(13, 18), color = cs.onSurfaceVariant)
        }
        if (shown.isEmpty()) {
            ToolEmpty(
                Sym.Timeline, "Aucun changement enregistré",
                if (events.isEmpty() && !recording) "Lancez l'enregistrement : chaque changement de cellule ou de technologie sera consigné ici."
                else if (events.isEmpty()) "Vous êtes resté sur la même cellule depuis le début de l'enregistrement. Les changements s'afficheront ici."
                else "Aucun événement ne correspond à ce filtre.",
                if (events.isEmpty()) null else "Tout afficher",
            ) { filter = LogFilter.All }
        }
        var index = 0
        shown.groupBy { dayLabel(it.atMs) }.forEach { (day, items) ->
            Text(day, Modifier.padding(start = 4.dp, top = 8.dp), style = rf(14, 20, 600), color = acc.accent)
            Column {
                items.forEachIndexed { i, e ->
                    EventRow(e, time.format(Date(e.atMs)), first = i == 0, last = i == items.lastIndex, modifier = Modifier.rise(index++, 40), dim = !recording)
                }
            }
        }
    }
    if (sheet) {
        val scope = rememberCoroutineScope()
        var format by rememberSaveable { mutableStateOf("CSV") }
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = cs.surfaceContainerLow) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Exporter le journal", style = gs(22, 28, 500))
                Text("${events.size} ${plural(events.size, "événement")} · stockés sur cet appareil", style = rf(13, 18), color = cs.onSurfaceVariant)
                listOf(
                    Triple("CSV", Sym.TableView, "Tableur · une ligne par événement"),
                    Triple("JSON", Sym.DataObject, "Données brutes complètes"),
                ).forEach { (f, icon, sub) ->
                    val a = format == f
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (a) acc.container else cs.surface).clickable { format = f }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Symbol(icon, size = 24.dp, tint = if (a) acc.onContainer else cs.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(f, style = rf(15, 20, 600), color = if (a) acc.onContainer else cs.onSurface)
                            Text(sub, style = rf(12, 16), color = if (a) acc.onContainer else cs.onSurfaceVariant)
                        }
                        if (a) Symbol(Sym.CheckCircle, size = 22.dp, filled = true, tint = acc.onContainer)
                    }
                }
                PillButton(
                    "Exporter et partager",
                    {
                        sheet = false
                        scope.launch { actions.share("Journal des cellules", if (format == "CSV") csv(events) else json(events)) }
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp), icon = Sym.IosShare, bg = acc.accent, fg = acc.onAccent,
                )
            }
        }
    }
}

@Composable
private fun EventRow(e: CellEvent, time: String, first: Boolean, last: Boolean, modifier: Modifier, dim: Boolean) {
    val acc = AntTheme.accent
    val net = AntTheme.net
    val line = cs.outlineVariant
    val (dotBg, dotFg) = when (e.kind) {
        "up" -> acc.accent to acc.onAccent
        "down" -> net.fair to cs.surface
        "lost" -> cs.error to cs.onError
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(time, Modifier.width(40.dp).padding(top = 14.dp), style = rf(12, 16, 500, tnum = true), color = cs.onSurfaceVariant)
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(10.dp).background(if (first) Color.Transparent else line))
            Box(
                Modifier.size(32.dp).clip(if (e.techChange) RoundedCornerShape(10.dp) else CircleShape).background(dotBg),
                contentAlignment = Alignment.Center,
            ) { Symbol(e.icon, size = 18.dp, filled = true, tint = dotFg) }
            Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else line))
        }
        Column(
            Modifier.weight(1f).padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                .background(if (e.techChange) cs.surfaceContainer else cs.surfaceContainerLow)
                .then(if (e.techChange) Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val alpha = if (dim) 0.7f else 1f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, Modifier.weight(1f), style = rf(14, 20, 600), color = cs.onSurface.copy(alpha = alpha))
                e.level?.let { Text("${com.allnetworktools.util.fmt(it)} dBm", style = rf(12, 16, 500, tnum = true), color = cs.onSurfaceVariant) }
            }
            if (e.detail.isNotEmpty()) Text(e.detail, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            if (e.note.isNotEmpty()) Text(e.note, style = rf(12, 16), color = cs.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}
