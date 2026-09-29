package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.FollowAssessment
import com.allnetworktools.data.FollowLevel
import com.allnetworktools.data.PositionSet
import com.allnetworktools.data.Sighting
import com.allnetworktools.data.TrackerDetect
import com.allnetworktools.data.TrackerSignal
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PillButton
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
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import kotlinx.coroutines.delay

/** A tag seen during the session, with where and when. */
class TrackerCandidate(val address: String, val signal: TrackerSignal) {
    val sightings = mutableListOf<Sighting>()
    var device by mutableStateOf<BleDevice?>(null)
    var assessment by mutableStateOf(FollowAssessment(FollowLevel.Nearby, 0, null, 0, 0))
}

/**
 * Records every tracker tag heard while the tool is open, with the phone's position, and flags the
 * ones that stay with you over time and distance.
 */
class UnknownTrackersController {
    val candidates = mutableStateMapOf<String, TrackerCandidate>()
    var sessionStart by mutableStateOf(System.currentTimeMillis())
        private set
    var travelledM by mutableStateOf(0.0)
        private set
    private var lastFix: Pair<Double, Double>? = null

    fun reset() {
        candidates.clear()
        sessionStart = System.currentTimeMillis()
        travelledM = 0.0
        lastFix = null
    }

    fun feed(devices: List<BleDevice>, pos: PositionSet, now: Long = System.currentTimeMillis()) {
        val loc = pos.fused ?: pos.gnss ?: pos.network
        val lat = loc?.latitude
        val lon = loc?.longitude
        if (lat != null && lon != null) {
            val prev = lastFix
            if (prev == null) {
                lastFix = lat to lon
            } else {
                // Ignore the jitter of a still phone.
                val d = TrackerDetect.distanceM(prev.first, prev.second, lat, lon)
                if (d > 20) {
                    travelledM += d
                    lastFix = lat to lon
                }
            }
        }
        devices.forEach { d ->
            val sig = TrackerDetect.classify(d.ads) ?: return@forEach
            val c = candidates.getOrPut(d.address) { TrackerCandidate(d.address, sig) }
            c.device = d
            val last = c.sightings.lastOrNull()
            // One sighting every 15 s is plenty to follow a walk and keeps memory bounded.
            if (last == null || now - last.timeMs >= 15_000L) {
                c.sightings += Sighting(now, lat, lon, d.rssi)
                if (c.sightings.size > 2000) c.sightings.removeAt(0)
            }
            c.assessment = TrackerDetect.assess(sig, c.sightings)
        }
    }

    internal fun setForTest(list: List<TrackerCandidate>, start: Long, travelled: Double) {
        candidates.clear()
        list.forEach { candidates[it.address] = it }
        sessionStart = start
        travelledM = travelled
    }
}

@Composable
private fun levelColor(l: FollowLevel): Color = when (l) {
    FollowLevel.Following -> cs.error
    FollowLevel.Watch -> AntTheme.net.fair
    FollowLevel.Nearby -> AntTheme.accent.accent
    FollowLevel.WithOwner -> AntTheme.net.good
}

private fun minutes(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else if (m >= 1) "$m min" else "< 1 min"
}

private fun dist(m: Double) = if (m >= 1000) "${fmt(m / 1000, 1)} km" else "${fmt(m)} m"

