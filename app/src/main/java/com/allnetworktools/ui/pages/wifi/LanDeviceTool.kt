package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.LanDevice
import com.allnetworktools.data.net.LanScanner
import com.allnetworktools.data.net.Services
import com.allnetworktools.data.net.WakeOnLan
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LanDeviceController(private val scope: CoroutineScope, private val scanner: LanScanner) {
    var ip by mutableStateOf<String?>(null)
    var phase by mutableStateOf(Phase.Running)
    var latency by mutableStateOf<Float?>(null)
    var online by mutableStateOf(true)
    val services = mutableStateListOf<Pair<Int, String?>>()
    private var job: Job? = null

    fun open(target: String) {
        if (target == ip && phase != Phase.Error) return
        ip = target
        identify()
    }

    fun identify() {
        val target = ip ?: return
        job?.cancel()
        services.clear()
        phase = Phase.Running
        job = scope.launch {
            val ms = scanner.isAlive(target)
            latency = ms; online = ms != null
            if (ms == null) {
                phase = Phase.Error; return@launch
            }
            services.addAll(scanner.services(target, Services.Fingerprint))
            phase = if (services.isEmpty()) Phase.Empty else Phase.Results
        }
    }

    /** Refreshes the online status while the page is visible. */
    suspend fun watch() {
        while (true) {
            delay(5000)
            val target = ip ?: continue
            if (phase == Phase.Running) continue
            val ms = scanner.isAlive(target)
            latency = ms ?: latency
            online = ms != null
        }
    }
}

@Composable
fun LanDeviceTool(c: LanDeviceController, ip: String?, device: LanDevice?, prefix: Int?, onPing: (String) -> Unit, onPorts: (String) -> Unit) {
    val actions = LocalActions.current
    val acc = AntTheme.accent
    val net = AntTheme.net
    val scope = rememberCoroutineScope()
    var wolOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ip) { if (ip != null) c.open(ip) }
    LaunchedEffect(Unit) { c.watch() }
    val target = c.ip ?: ip ?: return
    val d = device ?: LanDevice(target)
    val (icon, type, _) = d.kind
    TopBarAction(Sym.ContentCopy) { actions.copy("Adresse IP", target) }
    PageColumn {
        if (c.phase == Phase.Error) {
            ToolError(Sym.WifiOffPortable, "Appareil hors ligne", "Aucune réponse ICMP ni TCP. Il est peut-être en veille ou a changé d'adresse IP.", "Réessayer") { c.identify() }
        }
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeBadge(icon, cookieShape(), 72.dp, acc.accent, acc.onAccent, 34.dp, spinMs = 30_000)
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = gs(24, 30, 500), maxLines = 2)
                    Text("$type · $target", Modifier.graphicsLayer { alpha = 0.85f }, style = rf(13, 18))
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (c.online) BlinkDot(net.good, 8.dp) else Box(Modifier.size(8.dp).clip(CircleShape).background(cs.error))
                    Text(
                        if (c.online) "En ligne${c.latency?.let { " · ${fmt(it)} ms" } ?: ""}" else "Hors ligne",
                        style = rf(12, 16, 600), color = cs.onSurface,
                    )
                }
                if (d.mdnsName != null || d.services.isNotEmpty()) {
                    Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("mDNS", style = rf(12, 16, 500), color = cs.onSurface)
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp).fillMaxWidth()) {
                listOf(
                    Triple(Sym.NetworkPing, "Ping") { onPing(target) },
                    Triple(Sym.Lan, "Ports") { onPorts(target) },
                    Triple(Sym.PowerSettings, "Réveiller") { wolOpen = true },
                    Triple(Sym.ContentCopy, "Copier") { actions.copy("Adresse IP", target) },
                ).forEach { (ic, label, onClick) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(20.dp)).background(cs.surface), contentAlignment = Alignment.Center) {
                            Symbol(ic, size = 22.dp, filled = true, tint = acc.accent)
                        }
                        Text(label, style = rf(12, 16, 600))
                    }
                }
            }
        }
        if (c.phase == Phase.Running) {
            SectionCard {
                Text("Identification des services…", style = rf(14, 20, 500))
                IndeterminateBar(Modifier.padding(top = 12.dp))
            }
        }
        InfoList("Identité", acc.accent) {
            InfoRow("IPv4", target) { actions.copy("Adresse IP", target) }
            InfoRow("Nom d'hôte", d.hostname ?: "—")
            d.mdnsName?.let { InfoRow("Nom mDNS", it) }
            InfoRow("Type", type)
            if (d.services.isNotEmpty()) InfoRow("Services annoncés", d.services.joinToString(", ") { it.substringBefore("._").removePrefix("_") })
            InfoRow("Adresse MAC", "Non exposée par Android")
        }
        if (c.phase == Phase.Results) {
            InfoList("Services détectés", acc.accent) {
                c.services.forEach { (port, banner) ->
                    Hairline()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.defaultMinSize(minWidth = 52.dp).height(28.dp).clip(RoundedCornerShape(8.dp)).background(acc.container).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(port.toString(), style = mono(13, 16, 700), color = acc.onContainer) }
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(Services.name(port), style = rf(14, 20, 600))
                            if (banner != null) Text(banner, style = mono(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.Services, "Aucun service courant", "L'appareil répond mais n'expose aucun des ${Services.Fingerprint.size} ports courants testés.", "Scanner tous les ports") { onPorts(target) }
        }
    }
    if (wolOpen) {
        var mac by remember { mutableStateOf("") }
        val valid = WakeOnLan.parseMac(mac) != null
        AlertDialog(
            onDismissRequest = { wolOpen = false },
            icon = { Symbol(Sym.PowerSettings, size = 24.dp, tint = acc.accent) },
            title = { Text("Wake-on-LAN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Android ne révèle plus l'adresse MAC des autres appareils. Saisissez celle de ${d.name} (visible dans l'interface de votre box).", style = rf(14, 20))
                    OutlinedTextField(mac, { mac = it }, label = { Text("Adresse MAC") }, placeholder = { Text("a4:3e:51:7c:2a:9f") }, singleLine = true, isError = mac.isNotEmpty() && !valid)
                }
            },
            confirmButton = {
                TextButton(
                    {
                        wolOpen = false
                        scope.launch {
                            val ok = WakeOnLan.send(mac, target, prefix ?: 24)
                            actions.toast(if (ok) "Paquet magique envoyé à ${WakeOnLan.parseMac(mac)?.let(WakeOnLan::format)}" else "Envoi impossible")
                        }
                    },
                    enabled = valid,
                ) { Text("Envoyer") }
            },
            dismissButton = { TextButton({ wolOpen = false }) { Text("Annuler") } },
        )
    }
}
