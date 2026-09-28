package com.allnetworktools.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.gnss.headingLabel
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.plural

private data class ToolEntry(val tool: Tool, val title: String, val subtitle: String, val icon: String = tool.icon)
private data class ToolGroup(val title: String, val items: List<ToolEntry>)

private fun groups(net: Network, connectedDevice: String?, servingLabel: String?): List<ToolGroup> = when (net) {
    Network.Wifi -> listOf(
        ToolGroup("Analyse", listOf(ToolEntry(Tool.Channels, "Analyseur de canaux", "Canal recommandé"), ToolEntry(Tool.Speed, "Test de débit", "Ping, download, upload"))),
        ToolGroup("Réseau local", listOf(ToolEntry(Tool.Lan, "Appareils du LAN", "Découverte des hôtes"), ToolEntry(Tool.Ports, "Scan de ports", "TCP, ports courants"))),
        ToolGroup("Découverte", listOf(ToolEntry(Tool.Upnp, "Scanner UPnP", "Box, TV, NAS, SSDP"), ToolEntry(Tool.Bonjour, "Scanner Bonjour", "Services mDNS / DNS-SD"))),
        ToolGroup(
            "Diagnostic",
            listOf(ToolEntry(Tool.Ping, "Ping", "Latence, gigue, pertes"), ToolEntry(Tool.Trace, "Traceroute", "Sauts jusqu'à l'hôte"), ToolEntry(Tool.Dns, "DNS Lookup", "A, AAAA, MX, TXT, NS"), ToolEntry(Tool.Whois, "Whois", "Domaine ou adresse IP")),
        ),
    )
    Network.Bluetooth -> listOf(
        ToolGroup(
            "Appareils",
            listOfNotNull(
                ToolEntry(Tool.Tracker, "Traqueur de proximité", "Chaud / froid"),
                ToolEntry(Tool.Gatt, "Services GATT", "Lecture / écriture"),
                ToolEntry(Tool.Paired, connectedDevice ?: "Appareil appairé", if (connectedDevice != null) "Appareil connecté" else "Profils, batterie", Sym.Headphones),
            ),
        ),
    )
    Network.Cellular -> listOf(
        ToolGroup("Historique", listOf(ToolEntry(Tool.CellLog, "Journal des cellules", "Changements de cellule"), ToolEntry(Tool.SignalHistory, "Historique du signal", "1 h à 30 j"))),
        ToolGroup("Consommation", listOf(ToolEntry(Tool.DataUsage, "Données mobiles", "Par SIM et par app"), ToolEntry(Tool.CellDetail, "Cellule de service", servingLabel ?: "Identifiants et mesures"))),
    )
    Network.Gnss -> listOf(
        ToolGroup("Outils", listOf(ToolEntry(Tool.Compass, "Boussole", "Cap magnétique et vrai"), ToolEntry(Tool.Nmea, "Journal NMEA", "Phrases du récepteur"))),
    )
}

private fun featuredSubtitle(net: Network) = when (net) {
    Network.Wifi -> "Réseaux alentour et occupation des canaux"
    Network.Bluetooth -> "Appareils à proximité, en direct, avec filtres"
    Network.Cellular -> "Cellules NR et LTE détectées par le modem"
    Network.Gnss -> "Sky plot et carte du monde des satellites"
}

@Composable
private fun featuredBadge(net: Network, vm: MainViewModel): String? = when (net) {
    Network.Wifi -> vm.wifiScan.collectAsStateWithLifecycle().value?.let { "${it.size} ${plural(it.size, "réseau", "réseaux")}" }
    Network.Bluetooth -> vm.ble.collectAsStateWithLifecycle().value.size.let { "$it ${plural(it, "appareil")}" }
    Network.Cellular -> vm.cell.collectAsStateWithLifecycle().value.state?.neighbors?.size?.let { "$it ${plural(it, "cellule")}" }
    Network.Gnss -> vm.gnss.collectAsStateWithLifecycle().value.let { "${it.used.size}/${it.visible.size} satellites" }
}

@Composable
fun ToolsPage(net: Network, vm: MainViewModel) {
    val acc = AntTheme.accent
    val open = { tool: Tool -> vm.navigate { it.copy(page = Page.ToolPage(tool)) } }
    val connected = vm.bluetooth.collectAsStateWithLifecycle().value.connected.firstOrNull()?.name
    val serving = vm.cell.collectAsStateWithLifecycle().value.state?.serving
    val servingLabel = serving?.let { s -> listOfNotNull(s.pci?.let { "PCI $it" }, s.band).joinToString(" · ").ifEmpty { null } }
    val badge = featuredBadge(net, vm)
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(acc.container)
                .clickable { open(net.featured) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(net.featured.icon, cookieShape(), 72.dp, acc.accent, acc.onAccent, 34.dp, spinMs = 30_000)
                if (badge != null) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(badge, style = rf(12, 18, 600), color = cs.onSurface)
                    }
                }
            }
            Text(net.featured.title, Modifier.padding(top = 16.dp), style = gs(26, 32, 500), color = acc.onContainer)
            Text(featuredSubtitle(net), Modifier.padding(top = 2.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = acc.onContainer)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Lancer", { open(net.featured) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        groups(net, connected, servingLabel).forEach { g ->
            SectionTitle(g.title)
            TileGrid(g.items) { e, mod ->
                Column(
                    mod.fadeUp(60).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow)
                        .clickable { open(e.tool) }.heightIn(min = 132.dp).padding(16.dp),
                ) {
                    LeadingIcon(e.icon, cs.surfaceContainerHighest, acc.accent, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.weight(1f, fill = false).heightIn(min = 12.dp))
                    Text(e.title, Modifier.padding(top = 12.dp), style = rf(15, 20, 600), maxLines = 2)
                    Text(e.subtitle, Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}
