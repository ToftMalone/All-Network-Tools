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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.CheckLevel
import com.allnetworktools.data.GatewaySnapshot
import com.allnetworktools.data.LinkReader
import com.allnetworktools.data.LinkSnapshot
import com.allnetworktools.data.MitmEvent
import com.allnetworktools.data.MitmWatch
import com.allnetworktools.data.NetProbes
import com.allnetworktools.data.NetworkAudit
import com.allnetworktools.data.PortalDnsCheck
import com.allnetworktools.data.SecCheck
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.ToolEmpty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun levelColor(l: CheckLevel): Color = when (l) {
    CheckLevel.Bad -> cs.error
    CheckLevel.Warn -> AntTheme.net.fair
    CheckLevel.Info -> AntTheme.accent.accent
    CheckLevel.Good -> AntTheme.net.good
}

@Composable
internal fun CheckList(title: String, checks: List<SecCheck>) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        checks.sortedBy { it.level.ordinal }.forEach { c ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val col = levelColor(c.level)
                Box(Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).background(col.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Symbol(
                        when (c.level) {
                            CheckLevel.Bad -> Sym.Error
                            CheckLevel.Warn -> Sym.Warning
                            CheckLevel.Info -> Sym.Info
                            CheckLevel.Good -> Sym.Check
                        },
                        size = 16.dp, filled = true, tint = col,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = rf(15, 20, 600))
                    Text(c.detail, Modifier.padding(top = 2.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Text(text, style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

private fun summary(checks: List<SecCheck>): Pair<Int, Int> = checks.count { it.level == CheckLevel.Bad } to checks.count { it.level == CheckLevel.Warn }

// ---- 1. Network audit -----------------------------------------------------------------------------

class AuditController(private val probes: NetProbes, private val links: LinkReader?, private val scope: CoroutineScope) {
    var checks by mutableStateOf<List<SecCheck>?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var auditedSsid by mutableStateOf<String?>(null)
        private set

    fun run(conn: WifiConnection, scan: List<WifiAp>?) {
        if (running) return
        running = true
        scope.launch {
            val link = links?.read()
            val ports = conn.gateway?.let { gw -> listOf(23, 80, 443).filter { probes.tcpOpen(gw, it) }.toSet() }
            val caps = scan?.firstOrNull { it.bssid.equals(conn.bssid, true) }?.caps
            checks = NetworkAudit.checks(conn, caps, link, ports)
            auditedSsid = conn.ssid
            running = false
        }
    }

    internal fun setForTest(c: List<SecCheck>, ssid: String) {
        checks = c
        auditedSsid = ssid
    }
}

@Composable
fun AuditTool(c: AuditController, conn: WifiConnection?, scan: List<WifiAp>?) {
    LaunchedEffect(conn?.bssid) { if (conn != null && c.checks == null) c.run(conn, scan) }
    if (conn != null) TopBarAction(Sym.Refresh) { c.run(conn, scan) }
    PageColumn {
        if (conn == null) {
            ToolEmpty(Sym.WifiOff, "Pas de Wi-Fi", "Connectez-vous à un réseau Wi-Fi pour l'auditer.", null)
            return@PageColumn
        }
        val checks = c.checks
        HeroCard {
            if (checks == null) {
                Text("Audit de ${conn.ssid ?: "votre réseau"}", style = gs(24, 30, 500))
                Text("Analyse du chiffrement, du point d'accès et de la box…", Modifier.padding(top = 4.dp), style = rf(14, 20))
                IndeterminateBar(Modifier.padding(top = 16.dp))
            } else {
                val score = NetworkAudit.score(checks)
                val (bad, warn) = summary(checks)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(NetworkAudit.grade(score), style = gs(64, 68, 500, tnum = true))
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text("$score / 100", style = rf(18, 24, 600, tnum = true))
                        Text(c.auditedSsid ?: "", style = rf(14, 20))
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(if (bad == 0) "Aucun risque" else "$bad ${if (bad > 1) "risques" else "risque"}", if (bad == 0) AntTheme.net.good else cs.error)
                    if (warn > 0) HeroChip("$warn à améliorer", AntTheme.net.fair)
                    if (c.running) HeroChip("Nouvelle analyse…", AntTheme.net.fair, blink = true)
                }
            }
        }
        if (checks != null) CheckList("Points vérifiés", checks)
        Note(
            "Le chiffrement vient d'Android, WPS, PMF et TKIP des annonces du point d'accès (dernier scan), et les ports 23, 80 et 443 sont testés sur la box. " +
                "Pour corriger, ouvrez l'interface d'administration de votre box (souvent http://${conn.gateway ?: "192.168.1.1"}).",
        )
    }
}

// ---- 2. Captive portal and DNS --------------------------------------------------------------------

class PortalDnsController(private val probes: NetProbes, private val links: LinkReader?, private val scope: CoroutineScope) {
    var checks by mutableStateOf<List<SecCheck>?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var network by mutableStateOf<String?>(null)
        private set

    fun run(label: String) {
        if (running) return
        running = true
        scope.launch {
            checks = PortalDnsCheck.run(probes, links?.read())
            network = label
            running = false
        }
    }

    internal fun setForTest(c: List<SecCheck>, label: String) {
        checks = c
        network = label
    }
}

@Composable
fun PortalDnsTool(c: PortalDnsController, networkLabel: String) {
    LaunchedEffect(Unit) { if (c.checks == null) c.run(networkLabel) }
    TopBarAction(Sym.Refresh) { c.run(networkLabel) }
    PageColumn {
        val checks = c.checks
        HeroCard {
            if (checks == null) {
                Text("Portail captif et DNS", style = gs(24, 30, 500))
                Text("Tests en cours sur $networkLabel…", Modifier.padding(top = 4.dp), style = rf(14, 20))
                IndeterminateBar(Modifier.padding(top = 16.dp))
            } else {
                val (bad, warn) = summary(checks)
                Text(
                    when {
                        bad > 0 -> "Trafic manipulé"
                        warn > 0 -> "À surveiller"
                        else -> "Rien d'anormal"
                    },
                    style = gs(30, 36, 500),
                )
                Text("Réseau testé : ${c.network}", Modifier.padding(top = 4.dp), style = rf(14, 20))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip("${checks.count { it.level == CheckLevel.Good }} tests réussis", AntTheme.net.good)
                    if (bad > 0) HeroChip("$bad ${if (bad > 1) "anomalies" else "anomalie"}", cs.error)
                    if (c.running) HeroChip("Nouveau test…", AntTheme.net.fair, blink = true)
                }
            }
        }
        if (checks != null) CheckList("Résultats", checks)
        Note(
            "Tests : page HTTP de contrôle de Google (réponse vide attendue), noms à adresse fixe (one.one.one.one, dns.google), nom inventé qui doit être inexistant, " +
                "et requête DNS vers ${PortalDnsCheck.Blackhole}, une adresse réservée où aucun serveur n'existe : une réponse prouve que le réseau capte le DNS.",
        )
    }
}

// ---- 3. Man in the middle -----------------------------------------------------------------------

class MitmController(private val probes: NetProbes, private val links: LinkReader?, private val scope: CoroutineScope) {
    var current by mutableStateOf<GatewaySnapshot?>(null)
        private set
    val events = mutableStateListOf<MitmEvent>()
    var rounds by mutableStateOf(0)
        private set
    var startedAt by mutableStateOf(0L)
        private set
    private var job: Job? = null

    private var frozen = false

    fun start(bssid: () -> String?) {
        if (frozen || job?.isActive == true) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                val l: LinkSnapshot? = links?.read()
                val s = GatewaySnapshot(System.currentTimeMillis(), l?.gateway, l?.dhcpServer, l?.dns.orEmpty(), l?.ipv6Routers.orEmpty(), probes.firstHop(), bssid())
                current?.let { prev -> events.addAll(0, MitmWatch.changes(prev, s)) }
                current = s
                rounds++
                delay(15_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun reset() {
        events.clear()
        rounds = 0
        startedAt = System.currentTimeMillis()
    }

    internal fun setForTest(s: GatewaySnapshot, e: List<MitmEvent>, r: Int, start: Long) {
        frozen = true
        current = s
        events.clear(); events.addAll(e)
        rounds = r
        startedAt = start
    }
}

private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun MitmTool(c: MitmController, conn: WifiConnection?) {
    val bssid by androidx.compose.runtime.rememberUpdatedState(conn?.bssid)
    DisposableEffect(Unit) {
        c.start { bssid }
        onDispose { c.stop() }
    }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    PageColumn {
        val s = c.current
        val checks = s?.let(MitmWatch::checks).orEmpty()
        val bad = checks.count { it.level == CheckLevel.Bad } + c.events.count { it.level == CheckLevel.Bad }
        HeroCard {
            Text(
                when {
                    s == null -> "Analyse du chemin réseau…"
                    bad > 0 -> "Interception possible"
                    else -> "Aucun intermédiaire détecté"
                },
                style = gs(28, 34, 500),
            )
            Text(
                (conn?.ssid?.let { "Réseau $it · " } ?: "") + "contrôle toutes les 15 s · ${c.rounds} ${if (c.rounds > 1) "contrôles" else "contrôle"}",
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip("Surveillance active", AntTheme.net.good, blink = true)
                if (c.events.isNotEmpty()) HeroChip("${c.events.size} ${if (c.events.size > 1) "changements" else "changement"}", AntTheme.net.fair)
            }
            if (s == null) IndeterminateBar(Modifier.padding(top = 16.dp))
        }
        if (s != null) {
            CheckList("État actuel", checks)
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Chemin observé", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                listOf(
                    "Passerelle" to (s.gateway ?: "—"),
                    "Premier routeur" to (s.firstHop ?: "sans réponse"),
                    "Serveur DHCP" to (s.dhcpServer ?: "—"),
                    "DNS" to s.dns.joinToString().ifEmpty { "—" },
                    "Routeurs IPv6" to s.ipv6Routers.joinToString().ifEmpty { "aucun" },
                ).forEach { (k, v) -> com.allnetworktools.ui.components.InfoRow(k, v) }
            }
        }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Changements pendant la surveillance", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
            if (c.events.isEmpty()) Text("Aucun changement depuis le début de la surveillance.", Modifier.padding(vertical = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            c.events.take(30).forEach { e ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(levelColor(e.level)))
                    Text(e.text, Modifier.weight(1f), style = rf(13, 18))
                    Text(Instant.ofEpochMilli(e.timeMs).atZone(ZoneId.systemDefault()).format(hms), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
            }
        }
        Note(
            "Un attaquant qui usurpe la box (ARP spoofing) doit relayer vos paquets : il apparaît alors comme premier routeur à la place de la passerelle. " +
                "L'outil surveille aussi le serveur DHCP, les DNS et les routeurs IPv6 annoncés. Android interdit aux applications de lire la table ARP : " +
                "une usurpation qui ne relaie pas le trafic ne se voit pas ainsi.",
        )
    }
}
