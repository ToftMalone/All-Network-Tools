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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.data.EvilTwin
import com.allnetworktools.data.SsidReport
import com.allnetworktools.data.TwinRisk
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.plural
import kotlinx.coroutines.flow.StateFlow

/** Remembers when each BSSID and SSID was first seen while the tool is used. */
class EvilTwinController {
    val firstSeen = mutableStateMapOf<String, Long>()
    val ssidFirstSeen = mutableStateMapOf<String, Long>()
    var lastScanMs by mutableStateOf(0L)
        private set

    fun feed(scan: List<WifiAp>, now: Long = System.currentTimeMillis()) {
        scan.forEach { ap ->
            if (ap.bssid !in firstSeen) firstSeen[ap.bssid] = now
            if (ap.ssid.isNotBlank() && ap.ssid !in ssidFirstSeen) ssidFirstSeen[ap.ssid] = now
        }
        lastScanMs = now
    }

    fun reset() {
        firstSeen.clear()
        ssidFirstSeen.clear()
    }
}

@Composable
private fun riskColor(r: TwinRisk): Color = when (r) {
    TwinRisk.High -> cs.error
    TwinRisk.Medium -> AntTheme.net.fair
    TwinRisk.Low -> AntTheme.accent.accent
    TwinRisk.None -> AntTheme.net.good
}

@Composable
fun EvilTwinTool(
    c: EvilTwinController,
    conn: WifiConnection?,
    results: StateFlow<List<WifiAp>?>,
    startScan: () -> Boolean,
    locationOk: Boolean,
    onFixLocation: () -> Unit,
) {
    val scan by results.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { startScan() }
    LaunchedEffect(scan) { scan?.let { c.feed(it) } }
    TopBarAction(Sym.Refresh) { startScan() }
    val reports = EvilTwin.analyze(scan.orEmpty(), c.firstSeen, c.ssidFirstSeen, System.currentTimeMillis())
    PageColumn {
        if (!locationOk) {
            ToolError(Sym.LocationOff, "Localisation requise", "Android exige la position précise, et la localisation activée, pour lire les résultats d'un scan Wi-Fi.", "Autoriser", onFixLocation)
            return@PageColumn
        }
        val suspicious = reports.filter { it.risk <= TwinRisk.Medium }
        val mine = conn?.ssid?.let { s -> reports.firstOrNull { it.ssid == s } }
        val connectedFlagged = conn?.bssid != null && mine != null && conn.bssid.lowercase() in mine.flagged.map { it.lowercase() }
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(suspicious.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(suspicious.size, "réseau suspect", "réseaux suspects"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            val aps = scan.orEmpty().size
            Text(
                "${reports.size} ${plural(reports.size, "nom de réseau", "noms de réseau")} et $aps ${plural(aps, "point d'accès", "points d'accès")} analysés",
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    scan == null -> HeroChip("Scan en cours…", AntTheme.net.fair, blink = true)
                    connectedFlagged -> HeroChip("Votre point d'accès est suspect", cs.error, blink = true)
                    mine != null -> HeroChip("Votre réseau : ${mine.risk.label.lowercase()}", riskColor(mine.risk))
                    else -> HeroChip("Analyse à chaque scan", AntTheme.net.good)
                }
            }
        }
        if (connectedFlagged) {
            SectionCard(color = cs.errorContainer) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Warning, size = 24.dp, filled = true, tint = cs.onErrorContainer)
                    Text(
                        "Vous êtes connecté à ${conn!!.bssid?.uppercase()}, signalé ci-dessous. Si vous n'êtes pas sûr de ce point d'accès, déconnectez-vous ou n'utilisez que des sites en HTTPS et un VPN.",
                        style = rf(13, 18), color = cs.onErrorContainer,
                    )
                }
            }
        }
        val shown = reports.filter { it.findings.isNotEmpty() || it.aps.size >= 2 || it.ssid == conn?.ssid }
        shown.forEach { r -> ReportCard(r, conn) }
        val single = reports.size - shown.size
        if (single > 0) {
            Text(
                "$single ${plural(single, "autre réseau n'est diffusé", "autres réseaux ne sont diffusés")} que par un seul point d'accès, sans anomalie.",
                Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Un faux point d'accès (« evil twin ») reprend le nom d'un réseau connu. Un scan ne peut pas prouver qu'un point d'accès est faux : " +
                        "l'outil compare la sécurité annoncée, le fabricant (préfixe de l'adresse), les adresses générées par logiciel et les apparitions soudaines. " +
                        "Les réseaux maillés et les box d'un même opérateur sont normalement cohérents entre eux.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReportCard(r: SsidReport, conn: WifiConnection?) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(r.ssid, style = rf(16, 22, 600), maxLines = 1)
                Text("${r.aps.size} ${plural(r.aps.size, "point d'accès", "points d'accès")}" + if (r.ssid == conn?.ssid) " · votre réseau" else "", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
            TechChip(r.risk.label, riskColor(r.risk), if (r.risk == TwinRisk.High) cs.onError else Color.White)
        }
        r.findings.forEach { f ->
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(riskColor(f.risk)))
                Column {
                    Text(f.title, style = rf(14, 20, 600))
                    Text(f.detail, style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            r.aps.forEach { ap ->
                val flagged = r.findings.filter { ap.bssid in it.bssids }.minByOrNull { it.risk.ordinal }
                val connected = conn?.bssid?.equals(ap.bssid, ignoreCase = true) == true
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(flagged?.let { riskColor(it.risk) } ?: cs.outlineVariant))
                    Column(Modifier.weight(1f)) {
                        Text(ap.bssid.uppercase() + if (connected) "  · connecté" else "", style = mono(12.5f, 18, if (connected) 700 else 500))
                        Text(
                            listOfNotNull(
                                ap.security, "${ap.band.label} GHz · ch ${ap.channel}",
                                if (EvilTwin.locallyAdministered(ap.bssid)) "adresse locale" else null,
                            ).joinToString(" · "),
                            style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    Text("${ap.rssi} dBm", style = rf(13, 18, 600, tnum = true))
                }
            }
        }
    }
}
