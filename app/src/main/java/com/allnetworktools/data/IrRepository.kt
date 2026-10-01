package com.allnetworktools.data

import android.content.Context
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/** Infrared-related hardware Android lets an app see; none of it but the emitter can send remote codes. */
data class IrSensorInfo(val label: String, val detail: String)

/** A camera that can be opened for the infrared detector. */
data class IrCameraChoice(val id: String, val label: String, val front: Boolean, val sensorOrientation: Int)

/** The phone's infrared emitter (Xiaomi, Redmi, POCO, some Huawei/Honor) and its other infrared sensors. */
open class IrRepository(private val context: Context) {
    private val ir: ConsumerIrManager? = context.getSystemService(ConsumerIrManager::class.java)
    private val cameras: CameraManager? = context.getSystemService(CameraManager::class.java)

    open val hasEmitter: Boolean get() = runCatching { ir?.hasIrEmitter() == true }.getOrDefault(false)

    /** Carrier ranges the emitter supports, in Hz; empty when the HAL does not report them. */
    open fun carrierRanges(): List<IntRange> =
        runCatching { ir?.carrierFrequencies?.map { it.minFrequency..it.maxFrequency } }.getOrNull().orEmpty()

    /** Blocks for the length of the pattern. Returns null on success, otherwise why it failed. */
    open fun transmit(signal: IrSignal): String? {
        val m = ir ?: return "Pas d'émetteur infrarouge"
        val ranges = carrierRanges()
        if (ranges.isNotEmpty() && ranges.none { signal.carrier in it }) {
            return "L'émetteur ne prend pas en charge ${signal.carrier / 1000} kHz"
        }
        return runCatching { m.transmit(signal.carrier, signal.pattern) }.exceptionOrNull()?.let { it.message ?: "Émission refusée" }
    }

    private fun facing(c: CameraCharacteristics) = when (c.get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_FRONT -> "avant"
        CameraMetadata.LENS_FACING_BACK -> "arrière"
        else -> "externe"
    }

    /**
     * Infrared sensors the system declares: near-infrared or depth (time-of-flight) cameras, including the
     * physical cameras behind a logical one, and the proximity sensor. Many manufacturers keep their depth or
     * laser autofocus sensors private, so an empty camera list does not mean the phone has none.
     */
    open fun sensors(): List<IrSensorInfo> = buildList {
        val m = cameras
        if (m != null) {
            val ids = runCatching { m.cameraIdList.toList() }.getOrDefault(emptyList())
            val all = ids + ids.flatMap { id -> runCatching { m.getCameraCharacteristics(id).physicalCameraIds.toList() }.getOrDefault(emptyList()) }
            for (id in all.distinct()) {
                val c = runCatching { m.getCameraCharacteristics(id) }.getOrNull() ?: continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
                val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                when {
                    cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR ->
                        add(IrSensorInfo("Caméra proche infrarouge", "Caméra $id, ${facing(c)}"))
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps ->
                        add(IrSensorInfo("Capteur de profondeur", "Caméra $id, ${facing(c)} · mesure par infrarouge (temps de vol)"))
                }
            }
        }
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { s ->
            add(IrSensorInfo("Capteur de proximité", listOfNotNull(s.name.takeIf { it.isNotBlank() }, s.vendor.takeIf { it.isNotBlank() }).joinToString(" · ")))
        }
    }

    /** Front and back cameras usable by the detector (the front one usually lets more infrared through). */
    open fun detectorCameras(): List<IrCameraChoice> {
        val m = cameras ?: return emptyList()
        return runCatching { m.cameraIdList.toList() }.getOrDefault(emptyList()).mapNotNull { id ->
            val c = runCatching { m.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
            val f = c.get(CameraCharacteristics.LENS_FACING)
            if (f != CameraMetadata.LENS_FACING_FRONT && f != CameraMetadata.LENS_FACING_BACK) return@mapNotNull null
            IrCameraChoice(id, if (f == CameraMetadata.LENS_FACING_FRONT) "Caméra avant" else "Caméra arrière", f == CameraMetadata.LENS_FACING_FRONT, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
        }.distinctBy { it.front }.sortedByDescending { it.front }
    }

    open val hasCamera: Boolean by lazy { detectorCameras().isNotEmpty() }

    /** Opens the detector camera; stop it with [IrCameraSession.close]. */
    open fun openDetector(cameraId: String, onFrame: (IrFrame) -> Unit, onError: (String) -> Unit): IrDetectorHandle =
        IrCameraSession(context, cameraId, onFrame, onError)
}