@Composable
fun UnknownTrackersTool(
    c: UnknownTrackersController,
    devices: List<BleDevice>,
    positions: PositionSet,
    onLocate: (BleDevice) -> Unit,
    onDetails: (String) -> Unit,
) {
    LaunchedEffect(devices) { c.feed(devices, positions) }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    // Detection only runs while the tool is open; the app keeps the screen on when "Écran toujours allumé" is set.
    val awake = AntTheme.settings.keepAwake
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val list = c.candidates.values.sortedWith(compareBy<TrackerCandidate> { it.assessment.level.ordinal }.thenByDescending { it.assessment.durationMs })
    val following = list.count { it.assessment.level == FollowLevel.Following }
    val watch = list.count { it.assessment.level == FollowLevel.Watch }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(following.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(following, "traqueur vous suit", "traqueurs vous suivent"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                "${list.size} ${plural(list.size, "traqueur détecté", "traqueurs détectés")} · $watch à surveiller · analyse depuis ${minutes(now - c.sessionStart)}, ${dist(c.travelledM)} parcourus",
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip("Écoute Bluetooth en cours", AntTheme.net.good, blink = true)
                if (awake) HeroChip("Écran maintenu allumé", AntTheme.accent.accent)
            }
        }
        if (list.isEmpty()) {
            ToolEmpty(
                Sym.GppMaybe, "Aucun traqueur à proximité",
                "L'outil écoute les AirTag, SmartTag, Tile et tags des réseaux Localiser d'Apple et de Google. Gardez-le ouvert pendant vos déplacements : " +
                    "un traqueur qui reste avec vous plus de 10 minutes sur plus de 300 m est signalé.",
                null,
            )
        }
        list.forEach { t -> TrackerCard(t, now, onLocate, onDetails) }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Un AirTag ou un tag Google éloigné de son propriétaire l'annonce dans ses trames : c'est l'état « séparé » affiché ici. " +
                        "Un tag séparé qui reste avec vous dans le temps et sur la distance est suspect. Les tags Samsung et Tile ne disent pas s'ils sont séparés. " +
                        "Android propose aussi, dans Paramètres › Sécurité et urgences, des alertes de traqueurs inconnus qui fonctionnent en arrière-plan.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrackerCard(t: TrackerCandidate, now: Long, onLocate: (BleDevice) -> Unit, onDetails: (String) -> Unit) {
    val a = t.assessment
    val col = levelColor(a.level)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadingIcon(Sym.Sell, col.copy(alpha = 0.18f), col)
            Column(Modifier.weight(1f)) {
                Text(t.device?.name ?: t.signal.net.maker, style = rf(15, 20, 600), maxLines = 2)
                Text(t.signal.net.label, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                Text(
                    when (t.signal.separated) {
                        true -> "Séparé de son propriétaire"
                        false -> "Propriétaire à proximité"
                        null -> "État non annoncé par ce type de tag"
                    },
                    style = rf(12, 16, 600), color = if (t.signal.separated == true) col else cs.onSurfaceVariant,
                )
            }
            TechChip(a.level.label, col, if (a.level == FollowLevel.Following) cs.onError else Color.White)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun stat(v: String, k: String, m: Modifier) = Column(m.background(cs.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(v, style = rf(15, 20, 600, tnum = true), maxLines = 1)
                Text(k, style = rf(11, 14), color = cs.onSurfaceVariant, maxLines = 1)
            }
            stat(minutes(a.durationMs), "avec vous", Modifier.weight(1f))
            stat(a.spreadM?.let { dist(it) } ?: "—", "distance", Modifier.weight(1f))
            stat("${a.places}", plural(a.places, "lieu"), Modifier.weight(1f))
            stat(t.device?.rssi?.let { "$it" } ?: "—", "dBm", Modifier.weight(1f))
        }
        val lastSeen = t.sightings.lastOrNull()?.timeMs
        Text(
            "${t.address} · vu ${a.sightings} fois" + (lastSeen?.let { " · dernière fois il y a ${((now - it) / 1000).coerceAtLeast(0)} s" } ?: ""),
            Modifier.padding(top = 10.dp), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            t.device?.let { d -> PillButton("Chaud/Froid", { onLocate(d) }, Modifier.weight(1f), icon = Sym.MyLocation, height = 40.dp) }
            PillButton("Détails", { onDetails(t.address) }, Modifier.weight(1f), icon = Sym.Info, height = 40.dp, outlined = true, bg = AntTheme.accent.accent)
        }
    }
}
