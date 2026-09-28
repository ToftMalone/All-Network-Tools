package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiBand
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Legend
import com.allnetworktools.ui.components.PulseRing
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.pop
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ProgressBar
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.active
import com.allnetworktools.util.plural
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BandSpec(val label: String, val f0: Int, val f1: Int, val candidates: List<Int>, val axis: List<Int>, val channels: List<Int>, val fc: (Int) -> Int)

private val Ch5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
private val Psc6 = (0 until 15).map { 5 + 16 * it }

fun bandSpec(b: WifiBand) = when (b) {
    WifiBand.B24 -> BandSpec("2,4 GHz", 2398, 2486, listOf(1, 6, 11), listOf(1, 3, 5, 7, 9, 11, 13), (1..13).toList()) { if (it == 14) 2484 else 2407 + 5 * it }
    WifiBand.B5 -> BandSpec("5 GHz", 5165, 5850, listOf(36, 40, 44, 48, 149, 153, 157, 161, 165), listOf(36, 52, 100, 116, 132, 149, 165), Ch5) { 5000 + 5 * it }
    WifiBand.B6 -> BandSpec("6 GHz", 5940, 7130, Psc6, listOf(5, 53, 101, 149, 197), Psc6) { 5950 + 5 * it }
}

class ChannelAnalysis(val band: WifiBand, val nets: List<WifiAp>, own: WifiConnection?) {
    val spec = bandSpec(band)
    private fun center(n: WifiAp) = if (band == WifiBand.B24) n.frequency else n.centerFrequency
    private fun half(n: WifiAp) = if (band == WifiBand.B24) 11 else n.widthMhz / 2
    val congestion: List<Float> = spec.channels.map { c ->
        nets.sumOf { n ->
            val df = abs(spec.fc(c) - center(n)).toFloat()
            val lim = (if (band == WifiBand.B24) 22f else 20f + n.widthMhz) / 2f
            if (df < lim) ((n.rssi + 100) * (1 - df / lim)).toDouble() else 0.0
        }.toFloat()
    }
    private val max = (congestion.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val pct: List<Float> = congestion.map { it / max }
    private fun pctOf(c: Int) = spec.channels.indexOf(c).takeIf { it >= 0 }?.let { pct[it] } ?: 0f
    val best: Int = spec.candidates.minByOrNull { pctOf(it) } ?: spec.candidates.first()
    val current: Int? = own?.takeIf { it.band == band }?.channel
    fun score(c: Int) = Math.round(100 - pctOf(c) * 100)
    fun center(n: WifiAp, x: (Int) -> Float) = x(center(n)) to (x(center(n) + half(n)) - x(center(n)))
}

class ChannelsController(private val scope: CoroutineScope) {
    var band by mutableStateOf(WifiBand.B5)
    var phase by mutableStateOf(Phase.Idle)
    var pass by mutableIntStateOf(0)
    val seen = mutableStateMapOf<String, WifiAp>()
    private var job: Job? = null
    private var bandChosen = false

    fun chooseBand(b: WifiBand) {
        band = b; bandChosen = true
        if (phase == Phase.Empty || phase == Phase.Results) phase = if (seen.values.any { it.band == b }) Phase.Results else Phase.Empty
    }

    fun initBand(conn: WifiConnection?) {
        if (!bandChosen && conn != null) band = conn.band
    }

