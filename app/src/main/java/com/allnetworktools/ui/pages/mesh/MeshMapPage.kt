package com.allnetworktools.ui.pages.mesh

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

private class MapNode(val num: Long, val label: String, val color: Int, val lat: Double, val lon: Double, val stale: Boolean)

private class NodesOverlay(private val density: Float) : Overlay() {
    var nodes: List<MapNode> = emptyList()
    var selected: Long? = null
    var me: GeoPoint? = null
    var onTap: (Long?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        me?.let {
            proj.toPixels(it, pt)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, paint)
            paint.color = 0xFF1E88E5.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5 * density, paint)
        }
        nodes.forEach { n ->
            proj.toPixels(GeoPoint(n.lat, n.lon), pt)
            val sel = n.num == selected
            val r = (if (sel) 17 else 14) * density
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 2.5f * density, paint)
            paint.color = n.color
            if (n.stale) paint.alpha = 150
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
            paint.alpha = 255
            text.color = if (0.299 * ((n.color shr 16) and 0xFF) + 0.587 * ((n.color shr 8) and 0xFF) + 0.114 * (n.color and 0xFF) > 150) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            c.drawText(n.label, pt.x.toFloat(), pt.y + 4 * density, text)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        fun dist(n: MapNode): Float {
            proj.toPixels(GeoPoint(n.lat, n.lon), pt)
            return (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }
        val hit = nodes.minByOrNull(::dist)?.takeIf { dist(it) < (30 * density) * (30 * density) }
        onTap(hit?.num)
        return hit != null
    }
}

/** Mesh map: the nodes that share their position, like the official app's map tab. */
@Composable
fun MeshMapPage(vm: MainViewModel) {
    val nodes by vm.mesh.nodes.collectAsStateWithLifecycle()
    val now = rememberEpochS(10_000)
    val here = myPosition(vm)
    var selected by remember { mutableStateOf<Long?>(null) }
    val points = nodes.values.filter { it.position != null }.map { n ->
        MapNode(n.num, n.shortName.take(4), nodeColor(n.num).toArgb(), n.position!!.lat, n.position.lon, now - n.lastHeardS > 2 * 3600)
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { NodesOverlay(density) }
    var centred by remember { mutableStateOf(false) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 18.0
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(here, points.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = here ?: points.firstOrNull()?.let { it.lat to it.lon } ?: return@LaunchedEffect
        map.controller.setCenter(GeoPoint(target.first, target.second))
        centred = true
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }
    val nav = WindowInsets.navigationBars.asPaddingValues()
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.nodes = points
                overlay.selected = selected
                overlay.me = here?.let { GeoPoint(it.first, it.second) }
                overlay.onTap = { selected = it }
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 40.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 40.dp, bg = bg, tint = cs.onSurface)
            if (here != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(here.first, here.second), 13.0, 600L) }, size = 40.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            "${points.size} nœuds positionnés",
            Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(cs.surface.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
            style = rf(12, 16, 600),
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 100.dp + nav.calculateBottomPadding())) {
            val n = selected?.let { nodes[it] }
            if (n != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface).clickable { vm.navigate { it.copy(page = Page.ToolPage(Tool.MeshNode, n.num.toString())) } }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NodeBadge(n)
                    Column(Modifier.weight(1f)) {
                        Text(n.longName, style = rf(15, 20, 600), maxLines = 1)
                        val d = if (here != null && n.position != null) " · " + distanceText(distanceM(here.first, here.second, n.position.lat, n.position.lon)) else ""
                        Text(ago(now, n.lastHeardS) + d, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                    com.allnetworktools.ui.components.Symbol(Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
                }
            }
            Text(
                "© les contributeurs d'OpenStreetMap",
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = rf(10, 12), color = cs.onSurfaceVariant,
            )
        }
    }
}
