package com.allnetworktools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs

/** Edge-to-edge dialog for a map or plot, with a close button in the top start corner. */
@Composable
fun FullscreenDialog(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(cs.surface)) {
            content()
            IconCircleButton(
                Sym.FullscreenExit, onClose,
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                bg = cs.surface.copy(alpha = 0.92f), tint = cs.onSurface, size = 44.dp,
            )
        }
    }
}
