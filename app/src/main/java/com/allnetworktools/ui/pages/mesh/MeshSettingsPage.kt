package com.allnetworktools.ui.pages.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.mesh.MeshConn
import com.allnetworktools.data.mesh.MeshProto
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HostInputField

@Composable
private fun SettingCard(title: String, icon: String, content: @Composable () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(icon, size = 22.dp, tint = AntTheme.accent.accent)
                Text(title, style = rf(16, 22, 600))
            }
            content()
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = rf(13, 18, 600), color = cs.onSurfaceVariant)

/** Réglages: the radio configuration of the connected node, edited through admin messages like the official app. */
@Composable
fun MeshSettingsPage(vm: MainViewModel) {
    val mesh = vm.mesh
    val conn by mesh.conn.collectAsStateWithLifecycle()
    val cfg by mesh.config.collectAsStateWithLifecycle()
    val channels by mesh.channels.collectAsStateWithLifecycle()
    val nodes by mesh.nodes.collectAsStateWithLifecycle()
    val meta by mesh.metadata.collectAsStateWithLifecycle()
    val me = mesh.myNum.collectAsStateWithLifecycle().value
    val actions = LocalActions.current
    val connected = conn is MeshConn.Connected
    val self = me?.let { nodes[it] }
    var confirm by remember { mutableStateOf<String?>(null) }
    fun done(ok: Boolean, what: String) = actions.toast(if (ok) "$what : envoyé au nœud" else "Connectez d'abord votre nœud")

    PageColumn {
        if (!connected) NotConnectedCard(vm, conn)
        if (me == null) return@PageColumn

        // ---- User --------------------------------------------------------------------------------------------------
        var longName by remember(self?.user?.longName) { mutableStateOf(self?.user?.longName.orEmpty()) }
        var shortName by remember(self?.user?.shortName) { mutableStateOf(self?.user?.shortName.orEmpty()) }
        SettingCard("Utilisateur", Sym.Person) {
            HostInputField(longName, { longName = it.take(39) }, "Nom long", Sym.Person, keyboardType = KeyboardType.Text)
            HostInputField(shortName, { shortName = it.take(4) }, "Nom court (4 caractères max.)", Sym.Edit, keyboardType = KeyboardType.Text)
            Text("${MeshProto.nodeId(me)} · ${self?.user?.let { MeshProto.hwModel(it.hwModel) } ?: "—"}", style = rf(12, 16), color = cs.onSurfaceVariant)
            PillButton(
                "Enregistrer", { done(mesh.setOwner(longName, shortName), "Nom") }, Modifier.fillMaxWidth(), icon = Sym.Check, height = 44.dp,
                enabled = connected && longName.isNotBlank() && shortName.isNotBlank() && (longName != self?.user?.longName || shortName != self?.user?.shortName),
            )
        }

        // ---- Channels ----------------------------------------------------------------------------------------------
        val preset = cfg.lora?.int(2) ?: 0
        SettingCard("Canaux", Sym.Forum) {
            val active = channels.filter { it.role != 0 }
            if (active.isEmpty()) Text("Aucun canal reçu.", style = rf(13, 18), color = cs.onSurfaceVariant)
            active.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChannelBadge(c.index, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(channelName(c, preset), style = rf(15, 20, 600))
                        val info = buildList {
                            add(if (c.role == 1) "Principal" else "Secondaire")
                            add(MeshProto.keyKind(c.psk))
                            if (c.positionPrecision > 0) add("position partagée") else add("position non partagée")
                            if (c.uplink || c.downlink) add("MQTT")
                        }.joinToString(" · ")
                        Text(info, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- Device ------------------------------------------------------------------------------------------------
        val role = cfg.device?.int(1) ?: 0
        SettingCard("Appareil", Sym.Router) {
            Label("Rôle")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 4, 5, 6, 8, 11, 12).forEach { r ->
                    AntFilterChip(MeshProto.roleName(r), role == r, { if (r != role) done(mesh.setRole(r), "Rôle") })
                }
            }
            Text(roleHelp(role), style = rf(12, 16), color = cs.onSurfaceVariant)
        }

        // ---- LoRa --------------------------------------------------------------------------------------------------
        val lora = cfg.lora
        val region = lora?.int(7) ?: 0
        val hop = lora?.int(8)?.takeIf { it > 0 } ?: 3
        var showRegions by remember { mutableStateOf(false) }
        SettingCard("LoRa", Sym.Antenna) {
            Label("Région")
            AntFilterChip(MeshProto.regionName(region), true, { showRegions = true }, trailing = "changer")
            Label("Préréglage")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeshProto.presets.forEach { (v, name) -> AntFilterChip(name, preset == v && lora?.bool(1) != false, { if (v != preset) done(mesh.setLora(preset = v), "Préréglage") }) }
            }
            Label("Nombre de sauts")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { h -> AntFilterChip("$h", hop == h, { if (h != hop) done(mesh.setLora(hopLimit = h), "Sauts") }) }
            }
            val ignoreMqtt = lora?.bool(104) ?: false
            val okMqtt = lora?.bool(105) ?: false
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AntFilterChip("Ignorer MQTT", ignoreMqtt, { done(mesh.setLora(ignoreMqtt = !ignoreMqtt), "MQTT") })
                AntFilterChip("OK pour MQTT", okMqtt, { done(mesh.setLora(okToMqtt = !okMqtt), "MQTT") })
            }
            Text(
                "Émission ${if (lora?.bool(9) != false) "activée" else "désactivée"} · puissance ${lora?.int(10)?.takeIf { it != 0 }?.let { "$it dBm" } ?: "par défaut"}" +
                    (lora?.int(11)?.takeIf { it != 0 }?.let { " · créneau $it" } ?: ""),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        if (showRegions) AlertDialog(
            onDismissRequest = { showRegions = false },
            title = { Text("Région") },
            text = {
                Column(Modifier.padding(top = 4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeshProto.regions.filter { it.first != 0 }.forEach { (v, name) ->
                            AntFilterChip(name, v == region, { showRegions = false; if (v != region) done(mesh.setLora(region = v), "Région") })
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showRegions = false }) { Text("Fermer") } },
        )

        // ---- Position ----------------------------------------------------------------------------------------------
        val pos = cfg.position
        val interval = pos?.int(1)?.takeIf { it > 0 } ?: 900
        val smart = pos?.bool(2) ?: true
        SettingCard("Position", Sym.MyLocation) {
            Label("Diffusion de la position")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(300 to "5 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 h", 21_600 to "6 h", 43_200 to "12 h").forEach { (s, l) ->
                    AntFilterChip(l, interval == s, { if (s != interval) done(mesh.setPositionBroadcast(s, smart), "Position") })
                }
                AntFilterChip("Diffusion intelligente", smart, { done(mesh.setPositionBroadcast(interval, !smart), "Position") })
            }
            val gps = when (pos?.int(13)) { 1 -> "GPS activé"; 2 -> "pas de GPS"; 0 -> "GPS désactivé"; else -> null }
            Text(listOfNotNull(gps, if (pos?.bool(3) == true) "position fixe" else null).joinToString(" · ").ifEmpty { " " }, style = rf(12, 16), color = cs.onSurfaceVariant)
        }

        // ---- Read-only sections --------------------------------------------------------------------------------------
        InfoList("Bluetooth") {
            val bt = cfg.bluetooth
            InfoRow("Activé", if (bt?.bool(1) != false) "Oui" else "Non")
            InfoRow("Appairage", when (bt?.int(2)) { 1 -> "Code PIN fixe ${bt.int(3).takeIf { it != 0 } ?: ""}"; 2 -> "Sans code"; else -> "Code PIN aléatoire (affiché sur l'écran)" })
        }
        cfg.network?.let { n ->
            InfoList("Réseau") {
                InfoRow("Wi-Fi", if (n.bool(1)) "Activé · ${n.str(3) ?: ""}" else "Désactivé")
                if (n.bool(6)) InfoRow("Ethernet", "Activé")
            }
        }
        InfoList("Appareil connecté") {
            InfoRow("Numéro de nœud", "$me")
            InfoRow("Identifiant", MeshProto.nodeId(me))
            meta?.let { m ->
                m.firmware?.let { InfoRow("Firmware", it) }
                InfoRow("Matériel", MeshProto.hwModel(m.hwModel))
                InfoRow("Wi-Fi / Bluetooth", "${if (m.hasWifi) "oui" else "non"} / ${if (m.hasBluetooth) "oui" else "non"}")
            }
            InfoRow("Clé publique", if (cfg.security?.bytes(1)?.isNotEmpty() == true) "Présente (messages directs chiffrés de bout en bout)" else "—")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Redémarrer", { confirm = "reboot" }, Modifier.weight(1f), icon = Sym.RestartAlt, height = 44.dp, outlined = true, bg = cs.onSurface, enabled = connected)
            PillButton("Éteindre", { confirm = "shutdown" }, Modifier.weight(1f), icon = Sym.PowerSettings, height = 44.dp, outlined = true, bg = cs.onSurface, enabled = connected)
        }
        Text(
            "Les réglages sont envoyés au nœud, qui les enregistre et peut redémarrer (changement de région, de préréglage ou de rôle).",
            Modifier.padding(horizontal = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
    when (confirm) {
        "reboot", "shutdown" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (confirm == "reboot") "Redémarrer le nœud ?" else "Éteindre le nœud ?") },
            text = { Text(if (confirm == "reboot") "Le nœud redémarre dans 5 secondes ; l'application se reconnectera." else "Le nœud s'éteint dans 5 secondes. Il faudra le rallumer à la main.") },
            confirmButton = { TextButton({ val c = confirm; confirm = null; done(if (c == "reboot") mesh.reboot() else mesh.shutdown(), if (c == "reboot") "Redémarrage" else "Arrêt") }) { Text("Confirmer") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Annuler") } },
        )
    }
}

private fun roleHelp(role: Int): String = when (role) {
    0 -> "Client : relaie les messages et se connecte à l'application. Le rôle recommandé."
    1 -> "Client muet : ne relaie pas, utile quand plusieurs nœuds sont au même endroit."
    2 -> "Routeur : nœud fixe bien placé, relaie en priorité. À réserver aux points hauts."
    4 -> "Répéteur : relaie seulement, n'apparaît pas dans la liste des nœuds."
    5 -> "Traceur : diffuse sa position en priorité."
    6 -> "Capteur : diffuse ses mesures en priorité."
    8 -> "Client caché : ne diffuse que le strict nécessaire."
    11 -> "Routeur tardif : relaie seulement si personne d'autre ne l'a fait."
    12 -> "Client base : relaie en priorité pour ses favoris."
    else -> ""
}
