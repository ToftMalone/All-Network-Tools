package com.allnetworktools.ui.pages.bt

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.PulseRing
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rememberSpin
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import kotlin.math.cos
import kotlin.math.sin

private const val ALL = "Tous"

@Composable
fun BleScanner(vm: MainViewModel) {
    var paused by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ALL) }
    val haptics = AntTheme.haptics
    DisposableEffect(paused) {
        vm.setBleLowLatency(!paused)
        onDispose { vm.setBleLowLatency(false) }
    }
    var frozen by remember { mutableStateOf(emptyList<BleDevice>()) }
    val devices = if (paused) frozen else vm.ble.collectAsStateWithLifecycle().value.also { frozen = it }
    TopBarAction(if (paused) Sym.PlayArrow else Sym.Pause) { haptics.segment(); paused = !paused }

    val f = vm.tools.bleFilter
    val shown = devices.filter { (filter == ALL || it.kind.filter == filter) && f.matches(it) }.sortedByDescending { it.rssi }
    val roles = AntTheme.net.bt
    PageColumn {
        SectionCard(shape = RoundedCornerShape(32.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Radar(shown, sweeping = !paused)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!paused) BlinkDot(roles.accent, 8.dp, 1200) else Box(Modifier.size(8.dp).clip(CircleShape).background(cs.outline))
                    Text(
                        when {
                            paused -> "Scan en pause · ${shown.size} ${plural(shown.size, "appareil")}"
                            devices.isEmpty() -> "Recherche… aucun appareil détecté"
                            else -> "Scan actif · ${shown.size} ${plural(shown.size, "appareil")}"
                        },
                        style = rf(14, 20, 500),
                    )
                }
            }
        }
        BleSearchBar(f) { f.sheetOpen = true }
        BleActiveFilters(f) { f.sheetOpen = true }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(ALL) + BleKind.entries.map { it.filter }).forEach { f ->
                AntFilterChip(f, filter == f, { filter = f }, roles.container, roles.onContainer)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} ${plural(shown.size, "résultat")}", Modifier.weight(1f), style = rf(13, 18), color = cs.onSurfaceVariant)
            Symbol(Sym.SwapVert, size = 18.dp, tint = roles.accent)
            Text("RSSI décroissant", Modifier.padding(start = 4.dp), style = rf(13, 18, 600), color = roles.accent)
        }
        if (shown.isEmpty()) {
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (devices.isEmpty()) "Aucun appareil à proximité" else "Aucun appareil ne correspond", style = gs(20, 26, 500), textAlign = TextAlign.Center)
                    Text(
                        if (devices.isEmpty()) "Le scan continue. Vérifiez que les appareils sont allumés et en mode visible."
                        else "${devices.size} ${plural(devices.size, "appareil")} masqués par les filtres.",
                        Modifier.padding(top = 6.dp), style = rf(14, 20), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    PillButton(
                        if (devices.isEmpty()) "Relancer le scan" else "Réinitialiser les filtres",
                        {
                            if (devices.isEmpty()) {
                                paused = false
                                vm.restartBleScan()
                            } else {
                                f.reset(); f.text = ""; f.nameMode = NameMode.All; filter = ALL
                            }
                        },
                        Modifier.padding(top = 16.dp), bg = roles.accent, height = 40.dp, outlined = true,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEachIndexed { i, d ->
                BleItem(d, i, shown.size) { vm.navigate { it.copy(page = Page.ToolPage(Tool.Gatt, d.address)) } }
            }
        }
    }
    if (f.sheetOpen) BleFilterSheet(f, shown.size, devices.size) { f.sheetOpen = false }
}

@Composable
private fun Radar(devices: List<BleDevice>, sweeping: Boolean) {
    val roles = AntTheme.net.bt
    val sweep = rememberSpin(3200)
    Box(Modifier.size(300.dp).clip(CircleShape).background(roles.container)) {
        Canvas(Modifier.matchParentSize()) {
            val c = center
            val r = size.minDimension / 2
            drawCircle(roles.accent.copy(alpha = 0.35f), r * 100f / 150f, c, style = Stroke(1.dp.toPx()))
            drawCircle(roles.accent.copy(alpha = 0.35f), r * 50f / 150f, c, style = Stroke(1.dp.toPx()))
            drawLine(roles.accent.copy(alpha = 0.2f), Offset(c.x, 0f), Offset(c.x, size.height), 1.dp.toPx())
            drawLine(roles.accent.copy(alpha = 0.2f), Offset(0f, c.y), Offset(size.width, c.y), 1.dp.toPx())
            if (sweeping) {
                rotate(sweep.value - 90f, c) {
                    drawCircle(
                        Brush.sweepGradient(
                            0f to Color.Transparent, 280f / 360f to Color.Transparent, 1f to roles.accent.copy(alpha = 0.45f),
                            center = c,
                        ),
                        r, c,
                    )
                }
            }
        }
        devices.take(12).forEach { d ->
            key(d.address) {
                val dist = ((-d.rssi - 40) / 55f * 138f).coerceIn(30f, 138f)
                val a = Math.toRadians(d.angle.toDouble())
                val target = Offset((150 + dist * sin(a)).toFloat(), (150 - dist * cos(a)).toFloat())
                val pos by animateOffsetAsState(target, spring(0.8f, 380f), label = "dot")
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((pos.x - 7).dp.roundToPx(), (pos.y - 7).dp.roundToPx()) }) {
                    Box(Modifier.size(22.dp).offset((-4).dp, (-4).dp).clip(CircleShape).background(roles.accent.copy(alpha = 0.25f)))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(roles.accent))
                    Text(
                        d.displayName.substringBefore(' '),
                        Modifier.width(80.dp).offset(x = (-33).dp, y = 16.dp),
                        style = rf(10, 12, 600), color = roles.onContainer, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.offset(138.dp, 138.dp).size(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(roles.accent))
            Symbol(Sym.Smartphone, size = 16.dp, filled = true, tint = roles.onAccent)
        }
        Box(Modifier.offset(138.dp, 138.dp)) { PulseRing(roles.accent, 24.dp, periodMs = 2000) }
    }
}

@Composable
private fun BleItem(d: BleDevice, index: Int, count: Int, onClick: () -> Unit) {
    val roles = AntTheme.net.bt
    Row(
        Modifier.rise(index, 50).fillMaxWidth().clip(groupShape(index, count)).background(cs.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(roles.container), contentAlignment = Alignment.Center) {
            Symbol(d.icon, size = 22.dp, filled = true, tint = roles.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.displayName, Modifier.weight(1f, fill = false), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text(d.kind.label, style = rf(10, 12, 600))
                }
            }
            Text("${d.address} · ${d.maker ?: "Fabricant inconnu"}", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.End) {
            Text(fmt(d.rssi), style = gs(18, 22, 500, tnum = true))
            LevelBar(((d.rssi + 100) / 60f).coerceAtLeast(0.05f), roles.accent, Modifier.padding(top = 4.dp), height = 4.dp)
        }
    }
}
