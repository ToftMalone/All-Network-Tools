package com.allnetworktools.ui.pages.sdr

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.MeshPreset
import com.allnetworktools.data.sdr.MeshRegion
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.plural
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    return now
}

private fun ago(ms: Long, now: Long): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "il y a $s s"
        s < 3600 -> "il y a ${s / 60} min"
        s < 86_400 -> "il y a ${s / 3600} h"
        else -> "il y a ${s / 86_400} j"
    }
}

/** The one-sentence answer to "why do I see nothing?", with a colour. */
@Composable
internal fun MeshVerdictCard(c: MeshtasticController) {
    val v = c.verdict()
    val good = v.health == MeshHealth.Working
    val bad = v.health == MeshHealth.NoUsb || v.health == MeshHealth.BadCrc || v.health == MeshHealth.OtherNetwork || v.health == MeshHealth.OtherChannel
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(
                if (good) Sym.CheckCircle else if (bad) Sym.Warning else Sym.Info, size = 22.dp, filled = good || bad,
                tint = if (good) AntTheme.net.good else if (bad) AntTheme.net.fair else AntTheme.accent.accent,
            )
            Text(v.text, style = rf(14, 20), color = cs.onSurface)
        }
    }
}

// ---- Nodes ---------------------------------------------------------------------------------------------

@Composable
internal fun NodesPage(c: MeshtasticController) {
    val now = rememberNow()
    val list = c.nodes.values.sortedByDescending { it.lastHeardMs }
    val located = list.count { it.lat != null }
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${list.size}", style = gs(40, 44, 500, -1f, tnum = true))
            Text(plural(list.size, "nœud entendu", "nœuds entendus"), Modifier.padding(bottom = 6.dp), style = rf(16, 22, 500))
        }
        Text(
            if (list.isEmpty()) "Les nœuds apparaissent dès qu'ils émettent (position, informations ou télémétrie)."
            else "$located avec position · dernier ${ago(list.first().lastHeardMs, now)}",
            Modifier.padding(top = 2.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
        )
    }
    if (list.isNotEmpty()) ListCard { list.forEachIndexed { i, n -> if (i > 0) Hairline(); MeshNodeRow(n, now, false) { } } }
}

@Composable
internal fun MeshNodeRow(n: MeshNode, now: Long, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) AntTheme.accent.accent else AntTheme.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                n.shortName?.take(4) ?: "?", style = rf(12, 16, 700), maxLines = 1,
                color = if (selected) AntTheme.accent.onAccent else AntTheme.accent.onContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.label, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ago(n.lastHeardMs, now), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(Meshtastic.nodeId(n.num), n.hwModel?.let(Meshtastic::hwModel)).joinToString(" · "),
                style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    n.snr?.let { "SNR ${snr(it)}" },
                    n.hops?.let { if (it == 0) "direct" else "$it ${plural(it, "saut")}" },
                    n.battery?.let { if (it > 100) "secteur" else "batterie $it %" },
                    n.voltage?.takeIf { it > 0 }?.let { "%.2f V".format(Locale.FRANCE, it) },
                    "${n.packets} ${plural(n.packets, "paquet")}",
                ).joinToString(" · "),
                Modifier.padding(top = 2.dp), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (n.lat != null && n.lon != null) Text(
                "%.5f, %.5f".format(Locale.US, n.lat, n.lon) + (n.altitude?.let { " · $it m" } ?: "") + " · position ${ago(n.positionAtMs, now)}",
                Modifier.padding(top = 2.dp), style = rf(12, 16, tnum = true), color = AntTheme.accent.accent,
            )
        }
    }
}

// ---- Map -----------------------------------------------------------------------------------------------

private class MapNode(val num: Long, val label: String, val lat: Double, val lon: Double, val ageMs: Long)

/** Nodes as dots coloured by how recently they were heard, labelled with their short name. */
private class MeshOverlay(private val density: Float) : Overlay() {
    var nodes: List<MapNode> = emptyList()
    var selected: Long? = null
    var me: GeoPoint? = null
    var onTap: (Long?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true }
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
            val r = (if (sel) 12 else 9) * density
            val color = when {
                sel -> 0xFFE53935.toInt()
                n.ageMs < 10 * 60_000L -> 0xFF2E7D32.toInt()
                n.ageMs < 3_600_000L -> 0xFFF9A825.toInt()
                else -> 0xFF78909C.toInt()
            }
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 2 * density, paint)
            paint.color = color; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
            text.color = 0xFFFFFFFF.toInt(); text.style = Paint.Style.STROKE; text.strokeWidth = 3 * density
            c.drawText(n.label, pt.x + r + 4 * density, pt.y + 4 * density, text)
            text.style = Paint.Style.FILL; text.color = 0xFF202124.toInt()
            c.drawText(n.label, pt.x + r + 4 * density, pt.y + 4 * density, text)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        fun dist(n: MapNode): Float {
            proj.toPixels(GeoPoint(n.lat, n.lon), pt)
            return (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }
        val hit = nodes.minByOrNull(::dist)?.takeIf { dist(it) < (28 * density) * (28 * density) }
        onTap(hit?.num)
        return hit != null
    }
}

