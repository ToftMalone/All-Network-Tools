package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.SignalSample
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class HistPeriod(val label: String, val ms: Long, val fmt: String) {
    H1("1 h", 3_600_000L, "HH:mm"),
    H24("24 h", 86_400_000L, "HH'h'"),
    D7("7 j", 7 * 86_400_000L, "EEE"),
    D30("30 j", 30 * 86_400_000L, "d MMM"),
}

enum class HistMetric(val spec: QualitySpec, val min: Float, val max: Float, val pick: (SignalSample) -> Int?) {
    Rsrp(RsrpSpec, -125f, -65f, { it.rsrp }),
    Rsrq(RsrqSpec, -20f, -4f, { it.rsrq }),
    Sinr(SinrSpec, -5f, 30f, { it.sinr }),
}

/** A point of the downsampled series; [tech] is the dominant technology of the bucket. */
data class HistPoint(val atMs: Long, val value: Float?, val tech: String)

/** Averages samples into at most [buckets] points; a bucket without samples yields a gap (null). */
fun downsample(samples: List<SignalSample>, metric: HistMetric, from: Long, to: Long, buckets: Int = 90): List<HistPoint> {
    if (samples.isEmpty()) return emptyList()
    val width = (to - from).toDouble() / buckets
    val groups = samples.groupBy { ((it.atMs - from) / width).toInt().coerceIn(0, buckets - 1) }
    return (0 until buckets).mapNotNull { b ->
        val g = groups[b] ?: return@mapNotNull HistPoint(from + (b * width).toLong(), null, "")
        val vals = g.mapNotNull(metric.pick)
        HistPoint(g.first().atMs, vals.takeIf { it.isNotEmpty() }?.average()?.toFloat(), g.groupingBy { it.tech }.eachCount().maxBy { it.value }.key)
    }.dropWhile { it.value == null && it.tech.isEmpty() }
}

private fun techShort(t: String) = when (t) { "NR" -> "5G"; "LTE" -> "4G"; "WCDMA" -> "3G"; "GSM" -> "2G"; else -> t }

