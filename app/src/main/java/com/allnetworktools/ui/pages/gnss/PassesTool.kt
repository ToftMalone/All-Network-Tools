package com.allnetworktools.ui.pages.gnss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.orbit.GnssSystem
import com.allnetworktools.data.orbit.Observer
import com.allnetworktools.data.orbit.OrbitSat
import com.allnetworktools.data.orbit.Pass
import com.allnetworktools.data.orbit.PassPredictor
import com.allnetworktools.data.orbit.TleRepository
import com.allnetworktools.data.orbit.TleSet
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.plural
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of one prediction run. */
data class PassPlan(
    val observer: Observer,
    val startMs: Long,
    val endMs: Long,
    val mask: Double,
    val counts: List<Pair<Long, Int>>,
    val passes: List<Pass>,
    val sats: List<OrbitSat>,
)

class PassesController(private val repo: TleRepository, private val scope: CoroutineScope) {
    var tles by mutableStateOf<TleSet?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var plan by mutableStateOf<PassPlan?>(null)
        private set
    var computing by mutableStateOf(false)
        private set
    var systems by mutableStateOf(setOf(GnssSystem.GPS, GnssSystem.Galileo, GnssSystem.Glonass, GnssSystem.BeiDou))
    var mask by mutableStateOf(10)
    var hours by mutableStateOf(12)
    private var job: Job? = null

    fun load(force: Boolean = false) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                tles = repo.load(force)
            } catch (e: Exception) {
                error = e.message ?: "Réseau indisponible"
            } finally {
                loading = false
            }
        }
    }

    fun compute(observer: Observer, now: Long = System.currentTimeMillis()) {
        val set = tles ?: return
        val sats = set.sats.filter { it.system in systems }
        val m = mask.toDouble()
        val end = now + hours * 3_600_000L
        job?.cancel()
        computing = true
        job = scope.launch {
            val p = withContext(Dispatchers.Default) {
                val passes = sats.flatMap { PassPredictor.passes(it, observer, now, end, m) }
                val counts = PassPredictor.visibleCounts(sats, observer, now, end, m, (hours * 3_600_000L) / 96)
                PassPlan(observer, now, end, m, counts, passes, sats)
            }
            plan = p
            computing = false
        }
    }

    internal fun setForTest(t: TleSet, p: PassPlan) {
        tles = t
        plan = p
    }
}

private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)

internal fun passTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val z = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(z)
    val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
    val prefix = when (d.toLocalDate()) {
        today -> ""
        today.plusDays(1) -> "demain "
        else -> d.format(DateTimeFormatter.ofPattern("EEE d ", Locale.FRANCE))
    }
    return prefix + d.format(hm)
}

private fun cardinal(az: Double): String = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((az % 360) + 360) % 360 / 45).roundToInt() % 8]

private fun duration(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
}

