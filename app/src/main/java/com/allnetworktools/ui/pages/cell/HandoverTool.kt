package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.data.HandoverEvent
import com.allnetworktools.data.HandoverLog
import com.allnetworktools.data.PermGroup
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.Export
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

@Composable
fun HandoverTool(notificationsGranted: Boolean) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val running by HandoverLog.running.collectAsStateWithLifecycle()
    val events by HandoverLog.events.collectAsStateWithLifecycle()
    val changes = events.count { !it.isStart }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(changes.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(changes, "changement de cellule", "changements de cellule"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                when {
                    running -> "Depuis ${clock.format(HandoverLog.startedAt)} · continue même application fermée"
                    events.isNotEmpty() -> "Session arrêtée · ${events.size} ${plural(events.size, "ligne", "lignes")} à exporter"
                    else -> "Chaque passage d'une cellule à une autre, ou de la 4G à la 5G, avec l'heure et le lieu"
                },
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                if (running) HeroChip("Enregistrement en cours", AntTheme.net.good, blink = true)
                else HeroChip("À l'arrêt", cs.outline)
            }
        }
        if (running) {
            ToolButtons(ToolButton("Arrêter", Sym.Stop, BtnKind.Big) { HandoverLog.stop(context) })
        } else {
            ToolButtons(
                ToolButton(if (events.isEmpty()) "Démarrer" else "Nouvelle session", Sym.PlayArrow, BtnKind.Big) {
                    if (!notificationsGranted) actions.request(PermGroup.Notifications)
                    HandoverLog.start(context)
                },
            )
        }
        if (events.isNotEmpty()) {
            ToolButtons(
                ToolButton("Exporter en CSV", Sym.Download, BtnKind.Outline) {
                    val snapshot = HandoverLog.csv(events)
                    actions.saveFile("handover_${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
                },
            )
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                events.asReversed().take(200).forEachIndexed { i, e ->
                    if (i > 0) Hairline()
                    EventRow(e)
                }
            }
            if (events.size > 200) {
                Text("Les 200 derniers changements sont affichés ; l'export contient toute la session.", Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        SectionCard {
            Text(
                "Le journal tourne en arrière-plan avec une notification, jusqu'à ce que vous l'arrêtiez ici ou depuis la notification. " +
                    "Une nouvelle session efface la précédente : rien n'est conservé sur le téléphone, exportez-la avant si besoin.",
                style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventRow(e: HandoverEvent) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (e.isStart) Sym.PlayArrow else Sym.SwapCalls, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text(
                if (e.isStart) "Début · ${e.toTech} · ${e.toCell ?: "?"}" else "${e.fromCell ?: "?"} → ${e.toCell ?: "?"}",
                style = rf(14, 20, 600), maxLines = 1,
            )
            Text(
                listOfNotNull(
                    clock.format(e.timeMs),
                    if (!e.isStart && e.fromTech != e.toTech) "${e.fromTech} → ${e.toTech}" else e.toTech,
                    e.band, e.pci?.let { "PCI $it" }, e.levelDbm?.let { "$it dBm" },
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}
