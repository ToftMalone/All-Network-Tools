package com.allnetworktools.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import com.allnetworktools.service.RecKind
import com.allnetworktools.ui.AppActions
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Starts a background recording once the permissions it needs are there: location (the service
 * is a location foreground service) and, on Android 13+, notifications for its status.
 */
fun startRecording(vm: MainViewModel, actions: AppActions, kind: RecKind) {
    val perms = vm.permissions.value
    if (!perms.location) {
        actions.toast("Autorisez la position pour enregistrer en arrière-plan")
        actions.request(PermGroup.Location)
        return
    }
    if (android.os.Build.VERSION.SDK_INT >= 33 && PermGroup.Notifications !in perms) actions.request(PermGroup.Notifications)
    vm.recording.start(kind)
}

private fun sinceText(ms: Long?): String {
    ms ?: return ""
    val sameDay = System.currentTimeMillis() - ms < 86_400_000L
    return " depuis " + SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM, HH:mm", Locale.FRANCE).format(Date(ms))
}

/** Status of a background recording with its start / stop button. */
@Composable
fun RecordingCard(vm: MainViewModel, kind: RecKind, idleText: String, activeText: String) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    val active by vm.recording.active.collectAsStateWithLifecycle()
    val on = kind in active
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (on) acc.container else cs.surfaceContainerHigh)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (on) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { BlinkDot(cs.error, 12.dp, 1200) }
        } else {
            Symbol(Sym.PauseCircle, size = 24.dp, tint = cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (on) "Actif" + sinceText(vm.recording.since(kind)) else "Enregistrement arrêté",
                style = rf(15, 20, 600), color = if (on) acc.onContainer else cs.onSurface,
            )
            Text(if (on) activeText else idleText, style = rf(12, 16), color = if (on) acc.onContainer else cs.onSurfaceVariant)
        }
        if (on) {
            PillButton("Arrêter", { haptics.segment(); vm.recording.stop(kind) }, icon = Sym.Stop, height = 40.dp, bg = cs.surface, fg = acc.accent)
        } else {
            PillButton("Lancer", { haptics.confirm(); startRecording(vm, actions, kind) }, icon = Sym.PlayArrow, height = 40.dp, bg = acc.accent, fg = acc.onAccent)
        }
    }
}
