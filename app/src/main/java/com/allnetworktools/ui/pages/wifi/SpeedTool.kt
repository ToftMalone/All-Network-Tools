package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.HistoryStore
import com.allnetworktools.data.SpeedRecord
import com.allnetworktools.data.net.SpeedServer
import com.allnetworktools.data.net.SpeedStage
import com.allnetworktools.data.net.SpeedTest
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.MetricTile
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.fmt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun mbps(v: Float) = if (v >= 100) fmt(v) else fmt(v, 1)

fun relativeDate(atMs: Long): String {
    val now = Calendar.getInstance()
    val at = Calendar.getInstance().apply { timeInMillis = atMs }
    val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(at.time)
    val days = now.get(Calendar.DAY_OF_YEAR) - at.get(Calendar.DAY_OF_YEAR) + 365 * (now.get(Calendar.YEAR) - at.get(Calendar.YEAR))
    return when (days) {
        0 -> "Aujourd'hui, $time"
        1 -> "Hier, $time"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.FRANCE).format(at.time)
    }
}

class SpeedController(private val scope: CoroutineScope, private val history: HistoryStore) {
    var phase by mutableStateOf(Phase.Idle)
    var stage by mutableStateOf(SpeedStage.Ping)
    var current by mutableFloatStateOf(0f)
    var ping by mutableStateOf<Float?>(null)
    var jitter by mutableStateOf<Float?>(null)
    var down by mutableStateOf<Float?>(null)
    var up by mutableStateOf<Float?>(null)
    var server by mutableStateOf<SpeedServer?>(null)
    private var job: Job? = null

    val records = history.speed

    fun loadServer() {
        if (server == null) scope.launch { server = SpeedTest.server() }
    }

    fun start(networkLabel: String, networkIcon: String) {
        job?.cancel()
        ping = null; jitter = null; down = null; up = null; current = 0f
        stage = SpeedStage.Ping
        phase = Phase.Running
        job = scope.launch {
            runCatching {
                SpeedTest.run().collect { s ->
                    stage = s.stage
                    current = s.value
                    if (s.pingMs != null) ping = s.pingMs
                    if (s.jitterMs != null) jitter = s.jitterMs
                    if (s.downMbps != null) down = s.downMbps
                    if (s.upMbps != null) up = s.upMbps
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                phase = Phase.Error
                return@launch
            }
            current = down ?: 0f
            phase = Phase.Results
            history.addSpeed(SpeedRecord(System.currentTimeMillis(), networkLabel, networkIcon, down ?: 0f, up ?: 0f, ping ?: 0f, jitter ?: 0f))
        }
    }

    fun cancel() {
        job?.cancel()
        phase = Phase.Idle
        current = 0f
    }
}

@Composable
fun SpeedTool(c: SpeedController, networkLabel: String, networkIcon: String) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(Unit) { c.loadServer() }
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    val records by c.records.collectAsState()
    if (c.phase == Phase.Results) {
        TopBarAction(Sym.IosShare) {
            actions.share(
                "Test de débit",
                "Débit $networkLabel : ↓ ${mbps(c.down ?: 0f)} Mb/s · ↑ ${mbps(c.up ?: 0f)} Mb/s · ping ${fmt(c.ping ?: 0f)} ms (Cloudflare ${c.server?.colo ?: ""})",
            )
        }
    }
    val start = { haptics.confirm(); c.start(networkLabel, networkIcon) }
    PageColumn {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(Sym.Dns, size = 24.dp, filled = true, tint = acc.accent)
            Column(Modifier.weight(1f)) {
                val s = c.server
                Text(listOfNotNull(s?.city, "Cloudflare").joinToString(" · "), style = rf(15, 20, 600))
                Text(listOfNotNull(s?.colo, s?.isp, networkLabel).joinToString(" · "), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (c.phase == Phase.Error) {
            ToolError(Sym.SignalDisconnected, "Test interrompu", "Connexion perdue avec le serveur pendant la mesure. Les résultats partiels ne sont pas enregistrés.", "Relancer", start)
        }
        SectionCard(shape = RoundedCornerShape(32.dp), padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val running = c.phase == Phase.Running
                val showPing = running && c.stage == SpeedStage.Ping
                val value = when {
                    c.phase == Phase.Results -> c.down ?: 0f
                    c.phase == Phase.Error -> 0f
                    running && !showPing -> c.current
                    else -> 0f
                }
                SpeedGauge(
                    value = value,
                    big = when {
                        showPing -> fmt(c.current)
                        c.phase == Phase.Error -> "—"
                        else -> mbps(value)
                    },
                    unit = if (showPing) "ms · ping" else "Mb/s",
                    label = when {
                        running -> when (c.stage) {
                            SpeedStage.Ping -> "Mesure du ping…"
                            SpeedStage.Download -> "Download en cours…"
                            else -> "Upload en cours…"
                        }
                        c.phase == Phase.Results -> "Download"
                        c.phase == Phase.Error -> "Interrompu"
                        else -> "Prêt"
                    },
                    upload = running && c.stage == SpeedStage.Upload,
                )
                PhaseSteps(c)
            }
        }
        if (c.phase == Phase.Results) {
            TileGrid(
                listOf(
                    Triple(Sym.Download, "Download", mbps(c.down ?: 0f)) to "Mb/s",
                    Triple(Sym.Upload, "Upload", mbps(c.up ?: 0f)) to "Mb/s",
                    Triple(Sym.NetworkPing, "Ping (inactif)", fmt(c.ping ?: 0f)) to "ms",
                    Triple(Sym.SwapVert, "Gigue", fmt(c.jitter ?: 0f, 1)) to "ms",
                ),
            ) { (t, u), mod -> MetricTile(t.second, t.third, u, t.first, mod) }
        }
        when (c.phase) {
            Phase.Running -> ToolButtons(ToolButton("Annuler", Sym.Close, BtnKind.OutlineOnSurface) { c.cancel() })
            Phase.Results -> ToolButtons(ToolButton("Relancer le test", Sym.Refresh, BtnKind.Big, start))
            Phase.Idle -> if (records.isNotEmpty()) ToolButtons(ToolButton("Démarrer le test", Sym.PlayArrow, BtnKind.Big, start))
            else -> Unit
        }
        if (c.phase == Phase.Idle && records.isEmpty()) {
            ToolEmpty(Sym.Speed, "Aucun test enregistré", "Lancez un premier test pour suivre l'évolution de votre débit.", "Démarrer", start)
        }
        if ((c.phase == Phase.Idle || c.phase == Phase.Results) && records.isNotEmpty()) {
            SectionTitle("Historique")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                records.take(10).forEachIndexed { i, r -> HistoryItem(r, i, minOf(10, records.size)) }
            }
        }
    }
}

