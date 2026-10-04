package com.allnetworktools.ui.pages.mesh

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.mesh.FoundNode
import com.allnetworktools.data.mesh.MeshConn
import com.allnetworktools.data.mesh.MeshLink
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
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

/** Connecter: pick the node, over Bluetooth or the network, as in the official app's Connections tab. */
@Composable
fun MeshConnectPage(vm: MainViewModel) {
    val mesh = vm.mesh
    val conn by mesh.conn.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val btOn by vm.bluetoothEnabled.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val acc = AntTheme.accent
    val canScan = perms.nearby && btOn
    val found by remember(canScan) { if (canScan) mesh.scan() else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    var host by rememberSaveable { mutableStateOf(mesh.lastLink?.takeIf { it.kind == MeshLink.Kind.Tcp }?.address ?: "") }
    val current = when (val c = conn) {
        is MeshConn.Connected -> c.link
        is MeshConn.Connecting -> c.link
        is MeshConn.Failed -> c.link
        else -> null
    }
    PageColumn {
        SectionCard(color = acc.container) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Symbol(
                    when (conn) { is MeshConn.Connected -> Sym.Link; is MeshConn.Connecting -> Sym.Sync; else -> Sym.LinkOff },
                    size = 32.dp, tint = acc.onContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        when (val c = conn) {
                            is MeshConn.Connected -> c.link.name
                            is MeshConn.Connecting -> c.link.name
                            is MeshConn.Failed -> c.link?.name ?: "Échec de connexion"
                            else -> "Aucun nœud"
                        },
                        style = gs(22, 28, 500), color = acc.onContainer,
                    )
                    Text(
                        when (val c = conn) {
                            is MeshConn.Connected -> "Connecté en ${if (c.link.kind == MeshLink.Kind.Bluetooth) "Bluetooth" else "Wi-Fi (TCP)"}"
                            is MeshConn.Connecting -> c.step
                            is MeshConn.Failed -> c.message
                            else -> "Choisissez votre nœud ci-dessous."
                        },
                        style = rf(13, 18), color = acc.onContainer,
                    )
                }
            }
            if (conn is MeshConn.Connected || conn is MeshConn.Connecting) {
                PillButton("Déconnecter", { mesh.disconnect() }, Modifier.padding(top = 12.dp).fillMaxWidth(), icon = Sym.LinkOff, height = 44.dp, outlined = true, bg = acc.onContainer)
            } else if (current != null) {
                PillButton("Réessayer", { mesh.connect(current) }, Modifier.padding(top = 12.dp).fillMaxWidth(), icon = Sym.Refresh, height = 44.dp)
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Symbol(Sym.Bluetooth, size = 22.dp, tint = acc.accent)
                    Text("Bluetooth", Modifier.weight(1f), style = rf(16, 22, 600))
                    if (canScan) TechChip("Recherche…", acc.container, acc.onContainer)
                }
                when {
                    !perms.nearby -> {
                        Text("Android doit autoriser l'accès aux « Appareils à proximité » pour trouver les nœuds.", style = rf(13, 18), color = cs.onSurfaceVariant)
                        PillButton("Autoriser", { actions.request(PermGroup.Nearby) }, Modifier.fillMaxWidth(), icon = Sym.Bluetooth, height = 44.dp)
                    }
                    !btOn -> Text("Le Bluetooth est désactivé.", style = rf(13, 18), color = cs.onSurfaceVariant)
                    found.isEmpty() -> Text("Recherche des nœuds Meshtastic à proximité… Le nœud doit être allumé, avec le Bluetooth activé.", style = rf(13, 18), color = cs.onSurfaceVariant)
                    else -> Column {
                        found.forEachIndexed { i, f ->
                            if (i > 0) Hairline()
                            FoundRow(f, current?.address == f.address && conn is MeshConn.Connected) {
                                mesh.connect(MeshLink(MeshLink.Kind.Bluetooth, f.address, f.name))
                            }
                        }
                    }
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Symbol(Sym.Wifi, size = 22.dp, tint = acc.accent)
                    Text("Réseau (Wi-Fi ou Ethernet)", style = rf(16, 22, 600))
                }
                HostInputField(host, { host = it.trim() }, "Adresse IP ou nom du nœud", Sym.Router, onDone = {
                    if (host.isNotBlank()) mesh.connect(MeshLink(MeshLink.Kind.Tcp, host, host))
                })
                PillButton("Connecter", { mesh.connect(MeshLink(MeshLink.Kind.Tcp, host, host)) }, Modifier.fillMaxWidth(), icon = Sym.Link, height = 44.dp, enabled = host.isNotBlank())
                Text("Pour les nœuds dont le Wi-Fi est activé (port 4403), sur le même réseau que le téléphone.", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        if (mesh.lastLink != null && conn !is MeshConn.Connected && conn !is MeshConn.Connecting) {
            PillButton("Oublier le dernier nœud", { mesh.forget() }, Modifier.fillMaxWidth(), icon = Sym.Delete, height = 44.dp, outlined = true, bg = cs.onSurface)
        }
        Text(
            "La connexion USB n'est pas encore prise en charge. Le premier appairage Bluetooth demande le code PIN affiché sur l'écran du nœud (123456 s'il n'en a pas).",
            Modifier.padding(horizontal = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun FoundRow(f: FoundNode, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(if (active) Sym.BluetoothConnected else Sym.Bluetooth, size = 24.dp, tint = AntTheme.accent.accent)
        Column(Modifier.weight(1f)) {
            Text(f.name, style = rf(15, 20, 600), maxLines = 1)
            Text(listOfNotNull(f.address, f.rssi?.let { "$it dBm" }, if (f.bonded) "appairé" else null).joinToString(" · "), style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        if (active) TechChip("Connecté", AntTheme.accent.accent, AntTheme.accent.onAccent)
    }
}