    fun start(results: StateFlow<List<WifiAp>?>, startScan: () -> Boolean) {
        job?.cancel()
        seen.clear(); pass = 0
        phase = Phase.Running
        job = scope.launch {
            for (p in 1..3) {
                pass = p
                val triggered = startScan()
                val list = if (triggered) withTimeoutOrNull(8000) { results.drop(1).first() } else null
                (list ?: results.value).orEmpty().forEach { ap ->
                    val prev = seen[ap.bssid]
                    if (prev == null || ap.rssi > prev.rssi) seen[ap.bssid] = ap
                }
                if (p < 3) delay(1200)
            }
            pass = 3
            phase = if (seen.values.any { it.band == band }) Phase.Results else Phase.Empty
        }
    }
}

@Composable
fun ChannelsTool(
    c: ChannelsController,
    conn: WifiConnection?,
    results: StateFlow<List<WifiAp>?>,
    startScan: () -> Boolean,
    locationOk: Boolean,
    onFixLocation: () -> Unit,
) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    LaunchedEffect(conn?.band) { c.initBand(conn) }
    val start = { haptics.confirm(); c.start(results, startScan) }
    if (c.phase != Phase.Running) TopBarAction(Sym.Refresh, start)
    val analysis = remember(c.seen.toMap(), c.band, conn?.bssid) { ChannelAnalysis(c.band, c.seen.values.filter { it.band == c.band }.sortedByDescending { it.rssi }, conn) }
    PageColumn {
        if (!locationOk) {
            ToolError(Sym.LocationOff, "Localisation requise", "Android exige la position précise, et la localisation activée, pour lire les résultats d'un scan Wi-Fi.", "Autoriser", onFixLocation)
            return@PageColumn
        }
        SegmentedRow(WifiBand.entries.map { it to bandSpec(it).label }, c.band, { c.chooseBand(it) }, Modifier.fillMaxWidth())
        when (c.phase) {
            Phase.Idle, Phase.Error -> HeroCard {
                ShapeBadge(Sym.BarChart, cookieShape(), 72.dp, AntTheme.accent.accent, AntTheme.accent.onAccent, 34.dp, spinMs = 30_000)
                Text("Trouvez le canal le moins encombré", Modifier.padding(top = 16.dp), style = gs(24, 30, 500))
                Text(
                    "L'analyse mesure les réseaux voisins sur la bande choisie et recommande un canal pour votre box. Canal actuel : ${analysis.current ?: "—"}.",
                    Modifier.padding(top = 6.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20),
                )
                Box(Modifier.padding(top = 18.dp)) { StartButton("Analyser", onClick = start) }
            }
            Phase.Results -> RecommendationCard(analysis, conn, actions::openUrl)
            else -> Unit
        }
        if (c.phase == Phase.Running) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(AntTheme.accent.accent))
                        PulseRing(AntTheme.accent.accent, 12.dp, filled = true)
                    }
                    Text("Scan ${c.pass} / 3 · ${analysis.spec.label}", Modifier.weight(1f), style = rf(14, 20, 500))
                    Text("${analysis.nets.size} ${plural(analysis.nets.size, "réseau", "réseaux")}", style = rf(12, 16), color = cs.onSurfaceVariant)
                }
                ProgressBar(c.pass / 3f, Modifier.padding(top = 12.dp))
            }
        }
        if (c.phase.active) {
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                    Text("Occupation des canaux", Modifier.weight(1f), style = rf(14, 20, 600))
                    Text("${analysis.nets.size} ${plural(analysis.nets.size, "réseau", "réseaux")}", style = rf(12, 16), color = cs.onSurfaceVariant)
                }
                SpectrumChart(analysis, conn?.bssid, showBest = c.phase == Phase.Results)
            }
        }
        if (c.phase == Phase.Results) CongestionBars(analysis)
        if (c.phase == Phase.Empty) {
            ToolEmpty(
                Sym.WifiFind, "Aucun réseau sur ${analysis.spec.label}",
                "Aucun point d'accès ${analysis.spec.label} autour de vous. Votre box émet peut-être seulement sur d'autres bandes.",
                "Analyser à nouveau", start,
            )
        }
    }
}