@Composable
internal fun MeshMapPage(vm: MainViewModel, c: MeshtasticController) {
    val now = rememberNow()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    var selected by remember { mutableStateOf<Long?>(null) }
    val located = c.nodes.values.filter { it.lat != null && it.lon != null }.sortedByDescending { it.lastHeardMs }
    val points = located.map { MapNode(it.num, it.shortName?.take(4) ?: Meshtastic.nodeId(it.num).takeLast(4), it.lat!!, it.lon!!, now - it.positionAtMs) }
    MeshMap(points, selected, me, { selected = it }, Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(24.dp)))
    if (located.isEmpty()) Empty("Aucun nœud avec position pour l'instant. Un nœud apparaît sur la carte dès qu'il diffuse sa position (toutes les 15 minutes environ, et seulement s'il la partage).")
    else ListCard {
        located.forEachIndexed { i, n ->
            if (i > 0) Hairline()
            MeshNodeRow(n, now, n.num == selected) { selected = if (selected == n.num) null else n.num }
        }
    }
}

@Composable
private fun MeshMap(nodes: List<MapNode>, selected: Long?, me: Pair<Double, Double>?, onSelect: (Long?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { MeshOverlay(density) }
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
    LaunchedEffect(me, nodes.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = nodes.firstOrNull()?.let { it.lat to it.lon } ?: me ?: return@LaunchedEffect
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
    val safe = WindowInsets.safeDrawing
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.nodes = nodes
                overlay.selected = selected
                overlay.me = me?.let { GeoPoint(it.first, it.second) }
                overlay.onTap = onSelect
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(safe).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            if (me != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 12.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            "© les contributeurs d'OpenStreetMap",
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}

// ---- Channels ------------------------------------------------------------------------------------------

@Composable
internal fun ChannelsPage(c: MeshtasticController) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Radio", style = rf(14, 20, 600), color = AntTheme.accent.accent)
            Text("Préréglage du réseau", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeshPreset.entries.forEach { p -> AntFilterChip(p.channelName, c.preset == p, { c.selectPreset(p) }) }
            }
            Text("${c.preset.label} · ${c.preset.description}", style = rf(12, 16), color = cs.onSurfaceVariant)
            Text("Région", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeshRegion.entries.forEach { r -> AntFilterChip(r.label, c.region == r, { c.selectRegion(r) }) }
            }
            val plan = c.planMhz
            Text(
                if (plan != null) "Fréquence par défaut : ${"%.3f".format(Locale.FRANCE, plan)} MHz (calculée d'après la région et le préréglage ; le LongFast européen est à 869,525 MHz)."
                else "Ce préréglage (${c.preset.bandwidthHz / 1000} kHz) ne tient pas dans la bande de cette région : saisissez la fréquence à la main.",
                style = rf(12, 16), color = if (plan != null) cs.onSurfaceVariant else cs.error,
            )
            HostInputField(c.frequencyMhz, { c.frequencyMhz = it; c.persist() }, "Fréquence (MHz)", Sym.Stream, keyboardType = KeyboardType.Decimal)
            if (c.running) Text("Les réglages radio s'appliquent au prochain lancement.", style = rf(12, 16), color = cs.onSurfaceVariant)
        }
    }
    c.channels.forEachIndexed { i, ch ->
        val (resolved, err) = c.resolve(ch)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Canal ${i + 1}" + if (i == 0) " · principal" else "", Modifier.weight(1f),
                        style = rf(14, 20, 600), color = AntTheme.accent.accent,
                    )
                    if (resolved != null) Text(
                        "hash 0x%02X · ${c.channelCounts[resolved.hash] ?: 0} ${plural(c.channelCounts[resolved.hash] ?: 0, "paquet")}".format(resolved.hash),
                        style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                    )
                    if (c.channels.size > 1) IconCircleButton(Sym.Delete, { c.removeChannel(i) }, size = 36.dp, tint = cs.onSurfaceVariant)
                }
                HostInputField(ch.name, { c.setChannel(i, ch.copy(name = it.take(11))) }, "Nom du canal (11 caractères max)", Sym.Tag)
                HostInputField(ch.keyBase64, { c.setChannel(i, ch.copy(keyBase64 = it)) }, "Clé (Base64, AQ== = clé publique)", Sym.Key, keyboardType = KeyboardType.Ascii)
                if (err != null) Text(err, style = rf(13, 18), color = cs.error)
            }
        }
    }
    ToolButtons(
        ToolButton("Ajouter un canal", Sym.Add, BtnKind.OutlineOnSurface) { c.addChannel() },
        ToolButton("Rétablir le défaut", Sym.Refresh, BtnKind.OutlineOnSurface) { c.resetChannels() },
    )
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Text(
                "Un canal Meshtastic, c'est un nom et une clé. Un paquet n'est lisible que si son canal (identifié par le hash ci-dessus) est dans cette liste. " +
                    "Le canal par défaut s'appelle comme le préréglage (LongFast…) avec la clé publique AQ==. " +
                    "${c.otherChannel} ${plural(c.otherChannel, "paquet reçu", "paquets reçus")} d'un autre canal depuis le lancement.",
                style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
    }
}

