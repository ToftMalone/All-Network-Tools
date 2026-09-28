package com.allnetworktools.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.Blocker
import com.allnetworktools.CellUi
import com.allnetworktools.WifiUi
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BluetoothSnapshot
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.FixType
import com.allnetworktools.data.GnssState
import com.allnetworktools.model.Network
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.PulseRing
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Sparkline
import com.allnetworktools.ui.components.StatusChip
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.cloverShape
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.rememberSpin
import com.allnetworktools.ui.components.spinning
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.SignalValue
import com.allnetworktools.util.plural
import com.allnetworktools.util.signalValue
import kotlinx.coroutines.delay

data class HomeData(
    val blockers: Map<Network, Blocker?>,
    val wifi: WifiUi,
    val bluetooth: BluetoothSnapshot,
    val ble: List<BleDevice>,
    val cell: CellUi,
    val gnss: GnssState,
)

/** Cycles 0,1,2 at 1 Hz, for the animated bar icons. */
@Composable
private fun rememberBarsPhase(): Int {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000); t = (t + 1) % 3
        }
    }
    return t
}

@Composable
fun HomeScreen(
    data: HomeData,
    selected: Network?,
    cardBounds: SnapshotStateMap<Network, Rect>,
    onSettingsBounds: (Rect) -> Unit,
    onCard: (Network) -> Unit,
    onBlockerAction: (Blocker) -> Unit,
    onDeselect: () -> Unit,
    onSettings: () -> Unit,
) {
    val active = Network.entries.count { data.blockers[it] == null }
    val needed = 4 - active
    Box(
        Modifier
            .fillMaxSize()
            .background(cs.surface)
            .clickable(interactionSource = null, indication = null, onClick = onDeselect),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            val availableHeight = maxHeight
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("All Network Tools", style = gs(32, 40, 500, -0.3f), color = cs.onSurface, maxLines = 1)
                        Text(
                            if (needed == 0) "4 réseaux actifs · mesures en direct"
                            else "$active sur 4 actifs · $needed ${plural(needed, "action requise", "actions requises")}",
                            Modifier.padding(top = 4.dp), style = rf(14, 20), color = cs.onSurfaceVariant,
                        )
                    }
                    IconCircleButton(
                        Sym.Settings, onSettings,
                        Modifier.onGloballyPositioned { onSettingsBounds(it.boundsInRoot()) },
                        bg = cs.surfaceContainerHigh, tint = cs.onSurfaceVariant,
                    )
                }
                val gridTop = 92.dp
                val cardH = ((availableHeight - gridTop - 124.dp - 12.dp) / 2).coerceIn(236.dp, 316.dp)
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(Network.Wifi to Network.Bluetooth, Network.Cellular to Network.Gnss).forEach { (a, b) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(a, b).forEach { n ->
                                NetworkCard(
                                    network = n,
                                    data = data,
                                    selected = selected == n,
                                    dimmed = selected != null && selected != n,
                                    onClick = { onCard(n) },
                                    onAction = onBlockerAction,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(cardH)
                                        .onGloballyPositioned { cardBounds[n] = it.boundsInRoot() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(
    network: Network,
    data: HomeData,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onAction: (Blocker) -> Unit,
    modifier: Modifier,
) {
    val roles = AntTheme.net[network]
    val blocker = data.blockers[network]
    val scale by animateFloatAsState(if (selected) 1.03f else if (dimmed) 0.96f else 1f, Motion.standard(), label = "cardScale")
    val alpha by animateFloatAsState(if (dimmed) 0.5f else 1f, tween(250, easing = Motion.Emphasized), label = "cardAlpha")
    val outline by animateColorAsState(if (selected) roles.accent else Color.Transparent, tween(200, easing = Motion.Emphasized), label = "outline")
    val elevation by animateDpAsState(if (selected) 10.dp else 0.dp, tween(300, easing = Motion.Emphasized), label = "elev")
    val shadowColor = AntTheme.net.shadow
    val bg by animateColorAsState(if (blocker != null) cs.surfaceContainerHigh else roles.container, tween(250, easing = Motion.Emphasized), label = "cardBg")
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .drawBehind {
                val inset = 3.dp.toPx() + 1.5.dp.toPx()
                drawRoundRect(
                    outline, Offset(-inset, -inset), Size(size.width + inset * 2, size.height + inset * 2),
                    CornerRadius(28.dp.toPx() + inset), style = Stroke(3.dp.toPx()),
                )
            }
            .shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        if (blocker != null) {
            BlockedCardContent(network, blocker, onAction)
        } else {
            CompositionLocalProvider(LocalContentColor provides roles.onContainer) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    when (network) {
                        Network.Wifi -> WifiCardContent(data.wifi)
                        Network.Bluetooth -> BtCardContent(data.bluetooth, data.ble)
                        Network.Cellular -> CellCardContent(data.cell)
                        Network.Gnss -> GnssCardContent(data.gnss)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTop(icon: @Composable () -> Unit, chip: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        icon(); chip()
    }
}

@Composable
private fun ColumnScope.CardTexts(name: String, subtitle: String) {
    Spacer(Modifier.weight(1f))
    Text(name, style = rf(16, 22, 600), maxLines = 1)
    Text(subtitle, Modifier.graphicsLayer { alpha = 0.85f }, style = rf(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CardMetric(value: String, unit: String) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = gs(48, 52, 500, -1f, tnum = true), maxLines = 1)
        Text(unit, Modifier.padding(bottom = 7.dp), style = rf(14, 18, 500), maxLines = 1)
    }
}

private fun sparkRange(values: List<Float>): Pair<Float, Float> {
    if (values.isEmpty()) return -90f to -30f
    val lo = values.min(); val hi = values.max()
    val mid = (lo + hi) / 2
    val span = maxOf(hi - lo + 6f, 14f)
    return mid - span / 2 to mid + span / 2
}

@Composable
private fun ColumnScope.WifiCardContent(wifi: WifiUi) {
    val roles = AntTheme.net.wifi
    val c = wifi.connection
    val phase = rememberBarsPhase()
    val settings = AntTheme.settings
    CardTop(
        icon = {
            ShapeBadge(
                listOf(Sym.Wifi1Bar, Sym.Wifi2Bar, Sym.Wifi)[phase], cookieShape(), 56.dp, roles.accent, roles.onAccent, 28.dp, spinMs = 30_000,
            )
        },
        chip = { if (c != null) StatusChip("Connecté", AntTheme.net.good) else StatusChip("Déconnecté", cs.outline) },
    )
    CardTexts("Wi-Fi", if (c == null) "Aucun réseau" else "${c.ssid ?: "SSID masqué"} · ${c.band.label} GHz")
    val v: SignalValue = signalValue(c?.rssi, settings, -100f, -30f)
    CardMetric(v.text, v.unit)
    val hist = wifi.history.takeLast(30)
    val (lo, hi) = sparkRange(hist)
    Sparkline(hist, lo, hi, roles.accent, Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp), morphMs = settings.refreshMillis.toInt())
}

@Composable
private fun ColumnScope.BtCardContent(bt: BluetoothSnapshot, ble: List<BleDevice>) {
    val roles = AntTheme.net.bt
    CardTop(
        icon = {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(48.dp).spinning(rememberSpin(30_000)).clip(RoundedCornerShape(18.dp)).background(roles.accent))
                PulseRing(roles.accent, 40.dp, periodMs = 2400)
                Symbol(Sym.Bluetooth, size = 28.dp, filled = true, tint = roles.onAccent)
            }
        },
        chip = { StatusChip("Actif", AntTheme.net.good) },
    )
    val connected = bt.connected.size
    CardTexts("Bluetooth", "$connected ${plural(connected, "connecté")}")
    CardMetric(ble.size.toString(), "à proximité")
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val top = ble.take(7)
        repeat(7) { i ->
            val d = top.getOrNull(i)
            val f = if (d == null) 0.12f else ((d.rssi + 100) / 60f).coerceIn(0.12f, 1f)
            val h by animateFloatAsState(f, Motion.standard(), label = "btBar")
            Box(
                Modifier.weight(1f).fillMaxHeight(h)
                    .graphicsLayer { alpha = if (d == null) 0.2f else 0.35f + 0.65f * f }
                    .clip(RoundedCornerShape(4.dp)).background(roles.accent),
            )
        }
    }
}

@Composable
private fun ColumnScope.CellCardContent(cell: CellUi) {
    val roles = AntTheme.net.cell
    val s = cell.state
    val phase = rememberBarsPhase()
    val settings = AntTheme.settings
    CardTop(
        icon = { ShapeBadge(listOf(Sym.CellBars1, Sym.CellBars2, Sym.CellBars3)[phase], cloverShape(), 56.dp, roles.accent, roles.onAccent, 28.dp, spinMs = 40_000, reverse = true) },
        chip = { TechChip(s?.techLabel ?: "—", roles.accent, roles.onAccent) },
    )
    val sub = listOfNotNull(s?.operator, s?.serving?.band).joinToString(" · ").ifEmpty { if (s?.hasService == false) "Hors service" else "Recherche…" }
    CardTexts("Réseau mobile", sub)
    val v = signalValue(s?.serving?.level, settings, -125f, -70f)
    CardMetric(v.text, v.unit)
    val hist = cell.history.takeLast(30)
    val (lo, hi) = sparkRange(hist)
    Sparkline(hist, lo, hi, roles.accent, Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp), areaAlpha = 0.18f, morphMs = settings.refreshMillis.toInt())
}

@Composable
private fun ColumnScope.GnssCardContent(g: GnssState) {
    val roles = AntTheme.net.gnss
    val spin = rememberSpin(8_000)
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
    CardTop(
        icon = {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(roles.accent))
                Canvas(Modifier.size(56.dp).spinning(spin)) {
                    drawCircle(roles.accent, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx(), pathEffect = dash))
                }
                Symbol(Sym.SatelliteAlt, size = 26.dp, filled = true, tint = roles.onAccent)
            }
        },
        chip = {
            val fix = g.fix != FixType.None
            StatusChip(if (fix) g.fix.label else "Recherche…", if (fix) AntTheme.net.good else AntTheme.net.fair, blink = true)
        },
    )
    val acc = g.location?.takeIf { it.hasAccuracy() }?.accuracy
    val sub = buildString {
        append(if (acc != null) "±${com.allnetworktools.util.fmt(acc, 1)} m" else "Précision —")
        append(" · ${g.constellationCount} ${plural(g.constellationCount, "constellation")}")
    }
    CardTexts("GNSS", sub)
    CardMetric(g.used.size.toString(), "/ ${g.visible.size} fixés")
    val consts = listOf(Constellation.GPS, Constellation.Galileo, Constellation.Glonass, Constellation.BeiDou, Constellation.QZSS)
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        consts.forEach { c ->
            val count = g.used.count { it.constellation == c }
            val w by animateFloatAsState(maxOf(0.2f, count.toFloat()), Motion.standard(), label = "seg")
            Box(Modifier.weight(w).fillMaxHeight().background(constellationColor(c)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).graphicsLayer { alpha = 0.85f }, horizontalArrangement = Arrangement.SpaceBetween) {
        consts.forEach { Text(it.short, style = rf(11, 16)) }
    }
}

@Composable
private fun BlockedCardContent(network: Network, blocker: Blocker, onAction: (Blocker) -> Unit) {
    val d = cardBlock(blocker)
    val roles = AntTheme.net[network]
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Symbol(d.icon, size = 28.dp, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Text(network.label, style = rf(16, 22, 600), color = cs.onSurface)
        Text(d.title, Modifier.padding(top = 2.dp), style = rf(14, 20, 500), color = cs.onSurface)
        Text(d.message, Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
        if (d.button != null) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(roles.accent).clickable { onAction(blocker) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(d.buttonIcon, size = 18.dp, filled = true, tint = roles.onAccent)
                Text(d.button, style = rf(14, 20, 600), color = roles.onAccent)
            }
        }
    }
}
