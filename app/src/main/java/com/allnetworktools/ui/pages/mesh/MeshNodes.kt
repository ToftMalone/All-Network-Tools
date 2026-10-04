package com.allnetworktools.ui.pages.mesh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.mesh.MeshConn
import com.allnetworktools.data.mesh.MeshNode
import com.allnetworktools.data.mesh.MeshProto
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.util.plural
import java.util.Locale

/** What the phone can measure a distance from: our node's position, else the phone's own. */
@Composable
internal fun myPosition(vm: MainViewModel): Pair<Double, Double>? {
    val me = vm.mesh.myNum.collectAsStateWithLifecycle().value
    val nodes by vm.mesh.nodes.collectAsStateWithLifecycle()
    nodes[me]?.position?.let { return it.lat to it.lon }
    val positions by vm.positions.collectAsStateWithLifecycle()
    return (positions.fused ?: positions.gnss ?: positions.network)?.let { it.latitude to it.longitude }
}

private enum class NodeSort(val label: String) { Heard("Dernier contact"), Name("Nom"), Distance("Distance"), Hops("Sauts") }

/** Nodes: every node our node knows, newest first, with search and sorting, as in the official app. */
@Composable
fun MeshNodesPage(vm: MainViewModel) {
    val mesh = vm.mesh
    val conn by mesh.conn.collectAsStateWithLifecycle()
    val nodes by mesh.nodes.collectAsStateWithLifecycle()
    val me = mesh.myNum.collectAsStateWithLifecycle().value
    val now = rememberEpochS()
    val here = myPosition(vm)
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(NodeSort.Heard) }
    val online = nodes.values.count { it.num != me && now - it.lastHeardS < 2 * 3600 }
    fun dist(n: MeshNode) = if (here != null && n.position != null) distanceM(here.first, here.second, n.position.lat, n.position.lon) else null
    val shown = nodes.values.filter { !it.isIgnored }
        .filter { query.isBlank() || it.longName.contains(query, true) || it.shortName.contains(query, true) || it.id.contains(query, true) }
        .sortedWith(
            compareByDescending<MeshNode> { it.num == me }.thenByDescending { it.isFavorite }.then(
                when (sort) {
                    NodeSort.Heard -> compareByDescending { it.lastHeardS }
                    NodeSort.Name -> compareBy { it.longName.lowercase() }
                    NodeSort.Distance -> compareBy { dist(it) ?: Double.MAX_VALUE }
                    NodeSort.Hops -> compareBy { it.hopsAway ?: 99 }
                },
            ),
        )
    PageColumn {
        if (conn !is MeshConn.Connected) NotConnectedCard(vm, conn)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("${nodes.size}", style = gs(40, 44, 500, -1f, tnum = true))
            Text("${plural(nodes.size, "nœud")} · $online en ligne", Modifier.padding(bottom = 6.dp), style = rf(15, 20, 500))
        }
        HostInputField(query, { query = it }, "Filtrer par nom ou identifiant", Sym.Search)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NodeSort.entries.forEach { s -> AntFilterChip(s.label, sort == s, { sort = s }) }
        }
        if (shown.isEmpty()) {
            SectionCard { Text(if (nodes.isEmpty()) "Aucun nœud connu. Ils apparaissent dès que votre nœud est connecté." else "Aucun nœud ne correspond.", style = rf(13, 18), color = cs.onSurfaceVariant) }
            return@PageColumn
        }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            shown.forEachIndexed { i, n ->
                if (i > 0) Hairline()
                NodeRow(n, n.num == me, now, dist(n)) { vm.navigate { it.copy(page = Page.ToolPage(Tool.MeshNode, n.num.toString())) } }
            }
        }
    }
}

