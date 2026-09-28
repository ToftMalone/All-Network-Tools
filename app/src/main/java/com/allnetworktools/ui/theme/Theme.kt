package com.allnetworktools.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.AppSettings
import com.allnetworktools.data.ThemeMode
import com.allnetworktools.model.Network

object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The workhorse spring of the mockups: spring(0.8, 380). */
    fun <T> standard(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Slight overshoot, used for entrances (dock, recommended card). */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 500f)

    fun <T> emphasized(ms: Int, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, Emphasized)
}

val AntShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
)

class Haptics(private val view: View, private val enabled: Boolean) {
    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }

    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun segment() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun threshold() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
}

val LocalNetworkColors = staticCompositionLocalOf { NetworkColorsLight }
val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics not provided") }
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

/** Accent roles of the network currently in focus (the prototype's --acc variables). */
val LocalAccent = staticCompositionLocalOf { NetworkColorsLight.wifi }

object AntTheme {
    val net: NetworkColors @Composable get() = LocalNetworkColors.current
    val accent: AccentRoles @Composable get() = LocalAccent.current
    val haptics: Haptics @Composable get() = LocalHaptics.current
    val settings: AppSettings @Composable get() = LocalAppSettings.current
}

@Composable
fun isDarkTheme(settings: AppSettings): Boolean = when (settings.theme) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun AntTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val context = LocalContext.current
    val scheme = when {
        !settings.dynamicColor -> staticColorScheme(dark)
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    val netColors = remember(scheme.primary, dark, settings.dynamicColor) {
        if (settings.dynamicColor) harmonizedNetworkColors(scheme.primary, dark)
        else if (dark) NetworkColorsDark else NetworkColorsLight
    }
    val view = LocalView.current
    val haptics = remember(view, settings.haptics) { Haptics(view, settings.haptics) }
    MaterialExpressiveTheme(
        colorScheme = scheme.animated(),
        typography = AntTypography,
        shapes = AntShapes,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalNetworkColors provides netColors,
            LocalHaptics provides haptics,
            LocalAppSettings provides settings,
            LocalAccent provides netColors.wifi,
            content = content,
        )
    }
}

/** Provides the accent roles of [network] to the content below. */
@Composable
fun ProvideAccent(network: Network, content: @Composable () -> Unit) {
    val roles = LocalNetworkColors.current[network]
    val accent by animateColorAsState(roles.accent, tween(350, easing = Motion.Emphasized), label = "acc")
    val onAccent by animateColorAsState(roles.onAccent, tween(350, easing = Motion.Emphasized), label = "onAcc")
    val container by animateColorAsState(roles.container, tween(350, easing = Motion.Emphasized), label = "accC")
    val onContainer by animateColorAsState(roles.onContainer, tween(350, easing = Motion.Emphasized), label = "onAccC")
    CompositionLocalProvider(LocalAccent provides AccentRoles(accent, onAccent, container, onContainer), content = content)
}

@Composable
private fun androidx.compose.material3.ColorScheme.animated(): androidx.compose.material3.ColorScheme {
    val spec = tween<Color>(400, easing = Motion.Emphasized)
    @Composable fun Color.a() = animateColorAsState(this, spec, label = "scheme").value
    return copy(
        primary = primary.a(), onPrimary = onPrimary.a(),
        primaryContainer = primaryContainer.a(), onPrimaryContainer = onPrimaryContainer.a(),
        background = background.a(), onBackground = onBackground.a(),
        surface = surface.a(), onSurface = onSurface.a(), onSurfaceVariant = onSurfaceVariant.a(),
        surfaceContainerLow = surfaceContainerLow.a(), surfaceContainer = surfaceContainer.a(),
        surfaceContainerHigh = surfaceContainerHigh.a(), surfaceContainerHighest = surfaceContainerHighest.a(),
        outline = outline.a(), outlineVariant = outlineVariant.a(),
        inverseSurface = inverseSurface.a(), inverseOnSurface = inverseOnSurface.a(),
    )
}

val cs @Composable get() = MaterialTheme.colorScheme

@Composable
fun constellationColor(c: com.allnetworktools.data.Constellation): Color {
    val n = LocalNetworkColors.current
    return when (c) {
        com.allnetworktools.data.Constellation.GPS -> n.gps
        com.allnetworktools.data.Constellation.Galileo -> n.galileo
        com.allnetworktools.data.Constellation.Glonass -> n.glonass
        com.allnetworktools.data.Constellation.BeiDou -> n.beidou
        com.allnetworktools.data.Constellation.QZSS -> n.qzss
        com.allnetworktools.data.Constellation.Other -> MaterialTheme.colorScheme.outline
    }
}