@Composable
private fun RecommendationCard(a: ChannelAnalysis, conn: WifiConnection?, openUrl: (String) -> Unit) {
    val acc = AntTheme.accent
    val net = AntTheme.net
    val same = a.current == a.best
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.pop(key = a.best).size(88.dp).clip(RoundedCornerShape(30.dp, 30.dp, 30.dp, 10.dp)).background(acc.accent),
                contentAlignment = Alignment.Center,
            ) { Text(a.best.toString(), style = gs(40, 44, 600, -1f), color = acc.onAccent) }
            Column {
                Text("CANAL RECOMMANDÉ", Modifier.graphicsLayer { alpha = 0.85f }, style = rf(12, 16, 700, 0.5f))
                Text(
                    when {
                        same -> "Votre canal actuel est optimal"
                        a.current != null -> "Passez du canal ${a.current} au canal ${a.best}"
                        else -> "Canal ${a.best} sur ${a.spec.label}"
                    },
                    Modifier.padding(top = 2.dp), style = rf(17, 22, 600),
                )
                Text(
                    when {
                        same -> "${a.spec.label} peu encombré : aucun changement nécessaire."
                        a.band == WifiBand.B24 -> "Canaux 1, 6 et 11 uniquement, pour éviter les chevauchements."
                        a.band == WifiBand.B5 -> "Hors canaux DFS, sans attente radar au démarrage de la box."
                        else -> "Canaux PSC, découverts plus vite par les appareils Wi-Fi 6E / 7."
                    },
                    Modifier.padding(top = 2.dp).graphicsLayer { alpha = 0.85f }, style = rf(13, 18),
                )
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(a.current?.let { "Actuel" to it }, "Recommandé" to a.best).forEach { (k, ch) ->
                val score = a.score(ch)
                val color = when {
                    score >= 65 -> net.good
                    score >= 35 -> net.fair
                    else -> net.poor
                }
                val w by animateFloatAsState(score / 100f, Motion.standard(), label = "score")
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(12.dp)) {
                    Text(k, style = rf(12, 16), color = cs.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ch $ch", style = gs(24, 30, 500), color = cs.onSurface)
                        Text("score $score", Modifier.padding(bottom = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(w).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
            }
        }
        val gateway = conn?.gateway
        if (gateway != null) {
            ToolButtons(ToolButton("Changer le canal sur la box", Sym.OpenInNew, BtnKind.Outline) { openUrl("http://$gateway") }, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun SpectrumChart(a: ChannelAnalysis, ownBssid: String?, showBest: Boolean) {
    val net = AntTheme.net
    val acc = AntTheme.accent.accent
    val hue = listOf(net.gps, net.galileo, net.beidou, net.qzss, cs.primary, net.glonass, net.gnss.accent, net.cell.accent)
    val outline = cs.outline
    val bestColor = net.good
    val grow = remember { Animatable(0f) }
    LaunchedEffect(a.band) { grow.snapTo(0f); grow.animateTo(1f, tween(800, easing = Motion.Emphasized)) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(170.dp)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val unit = with(density) { 1.dp.toPx() }
        fun x(f: Int) = 8 * unit + (w - 12 * unit) * (f - a.spec.f0) / (a.spec.f1 - a.spec.f0).toFloat()
        fun y(d: Int) = (140f - ((d.coerceIn(-95, -30) + 95) / 65f) * 128f) * unit
        val humps = a.nets.mapIndexed { i, n ->
            val (cx, hw) = a.center(n, ::x)
            val own = n.bssid.equals(ownBssid, true)
            Triple(n, Triple(cx, hw, y(n.rssi)), if (own) acc else hue[i % hue.size])
        }
        Canvas(Modifier.matchParentSize()) {
            val base = 140 * unit
            if (showBest) {
                val bx = x(a.spec.fc(a.best))
                drawRoundRect(bestColor.copy(alpha = 0.14f), Offset(bx - 10 * unit, 2 * unit), Size(20 * unit, base - 2 * unit), CornerRadius(10 * unit))
            }
            drawLine(outline, Offset(8 * unit, base), Offset(size.width - 4 * unit, base), unit)
            humps.forEach { (n, geo, color) ->
                val (cx, hw, py0) = geo
                val py = base + (py0 - base) * grow.value
                val path = Path().apply {
                    if (a.band == WifiBand.B24) {
                        moveTo(cx - hw, base); quadraticTo(cx, 2 * py - base, cx + hw, base)
                    } else {
                        val e = minOf(8 * unit, hw * 0.25f)
                        moveTo(cx - hw, base); lineTo(cx - hw + e, py); lineTo(cx + hw - e, py); lineTo(cx + hw, base)
                    }
                }
                val own = n.bssid.equals(ownBssid, true)
                drawPath(path, color.copy(alpha = 0.16f))
                drawPath(path, color, style = Stroke((if (own) 3f else 1.5f) * unit, join = StrokeJoin.Round))
            }
        }
        val placed = mutableListOf<Pair<Float, Float>>()
        humps.forEach { (n, geo, color) ->
            val (cx, _, py) = geo
            if (placed.size < 4 && placed.none { abs(it.first - cx) < 70 * unit && abs(it.second - py) < 18 * unit }) {
                placed += cx to py
                val own = n.bssid.equals(ownBssid, true)
                Text(
                    n.ssid,
                    Modifier.width(120.dp).offset { androidx.compose.ui.unit.IntOffset((cx - 60 * unit).toInt(), (py - 18 * unit).toInt()) },
                    style = rf(10, 14, 700), color = if (own) acc else cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        a.spec.axis.forEach { ch ->
            Text(
                ch.toString(),
                Modifier.width(30.dp).offset { androidx.compose.ui.unit.IntOffset((x(a.spec.fc(ch)) - 15 * unit).toInt(), (146 * unit).toInt()) },
                style = rf(10, 14), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CongestionBars(a: ChannelAnalysis) {
    val net = AntTheme.net
    val onSurface = cs.onSurface
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Encombrement par canal", Modifier.weight(1f), style = rf(14, 20, 600))
            Text("plus bas = mieux", style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            a.spec.channels.forEachIndexed { i, ch ->
                val p = a.pct[i]
                val h by animateFloatAsState(p.coerceAtLeast(0.04f), Motion.standard(), label = "cong")
                val color = when {
                    p < 0.35f -> net.good
                    p < 0.7f -> net.fair
                    else -> net.poor
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight(h)
                        .then(if (ch == a.best) Modifier.border(2.dp, onSurface, RoundedCornerShape(6.dp, 6.dp, 2.dp, 2.dp)) else Modifier)
                        .clip(RoundedCornerShape(6.dp, 6.dp, 2.dp, 2.dp)).background(color),
                )
            }
        }
        BoxWithConstraints(Modifier.padding(top = 6.dp).fillMaxWidth().height(14.dp)) {
            val n = a.spec.channels.size
            val slot = (maxWidth - 3.dp * (n - 1)) / n
            val sparse = n > 15
            a.spec.channels.forEachIndexed { i, ch ->
                val bestIndex = a.spec.channels.indexOf(a.best)
                if (ch == a.best || (!sparse || i % 4 == 0) && abs(i - bestIndex) > 1) {
                    Text(
                        ch.toString(),
                        Modifier.width(32.dp).offset(x = (slot + 3.dp) * i + slot / 2 - 16.dp),
                        style = rf(9, 12, if (ch == a.best) 800 else 500),
                        color = if (ch == a.best) cs.onSurface else cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.padding(top = 12.dp)) { Legend(listOf("Libre" to net.good, "Chargé" to net.fair, "Saturé" to net.poor)) }
    }
}
