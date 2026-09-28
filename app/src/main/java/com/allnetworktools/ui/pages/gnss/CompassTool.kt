package com.allnetworktools.ui.pages.gnss

import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.StatePanel
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.ValueTile
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private fun accuracyLabel(a: Int) = when (a) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Élevée"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Moyenne"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Faible"
    else -> "À calibrer"
}

@Composable
fun CompassTool(vm: MainViewModel) {
    if (!vm.compassAvailable) {
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
            StatePanel(Sym.Explore, "Boussole indisponible", "Cet appareil ne possède pas de magnétomètre ou de capteur de rotation.", null)
        }
        return
    }
    val reading by vm.compass.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val roles = AntTheme.net.gnss
    var trueNorth by rememberSaveable { mutableStateOf(true) }
    val declination = remember(gnss.location?.latitude?.roundToInt(), gnss.location?.longitude?.roundToInt()) { vm.declination() }
    val magnetic = reading?.magneticHeading ?: 0f
    val heading = ((if (trueNorth && declination != null) magnetic + declination else magnetic) + 360f) % 360f

    // Unwrapped angle so the rose never spins the long way round between 359° and 0°.
    val rotation = remember { Animatable(0f) }
    var cardinal by remember { mutableIntStateOf(-1) }
    LaunchedEffect(heading) {
        val current = rotation.value
        val delta = ((heading - current) % 360f + 540f) % 360f - 180f
        val sector = (heading / 90f).toInt() % 4
        if (cardinal != -1 && sector != cardinal) haptics.tick()
        cardinal = sector
        rotation.animateTo(current + delta, Motion.standard())
    }

    PageColumn {
        SectionCard(shape = RoundedCornerShape(32.dp), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                SegmentedRow(
                    listOf(true to "Nord vrai", false to "Nord magnétique"), trueNorth, { trueNorth = it },
                    Modifier.width(300.dp).padding(bottom = 18.dp), selectedColor = roles.accent, onSelectedColor = roles.onAccent, height = 36.dp,
                )
                Rose(rotation.value, heading)
            }
        }
        val r = reading
        TileGrid(
            listOf(
                "Déclinaison" to (declination?.let { "${if (it >= 0) "+" else ""}${fmt(it, 1)}° ${if (it >= 0) "E" else "O"}" } ?: "Position requise"),
                "Précision capteur" to (r?.let { accuracyLabel(it.accuracy) } ?: "—"),
                "Inclinaison" to (r?.let { "${fmt(abs(it.pitch))}° · ${fmt(abs(it.roll))}°" } ?: "—"),
                "Champ magnétique" to (r?.fieldMicroTesla?.let { "${fmt(it)} µT" } ?: "—"),
            ),
        ) { (k, v), mod -> ValueTile(k, v, mod) }
        PillButton(
            "Calibrer (mouvement en 8)",
            { actions.toast("Décrivez un 8 avec le téléphone pendant quelques secondes") },
            Modifier.fillMaxWidth(), icon = Sym.ScreenRotation, bg = roles.accent, height = 48.dp, outlined = true,
        )
    }
}

@Composable
private fun Rose(rotation: Float, heading: Float) {
    val roles = AntTheme.net.gnss
    val surface = cs.surface
    Box(Modifier.size(320.dp, 332.dp)) {
        Canvas(Modifier.size(24.dp, 16.dp).offset(148.dp, 0.dp)) {
            val p = Path().apply { moveTo(size.width / 2, size.height); lineTo(0f, 0f); lineTo(size.width, 0f); close() }
            drawPath(p, roles.accent)
        }
        Canvas(Modifier.size(320.dp).offset(y = 12.dp)) {
            val c = center
            val s = size.width / 320f
            drawCircle(roles.container, 158 * s, c)
            rotate(-rotation, c) {
                for (i in 0 until 72) {
                    val major = i % 6 == 0
                    rotate(i * 5f, c) {
                        drawLine(
                            if (i == 0) roles.accent else roles.onContainer,
                            Offset(c.x, 8 * s), Offset(c.x, (if (major) 30 else 20) * s),
                            (if (major) 3f else 1.5f) * s, StrokeCap.Round,
                        )
                    }
                }
            }
            drawCircle(surface, 88 * s, c)
        }
        Box(Modifier.size(320.dp).offset(y = 12.dp).graphicsLayer { rotationZ = -rotation }) {
            listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO").forEachIndexed { i, t ->
                val a = Math.toRadians(i * 45.0)
                val rr = if (i % 2 == 1) 50f else 48f
                val x = 160 + (160 - rr) * sin(a)
                val y = 160 - (160 - rr) * cos(a)
                Text(
                    t,
                    Modifier.offset((x - 20).dp, (y - 14).dp).size(40.dp, 28.dp).graphicsLayer { rotationZ = i * 45f },
                    style = rf(if (i % 2 == 1) 12 else 20, 28, 700),
                    color = if (i == 0) roles.accent else roles.onContainer, textAlign = TextAlign.Center,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${Math.round(heading) % 360}°", style = gs(56, 60, 500, -1.5f, tnum = true))
            Text(directionOf(heading), style = rf(16, 22, 600), color = roles.accent)
        }
    }
}