@Composable
private fun systemColor(s: GnssSystem): Color = if (s.constellation == com.allnetworktools.data.Constellation.Other) {
    if (s == GnssSystem.NavIC) AntTheme.net.bt.accent else cs.outline
} else constellationColor(s.constellation)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PassesTool(vm: MainViewModel) {
    val c = vm.tools.passes
    val positions by vm.positions.collectAsStateWithLifecycle()
    val fallback = remember { vm.lastKnownLocation() }
    val loc = positions.gnss ?: positions.fused ?: positions.network ?: fallback
    // Rounded so that a GPS jitter of a few metres does not restart the computation.
    val observer = loc?.let { Observer((it.latitude * 1000).roundToInt() / 1000.0, (it.longitude * 1000).roundToInt() / 1000.0, if (it.hasAltitude()) it.altitude else 0.0) }
    TopBarAction(Sym.Refresh) { c.load(force = true) }
    LaunchedEffect(Unit) { if (c.tles == null) c.load() }
    LaunchedEffect(c.tles, observer, c.systems, c.mask, c.hours) { if (observer != null) c.compute(observer) }
    val plan = c.plan
    PageColumn {
        HeroCard {
            val now = plan?.counts?.firstOrNull()?.second
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(now?.toString() ?: "—", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(now ?: 0, "satellite"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text("au-dessus de ${c.mask}° en ce moment · ${c.systems.size} ${plural(c.systems.size, "constellation")}", style = rf(14, 20))
            if (plan != null && plan.counts.isNotEmpty()) {
                val hi = plan.counts.maxBy { it.second }
                val lo = plan.counts.minBy { it.second }
                Text(
                    "Maximum ${hi.second} à ${passTime(hi.first)} · minimum ${lo.second} à ${passTime(lo.first)}",
                    Modifier.padding(top = 2.dp), style = rf(13, 18), color = AntTheme.accent.onContainer,
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = c.tles
                when {
                    c.loading -> HeroChip("Téléchargement des orbites…", AntTheme.net.fair, blink = true)
                    t != null -> HeroChip("${t.sats.size} orbites · ${if (t.fromCache) "copie du " else ""}${passTime(t.fetchedAtMs)}", AntTheme.net.good)
                }
                if (c.computing) HeroChip("Calcul…", AntTheme.net.fair, blink = true)
            }
        }
        val err = c.error
        if (err != null && c.tles == null) {
            ToolError(Sym.CloudOff, "Orbites indisponibles", "Impossible de joindre CelesTrak ($err). Une connexion est nécessaire au premier lancement.", "Réessayer") { c.load(force = true) }
            return@PageColumn
        }
        if (observer == null) {
            EmptyStateCard(Sym.LocationSearching, "Position inconnue", "La prédiction a besoin de votre position. Activez la localisation et attendez un premier fix.")
            return@PageColumn
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GnssSystem.entries.forEach { s ->
                val n = c.tles?.sats?.count { it.system == s } ?: 0
                AntFilterChip(s.label, s in c.systems, { c.systems = if (s in c.systems) (c.systems - s).ifEmpty { c.systems } else c.systems + s }, leadingDot = systemColor(s), trailing = n.takeIf { it > 0 }?.toString())
            }
        }
        SegmentedRow(listOf(0, 10, 15, 20).map { it to "$it°" }, c.mask, { c.mask = it }, Modifier.fillMaxWidth(), height = 36.dp)
        SegmentedRow(listOf(6, 12, 24).map { it to "$it h" }, c.hours, { c.hours = it }, Modifier.fillMaxWidth(), height = 36.dp)
        if (plan == null) {
            SectionCard { IndeterminateBar() }
            return@PageColumn
        }
        SectionCard {
            Text("Satellites visibles", style = rf(16, 22, 600))
            Text("au-dessus de ${plan.mask.toInt()}°, sur les ${c.hours} prochaines heures", style = rf(12, 16), color = cs.onSurfaceVariant)
            CountChart(plan, Modifier.padding(top = 12.dp).fillMaxWidth().height(140.dp))
        }
        val upcoming = plan.passes.filter { (it.rise ?: 0L) > plan.startMs }.sortedBy { it.rise }
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Prochains levers", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
            if (upcoming.isEmpty()) Text("Aucun lever dans cette fenêtre.", Modifier.padding(vertical = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            upcoming.take(25).forEach { p -> PassRow(p) }
        }
        val up = plan.passes.filter { it.rise == null }.sortedByDescending { it.maxElevation }
        if (up.isNotEmpty()) {
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Déjà levés", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                up.forEach { p -> PassRow(p) }
            }
        }
        SectionCard {
            Text("Frise", style = rf(16, 22, 600))
            Text("Chaque barre : période où le satellite est au-dessus de ${plan.mask.toInt()}°", style = rf(12, 16), color = cs.onSurfaceVariant)
            Timeline(plan, Modifier.padding(top = 10.dp))
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Orbites : éléments GP du catalogue de l'US Space Force, diffusés par CelesTrak (groupe « gnss »). " +
                        "Positions calculées sur le téléphone avec le modèle SGP4/SDP4 : quelques kilomètres d'erreur, soit bien moins d'un dixième de degré vu du sol. " +
                        "Les bâtiments et le relief ne sont pas pris en compte.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PassRow(p: Pass) {
    Hairline()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val col = systemColor(p.sat.system)
        Box(Modifier.size(38.dp).clip(CircleShape).background(col.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text(p.sat.prn ?: p.sat.system.label.take(3), style = rf(11, 14, 700), color = col, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(p.sat.name, style = rf(15, 20, 600), maxLines = 1)
            val span = when {
                p.rise == null && p.set == null -> "visible toute la période"
                p.rise == null -> "jusqu'à ${passTime(p.set!!)}"
                p.set == null -> "${passTime(p.rise)} → après la fenêtre"
                else -> "${passTime(p.rise)} → ${passTime(p.set)} · ${duration(p.set - p.rise)}"
            }
            Text(span, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1)
            val dirs = listOfNotNull(p.riseAzimuth?.let { "lever ${cardinal(it)}" }, p.setAzimuth?.let { "coucher ${cardinal(it)}" }).joinToString(" · ")
            if (dirs.isNotEmpty()) Text(dirs, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${p.maxElevation.roundToInt()}°", style = rf(15, 20, 600, tnum = true))
            Text("max ${passTime(p.maxAt)}", style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun CountChart(plan: PassPlan, modifier: Modifier) {
    val acc = AntTheme.accent.accent
    val grid = cs.outlineVariant
    val label = cs.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val style = rf(10, 12, tnum = true)
    val maxN = (plan.counts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Canvas(modifier) {
        val left = 26.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val w = size.width - left
        val span = (plan.endMs - plan.startMs).toFloat()
        val top = (kotlin.math.ceil(maxN / 5.0) * 5).toInt().coerceAtLeast(5)
        for (v in 0..top step (top / 5).coerceAtLeast(1)) {
            val y = bottom - bottom * v / top
            drawLine(grid, Offset(left, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawText(tm, v.toString(), Offset(0f, y - 7.dp.toPx()), style.copy(color = label))
        }
        val bw = w / plan.counts.size
        plan.counts.forEachIndexed { i, (_, n) ->
            val h = bottom * n / top
            drawRoundRect(acc, Offset(left + i * bw + bw * 0.15f, bottom - h), Size(bw * 0.7f, h), CornerRadius(bw * 0.3f))
        }
        val z = ZoneId.systemDefault()
        val stepH = if (span > 13 * 3_600_000f) 4 else 2
        var t = Instant.ofEpochMilli(plan.startMs).atZone(z).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (t.toInstant().toEpochMilli() < plan.endMs) {
            if (t.hour % stepH == 0) {
                val x = left + w * (t.toInstant().toEpochMilli() - plan.startMs) / span
                drawText(tm, "${t.hour}h", Offset(x - 8.dp.toPx(), bottom + 2.dp.toPx()), style.copy(color = label))
            }
            t = t.plusHours(1)
        }
    }
}

@Composable
private fun Timeline(plan: PassPlan, modifier: Modifier) {
    val rows = plan.sats.sortedWith(compareBy<OrbitSat> { it.system.ordinal }.thenBy { it.prn ?: it.name })
    val byName = plan.passes.groupBy { it.sat.tle.name }
    val track = cs.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { s ->
            val col = systemColor(s.system)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.prn ?: s.name.takeLast(8), Modifier.width(56.dp), style = rf(10, 12, 600, tnum = true), color = cs.onSurfaceVariant, maxLines = 1)
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                    val span = (plan.endMs - plan.startMs).toFloat()
                    byName[s.tle.name].orEmpty().forEach { p ->
                        val a = ((p.rise ?: plan.startMs) - plan.startMs) / span * size.width
                        val b = ((p.set ?: plan.endMs) - plan.startMs) / span * size.width
                        drawRoundRect(col, Offset(a, 0f), Size((b - a).coerceAtLeast(2f), size.height), CornerRadius(size.height / 2))
                    }
                }
            }
        }
    }
}