@Composable
private fun SpeedGauge(value: Float, big: String, unit: String, label: String, upload: Boolean) {
    val net = AntTheme.net
    val acc = AntTheme.accent
    val frac by animateFloatAsState(log10(1 + value.coerceAtLeast(0f)) / log10(1001f), Motion.standard(), label = "speed")
    val color by animateColorAsState(if (upload) net.galileo else acc.accent, tween(300), label = "speedColor")
    val track = cs.surfaceContainerHighest
    val knobBg = cs.surface
    val knobRing = cs.onSurface
    Box(Modifier.size(300.dp, 262.dp)) {
        Canvas(Modifier.size(300.dp)) {
            val s = size.width / 300f
            val r = 120 * s
            val tl = Offset(150 * s - r, 150 * s - r)
            val stroke = Stroke(22.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 135f, 270f, false, tl, Size(r * 2, r * 2), style = stroke)
            if (frac > 0.002f) drawArc(color, 135f, 270f * frac.coerceAtMost(1f), false, tl, Size(r * 2, r * 2), style = stroke)
            val a = Math.toRadians((135 + 270 * frac.coerceAtMost(1f)).toDouble())
            val c = Offset((150 * s + r * cos(a)).toFloat(), (150 * s + r * sin(a)).toFloat())
            drawCircle(knobBg, 11.dp.toPx(), c)
            drawCircle(knobRing, 9.dp.toPx(), c, style = Stroke(4.dp.toPx()))
        }
        listOf(0, 10, 50, 100, 250, 500, 1000).forEach { v ->
            val f = log10(1f + v) / log10(1001f)
            val a = Math.toRadians((135 + 270 * f).toDouble())
            val x = 150 + 96 * cos(a)
            val y = 150 + 96 * sin(a)
            Text(
                if (v == 1000) "1 G" else v.toString(),
                Modifier.width(32.dp).offset((x - 16).dp, (y - 8).dp),
                style = rf(11, 16, 600), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, style = gs(64, 64, 500, -2f, tnum = true))
            Text(unit, Modifier.padding(top = 4.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
        }
        Text(label, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), style = rf(13, 18, 600), color = acc.accent)
    }
}

@Composable
private fun PhaseSteps(c: SpeedController) {
    val acc = AntTheme.accent
    val stageIndex = when {
        c.phase == Phase.Results -> 3
        c.phase != Phase.Running -> -1
        else -> c.stage.ordinal
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple(Sym.NetworkPing, "Ping", c.ping?.let { "${fmt(it)} ms" }),
            Triple(Sym.Download, "Download", c.down?.let(::mbps) ?: if (stageIndex == 1) mbps(c.current) else null),
            Triple(Sym.Upload, "Upload", c.up?.let(::mbps) ?: if (stageIndex == 2) mbps(c.current) else if (c.phase == Phase.Error) "échec" else null),
        ).forEachIndexed { i, (icon, label, v) ->
            val active = stageIndex == i
            val done = stageIndex > i
            val error = c.phase == Phase.Error && i == 2
            val bg by animateColorAsState(
                when {
                    active -> acc.accent
                    done -> acc.container
                    error -> cs.errorContainer
                    else -> cs.surfaceContainerHigh
                },
                tween(300, easing = Motion.Emphasized), label = "step",
            )
            val fg = when {
                active -> acc.onAccent
                done -> acc.onContainer
                error -> cs.onErrorContainer
                else -> cs.onSurfaceVariant
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(if (error) Sym.Error else if (done) Sym.CheckCircle else icon, size = 18.dp, filled = true, tint = fg)
                Column {
                    Text(label, style = rf(11, 14, 600), color = fg, maxLines = 1)
                    Text(v ?: if (active) "…" else "—", style = rf(12, 14, 700, tnum = true), color = fg, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(r: SpeedRecord, index: Int, count: Int) {
    val acc = AntTheme.accent
    Row(
        Modifier.rise(index).fillMaxWidth().clip(groupShape(index, count)).background(cs.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Symbol(r.icon, size = 22.dp, filled = true, tint = acc.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(relativeDate(r.atMs), style = rf(14, 20, 600))
            Text(r.network, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("↓ ${mbps(r.downMbps)}", style = rf(12, 17, 700, tnum = true))
            Text("↑ ${mbps(r.upMbps)} · ${fmt(r.pingMs)} ms", style = rf(12, 17, tnum = true), color = cs.onSurfaceVariant)
        }
    }
}
