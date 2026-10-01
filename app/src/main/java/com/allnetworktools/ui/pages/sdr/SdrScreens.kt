package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

private fun mhz(hz: Long) = "%.3f MHz".format(Locale.FRANCE, hz / 1e6)

private fun snr(db: Double) = "%+.1f dB".format(Locale.FRANCE, db)

@Composable
fun SdrDashboard(vm: MainViewModel) {
    val roles = AntTheme.net.sdr
    val c = vm.tools.meshtastic
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val open = { t: Tool -> vm.navigate { it.copy(page = Page.ToolPage(t)) } }
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container)
                .clickable { open(Tool.Meshtastic) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(Sym.Hub, cookieShape(), 64.dp, roles.accent, roles.onAccent, 30.dp, spinMs = 20_000)
                if (c.running) TechChip("En écoute", roles.accent, roles.onAccent)
            }
            Text("Meshtastic", Modifier.padding(top = 16.dp), style = gs(24, 30, 500), color = roles.onContainer)
            Text(
                if (c.running) "${c.rows.size} ${plural(c.rows.size, "paquet")} · ${c.nodes.size} ${plural(c.nodes.size, "nœud")} sur ${c.listeningHz?.let(::mhz) ?: "—"}"
                else "Écoute du réseau maillé LoRa en LongFast sur le canal par défaut.",
                Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = roles.onContainer,
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Ouvrir", { open(Tool.Meshtastic) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        InfoList("Récepteur") {
            InfoRow("Matériel", device?.name ?: "—")
            c.board?.let { InfoRow("Carte", it) }
            c.firmware?.let { InfoRow("Firmware", it) }
            InfoRow("Mode", "Réception uniquement")
            InfoRow("Échantillonnage", "2 MS/s, 8 bits I/Q")
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Le HackRF ne reçoit que lorsque vous lancez l'écoute, et s'arrête quand vous quittez l'onglet SDR. " +
                        "L'application n'émet jamais. Le décodage LoRa est fait par l'application, sur le téléphone.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class MeshView(val label: String) { Messages("Messages"), Packets("Paquets"), Nodes("Nœuds") }

@Composable
fun MeshtasticTool(vm: MainViewModel) {
    val c = vm.tools.meshtastic
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf(MeshView.Messages) }
    var showSettings by remember { mutableStateOf(false) }
    TopBarAction(Sym.Delete) { c.clear() }
    val (_, settingsError) = c.settings()
    PageColumn {
        HeroCard {
            Text(if (c.running) "En écoute" else "Meshtastic", style = gs(28, 34, 500))
            Text(
                "LongFast · SF11 · 250 kHz · ${c.listeningHz?.let(::mhz) ?: c.frequencyMhz.replace('.', ',') + " MHz"}",
                Modifier.padding(top = 2.dp), style = rf(14, 20),
            )
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip("Démarrage du HackRF…", AntTheme.net.fair, blink = true)
                    c.running -> HeroChip("${c.framesOk} ${plural(c.framesOk, "trame décodée", "trames décodées")}", AntTheme.net.good, blink = true)
                    device == null -> HeroChip("Aucun HackRF branché", AntTheme.net.poor)
                    else -> HeroChip("${device!!.name} prêt", AntTheme.net.good)
                }
                if (c.framesBad > 0) HeroChip("${c.framesBad} CRC invalides", AntTheme.net.fair)
                if (c.dropped > 0) HeroChip("${c.dropped} pertes USB", AntTheme.net.poor)
            }
        }
        val d = device
        if (c.running || c.starting) {
            StartButton("Arrêter l'écoute", Sym.Stop) { c.stop() }
        } else {
            StartButton("Lancer l'écoute", Sym.PlayArrow, enabled = d != null && settingsError == null) { d?.let(c::start) }
        }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { showSettings = !showSettings }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Symbol(Sym.Tune, size = 20.dp, tint = AntTheme.accent.accent)
                Text("Réglages", Modifier.weight(1f), style = rf(14, 20, 600))
                Text("Clé ${c.keyBase64.ifBlank { "—" }} · LNA ${c.lnaGain} · VGA ${c.vgaGain}${if (c.amp) " · ampli" else ""}", style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
            }
            if (showSettings) {
                Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, "Fréquence (MHz)", Sym.Stream, keyboardType = KeyboardType.Decimal)
                    HostInputField(c.keyBase64, { c.keyBase64 = it }, "Clé du canal (Base64)", Sym.Key, keyboardType = KeyboardType.Ascii)
                    if (settingsError != null) Text(settingsError, style = rf(13, 18), color = cs.error)
                    Text("Gain LNA (dB)", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 8, 16, 24, 32, 40).forEach { g -> AntFilterChip("$g", c.lnaGain == g, { c.lnaGain = g }) }
                    }
                    Text("Gain VGA (dB)", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 20, 30, 40, 50).forEach { g -> AntFilterChip("$g", c.vgaGain == g, { c.vgaGain = g }) }
                    }
                    AntFilterChip("Ampli RF +14 dB", c.amp, { c.amp = !c.amp })
                    Text(
                        "Les réglages s'appliquent au prochain lancement. Par défaut : canal LongFast, clé AQ== (clé publique du canal par défaut).",
                        style = rf(12, 16), color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        SegmentedRow(MeshView.entries.map { it to it.label }, view, { view = it }, Modifier.fillMaxWidth(), height = 36.dp)
        when (view) {
            MeshView.Messages -> {
                val msgs = c.rows.filter { it.packet.data?.content is Meshtastic.Content.Text }
                if (msgs.isEmpty()) Empty(if (c.running) "Aucun message pour l'instant. Les nœuds envoient surtout positions et télémétrie ; les messages texte sont plus rares." else "Lancez l'écoute pour voir les messages du canal.")
                else ListCard { msgs.forEachIndexed { i, r -> if (i > 0) Hairline(); MessageRow(c, r) } }
            }
            MeshView.Packets -> {
                if (c.rows.isEmpty()) Empty(if (c.running) "En attente de trames LoRa sur la fréquence…" else "Lancez l'écoute pour voir passer les paquets.")
                else ListCard { c.rows.forEachIndexed { i, r -> if (i > 0) Hairline(); PacketRow(c, r) } }
            }
            MeshView.Nodes -> {
                val list = c.nodes.values.sortedByDescending { it.lastHeardMs }
                if (list.isEmpty()) Empty("Aucun nœud entendu pour l'instant.")
                else ListCard { list.forEachIndexed { i, n -> if (i > 0) Hairline(); NodeRow(n) } }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Réception seule : rien n'est émis. Seuls les paquets du canal dont vous avez la clé sont lisibles ; " +
                        "les messages privés (chiffrés pour un destinataire) et les autres canaux restent chiffrés. " +
                        "En Europe, la fréquence par défaut de LongFast est 869,525 MHz.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    SectionCard { Text(text, style = rf(13, 18), color = cs.onSurfaceVariant) }
}

@Composable
private fun ListCard(content: @Composable () -> Unit) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) { content() }
}