// ---- This node -----------------------------------------------------------------------------------------

@Composable
internal fun NodePage(vm: MainViewModel, c: MeshtasticController) {
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    MeshRunButton(vm, c)
    MeshVerdictCard(c)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Identité du nœud", style = rf(14, 20, 600), color = AntTheme.accent.accent)
            HostInputField(c.longName, { c.longName = it.take(39); c.persist() }, "Nom long", Sym.Badge)
            HostInputField(c.shortName, { c.shortName = it.take(4); c.persist() }, "Nom court (4 caractères)", Sym.Tag)
            Text(
                "Identifiant ${c.nodeId} : celui que vos messages porteront quand l'émission sera disponible. Pour l'instant le HackRF écoute seulement.",
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
            ToolButtons(ToolButton("Nouvel identifiant", Sym.Refresh, BtnKind.OutlineOnSurface) { c.regenerateNodeId() })
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gains du récepteur", style = rf(14, 20, 600), color = AntTheme.accent.accent)
            Text("LNA (dB)", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 8, 16, 24, 32, 40).forEach { g -> AntFilterChip("$g", c.lnaGain == g, { c.lnaGain = g; c.applyGains() }) }
            }
            Text("VGA (dB)", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 40, 50).forEach { g -> AntFilterChip("$g", c.vgaGain == g, { c.vgaGain = g; c.applyGains() }) }
            }
            AntFilterChip("Ampli RF +14 dB", c.amp, { c.amp = !c.amp; c.applyGains() })
        }
    }
    InfoList("Récepteur") {
        InfoRow("Matériel", device?.name ?: "—")
        c.board?.let { InfoRow("Carte", it) }
        c.firmware?.let { InfoRow("Firmware", it) }
        InfoRow("Préréglage", "${c.preset.channelName} · ${c.preset.description}")
        InfoRow("Fréquence", c.listeningHz?.let(::meshMhz) ?: (c.frequencyMhz.replace('.', ',') + " MHz"))
        InfoRow("Échantillonnage", "2 MS/s, 8 bits I/Q")
    }
    InfoList("Mesures de réception") {
        InfoRow("Flux USB", "%.1f Mo/s (attendu 4)".format(Locale.FRANCE, c.usbMBps))
        InfoRow("Niveau dans le canal", c.levelDb?.let { "%.0f dBFS".format(Locale.FRANCE, it) + (c.noiseDb?.let { n -> " · bruit %.0f".format(Locale.FRANCE, n) } ?: "") } ?: "—")
        InfoRow("Débuts de trame LoRa", "${c.preambles}")
        InfoRow("Synchro refusée", "${c.syncMismatches}" + if (c.lastSyncSeen >= 0) " (mot vu : 0x%02X)".format(c.lastSyncSeen) else "")
        InfoRow("En-têtes illisibles", "${c.headerErrors}")
        InfoRow("Trames décodées", "${c.framesOk} · CRC invalides ${c.framesBad}")
        InfoRow("Paquets lisibles", "${c.decoded} · autres canaux ${c.otherChannel}")
        InfoRow("Pertes USB", "${c.dropped}")
    }
}
