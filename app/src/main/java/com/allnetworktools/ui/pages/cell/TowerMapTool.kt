package com.allnetworktools.ui.pages.cell

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.OperatorChoice
import com.allnetworktools.data.TowerQuery
import com.allnetworktools.data.TowerRepository
import com.allnetworktools.data.TowerResult
import com.allnetworktools.data.TowerSite
import com.allnetworktools.data.chooseOperator
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

/** Sites loaded for the visible map area; they accumulate as the map is moved. */
class TowerMapController(private val repo: TowerRepository, private val scope: CoroutineScope) {
    val sites = androidx.compose.runtime.mutableStateMapOf<Long, TowerSite>()
    var operator by mutableStateOf<com.allnetworktools.data.FrOperator?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The visible area is too large to ask the ANFR for every emitter: zoom in. */
    var tooWide by mutableStateOf(false)
        private set
    var truncated by mutableStateOf(false)
        private set
    var dataUpdated by mutableStateOf<java.time.LocalDate?>(null)
        private set
    var generations by mutableStateOf(setOf("2G", "3G", "4G", "5G"))
    var showPlanned by mutableStateOf(false)
    var selected by mutableStateOf<Long?>(null)
    /** Camera kept between the card and the full-screen map, and across visits. */
    var center by mutableStateOf<Pair<Double, Double>?>(null)
    var zoom by mutableStateOf(15.0)
    /** Set by the refresh action: the next camera report reloads even an area already loaded. */
    var forceNext by mutableStateOf(false)
    private var lastQuery: TowerQuery? = null
    private var job: Job? = null

    /** Called when the camera settles; [box] is south, west, north, east. */
    fun onViewport(op: com.allnetworktools.data.FrOperator, box: DoubleArray, me: Pair<Double, Double>, force: Boolean = false) {
        if (operator != op) { sites.clear(); operator = op }
        val (s, w, n, e) = box.toList()
        val diag = TowerSite(0, s, w, null, "", emptyList(), emptyList(), null).distanceTo(n, e)
        tooWide = diag > MaxDiagonalM
        if (tooWide) return
        val q = TowerQuery.area(op, s, w, n, e, me.first, me.second)
        val last = lastQuery
        // Skip when the new area lies inside the one already loaded.
        if (!force && last != null && last.operator == op && last.box!!.let { b -> s >= b[0] && w >= b[1] && n <= b[2] && e <= b[3] }) return
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                // Load a margin around the view so small pans need no new request.
                val dl = (n - s) * 0.25
                val dw = (e - w) * 0.25
                val wide = TowerQuery.area(op, s - dl, w - dw, n + dl, e + dw, me.first, me.second)
                val r = repo.fetch(wide)
                merge(r)
                lastQuery = wide
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Réseau indisponible"
            } finally {
                loading = false
            }
        }
    }

    private fun merge(r: TowerResult) {
        r.sites.forEach { sites[it.supportId] = it }
        truncated = r.truncated
        r.dataUpdated?.let { d -> if (dataUpdated == null || d > dataUpdated!!) dataUpdated = d }
        // Keep memory bounded when the user explores far.
        if (sites.size > MaxSites) {
            val q = r.query
            sites.values.sortedByDescending { it.distanceTo(q.lat, q.lon) }.take(sites.size - MaxSites).forEach { sites.remove(it.supportId) }
        }
    }

    fun visible(): List<TowerSite> = sites.values.mapNotNull { s ->
        val em = s.emitters.filter { it.generation in generations && (showPlanned || it.status != TowerSite.StatusPlanned) }
        if (em.isEmpty()) null else s.copy(emitters = em)
    }

    internal fun setForTest(r: TowerResult) {
        operator = r.query.operator
        merge(r)
    }

    companion object {
        const val MaxDiagonalM = 40_000.0
        const val MaxSites = 6000
    }
}

private val dmy = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)

internal fun meters(m: Double) = if (m >= 1000) "${fmt(m / 1000, 1)} km" else "${fmt(m)} m"

