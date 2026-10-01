package com.allnetworktools.ui.pages.ir

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.IrCameraChoice
import com.allnetworktools.data.IrDetectorHandle
import com.allnetworktools.data.IrFrame
import com.allnetworktools.data.IrRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.plural
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Watches a camera for infrared flashes: shows what the sensor sees and counts the LED's blinks. */
class IrDetectorController(private val repo: IrRepository, private val scope: CoroutineScope) {
    val cameras: List<IrCameraChoice> by lazy { repo.detectorCameras() }
    var camera by mutableStateOf<IrCameraChoice?>(null)
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var flashes by mutableIntStateOf(0)
        private set
    var lastFlashAt by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bright-pixel count of recent frames, for the activity strip. */
    val levels = mutableStateListOf<Int>()
    private var session: IrDetectorHandle? = null

    fun start() {
        stop()
        val cam = camera ?: cameras.firstOrNull()?.also { camera = it } ?: run { error = "Aucune caméra disponible"; return }
        error = null
        session = repo.openDetector(cam.id, { f -> onFrame(f) }, { e -> scope.launch { error = e } })
    }

    fun stop() {
        session?.close()
        session = null
    }

    fun select(c: IrCameraChoice) {
        camera = c
        if (session != null) start()
    }

    fun reset() {
        flashes = 0
        lastFlashAt = 0L
        levels.clear()
    }

    private fun onFrame(f: IrFrame) {
        // Built on the camera thread, handed to Compose on the main thread.
        val bmp = Bitmap.createBitmap(IntArray(f.gray.size) { i -> val v = f.gray[i]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, f.width, f.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        scope.launch {
            preview = bmp
            levels.add(f.brightPixels)
            while (levels.size > 90) levels.removeAt(0)
            if (f.flash) { flashes++; lastFlashAt = System.currentTimeMillis() }
        }
    }

    internal fun setForTest(flashCount: Int, recent: List<Int>, flashing: Boolean) {
        flashes = flashCount
        levels.clear(); levels.addAll(recent)
        lastFlashAt = if (flashing) System.currentTimeMillis() else 0L
    }
}

@Composable
fun IrDetectTool(c: IrDetectorController, cameraGranted: Boolean, onGrant: () -> Unit) {
    if (cameraGranted) {
        DisposableEffect(Unit) {
            c.start()
            onDispose { c.stop() }
        }
    }
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(100); now = System.currentTimeMillis() } }
    val lit = c.lastFlashAt > 0 && now - c.lastFlashAt < 400
    PageColumn {
        if (!cameraGranted) {
            ToolError(Sym.Videocam, "Accès à l'appareil photo requis", "Le détecteur regarde l'image de la caméra pour repérer la lumière infrarouge. Rien n'est enregistré.", "Autoriser", onGrant)
            return@PageColumn
        }
        HeroCard {
            Text(if (lit) "Infrarouge détecté" else "${c.flashes} ${plural(c.flashes, "éclair", "éclairs")}", style = gs(28, 34, 500))
            Text(
                "Pointez une télécommande vers la caméra et appuyez sur un bouton : sa LED infrarouge, invisible à l'œil, apparaît à l'écran et chaque éclair est compté.",
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    lit -> HeroChip("La télécommande émet", AntTheme.net.good, blink = true)
                    else -> HeroChip("Caméra en écoute", AntTheme.net.fair, blink = true)
                }
            }
        }
        if (c.cameras.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.cameras.forEach { cam -> AntFilterChip(cam.label, (c.camera ?: c.cameras.first()) == cam, { c.select(cam) }) }
            }
        }
        val cam = c.camera ?: c.cameras.firstOrNull()
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHighest)
                .border(if (lit) 4.dp else 0.dp, if (lit) AntTheme.net.good else cs.surfaceContainerHighest, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val img = c.preview
            if (img != null) {
                Image(
                    img, "Image de la caméra",
                    Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = (cam?.sensorOrientation ?: 0).toFloat()
                        if (cam?.front == true) scaleX = -1f
                        val sideways = (cam?.sensorOrientation ?: 0) % 180 != 0
                        if (sideways) { scaleY *= 0.75f; scaleX *= 0.75f }
                    },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Symbol(Sym.Videocam, size = 40.dp, tint = cs.onSurfaceVariant)
            }
        }
        SectionCard {
            Text("Activité", style = rf(16, 22, 600))
            Text("Lumière très vive vue par la caméra, image par image.", Modifier.padding(top = 2.dp, bottom = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            val levels = c.levels.toList()
            val bar = AntTheme.accent.accent
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                if (levels.isEmpty()) return@Canvas
                val max = (levels.maxOrNull() ?: 1).coerceAtLeast(10).toFloat()
                val w = size.width / 90f
                levels.forEachIndexed { i, v ->
                    val h = (v / max).coerceIn(0.02f, 1f) * size.height
                    drawLine(bar, Offset(i * w + w / 2, size.height), Offset(i * w + w / 2, size.height - h), strokeWidth = (w * 0.6f).coerceAtLeast(1f))
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Pratique pour savoir si une télécommande a encore des piles ou si un bouton marche. Beaucoup de caméras arrière filtrent l'infrarouge : " +
                        "si rien n'apparaît, essayez la caméra avant. Évitez le plein soleil et les lampes dans le champ, qui masquent l'éclair. " +
                        "Les images ne quittent pas le téléphone et ne sont pas enregistrées.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
