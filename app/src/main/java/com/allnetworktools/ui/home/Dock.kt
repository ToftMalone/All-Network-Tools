package com.allnetworktools.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.allnetworktools.Page
import com.allnetworktools.model.Network
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.pop
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.rf
import kotlinx.coroutines.launch

enum class DockTab { Dashboard, Featured, Tools }

fun activeTab(page: Page): DockTab? = when (page) {
    Page.Dashboard -> DockTab.Dashboard
    Page.Tools -> DockTab.Tools
    is Page.ToolPage -> if (page.tool.isDockShortcut) DockTab.Featured else DockTab.Tools
    else -> null
}

/**
 * Floating toolbar: leading network button (shape morphs per network), then either the three tabs
 * or the network switcher, then a trailing close / back-to-grid action. Colors come from LocalAccent.
 */
@Composable
fun FloatingDock(
    network: Network,
    page: Page,
    switcherOpen: Boolean,
    onToggleSwitcher: () -> Unit,
    onNetwork: (Network) -> Unit,
    onTab: (DockTab) -> Unit,
    onTrailing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val acc = AntTheme.accent
    val squash = remember { Animatable(0f) }
    LaunchedEffect(network) {
        squash.snapTo(1f)
        launch { squash.animateTo(0f, spring(0.5f, 500f)) }
    }
    Row(
        modifier
            .graphicsLayer {
                scaleX = 1f + 0.06f * squash.value
                scaleY = 1f - 0.1f * squash.value
            }
            .shadow(14.dp, RoundedCornerShape(32.dp), ambientColor = AntTheme.net.shadow, spotColor = AntTheme.net.shadow)
            .clip(RoundedCornerShape(32.dp))
            .background(acc.container)
            .height(64.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LeadButton(network, switcherOpen, onToggleSwitcher)
        AnimatedContent(
            targetState = switcherOpen,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(90)) },
            label = "dockMiddle",
        ) { open ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (open) {
                    Network.entries.forEachIndexed { i, n ->
                        val roles = AntTheme.net[n]
                        Box(
                            Modifier
                                .pop(i * 40)
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(roles.accent)
                                .then(if (n == network) Modifier.border(2.dp, acc.onContainer, CircleShape) else Modifier)
                                .clickable { onNetwork(n) },
                            contentAlignment = Alignment.Center,
                        ) { Symbol(n.icon, size = 22.dp, filled = true, tint = roles.onAccent) }
                    }
                } else {
                    val active = activeTab(page)
                    // Outils is always the last tab, just before the trailing action.
                    DockTab.entries.filter { it != DockTab.Featured || network.dockShortcut != null }.forEach { tab ->
                        val (icon, label) = when (tab) {
                            DockTab.Dashboard -> Sym.SpaceDashboard to "Dashboard"
                            DockTab.Featured -> network.dockShortcut!!.icon to network.featuredShort
                            DockTab.Tools -> Sym.Handyman to "Outils"
                        }
                        TabButton(icon, label, tab == active) { onTab(tab) }
                    }
                }
            }
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onTrailing),
            contentAlignment = Alignment.Center,
        ) { Symbol(if (page == Page.Home) Sym.Close else Sym.GridView, size = 24.dp, tint = acc.onContainer) }
    }
}

@Composable
private fun LeadButton(network: Network, switcherOpen: Boolean, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val c = if (switcherOpen) com.allnetworktools.model.LeadCorners(24f, 24f, 24f, 24f) else network.lead
    val spec = Motion.standard<androidx.compose.ui.unit.Dp>()
    val ts by animateDpAsState(c.ts.dp, spec, label = "ts")
    val te by animateDpAsState(c.te.dp, spec, label = "te")
    val be by animateDpAsState(c.be.dp, spec, label = "be")
    val bs by animateDpAsState(c.bs.dp, spec, label = "bs")
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(ts, te, be, bs))
            .background(acc.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val key = if (switcherOpen) "close" else network.name
        Box(Modifier.pop(key = key)) {
            Symbol(if (switcherOpen) Sym.UnfoldLess else network.icon, size = 24.dp, filled = !switcherOpen, tint = acc.onAccent)
        }
    }
}

@Composable
private fun TabButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val width by animateDpAsState(if (active) 128.dp else 48.dp, Motion.standard(), label = "tabW")
    val bg by animateColorAsState(if (active) acc.accent else Color.Transparent, tween(250, easing = Motion.Emphasized), label = "tabBg")
    val fg by animateColorAsState(if (active) acc.onAccent else acc.onContainer, tween(250, easing = Motion.Emphasized), label = "tabFg")
    val labelAlpha by animateFloatAsState(if (active) 1f else 0f, tween(200, easing = Motion.Emphasized), label = "tabLabel")
    Row(
        Modifier
            .width(width)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(icon, size = 24.dp, filled = active, tint = fg)
        if (labelAlpha > 0.01f) {
            Text(
                label, Modifier.padding(start = 6.dp).graphicsLayer { alpha = labelAlpha },
                style = rf(14, 20, 600), color = fg, maxLines = 1, softWrap = false,
            )
        }
    }
}