private fun cardinal(az: Double): String = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")[(((az % 360) + 360) % 360 / 45).roundToInt() % 8]

@Composable
private fun genColor(g: String): Color {
    val n = AntTheme.net
    return when (g) {
        "5G" -> n.cell.accent
        "4G" -> n.wifi.accent
        "3G" -> n.gnss.accent
        else -> cs.outline
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TowerMapTool(vm: MainViewModel) {
    val c = vm.tools.towerMap
    val positions by vm.positions.collectAsStateWithLifecycle()
    val fallback = remember { vm.lastKnownLocation() }
    val loc = positions.fused ?: positions.gnss ?: positions.network ?: fallback
    val choice = remember { vm.operatorPlmns().let { (sim, net) -> chooseOperator(sim, net) to (sim to net) } }
    val op: OperatorChoice? = choice.first
    var full by remember { mutableStateOf(false) }
    TopBarAction(Sym.Refresh) { c.forceNext = true }
    PageColumn {
        if (op == null) {
            val (sim, net) = choice.second
            EmptyStateCard(
                Sym.CellTower, "Opérateur non couvert",
                "Cette carte utilise uniquement les données publiques de l'ANFR, qui recensent les antennes d'Orange, SFR, Free Mobile et Bouygues Telecom en France. " +
                    "Aucun de ces opérateurs n'a été reconnu pour la carte SIM active (" + listOfNotNull(sim?.let { "SIM $it" }, net?.let { "réseau $it" }).joinToString(", ").ifEmpty { "aucune SIM lue" } + ").",
            )
            return@PageColumn
        }
        if (loc == null) {
            EmptyStateCard(Sym.LocationSearching, "Position inconnue", "La carte a besoin de votre position pour se centrer sur vous.")
            return@PageColumn
        }
        val me = loc.latitude to loc.longitude
        val sites = c.visible()
        val nearest = sites.sortedBy { it.distanceTo(me.first, me.second) }
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(Sym.CellTower, size = 28.dp, filled = true, tint = AntTheme.accent.accent)
                Text(op.operator.label, style = gs(26, 32, 500))
            }
            Text(
                (if (op.fromSim) "Opérateur de votre carte SIM" else "Réseau sur lequel vous êtes connecté") + " (${op.plmn.take(3)} ${op.plmn.drop(3)}). Seuls ses sites sont affichés.",
                Modifier.padding(top = 4.dp), style = rf(13, 18),
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sites.size.toString(), style = gs(48, 52, 500, -1.5f, tnum = true))
                Text("${plural(sites.size, "site chargé", "sites chargés")} sur la carte", Modifier.padding(bottom = 6.dp), style = rf(16, 22, 500))
            }
            nearest.firstOrNull()?.let { s ->
                Text("Le plus proche : ${meters(s.distanceTo(me.first, me.second))} au ${cardinal(s.bearingFrom(me.first, me.second))}", style = rf(13, 18))
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c.loading -> HeroChip("Interrogation de l'ANFR…", AntTheme.net.fair, blink = true)
                    c.tooWide -> HeroChip("Zoomez pour charger les antennes", AntTheme.net.fair)
                    c.dataUpdated != null -> HeroChip("ANFR · données du ${c.dataUpdated!!.format(dmy)}", AntTheme.net.good)
                }
            }
        }
        val err = c.error
        if (err != null) {
            ToolError(Sym.CloudOff, "Données ANFR indisponibles", "Impossible de joindre data.anfr.fr ($err).", "Réessayer") { c.forceNext = true }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("5G", "4G", "3G", "2G").forEach { g ->
                AntFilterChip(g, g in c.generations, { c.generations = if (g in c.generations) (c.generations - g).ifEmpty { c.generations } else c.generations + g }, leadingDot = genColor(g))
            }
            AntFilterChip("Projets approuvés", c.showPlanned, { c.showPlanned = !c.showPlanned })
        }
        if (c.truncated) {
            SectionCard(color = cs.errorContainer) {
                Text("Zone très dense : l'ANFR renvoie au plus ${TowerRepository.MaxRows} émetteurs par requête. Zoomez pour tout voir.", style = rf(13, 18), color = cs.onErrorContainer)
            }
        }
        if (full) {
            com.allnetworktools.ui.components.FullscreenDialog({ full = false }) {
                TowerMap(c, op, me, sites, Modifier.fillMaxSize(), onFullscreen = null)
            }
        }
        SectionCard(shape = RoundedCornerShape(28.dp), padding = PaddingValues(8.dp)) {
            TowerMap(c, op, me, sites, Modifier.fillMaxWidth().aspectRatio(0.95f).clip(RoundedCornerShape(20.dp)), onFullscreen = { full = true })
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("5G", "4G", "3G", "2G").filter { it in c.generations }.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(genColor(g)))
                        Text(g, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                }
            }
            Text("Déplacez la carte : les antennes de la zone affichée se chargent automatiquement. Touchez un site pour voir ses émetteurs et leurs azimuts.", Modifier.padding(horizontal = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        val sel = sites.firstOrNull { it.supportId == c.selected }
        if (sel != null) SiteCard(sel, me.first, me.second)
        if (nearest.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Sites les plus proches de vous", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                nearest.take(20).forEach { s ->
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable { c.selected = s.supportId; c.center = s.lat to s.lon }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(genColor(s.bestGeneration)))
                        Column(Modifier.weight(1f)) {
                            Text(s.address, style = rf(14, 20, 600), maxLines = 1)
                            Text(
                                s.generations.joinToString(" · ") + (s.heightM?.let { " · ${fmt(it)} m" } ?: "") + (if (!s.inService) " · en projet" else ""),
                                style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
                            )
                        }
                        Text(meters(s.distanceTo(me.first, me.second)), style = rf(13, 18, 600, tnum = true))
                    }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Source : ANFR, Observatoire du déploiement 2G/3G/4G/5G (data.anfr.fr, Licence Ouverte). " +
                        "Emplacements, hauteurs, fréquences et azimuts sont ceux déclarés par ${op.operator.label} à l'ANFR ; aucune position n'est estimée par l'application. " +
                        "Les antennes des autres opérateurs ne sont pas affichées.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun SiteCard(s: TowerSite, lat: Double, lon: Double) {
    val actions = LocalActions.current
    InfoList(s.address) {
        InfoRow("Distance", "${meters(s.distanceTo(lat, lon))} au ${cardinal(s.bearingFrom(lat, lon))}")
        s.heightM?.let { InfoRow("Hauteur du support", "${fmt(it)} m") }
        if (s.azimuths.isNotEmpty()) InfoRow("Azimuts", s.azimuths.joinToString(" · ") { "$it°" })
        InfoRow("Coordonnées", "%.5f, %.5f".format(Locale.US, s.lat, s.lon)) { actions.copy("Coordonnées", "%.6f, %.6f".format(Locale.US, s.lat, s.lon)) }
        if (s.stations.isNotEmpty()) InfoRow("N° de station ANFR", s.stations.joinToString(", ")) { actions.copy("Station ANFR", s.stations.joinToString(", ")) }
        InfoRow("N° de support", s.supportId.toString())
        Hairline()
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.emitters.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TechChip(e.generation, genColor(e.generation), Color.White)
                    Column(Modifier.weight(1f)) {
                        Text(e.system, style = rf(14, 20, 600))
                        Text(
                            e.status + (e.since?.let { " depuis le ${it.format(dmy)}" } ?: "") + (if (e.azimuths.isNotEmpty()) " · " + e.azimuths.joinToString("/") { "$it°" } else ""),
                            style = rf(12, 16), color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Site markers, antenna azimuths of the selected site and the user's position. */
private class TowerOverlay(private val density: Float) : Overlay() {
    var sites: List<Triple<TowerSite, Int, Boolean>> = emptyList()
    var me: GeoPoint? = null
    var accent = 0
    var onTap: (Long?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = android.graphics.Point()
    private val pt2 = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        sites.filter { it.third }.forEach { (s, color, _) ->
            proj.toPixels(GeoPoint(s.lat, s.lon), pt)
            paint.color = color
            paint.strokeWidth = 3 * density
            paint.strokeCap = Paint.Cap.ROUND
            s.azimuths.forEach { az ->
                val len = 46 * density
                val a = Math.toRadians(az.toDouble())
                c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt.x + (len * sin(a)).toFloat(), pt.y - (len * cos(a)).toFloat(), paint)
            }
        }
        sites.sortedBy { it.third }.forEach { (s, color, sel) ->
            proj.toPixels(GeoPoint(s.lat, s.lon), pt)
            val r = (if (sel) 9 else 6) * density
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 2 * density, paint)
            paint.color = color
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
        }
        me?.let {
            proj.toPixels(it, pt2)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt2.x.toFloat(), pt2.y.toFloat(), 9 * density, paint)
            paint.color = accent
            c.drawCircle(pt2.x.toFloat(), pt2.y.toFloat(), 6 * density, paint)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        var best: TowerSite? = null
        var bestD = (24 * density).let { it * it }
        sites.forEach { (s, _, _) ->
            proj.toPixels(GeoPoint(s.lat, s.lon), pt)
            val dx = pt.x - e.x
            val dy = pt.y - e.y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = s }
        }
        onTap(best?.supportId)
        return best != null
    }
}


@Composable
private fun TowerMap(c: TowerMapController, op: OperatorChoice, me: Pair<Double, Double>, sites: List<TowerSite>, modifier: Modifier, onFullscreen: (() -> Unit)?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { TowerOverlay(density) }
    val colors = mapOf("5G" to genColor("5G").toArgb(), "4G" to genColor("4G").toArgb(), "3G" to genColor("3G").toArgb(), "2G" to genColor("2G").toArgb())
    val accent = AntTheme.accent.accent.toArgb()
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            controller.setZoom(c.zoom)
            controller.setCenter((c.center ?: me).let { GeoPoint(it.first, it.second) })
        }
    }
    val report by rememberUpdatedState { force: Boolean ->
        val b = map.boundingBox
        if (map.width > 0 && map.height > 0) {
            c.center = map.mapCenter.latitude to map.mapCenter.longitude
            c.zoom = map.zoomLevelDouble
            c.onViewport(op.operator, doubleArrayOf(b.latSouth, b.lonWest, b.latNorth, b.lonEast), me, force)
        }
    }
    DisposableEffect(map) {
        val listener = org.osmdroid.events.DelayedMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean { report(false); return false }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean { report(false); return false }
        }, 500)
        map.addMapListener(listener)
        map.addOnFirstLayoutListener { _, _, _, _, _ -> report(false) }
        onDispose { map.removeMapListener(listener) }
    }
    LaunchedEffect(c.forceNext) { if (c.forceNext) { c.forceNext = false; report(true) } }
    // A site picked in the list: bring it into view.
    LaunchedEffect(c.selected) {
        val s = sites.firstOrNull { it.supportId == c.selected } ?: return@LaunchedEffect
        map.controller.animateTo(GeoPoint(s.lat, s.lon))
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
    val safe = WindowInsets.safeDrawing
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.sites = sites.map { Triple(it, colors[it.bestGeneration] ?: accent, it.supportId == c.selected) }
                overlay.me = GeoPoint(me.first, me.second)
                overlay.accent = accent
                overlay.onTap = { c.selected = it }
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(safe).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            if (onFullscreen != null) IconCircleButton(Sym.Fullscreen, onFullscreen, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 15.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        if (c.tooWide) {
            Row(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(safe).padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(cs.inverseSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(Sym.ZoomIn, size = 18.dp, tint = cs.inverseOnSurface)
                Text("Zoomez pour charger les antennes", style = rf(12, 16, 600), color = cs.inverseOnSurface)
            }
        }
        Text(
            "© les contributeurs d'OpenStreetMap",
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(safe).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f))
                .clickable {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 13, 500), color = cs.onSurface,
        )
    }
}
