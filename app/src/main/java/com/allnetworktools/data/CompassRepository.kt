package com.allnetworktools.data

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

data class CompassReading(
    /** Magnetic heading, degrees in 0..360. */
    val magneticHeading: Float,
    val pitch: Float,
    val roll: Float,
    val fieldMicroTesla: Float?,
    val accuracy: Int,
)

open class CompassRepository(private val context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)

    open val available: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    open fun declination(location: Location?): Float? = location?.let {
        GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
    }

    open val readings: Flow<CompassReading> = callbackFlow {
        val mgr = sm
        val rotation = mgr?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (mgr == null || rotation == null) {
            awaitClose { }; return@callbackFlow
        }
        val magnetic = mgr.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val matrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var field: Float? = null
        var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        val display = runCatching { context.display }.getOrNull()
            ?: @Suppress("DEPRECATION") context.getSystemService(WindowManager::class.java)?.defaultDisplay
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val (x, y, z) = e.values
                        field = sqrt(x * x + y * y + z * z)
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(matrix, e.values)
                        val (ax, ay) = when (display?.rotation) {
                            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                        }
                        SensorManager.remapCoordinateSystem(matrix, ax, ay, remapped)
                        SensorManager.getOrientation(remapped, orientation)
                        val heading = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                        trySend(
                            CompassReading(
                                magneticHeading = heading,
                                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat(),
                                roll = Math.toDegrees(orientation[2].toDouble()).toFloat(),
                                fieldMicroTesla = field,
                                accuracy = accuracy,
                            ),
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, acc: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = acc
            }
        }
        mgr.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        magnetic?.let { mgr.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        awaitClose { mgr.unregisterListener(listener) }
    }
}