@Composable
fun SignalHistoryTool(vm: MainViewModel) {
    val all by vm.history.signal.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(HistPeriod.H24) }
    var metric by rememberSaveable { mutableStateOf(HistMetric.Rsrp) }
    val now = System.currentTimeMillis()
    val from = now - period.ms
    val samples = all.filter { it.atMs >= from }
    PageColumn {
        SegmentedRow(HistPeriod.entries.map { it to it.label }, period, { period = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistMetric.entries.forEach { m -> AntFilterChip(m.spec.key, metric == m, { metric = m }) }
        }
        val points = downsample(samples, metric, from, now)
        if (points.count { it.value != null } < 2) {
            ToolEmpty(
                com.allnetworktools.ui.theme.Sym.Monitoring, "Pas encore de données",
                if (all.isEmpty()) "L'historique enregistre une mesure par minute tant que l'application est ouverte. Revenez dans quelques minutes."
                else "Aucune mesure sur cette période. L'historique ne couvre que les moments où l'application était ouverte.",
                if (period != HistPeriod.D30 && all.isNotEmpty()) "Voir 30 jours" else null,
            ) { period = HistPeriod.D30 }
            return@PageColumn
        }
        val techChanges = points.zipWithNext().count { (a, b) -> a.tech.isNotEmpty() && b.tech.isNotEmpty() && a.tech != b.tech }
        SectionCard(shape = RoundedCornerShape(28.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${metric.spec.key} · ${period.label}", Modifier.weight(1f), style = rf(16, 22, 600))
                val f = SimpleDateFormat(if (period.ms > 86_400_000L) "d MMM" else "HH:mm", Locale.FRANCE)
                Text("${f.format(Date(samples.first().atMs))} – maintenant", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
            HistoryChart(points, metric, period, from, now, Modifier.padding(top = 30.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("5G" to AntTheme.accent.accent, "4G ou moins" to cs.onSurfaceVariant).forEach { (t, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = 0.3f)))
                        Text(t, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(AntTheme.net.fair))
                    Text("Changement de techno", style = rf(12, 16), color = cs.onSurfaceVariant)
                }
            }
        }
        val vals = samples.mapNotNull(metric.pick)
        val nrPct = if (samples.isEmpty()) 0 else (samples.count { it.tech == "NR" } * 100f / samples.size).roundToInt()
        val u = metric.spec.unit
        TileGrid(
            listOf(
                Triple("Min", vals.minOrNull()?.let(::fmt) ?: "—", u),
                Triple("Moy", vals.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.let(::fmt) ?: "—", u),
                Triple("Max", vals.maxOrNull()?.let(::fmt) ?: "—", u),
                Triple("En 5G", fmt(nrPct), "% · $techChanges ${plural(techChanges, "bascule")}"),
            ),
        ) { (k, v, unit), m -> com.allnetworktools.ui.components.MetricTile(k, v, unit, modifier = m) }
        Text(
            "${fmt(samples.size)} ${plural(samples.size, "mesure")} · une par minute quand l'application est ouverte · conservées sur l'appareil.",
            Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryChart(points: List<HistPoint>, metric: HistMetric, period: HistPeriod, from: Long, to: Long, modifier: Modifier) {
    val acc = AntTheme.accent.accent
    val other = cs.onSurfaceVariant
    val grid = cs.outlineVariant
    val fair = AntTheme.net.fair
    val labelStyle = rf(10, 14)
    val chartH = 200.dp
    val left = 32.dp
    val min = metric.min
    val max = metric.max
    val span = (to - from).toFloat()
    fun fx(t: Long) = ((t - from) / span).coerceIn(0f, 1f)
    val marks = points.zipWithNext().filter { (a, b) -> a.tech.isNotEmpty() && b.tech.isNotEmpty() && a.tech != b.tech }.map { (_, b) -> b }
    BoxWithConstraints(modifier.fillMaxWidth().height(chartH + 22.dp)) {
        val w = maxWidth - left
        Canvas(Modifier.fillMaxWidth().height(chartH)) {
            val l = left.toPx()
            val cw = size.width - l
            val h = size.height
            fun y(v: Float) = h - (v.coerceIn(min, max) - min) / (max - min) * h
            // technology bands
            var segStart = 0
            for (i in 1..points.size) {
                if (i == points.size || points[i].tech != points[segStart].tech) {
                    val t = points[segStart].tech
                    if (t.isNotEmpty()) {
                        val x1 = l + fx(points[segStart].atMs) * cw
                        val x2 = if (i == points.size) size.width else l + fx(points[i].atMs) * cw
                        drawRect((if (t == "NR") acc else other).copy(alpha = if (t == "NR") 0.12f else 0.07f), Offset(x1, 0f), Size(x2 - x1, h))
                    }
                    segStart = i
                }
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            (0..4).forEach { i -> val gy = h * i / 4f; drawLine(grid, Offset(l, gy), Offset(size.width, gy), 1.dp.toPx(), pathEffect = dash) }
            marks.forEach { m -> val mx = l + fx(m.atMs) * cw; drawLine(fair, Offset(mx, 0f), Offset(mx, h), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))) }
            val path = Path()
            var pen = false
            points.forEach { p ->
                val v = p.value
                if (v == null) { pen = false; return@forEach }
                val px = l + fx(p.atMs) * cw
                if (pen) path.lineTo(px, y(v)) else path.moveTo(px, y(v))
                pen = true
            }
            drawPath(path, acc, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        (0..4).forEach { i ->
            val gv = max - (max - min) * i / 4f
            Text(fmt(gv.roundToInt()), Modifier.offset(y = chartH * i / 4f - 7.dp), style = labelStyle, color = cs.onSurfaceVariant)
        }
        var lastX = -1f
        marks.filter { m -> (fx(m.atMs) - lastX > 0.09f).also { if (it) lastX = fx(m.atMs) } }.take(8).forEach { m ->
            val nr = m.tech == "NR"
            Box(
                Modifier.offset(x = left + w * fx(m.atMs) - 14.dp, y = (-24).dp).size(28.dp, 20.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (nr) acc else fair),
                contentAlignment = Alignment.Center,
            ) { Text(techShort(m.tech), style = rf(10, 12, 700), color = if (nr) AntTheme.accent.onAccent else cs.surface) }
        }
        val f = SimpleDateFormat(period.fmt, Locale.FRANCE)
        (0..4).forEach { i ->
            val t = from + ((to - from) * i / 4f).toLong()
            Text(
                if (i == 4) "maint." else f.format(Date(t)),
                Modifier.offset(x = left + w * (i / 4f) - 24.dp, y = chartH + 6.dp).width(48.dp),
                style = labelStyle, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}