@Composable
private fun NodeRow(n: MeshNode, mine: Boolean, nowS: Long, distM: Double?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NodeBadge(n)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.longName, Modifier.weight(1f, fill = false), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.isFavorite) Symbol(Sym.Star, size = 16.dp, filled = true, tint = AntTheme.net.fair)
                if (mine) TechChip("Vous", AntTheme.accent.accent, AntTheme.accent.onAccent)
            }
            val line = buildList {
                if (!mine) add(ago(nowS, n.lastHeardS))
                n.hopsAway?.let { if (!mine) add(if (it == 0) "direct" else "$it ${plural(it, "saut")}") }
                distM?.let { if (!mine) add(distanceText(it)) }
                if (n.viaMqtt) add("MQTT")
            }.joinToString(" · ")
            Text(line.ifEmpty { n.id }, style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 1)
            val line2 = buildList {
                n.user?.let { add(MeshProto.roleName(it.role)) }
                n.metrics?.battery?.let { add(if (it > 100) "secteur" else "batterie $it %") }
                n.snr?.let { if (!mine) add("SNR %.1f dB".format(Locale.FRANCE, it)) }
            }.joinToString(" · ")
            if (line2.isNotEmpty()) Text(line2, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A node's details and actions: message, favourite, exchange user info, ignore, remove. */
@Composable
fun MeshNodePage(vm: MainViewModel, arg: String?) {
    val mesh = vm.mesh
    val nodes by mesh.nodes.collectAsStateWithLifecycle()
    val me = mesh.myNum.collectAsStateWithLifecycle().value
    val conn by mesh.conn.collectAsStateWithLifecycle()
    val num = arg?.toLongOrNull()
    val n = num?.let { nodes[it] ?: MeshNode(it) }
    val now = rememberEpochS()
    val here = myPosition(vm)
    val actions = com.allnetworktools.ui.LocalActions.current
    PageColumn {
        if (n == null) { SectionCard { Text("Nœud introuvable.", style = rf(13, 18)) }; return@PageColumn }
        val mine = n.num == me
        SectionCard(color = AntTheme.accent.container) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NodeBadge(n, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(n.longName, style = gs(22, 28, 500), color = AntTheme.accent.onContainer)
                    Text("${n.shortName} · ${n.id}", style = rf(13, 18), color = AntTheme.accent.onContainer)
                    n.user?.let { Text("${MeshProto.hwModel(it.hwModel)} · ${MeshProto.roleName(it.role)}", style = rf(13, 18), color = AntTheme.accent.onContainer) }
                }
            }
        }
        if (!mine) {
            val connected = conn is MeshConn.Connected
            PillButton("Message direct", { vm.navigate { it.copy(page = Page.ToolPage(Tool.MeshChat, "d${n.num}")) } }, Modifier.fillMaxWidth(), icon = Sym.Chat, height = 48.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AntFilterChip(if (n.isFavorite) "Favori" else "Ajouter aux favoris", n.isFavorite, { if (!mesh.setFavorite(n.num, !n.isFavorite)) actions.toast("Connectez d'abord votre nœud") })
                AntFilterChip("Échanger les infos", false, { actions.toast(if (connected && mesh.requestUserInfo(n.num)) "Demande envoyée" else "Connectez d'abord votre nœud") })
                AntFilterChip(if (n.isIgnored) "Ignoré" else "Ignorer", n.isIgnored, { if (!mesh.setIgnored(n.num, !n.isIgnored)) actions.toast("Connectez d'abord votre nœud") })
                AntFilterChip("Supprimer", false, { if (mesh.removeNode(n.num)) vm.back(Page.ToolPage(Tool.MeshNodes)) else actions.toast("Connectez d'abord votre nœud") })
            }
        }
        InfoList("Nœud") {
            InfoRow("Numéro", "${n.num}")
            InfoRow("Identifiant", n.id)
            n.user?.let { u ->
                InfoRow("Matériel", MeshProto.hwModel(u.hwModel))
                InfoRow("Rôle", MeshProto.roleName(u.role))
                InfoRow("Chiffrement direct", if (u.hasPublicKey) "Clé publique connue" else "Clé du canal")
                if (u.isLicensed) InfoRow("Radioamateur", "Oui (sans chiffrement)")
            }
            if (!mine) InfoRow("Dernier contact", ago(now, n.lastHeardS))
        }
        if (!mine) InfoList("Réception") {
            InfoRow("Sauts", n.hopsAway?.let { if (it == 0) "Direct" else "$it" } ?: "—")
            InfoRow("SNR", n.snr?.let { "%.1f dB".format(Locale.FRANCE, it) } ?: "—")
            InfoRow("RSSI", n.rssi?.let { "$it dBm" } ?: "—")
            InfoRow("Canal", "${n.channel}")
            if (n.viaMqtt) InfoRow("Via", "MQTT (Internet)")
        }
        n.position?.let { p ->
            InfoList("Position") {
                InfoRow("Latitude", "%.5f°".format(Locale.FRANCE, p.lat))
                InfoRow("Longitude", "%.5f°".format(Locale.FRANCE, p.lon))
                p.altitude?.let { InfoRow("Altitude", "$it m") }
                if (here != null && !mine) InfoRow("Distance", distanceText(distanceM(here.first, here.second, p.lat, p.lon)))
                if (p.sats > 0) InfoRow("Satellites", "${p.sats}")
                if (p.timeS > 0) InfoRow("Mise à jour", ago(now, p.timeS))
            }
        }
        n.metrics?.let { m ->
            InfoList("Appareil") {
                m.battery?.let { InfoRow("Batterie", if (it > 100) "Alimenté" else "$it %") }
                m.voltage?.let { InfoRow("Tension", "%.2f V".format(Locale.FRANCE, it)) }
                m.channelUtil?.let { InfoRow("Utilisation du canal", "%.1f %%".format(Locale.FRANCE, it)) }
                m.airUtilTx?.let { InfoRow("Temps d'émission", "%.1f %%".format(Locale.FRANCE, it)) }
                m.uptimeS?.let { InfoRow("Allumé depuis", uptime(it)) }
            }
        }
        n.environment?.let { e ->
            InfoList("Environnement") {
                e.temperature?.let { InfoRow("Température", "%.1f °C".format(Locale.FRANCE, it)) }
                e.humidity?.let { InfoRow("Humidité", "%.0f %%".format(Locale.FRANCE, it)) }
                e.pressure?.let { InfoRow("Pression", "%.0f hPa".format(Locale.FRANCE, it)) }
            }
        }
    }
}

internal fun uptime(s: Long): String = when {
    s < 3600 -> "${s / 60} min"
    s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} min"
    else -> "${s / 86_400} j ${s % 86_400 / 3600} h"
}
