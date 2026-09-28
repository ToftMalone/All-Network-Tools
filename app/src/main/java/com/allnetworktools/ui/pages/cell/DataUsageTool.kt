package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.UsagePeriod
import com.allnetworktools.data.UsageReport
import com.allnetworktools.data.UsageRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.TextAction
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.Stepper
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.fmt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "1,4 Go" / "812 Mo" / "96 Ko". */
fun bytesFr(b: Long): Pair<String, String> = when {
    b >= 1_000_000_000L -> fmt(b / 1e9, if (b >= 100_000_000_000L) 0 else 1) to "Go"
    b >= 1_000_000L -> fmt(b / 1e6) to "Mo"
    else -> fmt(b / 1e3) to "Ko"
}

class DataUsageController(private val scope: CoroutineScope, private val repo: UsageRepository) {
    var period by mutableStateOf(UsagePeriod.Month)
    var phase by mutableStateOf(Phase.Idle)
    var report by mutableStateOf<UsageReport?>(null)
    private var job: Job? = null

    fun load(p: UsagePeriod = period) {
        period = p
        job?.cancel()
        phase = Phase.Running
        job = scope.launch {
            val r = runCatching { repo.mobile(p) }.getOrNull()
            report = r
            phase = when {
                r == null -> Phase.Error
                r.total == 0L -> Phase.Empty
                else -> Phase.Results
            }
        }
    }
}

private val AvatarColors @Composable get() = with(AntTheme.net) { listOf(cs.error, wifi.accent, bt.accent, cell.accent, gnss.accent, beidou, cs.outline) }

