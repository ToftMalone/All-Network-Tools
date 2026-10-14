package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural

/** One measurement: where it was taken on the plan (0–1 on both axes) and the signal there. */
data class CoveragePoint(val x: Float, val y: Float, val rssi: Int)

/** Signal measured by tapping where you stand on a blank plan; kept in memory only. */
class CoverageController {
    val points = mutableStateListOf<CoveragePoint>()
    fun clear() = points.clear()
    fun undo() { if (points.isNotEmpty()) points.removeAt(points.lastIndex) }
}

object Coverage {
    /** Inverse-distance weighting of the measurements at (x, y); a measurement right there wins. */
    fun estimate(points: List<CoveragePoint>, x: Float, y: Float): Float? {
        if (points.isEmpty()) return null
        var num = 0.0
        var den = 0.0
        for (p in points) {
            val d2 = ((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)).toDouble()
            if (d2 < 1e-6) return p.rssi.toFloat()
            val w = 1.0 / (d2 * d2)
            num += w * p.rssi; den += w
        }
        return (num / den).toFloat()
    }

    /** -90 dBm and below is red, -50 dBm and above green. */
    fun level(rssi: Float): Float = ((rssi + 90f) / 40f).coerceIn(0f, 1f)
}

@Composable
fun CoverageTool(c: CoverageController, conn: WifiConnection?) {
    val net = AntTheme.net
    PageColumn {
        if (conn == null) {
            ToolEmpty(Sym.WifiOff, "Pas de Wi-Fi", "Connectez-vous au réseau Wi-Fi dont vous voulez cartographier la couverture.", null)
            return@PageColumn
        }
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(conn.rssi.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text("dBm ici", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text("${conn.ssid ?: "Réseau masqué"} · ${c.points.size} ${plural(c.points.size, "mesure", "mesures")}", style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                HeroChip(
                    when {
                        conn.rssi >= -60 -> "Excellent signal"
                        conn.rssi >= -70 -> "Bon signal"
                        conn.rssi >= -80 -> "Signal faible"
                        else -> "Signal très faible"
                    },
                    when {
                        conn.rssi >= -60 -> net.good
                        conn.rssi >= -75 -> net.fair
                        else -> cs.error
                    },
                )
            }
        }
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
            CoveragePlan(c, conn.rssi)
            Text(
                "Imaginez ce cadre comme le plan de votre logement. Placez-vous dans une pièce, attendez 2 à 3 secondes, " +
                    "puis touchez l'endroit où vous êtes : le signal y est mesuré et la carte se colore.",
                Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        Legend()
        if (c.points.isNotEmpty()) {
            ToolButtons(
                ToolButton("Annuler la dernière", Sym.Undo, BtnKind.Outline) { c.undo() },
                ToolButton("Effacer", Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() },
            )
        }
    }
}

@Composable
private fun CoveragePlan(c: CoverageController, rssi: Int) {
    val good = AntTheme.net.good
    val fair = AntTheme.net.fair
    val bad = cs.error
    val grid = cs.outlineVariant
    val ring = cs.surface
    val ink = cs.onSurface
    val points = c.points.toList()
    fun color(v: Float): Color = if (v < 0.5f) lerp(bad, fair, v * 2) else lerp(fair, good, (v - 0.5f) * 2)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(rssi) {
                detectTapGestures { o -> c.points += CoveragePoint(o.x / size.width, o.y / size.height, rssi) }
            },
        ) {
            val n = 36
            val cw = size.width / n
            val ch = size.height / n
            if (points.isNotEmpty()) {
                for (i in 0 until n) for (j in 0 until n) {
                    val v = Coverage.estimate(points, (i + 0.5f) / n, (j + 0.5f) / n) ?: continue
                    drawRect(color(Coverage.level(v)).copy(alpha = 0.55f), Offset(i * cw, j * ch), Size(cw + 1f, ch + 1f))
                }
            }
            for (k in 1 until 6) {
                drawLine(grid, Offset(size.width * k / 6, 0f), Offset(size.width * k / 6, size.height), 1.dp.toPx())
                drawLine(grid, Offset(0f, size.height * k / 6), Offset(size.width, size.height * k / 6), 1.dp.toPx())
            }
            points.forEach { p ->
                val at = Offset(p.x * size.width, p.y * size.height)
                drawCircle(ring, 9.dp.toPx(), at)
                drawCircle(color(Coverage.level(p.rssi.toFloat())), 7.dp.toPx(), at)
                drawCircle(ink, 7.dp.toPx(), at, style = Stroke(1.dp.toPx()))
            }
        }
        if (points.isEmpty()) {
            Text("Touchez l'endroit où vous êtes", Modifier.align(Alignment.Center), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend() {
    val good = AntTheme.net.good
    val fair = AntTheme.net.fair
    val bad = cs.error
    SectionCard {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(bad, fair, good))))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(-90, -80, -70, -60, -50).forEach { Text(fmt(it), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant) }
        }
        Text("dBm · au-dessus de -67 dBm : appels vidéo et streaming confortables", Modifier.padding(top = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}
