package com.allnetworktools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.ProgressBar
import com.allnetworktools.update.UpdateState
import kotlinx.coroutines.launch

/** Progress of an app update: download, permission to install, result. Hidden when nothing is going on. */
@Composable
fun UpdateBanner(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Back from the "install unknown apps" screen: try again.
    DisposableEffect(lifecycle, state) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && state is UpdateState.NeedsPermission && vm.updater.canInstall()) scope.launch { vm.updater.install() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val visible = when (state) {
        is UpdateState.Downloading, is UpdateState.NeedsPermission, is UpdateState.Installing, is UpdateState.Failed, is UpdateState.Available -> true
        else -> false
    }
    AnimatedVisibility(visible, modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(cs.inverseSurface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val fg = cs.inverseOnSurface
            when (val s = state) {
                is UpdateState.Available -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Update, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text("Version ${s.info.version} disponible", style = rf(15, 20, 600), color = fg)
                            Text("Téléchargement et installation sans quitter l'application.", style = rf(12, 16), color = fg)
                        }
                        PillButton("Installer", { scope.launch { vm.updater.downloadAndInstall(s.info) } }, height = 40.dp, bg = cs.inversePrimary, fg = cs.onSurface)
                    }
                }
                is UpdateState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Download, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text("Mise à jour ${s.info.version}", style = rf(15, 20, 600), color = fg)
                            Text("Téléchargement · ${(s.progress * 100).toInt()} %", style = rf(12, 16), color = fg)
                        }
                    }
                    ProgressBar(s.progress, track = fg.copy(alpha = 0.25f))
                }
                is UpdateState.NeedsPermission -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Shield, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text("Autoriser l'installation", style = rf(15, 20, 600), color = fg)
                            Text("Version ${s.info.version} téléchargée. Android demande d'autoriser All Network Tools à installer des mises à jour.", style = rf(12, 16), color = fg)
                        }
                    }
                    PillButton("Ouvrir le réglage", { vm.updater.openInstallPermissionSettings() }, Modifier.fillMaxWidth(), height = 44.dp, bg = cs.inversePrimary, fg = cs.onSurface)
                }
                is UpdateState.Installing -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Update, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text("Installation de la version ${s.info.version}", style = rf(15, 20, 600), color = fg)
                            Text("Confirmez dans la fenêtre Android si elle apparaît.", style = rf(12, 16), color = fg)
                        }
                    }
                }
                is UpdateState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Error, size = 24.dp, tint = fg)
                        Text(s.message, Modifier.weight(1f), style = rf(13, 18), color = fg)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.info != null) PillButton("Réessayer", { scope.launch { vm.updater.downloadAndInstall(s.info) } }, Modifier.weight(1f), height = 40.dp, bg = cs.inversePrimary, fg = cs.onSurface)
                        PillButton("Fermer", { vm.updater.dismiss() }, Modifier.weight(1f), height = 40.dp, outlined = true, bg = fg)
                    }
                }
                else -> Unit
            }
        }
    }
}