private fun summary(p: Meshtastic.Packet): String {
    val d = p.data
    return when {
        d == null && p.header.channel == 0 -> "Message privé (chiffré pour son destinataire)"
        d == null && p.undecodable -> "Illisible avec cette clé"
        d == null -> "Autre canal (hash 0x%02X)".format(p.header.channel)
        else -> when (val c = d.content) {
            is Meshtastic.Content.Text -> c.text
            is Meshtastic.Content.Position ->
                if (c.lat != null && c.lon != null) "%.5f, %.5f".format(Locale.US, c.lat, c.lon) + (c.altitude?.let { " · $it m" } ?: "") else "Position (masquée)"
            is Meshtastic.Content.NodeInfo -> listOfNotNull(c.longName, c.shortName?.let { "($it)" }, c.hwModel?.let(Meshtastic::hwModel)).joinToString(" ")
            is Meshtastic.Content.Telemetry -> listOfNotNull(
                c.battery?.let { "Batterie ${if (it > 100) "secteur" else "$it %"}" },
                c.voltage?.let { "%.2f V".format(Locale.FRANCE, it) },
                c.chUtil?.let { "canal %.1f %%".format(Locale.FRANCE, it) },
                c.temperature?.let { "%.1f °C".format(Locale.FRANCE, it) },
                c.humidity?.let { "%.0f %% HR".format(Locale.FRANCE, it) },
            ).joinToString(" · ").ifEmpty { "Télémétrie" }
            is Meshtastic.Content.Other -> "${c.size} octets"
        }
    }
}

