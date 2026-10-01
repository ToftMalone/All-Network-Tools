package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.util.concurrent.Executor

/** One analysed camera frame: a small grayscale preview plus how much very bright light it holds. */
class IrFrame(val width: Int, val height: Int, val gray: IntArray, val brightPixels: Int, val peak: Int, val flash: Boolean)

/**
 * Spots the blinking of an infrared LED: a remote's LED shows up on the sensor as a small saturated spot
 * that appears and disappears. A frame is a flash when its count of near-saturated pixels jumps well above
 * the recent background (lamps, windows), which a slow moving average follows.
 */
class IrFlashDetector(private val threshold: Int = 235) {
    private var baseline = -1.0

    fun isBright(y: Int) = y >= threshold

    /** Feeds one frame's bright-pixel count (for a fixed frame size); true when it stands out as a flash. */
    fun feed(bright: Int, total: Int): Boolean {
        if (baseline < 0) { baseline = bright.toDouble(); return false }
        val minJump = (total * 0.0008).coerceAtLeast(3.0)
        // A lamp in the frame flickers by a few percent; an LED adds a clear jump on top of it.
        val flash = bright > baseline + maxOf(minJump, baseline * 0.25)
        // Flashes barely move the background, so a held button does not become the new normal at once.
        baseline += (bright - baseline) * if (flash) 0.02 else 0.15
        return flash
    }
}

/** A running detector camera. */
fun interface IrDetectorHandle {
    fun close()
}

/** Camera2 session that streams small YUV frames to [onFrame] on a background thread. */
class IrCameraSession(
    context: Context,
    private val cameraId: String,
    private val onFrame: (IrFrame) -> Unit,
    private val onError: (String) -> Unit,
) : IrDetectorHandle {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("ir-detector").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private val detector = IrFlashDetector()
    @Volatile private var closed = false

    init {
        open()
    }

    private fun size(): Size {
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        return sizes.filter { it.width >= 320 && it.height >= 240 }.minByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: Size(640, 480)
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        try {
            val s = size()
            val r = ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!closed) onFrame(analyse(image.planes[0].buffer, image.planes[0].rowStride, image.width, image.height))
                } finally {
                    image.close()
                }
            }, handler)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (closed) { d.close(); return }
                    device = d
                    val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                    d.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(r.surface)), executor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    runCatching { session.setRepeatingRequest(request, null, handler) }.onFailure { onError("La caméra a refusé le flux") }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) = onError("La caméra n'a pas pu être configurée")
                            },
                        ),
                    )
                }

                override fun onDisconnected(d: CameraDevice) { d.close(); if (!closed) onError("Caméra utilisée par une autre application") }
                override fun onError(d: CameraDevice, error: Int) { d.close(); if (!closed) onError("Erreur caméra ($error)") }
            }, handler)
        } catch (e: Exception) {
            onError(e.message ?: "Caméra indisponible")
        }
    }

    private fun analyse(buf: java.nio.ByteBuffer, rowStride: Int, w: Int, h: Int): IrFrame {
        // Luma only; preview downsampled to about 120 px wide.
        val step = (w / 120).coerceAtLeast(1)
        val pw = w / step
        val ph = h / step
        val gray = IntArray(pw * ph)
        var bright = 0
        var peak = 0
        var total = 0
        for (y in 0 until h step 2) {
            val row = y * rowStride
            for (x in 0 until w step 2) {
                val v = buf.get(row + x).toInt() and 0xFF
                if (detector.isBright(v)) bright++
                if (v > peak) peak = v
                total++
            }
        }
        for (py in 0 until ph) for (px in 0 until pw) {
            gray[py * pw + px] = buf.get(py * step * rowStride + px * step).toInt() and 0xFF
        }
        return IrFrame(pw, ph, gray, bright, peak, detector.feed(bright, total))
    }

    override fun close() {
        closed = true
        runCatching { device?.close() }
        runCatching { reader?.close() }
        thread.quitSafely()
    }
}
