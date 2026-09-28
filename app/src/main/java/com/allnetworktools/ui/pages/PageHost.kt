package com.allnetworktools.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.NavState
import com.allnetworktools.Page
import com.allnetworktools.data.PermGroup
import com.allnetworktools.Blocker
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.PanelAction
import com.allnetworktools.ui.components.StatePanel
import com.allnetworktools.ui.home.pageBlock
import com.allnetworktools.ui.pages.bt.BleScanner
import com.allnetworktools.ui.pages.bt.BtDashboard
import com.allnetworktools.ui.pages.cell.CellDashboard
import com.allnetworktools.ui.pages.cell.NeighborCells
import com.allnetworktools.ui.pages.gnss.CompassTool
import com.allnetworktools.ui.pages.gnss.GnssDashboard
import com.allnetworktools.ui.pages.wifi.WifiDashboard
import com.allnetworktools.ui.pages.wifi.WifiScanner
import com.allnetworktools.ui.settings.SettingsScreen
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf

data class TopAction(val icon: String, val onClick: () -> Unit)

val LocalTopAction = staticCompositionLocalOf<MutableState<TopAction?>> { mutableStateOf(null) }

/** Registers the top-bar action of the current page while it is composed. */
@Composable
fun TopBarAction(icon: String, onClick: () -> Unit) {
    val slot = LocalTopAction.current
    val action = TopAction(icon, onClick)
    DisposableEffect(icon, onClick) {
        slot.value = action
        onDispose { if (slot.value === action) slot.value = null }
    }
}

private fun titles(nav: NavState): Pair<String, String> {
    val net = nav.network ?: Network.Wifi
    return when (val p = nav.page) {
        Page.Settings -> "Paramètres" to "All Network Tools 1.0"
        Page.Dashboard -> net.label to "Dashboard"
        Page.Tools -> net.label to "Outils"
        is Page.ToolPage -> p.tool.title to "${net.label} · Outils"
        Page.Home -> "" to ""
    }
}

/** Page chrome (top bar + scrolling body) with a fade-through between pages. */
@Composable
fun PageHostContent(vm: MainViewModel, nav: NavState, onBack: () -> Unit) {
    val topAction = remember { mutableStateOf<TopAction?>(null) }
    val (title, subtitle) = titles(nav)
    CompositionLocalProvider(LocalTopAction provides topAction) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(cs.surface).windowInsetsPadding(WindowInsets.statusBars)
                    .height(64.dp).padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconCircleButton(Sym.ArrowBack, onBack, tint = cs.onSurface)
                Column(Modifier.weight(1f)) {
                    Text(title, style = gs(22, 28, 500), maxLines = 1)
                    Text(subtitle, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                }
                val a = topAction.value
                if (a != null) IconCircleButton(a.icon, a.onClick, tint = cs.onSurfaceVariant) else Spacer(Modifier.size(48.dp))
            }
            AnimatedContent(
                targetState = nav.network to nav.page,
                transitionSpec = {
                    (fadeIn(tween(400, 90, Motion.Emphasized)) + slideInVertically(tween(400, 90, Motion.Emphasized)) { it / 60 })
                        .togetherWith(fadeOut(tween(90)))
                },
                label = "fadeThrough",
                modifier = Modifier.weight(1f),
            ) { (net, page) ->
                PageBody(vm, net ?: Network.Wifi, page)
            }
        }
    }
}

/** Scrollable page body with the standard 16 dp gutters and room for the dock. */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 128.dp + nav.calculateBottomPadding())),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PageBody(vm: MainViewModel, net: Network, page: Page) {
    if (page == Page.Settings) {
        SettingsScreen(vm)
        return
    }
    val blocker = vm.blockers.collectAsStateWithLifecycle().value[net]
    if (blocker != null) {
        BlockedPage(net, blocker)
        return
    }
    when (page) {
        Page.Dashboard -> when (net) {
            Network.Wifi -> WifiDashboard(vm)
            Network.Bluetooth -> BtDashboard(vm)
            Network.Cellular -> CellDashboard(vm)
            Network.Gnss -> GnssDashboard(vm)
        }
        Page.Tools -> ToolsPage(net, vm)
        is Page.ToolPage -> when (page.tool) {
            Tool.WifiScan -> WifiScanner(vm)
            Tool.BleScan -> BleScanner(vm)
            Tool.Neighbors -> NeighborCells(vm)
            Tool.Compass -> CompassTool(vm)
            else -> ComingSoon(page.tool)
        }
        else -> Unit
    }
}

@Composable
private fun BlockedPage(net: Network, blocker: Blocker) {
    val actions = LocalActions.current
    val group = when (blocker) {
        Blocker.LocationPermission -> PermGroup.Location
        Blocker.NearbyPermission -> PermGroup.Nearby
        Blocker.PhonePermission -> PermGroup.Phone
        else -> null
    }
    val denied = group != null && actions.isPermanentlyDenied(group)
    val d = pageBlock(net, blocker, denied)
    val secondary = d.secondary?.let { label ->
        PanelAction(label) {
            when (blocker) {
                Blocker.WifiOff -> actions.openWifiSettings()
                Blocker.BluetoothOff -> actions.openBluetoothSettings()
                else -> actions.openAppSettings()
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
        StatePanel(
            icon = d.icon, title = d.title, message = d.message,
            primary = d.primary?.let { PanelAction(it, d.primaryIcon) { if (denied) actions.openAppSettings() else actions.resolve(blocker) } },
            secondary = secondary,
        )
    }
}

@Composable
private fun ComingSoon(tool: Tool) {
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
        StatePanel(
            icon = tool.icon,
            title = tool.title,
            message = "Cet outil arrive dans la prochaine version de l'application.",
            primary = null,
        )
    }
}
