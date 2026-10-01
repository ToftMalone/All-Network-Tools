package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.data.P2pPeer
import com.allnetworktools.data.P2pStatus
import com.allnetworktools.data.WifiDirect
import com.allnetworktools.data.WifiDirectRepository
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.plural
import kotlinx.coroutines.flow.catch

/** Peers list while the screen is open; null until the first answer. Discovery stops when the screen leaves. */
@Composable
fun WifiDirectTool(repo: WifiDirectRepository, permitted: Boolean, permissionText: String, onFix: () -> Unit) {
    PageColumn {
        if (!repo.supported) {
            ToolError(Sym.WifiOff, "Wi-Fi Direct indisponible", "Ce téléphone ne prend pas en charge le Wi-Fi Direct.", "OK") {}
            return@PageColumn
        }
        if (!permitted) {
            ToolError(Sym.Lock, "Autorisation requise", permissionText, "Autoriser", onFix)
            return@PageColumn
        }
        val flow = remember { repo.peers().catch { emit(emptyList()) } }
        val peers: List<P2pPeer>? by flow.collectAsStateWithLifecycle(initialValue = null)
        val list = peers.orEmpty()
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(list.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(list.size, "appareil", "appareils"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                "Imprimantes, téléviseurs, projecteurs et téléphones qui acceptent une connexion directe, sans box.",
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) { HeroChip("Recherche Wi-Fi Direct active", AntTheme.net.good, blink = true) }
        }
        if (peers != null && list.isEmpty()) {
            SectionCard {
                Text("Aucun appareil pour l'instant", style = rf(16, 22, 600))
                Text(
                    "Les appareils n'apparaissent que si leur Wi-Fi Direct est allumé et visible (menu « Wi-Fi Direct » d'une imprimante ou d'un téléviseur, " +
                        "ou réglages Wi-Fi Direct d'un autre téléphone ouverts).",
                    Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
        if (list.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("À proximité", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                list.forEach { p ->
                    Hairline()
                    PeerRow(p)
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Pendant la recherche, votre téléphone est lui aussi visible en Wi-Fi Direct sous son nom d'appareil. " +
                        "Aucune connexion n'est établie. Les adresses MAC peuvent être aléatoires.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeerRow(p: P2pPeer) {
    val cat = WifiDirect.category(p.deviceType)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
            Symbol(cat?.icon ?: Sym.WifiTethering, size = 22.dp, filled = true, tint = AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = rf(15, 20, 600), maxLines = 1)
            Text(
                listOfNotNull(cat?.label, p.address, if (p.groupOwner) "propriétaire de groupe" else null).joinToString(" · "),
                style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2,
            )
        }
        val n = AntTheme.net
        val (bg, fg) = when (p.status) {
            P2pStatus.Connected -> n.good to cs.surface
            P2pStatus.Available -> AntTheme.accent.container to AntTheme.accent.onContainer
            P2pStatus.Failed -> n.poor to cs.surface
            else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        }
        TechChip(p.status.label, bg, fg)
    }
}
