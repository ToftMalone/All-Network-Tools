package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.net.LanCategory
import com.allnetworktools.data.net.LanDevice
import com.allnetworktools.data.net.LanScanner
import com.allnetworktools.data.net.subnetHosts
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroMetric
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ProgressBar
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.active
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LanController(private val scope: CoroutineScope, private val scanner: LanScanner) {
    var phase by mutableStateOf(Phase.Idle)
    var scanned by mutableIntStateOf(0)
    var total by mutableIntStateOf(254)
    var filter by mutableStateOf<LanCategory?>(null)
    var subnet by mutableStateOf("")
    var startedAt by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    val devices = mutableStateListOf<LanDevice>()
    private var job: Job? = null

    fun device(ip: String) = devices.firstOrNull { it.ip == ip }

    fun start(conn: WifiConnection?) {
        job?.cancel()
        val ip = conn?.ipv4
        if (ip == null) {
            phase = Phase.Error; return
        }
        val prefix = conn.prefix ?: 24
        val hosts = subnetHosts(ip, prefix)
        subnet = "${hosts.first().substringBeforeLast('.')}.0/${prefix.coerceIn(24, 30)}"
        devices.clear(); scanned = 0; total = hosts.size; filter = null
        phase = Phase.Running
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            scanner.scan(hosts, ip, conn.gateway).collect { p ->
                scanned = p.scanned
                devices.clear(); devices.addAll(p.devices)
                if (p.done) {
                    durationMs = System.currentTimeMillis() - startedAt
                    phase = if (devices.count { !it.isSelf } == 0) Phase.Empty else Phase.Results
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        durationMs = System.currentTimeMillis() - startedAt
        phase = if (devices.isEmpty()) Phase.Idle else Phase.Results
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanTool(c: LanController, conn: WifiConnection?, onDevice: (String) -> Unit) {
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    val start = { haptics.confirm(); c.start(conn) }
    if (c.phase != Phase.Running) TopBarAction(Sym.Refresh, start)
    val subnet = c.subnet.ifEmpty { conn?.ipv4?.let { "${it.substringBeforeLast('.')}.0/${(conn.prefix ?: 24).coerceIn(24, 30)}" } ?: "—" }
    PageColumn {
        if (c.phase == Phase.Error) {
            ToolError(Sym.WifiOff, "Aucun réseau local", "Connectez-vous à un réseau Wi-Fi pour scanner ses appareils.", "Réessayer", start)
        }
        HeroCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(subnet, style = gs(26, 32, 500))
                    Text(
                        listOfNotNull(conn?.ssid, conn?.gateway?.let { "passerelle $it" }).joinToString(" · ").ifEmpty { "Réseau local" },
                        Modifier.graphicsLayer { alpha = 0.85f }, style = rf(13, 18),
                    )
                }
                Symbol(Sym.Lan, size = 28.dp, filled = true, tint = acc.accent)
            }
            when (c.phase) {
                Phase.Running -> {
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Bottom) {
                        HeroMetric(c.scanned.toString(), "/ ${c.total}", 52)
                        Box(Modifier.weight(1f))
                        Text("${c.devices.size} trouvés", Modifier.padding(bottom = 8.dp), style = rf(13, 18, 600))
                    }
                    ProgressBar(c.scanned / c.total.toFloat().coerceAtLeast(1f), Modifier.padding(top = 10.dp), track = cs.surface)
                    Text("Sondes ICMP / TCP · mDNS à l'écoute", Modifier.padding(top = 8.dp).graphicsLayer { alpha = 0.85f }, style = mono(12, 16))
                }
                Phase.Results -> {
                    HeroMetric(c.devices.size.toString(), "appareils", 52, Modifier.padding(top = 18.dp))
                    Text("Scan terminé en ${fmt(c.durationMs / 1000f, 1)} s · ICMP, TCP, mDNS", Modifier.graphicsLayer { alpha = 0.85f }, style = rf(13, 18))
                }
                else -> {
                    FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ICMP", "TCP", "mDNS", "DNS inverse").forEach {
                            Box(Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).background(cs.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                Text(it, style = rf(12, 16, 500), color = cs.onSurface)
                            }
                        }
                    }
                }
            }
            val b = when (c.phase) {
                Phase.Running -> ToolButton("Arrêter", Sym.Stop, BtnKind.Outline) { c.stop() }
                Phase.Results, Phase.Empty -> ToolButton("Relancer le scan", Sym.Refresh, BtnKind.Outline, start)
                else -> ToolButton("Lancer le scan", Sym.PlayArrow, BtnKind.Big, start)
            }
            ToolButtons(b, modifier = Modifier.padding(top = 16.dp))
        }
        if (c.phase == Phase.Results) {
            val cats = c.devices.map { it.kind.third }.distinct()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AntFilterChip("Tous", c.filter == null, { c.filter = null })
                LanCategory.entries.filter { it in cats }.forEach { cat -> AntFilterChip(cat.label, c.filter == cat, { c.filter = cat }) }
            }
        }
        if (c.phase.active) {
            val list = c.devices.filter { c.filter == null || it.kind.third == c.filter }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEachIndexed { i, d -> LanItem(d, if (c.phase == Phase.Running) 0 else i, i, list.size) { onDevice(d.ip) } }
            }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.DevicesOther, "Aucun autre appareil", "Seul cet appareil répond. Le réseau isole peut-être ses clients (Wi-Fi invité, isolation AP).", "Relancer le scan", start)
        }
    }
}

@Composable
private fun LanItem(d: LanDevice, riseIndex: Int, index: Int, count: Int, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val (icon, type, _) = d.kind
    Row(
        Modifier.rise(riseIndex).fillMaxWidth().clip(groupShape(index, count)).background(cs.surfaceContainerLow).clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingIcon(icon, acc.container, acc.onContainer, shape = RoundedCornerShape(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, Modifier.weight(1f, fill = false), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.isSelf) {
                    Box(Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(acc.accent).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text("VOUS", style = rf(10, 12, 700), color = acc.onAccent)
                    }
                }
            }
            Text("${d.ip} · $type", style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (d.isSelf || d.ms == null) "—" else fmt(d.ms), style = gs(16, 20, 500, tnum = true))
            Text("ms", style = rf(11, 14), color = cs.onSurfaceVariant)
        }
        Symbol(Sym.ChevronRight, size = 20.dp, tint = cs.onSurfaceVariant)
    }
}
