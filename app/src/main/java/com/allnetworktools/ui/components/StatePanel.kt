package com.allnetworktools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf

@Composable
fun cookieShape(): Shape = MaterialShapes.Cookie9Sided.toShape()

@Composable
fun cloverShape(): Shape = MaterialShapes.Clover4Leaf.toShape()

/** An icon centred on an expressive shape; the shape may rotate while the icon stays upright. */
@Composable
fun ShapeBadge(
    icon: String,
    shape: Shape,
    size: Dp,
    color: Color,
    iconColor: Color,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    spinMs: Int = 0,
    reverse: Boolean = false,
    filled: Boolean = true,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val shapeMod = if (spinMs > 0) Modifier.spinning(rememberSpin(spinMs, reverse)) else Modifier
        Box(Modifier.matchParentSize().then(shapeMod).clip(shape).background(color))
        Symbol(icon, size = iconSize, filled = filled, tint = iconColor)
    }
}

data class PanelAction(val label: String, val icon: String? = null, val onClick: () -> Unit)

/** Full-page state for unavailable pages: Disabled, PermissionRequired, Empty, Error. */
@Composable
fun StatePanel(
    icon: String,
    title: String,
    message: String,
    primary: PanelAction?,
    secondary: PanelAction? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().heightIn(min = 600.dp).padding(horizontal = 16.dp).fadeUp(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShapeBadge(icon, cookieShape(), 120.dp, cs.surfaceContainerHighest, cs.onSurfaceVariant, 52.dp, spinMs = 40_000)
        Text(title, Modifier.padding(top = 28.dp), style = gs(26, 32, 500), textAlign = TextAlign.Center)
        Text(message, Modifier.padding(top = 10.dp).widthIn(max = 300.dp), style = rf(15, 22), color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        if (primary != null) PillButton(primary.label, primary.onClick, Modifier.padding(top = 28.dp), icon = primary.icon)
        if (secondary != null) {
            Box(Modifier.padding(top = 8.dp)) {
                TextAction(secondary.label, secondary.onClick, trailingIcon = null)
            }
        }
    }
}

/** Inline empty state inside a tool (neutral cookie, message and tonal action). */
@Composable
fun EmptyStateCard(icon: String, title: String, message: String, action: PanelAction? = null) {
    SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShapeBadge(icon, cookieShape(), 56.dp, cs.surfaceContainerHighest, cs.onSurfaceVariant, 26.dp)
            Text(title, style = gs(20, 26, 500), textAlign = TextAlign.Center)
            Text(message, style = rf(14, 20), color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            if (action != null) {
                PillButton(action.label, action.onClick, Modifier.padding(top = 10.dp), bg = AntTheme.accent.container, fg = AntTheme.accent.onContainer, height = 40.dp, icon = action.icon)
            }
        }
    }
}

/** Inline error (errorContainer) shown above a tool form so it can be corrected and relaunched. */
@Composable
fun ErrorBanner(icon: String, title: String, message: String, footer: String? = null, action: PanelAction? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.errorContainer).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(icon, size = 24.dp, filled = true, tint = cs.onErrorContainer)
        Column(Modifier.weight(1f)) {
            Text(title, style = rf(15, 20, 600), color = cs.onErrorContainer)
            Text(message, Modifier.padding(top = 2.dp), style = rf(13, 18), color = cs.onErrorContainer)
            if (footer != null) Text(footer, Modifier.padding(top = 8.dp), style = rf(13, 18, 600, tnum = true), color = cs.onErrorContainer)
            if (action != null) PillButton(action.label, action.onClick, Modifier.padding(top = 8.dp), bg = cs.error, fg = cs.onError, height = 32.dp)
        }
    }
}
