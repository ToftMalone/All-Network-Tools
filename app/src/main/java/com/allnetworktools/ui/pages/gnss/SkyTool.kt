package com.allnetworktools.ui.pages.gnss

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.Country
import com.allnetworktools.data.FixType
import com.allnetworktools.data.SatGeo
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.WorldMap
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.util.fmt

/** A satellite with the point of Earth it currently flies over. */
data class SatOverhead(val sat: Satellite, val lat: Double, val lon: Double, val place: String)

enum class SkyView(val label: String) { Sky("Ciel"), Map("Carte du monde") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkyTool(vm: MainViewModel) {
    val g by vm.gnss.collectAsStateWithLifecycle()
    var view by vm.tools.skyView
    val context = LocalContext.current
    val countries by produceState<List<Country>>(emptyList()) { value = WorldMap.load(context) }
    val observer = (g.location ?: remember { vm.lastKnownLocation() })?.let { it.latitude to it.longitude }
    val overhead = if (observer == null || countries.isEmpty()) emptyList() else g.visible.filter { it.elevation > 0f }.map { s ->
        val (lat, lon) = SatGeo.subPoint(observer.first, observer.second, s)
        SatOverhead(s, lat, lon, WorldMap.countryAt(countries, lat, lon) ?: WorldMap.oceanAt(lat, lon))
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(g.used.size.toString(), style = gs(48, 52, 500, -1.5f, tnum = true))
                Text("/ ${g.visible.size}", Modifier.padding(bottom = 6.dp), style = rf(18, 24, 500))
                Box(Modifier.weight(1f))
                Text(if (g.fix == FixType.None) "Recherche…" else g.fix.label, Modifier.padding(bottom = 8.dp), style = rf(14, 20, 600))
            }
            Text("satellites utilisés / visibles · ${g.constellationCount} constellations", style = rf(13, 18))
        }
        SegmentedRow(SkyView.entries.map { it to it.label }, view, { view = it }, Modifier.fillMaxWidth(), icons = mapOf(SkyView.Sky to Sym.Radar, SkyView.Map to Sym.Public))
        if (g.visible.isEmpty()) {
            EmptyStateCard(Sym.SatelliteAlt, "Recherche de satellites…", "Placez-vous à l'extérieur, ciel dégagé. Les premiers satellites apparaissent en quelques secondes.")
            return@PageColumn
        }
        when (view) {
            SkyView.Sky -> SectionCard(shape = RoundedCornerShape(32.dp)) {
                SkyPlot(g.visible, height = 360.dp)
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Constellation.entries.forEach { c ->
                        val vis = g.visible.count { it.constellation == c }
                        if (vis > 0) {
                            Row(
                                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(constellationColor(c)))
                                Text(c.label, style = rf(12, 16, 500))
                                Text("${g.used.count { it.constellation == c }}/$vis", style = rf(12, 16, 700, tnum = true))
                            }
                        }
                    }
                }
                Text(
                    "Centre : zénith · bord : horizon. Les satellites pleins servent au calcul de la position.",
                    Modifier.padding(top = 10.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
                )
            }
            SkyView.Map -> {
                if (observer == null) {
                    EmptyStateCard(Sym.LocationSearching, "Position inconnue", "La carte a besoin de votre position pour situer les satellites. Attendez le premier fix.")
                    return@PageColumn
                }
                SectionCard(shape = RoundedCornerShape(28.dp), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    WorldMapView(countries, observer, overhead)
                    Text(
                        "Point sous chaque satellite, calculé depuis sa direction et son orbite · pincez pour zoomer.",
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
                    )
                }
                SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Text("Au-dessus de…", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                    overhead.sortedWith(compareByDescending<SatOverhead> { it.sat.used }.thenByDescending { it.sat.elevation }).forEach { o ->
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val col = constellationColor(o.sat.constellation)
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (o.sat.used) col else cs.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) { Text(o.sat.svid.toString(), style = rf(12, 16, 700), color = if (o.sat.used) cs.surface else col) }
                            Column(Modifier.weight(1f)) {
                                Text(o.place, style = rf(15, 20, 600), maxLines = 1)
                                Text(
                                    "${o.sat.constellation.label} ${o.sat.id} · ${latLon(o.lat, o.lon)}",
                                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${fmt(o.sat.elevation)}°", style = rf(14, 20, 600, tnum = true))
                                Text("élévation", style = rf(11, 14), color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun latLon(lat: Double, lon: Double) =
    "${fmt(kotlin.math.abs(lat), 0)}° ${if (lat >= 0) "N" else "S"}, ${fmt(kotlin.math.abs(lon), 0)}° ${if (lon >= 0) "E" else "O"}"

@Composable
fun WorldMapView(countries: List<Country>, observer: Pair<Double, Double>, sats: List<SatOverhead>) {
    val land = cs.outlineVariant.copy(alpha = 0.55f)
    val sea = cs.surfaceContainerLow
    val border = cs.outline.copy(alpha = 0.5f)
    val grid = cs.outlineVariant
    val acc = AntTheme.accent.accent
    val ring = cs.surface
    val colors = Constellation.entries.associateWith { constellationColor(it) }
    val measurer = rememberTextMeasurer()
    val labelStyle = rf(9, 11, 700)
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val pulse by rememberInfiniteTransition(label = "me").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "me")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wDp = maxWidth
        val hDp = maxWidth / 2
        val paths = remember(countries, wDp) {
            countries.map { c ->
                Path().apply {
                    c.rings.forEach { r ->
                        for (i in 0 until r.size / 2) {
                            val x = (r[i * 2] + 180f) / 360f
                            val y = (90f - r[i * 2 + 1]) / 180f
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                }
            }
        }
        fun clamp(w: Float, h: Float) {
            ox = ox.coerceIn(w - w * scale, 0f)
            oy = oy.coerceIn(h - h * scale, 0f)
        }
        Box(Modifier.fillMaxWidth().height(hDp).clip(RoundedCornerShape(20.dp)).clipToBounds()) {
            Canvas(
                Modifier.fillMaxWidth().height(hDp)
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val ns = (scale * zoom).coerceIn(1f, 10f)
                            val f = ns / scale
                            ox = centroid.x - (centroid.x - ox) * f + pan.x
                            oy = centroid.y - (centroid.y - oy) * f + pan.y
                            scale = ns
                            clamp(size.width.toFloat(), size.height.toFloat())
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { c ->
                            val ns = if (scale >= 6f) 1f else scale * 2
                            val f = ns / scale
                            ox = c.x - (c.x - ox) * f; oy = c.y - (c.y - oy) * f; scale = ns
                            clamp(size.width.toFloat(), size.height.toFloat())
                        })
                    },
            ) {
                val w = size.width
                val h = size.height
                fun sx(lon: Double) = ((lon + 180) / 360 * w * scale + ox).toFloat()
                fun sy(lat: Double) = ((90 - lat) / 180 * h * scale + oy).toFloat()
                drawRect(sea)
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
                for (lon in -150..150 step 30) drawLine(grid, Offset(sx(lon.toDouble()), 0f), Offset(sx(lon.toDouble()), h), 1f, pathEffect = dash)
                for (lat in -60..60 step 30) drawLine(grid, Offset(0f, sy(lat.toDouble())), Offset(w, sy(lat.toDouble())), if (lat == 0) 1.5f else 1f, pathEffect = if (lat == 0) null else dash)
                withTransform({
                    translate(ox, oy)
                    scale(w * scale, h * scale, pivot = Offset.Zero)
                }) {
                    paths.forEach { p ->
                        drawPath(p, land)
                        drawPath(p, border, style = Stroke(0.8f / (w * scale)))
                    }
                }
                val me = Offset(sx(observer.second), sy(observer.first))
                sats.forEach { o ->
                    val p = Offset(sx(o.lon), sy(o.lat))
                    val c = colors.getValue(o.sat.constellation)
                    if (o.sat.used) drawLine(c.copy(alpha = 0.25f), me, p, 1.dp.toPx())
                }
                sats.forEach { o ->
                    val p = Offset(sx(o.lon), sy(o.lat))
                    val c = colors.getValue(o.sat.constellation)
                    val r = 5.5f.dp.toPx()
                    drawCircle(ring, r + 1.5f.dp.toPx(), p)
                    drawCircle(c, r, p, style = if (o.sat.used) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.dp.toPx()))
                    if (scale >= 1.8f) {
                        val t = measurer.measure(o.sat.id, labelStyle.copy(color = c))
                        drawText(t, topLeft = Offset(p.x + r + 3.dp.toPx(), p.y - t.size.height / 2f))
                    }
                }
                drawCircle(acc.copy(alpha = 0.3f * (1 - pulse)), 6.dp.toPx() + 14.dp.toPx() * pulse, me)
                drawCircle(ring, 6.dp.toPx(), me)
                drawCircle(acc, 4.5f.dp.toPx(), me)
            }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val wPx = with(density) { wDp.toPx() }
            val hPx = with(density) { hDp.toPx() }
            fun zoomAt(ns: Float, cx: Float, cy: Float) {
                val f = ns / scale
                ox = cx - (cx - ox) * f; oy = cy - (cy - oy) * f; scale = ns
                clamp(wPx, hPx)
            }
            Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val bg = cs.surface.copy(alpha = 0.9f)
                IconCircleButton(Sym.Add, { zoomAt((scale * 2).coerceAtMost(10f), wPx / 2, hPx / 2) }, size = 36.dp, bg = bg, tint = cs.onSurface)
                IconCircleButton(Sym.Remove, { zoomAt((scale / 2).coerceAtLeast(1f), wPx / 2, hPx / 2) }, size = 36.dp, bg = bg, tint = cs.onSurface)
                IconCircleButton(Sym.MyLocation, {
                    // ×4 centred on the observer.
                    scale = 4f
                    ox = wPx / 2 - ((observer.second + 180) / 360 * wPx * scale).toFloat()
                    oy = hPx / 2 - ((90 - observer.first) / 180 * hPx * scale).toFloat()
                    clamp(wPx, hPx)
                }, size = 36.dp, bg = bg, tint = acc)
            }
        }
    }
}