private fun portIcon(p: Meshtastic.Packet): String = when (p.data?.port) {
    null -> Sym.Lock
    1 -> Sym.Forum
    3 -> Sym.LocationOn
    4 -> Sym.Info
    67 -> Sym.Monitoring
    70 -> Sym.Route
    else -> Sym.Hub
}

@Composable
private fun PacketRow(c: MeshtasticController, r: MeshRow) {
    val p = r.packet
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
            Symbol(portIcon(p), size = 20.dp, filled = true, tint = AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.data?.let { Meshtastic.portName(it.port) } ?: "Chiffré", Modifier.weight(1f), style = rf(14, 20, 600), maxLines = 1)
                Text(timeFmt.format(Date(p.atMs)), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text("${c.nodeName(p.header.from)} → ${c.nodeName(p.header.to)}", style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary(p), Modifier.padding(top = 2.dp), style = rf(13, 18), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    "SNR ${snr(r.bestSnr)}",
                    r.minHops?.let { if (it == 0) "direct" else "$it ${plural(it, "saut")}" },
                    if (r.receptions > 1) "reçu ${r.receptions} fois" else null,
                    if (p.header.viaMqtt) "via MQTT" else null,
                ).joinToString(" · "),
                Modifier.padding(top = 2.dp), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageRow(c: MeshtasticController, r: MeshRow) {
    val p = r.packet
    val text = (p.data?.content as? Meshtastic.Content.Text)?.text ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.nodeName(p.header.from), Modifier.weight(1f), style = rf(14, 20, 600), color = AntTheme.accent.accent, maxLines = 1)
            Text(timeFmt.format(Date(p.atMs)), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
        }
        Box(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(18.dp)).background(AntTheme.accent.container).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(text, style = rf(15, 21), color = AntTheme.accent.onContainer)
        }
        Text(
            listOfNotNull(
                if (p.header.to == Meshtastic.BROADCAST) "À tous" else "À ${c.nodeName(p.header.to)}",
                "SNR ${snr(r.bestSnr)}",
                r.minHops?.let { if (it == 0) "direct" else "$it ${plural(it, "saut")}" },
            ).joinToString(" · "),
            Modifier.padding(top = 4.dp), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun NodeRow(n: MeshNode) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(AntTheme.accent.accent), contentAlignment = Alignment.Center) {
            Text(n.shortName?.take(4) ?: "?", style = rf(12, 16, 700), color = AntTheme.accent.onAccent, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(n.label, style = rf(14, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Meshtastic.nodeId(n.num), n.hwModel?.let(Meshtastic::hwModel)).joinToString(" · "),
                style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
            )
            Text(
                listOfNotNull(
                    timeFmt.format(Date(n.lastHeardMs)),
                    n.snr?.let { "SNR ${snr(it)}" },
                    n.hops?.let { if (it == 0) "direct" else "$it ${plural(it, "saut")}" },
                    n.battery?.let { if (it > 100) "secteur" else "$it %" },
                    if (n.lat != null) "%.4f, %.4f".format(Locale.US, n.lat, n.lon) else null,
                ).joinToString(" · "),
                style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant,
            )
        }
        Text("${n.packets}", style = gs(18, 24, 500, tnum = true))
    }
}