@Composable
fun DataUsageTool(c: DataUsageController, granted: Boolean, planGb: Int, onGrant: () -> Unit, onPlan: (Int) -> Unit) {
    val acc = AntTheme.accent
    var planDialog by remember { mutableStateOf(false) }
    LaunchedEffect(granted) { if (granted && c.phase == Phase.Idle) c.load() }
    PageColumn {
        if (!granted) {
            ToolEmptyTinted(onGrant)
            return@PageColumn
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsagePeriod.entries.forEach { p -> AntFilterChip(p.label, c.period == p, { c.load(p) }) }
        }
        Text(
            "Toutes les SIM · Android ne permet pas aux applications de séparer la consommation par SIM.",
            Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
        val r = c.report
        when {
            c.phase == Phase.Error -> ToolError(Sym.Error, "Statistiques indisponibles", "Le service NetworkStats n'a pas répondu. Réessayez dans quelques secondes.", "Réessayer") { c.load() }
            c.phase == Phase.Empty -> ToolEmpty(Sym.DataUsage, "Aucune donnée mobile", "Aucune consommation mobile sur la période choisie.", if (c.period != UsagePeriod.Month) "Voir le mois" else null) { c.load(UsagePeriod.Month) }
            r == null || c.phase == Phase.Running && r.period != c.period -> {
                SectionCard { IndeterminateBar(); Text("Lecture des statistiques…", Modifier.padding(top = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant) }
            }
            else -> {
                HeroCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        val planBytes = planGb * 1_000_000_000L
                        val monthTotal = if (r.period == UsagePeriod.Month) r.total else null
                        if (planGb > 0 && monthTotal != null) {
                            val pct = (monthTotal.toFloat() / planBytes).coerceIn(0f, 1f)
                            UsageRing(pct)
                        }
                        Column(Modifier.weight(1f)) {
                            val (v, u) = bytesFr(r.total)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(v, style = gs(44, 48, 500, -1f, tnum = true))
                                Text(u, Modifier.padding(bottom = 6.dp), style = rf(18, 24, 500))
                            }
                            Text(
                                if (planGb > 0) "sur $planGb Go · forfait mensuel" else "Données mobiles consommées",
                                style = rf(13, 18, 500),
                            )
                            Text(cycleText(r), Modifier.padding(top = 2.dp), style = rf(12, 16))
                        }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        TextAction(if (planGb > 0) "Modifier le forfait" else "Définir un forfait", { planDialog = true }, color = acc.onContainer, trailingIcon = Sym.Edit)
                    }
                }
                BarsCard(r)
                Text("Applications", Modifier.padding(start = 4.dp, top = 8.dp), style = rf(14, 20, 600), color = acc.accent)
                val top = r.apps.take(8)
                val max = top.firstOrNull()?.bytes ?: 1L
                SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    if (top.isEmpty()) Text("Détail par application indisponible.", Modifier.padding(vertical = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
                    top.forEachIndexed { i, a ->
                        Row(
                            Modifier.rise(i).fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(AvatarColors[i % 7]), contentAlignment = Alignment.Center) {
                                Text(a.label.take(1).uppercase(), style = rf(16, 20, 700), color = cs.surface)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(a.label, Modifier.weight(1f), style = rf(14, 20, 500), maxLines = 1)
                                    val (v, u) = bytesFr(a.bytes)
                                    Text("$v $u", style = rf(13, 18, 600, tnum = true), color = cs.onSurfaceVariant)
                                }
                                LevelBar(a.bytes.toFloat() / max, acc.accent, height = 6.dp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (planDialog) {
        var gb by remember { mutableIntStateOf(if (planGb > 0) planGb else 20) }
        AlertDialog(
            onDismissRequest = { planDialog = false },
            icon = { com.allnetworktools.ui.components.Symbol(Sym.DataUsage, size = 24.dp, tint = acc.accent) },
            title = { Text("Forfait mensuel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Volume de données inclus chaque mois, pour suivre votre consommation.", style = rf(14, 20))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$gb Go", Modifier.weight(1f), style = gs(24, 30, 500, tnum = true))
                        Stepper(gb, { gb = (gb - if (gb > 20) 10 else 5).coerceAtLeast(5) }, { gb = (gb + if (gb >= 20) 10 else 5).coerceAtMost(500) })
                    }
                }
            },
            confirmButton = { TextButton({ planDialog = false; onPlan(gb) }) { Text("Enregistrer") } },
            dismissButton = {
                TextButton({ planDialog = false; if (planGb > 0) onPlan(0) }) { Text(if (planGb > 0) "Supprimer" else "Annuler") }
            },
        )
    }
}

@Composable
private fun ToolEmptyTinted(onGrant: () -> Unit) {
    val acc = AntTheme.accent
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(acc.container).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.allnetworktools.ui.components.ShapeBadge(Sym.DataUsage, com.allnetworktools.ui.components.cookieShape(), 88.dp, acc.accent, acc.onAccent, 38.dp)
        Text("Accès aux statistiques", Modifier.padding(top = 16.dp), style = gs(22, 28, 500), color = acc.onContainer)
        Text(
            "Autorisez « Accès aux données d'utilisation » pour afficher la consommation mobile par application.",
            Modifier.padding(top = 8.dp), style = rf(14, 20), color = acc.onContainer, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        com.allnetworktools.ui.components.PillButton("Ouvrir les paramètres", onGrant, Modifier.padding(top = 16.dp), height = 44.dp, bg = acc.accent, fg = acc.onAccent)
    }
}

private fun cycleText(r: UsageReport): String {
    val d = SimpleDateFormat("d MMM", Locale.FRANCE)
    return when (r.period) {
        UsagePeriod.Day -> "Aujourd'hui, depuis 00:00"
        UsagePeriod.Week -> "${d.format(Date(r.start))} – ${d.format(Date(r.end))}"
        UsagePeriod.Month -> {
            val cal = Calendar.getInstance().apply { timeInMillis = r.end }
            val left = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH) + 1
            "Depuis le ${d.format(Date(r.start))} · fin du mois dans $left j"
        }
    }
}

@Composable
private fun UsageRing(pct: Float) {
    val acc = AntTheme.accent.accent
    val track = cs.surface
    val color = if (pct >= 0.9f) cs.error else acc
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(104.dp)) {
            val sw = 13.dp.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            drawArc(color, -90f, 360f * pct, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("${fmt(pct * 100)} %", style = gs(20, 24, 600, tnum = true))
    }
}

@Composable
private fun BarsCard(r: UsageReport) {
    val acc = AntTheme.accent.accent
    val max = (r.buckets.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val peakIdx = r.buckets.indices.maxByOrNull { r.buckets[it] } ?: 0
    val peak = r.bucketStarts.getOrNull(peakIdx)?.let {
        SimpleDateFormat(if (r.period == UsagePeriod.Day) "H 'h'" else if (r.period == UsagePeriod.Week) "EEEE" else "d MMM", Locale.FRANCE).format(Date(it))
    }
    val slots = when (r.period) { UsagePeriod.Day -> 24; UsagePeriod.Week -> 7; UsagePeriod.Month -> Calendar.getInstance().apply { timeInMillis = r.start }.getActualMaximum(Calendar.DAY_OF_MONTH) }
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (r.period == UsagePeriod.Day) "Par heure" else "Par jour", Modifier.weight(1f), style = rf(16, 22, 600))
            if (peak != null && r.buckets.any { it > 0 }) Text("pic : $peak", style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            (0 until slots).forEach { i ->
                val v = r.buckets.getOrNull(i)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    if (v != null) {
                        Box(
                            Modifier.rise(i, 15).fillMaxWidth().fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(4.dp, 4.dp, 1.dp, 1.dp))
                                .background(if (i == r.buckets.lastIndex) acc else acc.copy(alpha = 0.45f)),
                        )
                    }
                }
            }
        }
        val labels = when (r.period) {
            UsagePeriod.Day -> listOf("0 h", "6 h", "12 h", "18 h", "23 h")
            UsagePeriod.Week -> r.bucketStarts.let { s -> listOf(0, 2, 4, 6).mapNotNull { s.getOrNull(it) }.map { SimpleDateFormat("EEE", Locale.FRANCE).format(Date(it)) } }
            UsagePeriod.Month -> listOf("1", "8", "15", "22", slots.toString())
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { Text(it, style = rf(10, 14), color = cs.onSurfaceVariant) }
        }
    }
}
