package com.allnetworktools.data

import kotlin.math.abs

/** Where the signal is the strongest, in degrees from north, and how clearly (strongest minus opposite side, dB). */
data class DirectionEstimate(val bearing: Float, val marginDb: Float) {
    val confidence: String
        get() = when {
            marginDb >= 8f -> "forte"
            marginDb >= 4f -> "moyenne"
            else -> "faible"
        }
}

/**
 * Finds the direction of a Bluetooth device while the user turns on the spot: the body blocks the
 * signal coming from behind, so the RSSI peaks when the phone faces the device. RSSI samples are
 * averaged in 30° sectors of compass heading.
 */
class DirectionSweep(val sectors: Int = 12) {
    private val sum = FloatArray(sectors)
    private val count = IntArray(sectors)
    private val width = 360f / sectors

    fun add(heading: Float, rssi: Float) {
        val i = (((heading % 360f + 360f) % 360f) / width).toInt().coerceIn(0, sectors - 1)
        sum[i] += rssi
        count[i]++
    }

    fun covered(i: Int) = count[i] > 0

    val coveredCount: Int get() = count.count { it > 0 }

    /** Needs about a full turn (10 of 12 sectors) before an answer is given. */
    val complete: Boolean get() = coveredCount >= sectors - 2

    fun estimate(): DirectionEstimate? {
        if (!complete) return null
        val mean = FloatArray(sectors) { if (count[it] > 0) sum[it] / count[it] else Float.NaN }
        // Fill the missing sectors from their neighbours, then smooth over three sectors.
        val filled = FloatArray(sectors) { i ->
            if (!mean[i].isNaN()) mean[i] else listOf(mean[(i + sectors - 1) % sectors], mean[(i + 1) % sectors]).filter { !it.isNaN() }.average().toFloat()
        }
        val s = FloatArray(sectors) { i -> 0.25f * filled[(i + sectors - 1) % sectors] + 0.5f * filled[i] + 0.25f * filled[(i + 1) % sectors] }
        val best = s.indices.maxBy { s[it] }
        val l = s[(best + sectors - 1) % sectors]
        val c = s[best]
        val r = s[(best + 1) % sectors]
        val den = l - 2 * c + r
        val offset = if (abs(den) > 1e-6f) (0.5f * (l - r) / den).coerceIn(-0.5f, 0.5f) else 0f
        val bearing = (((best + 0.5f + offset) * width) % 360f + 360f) % 360f
        val opposite = (-1..1).map { s[(best + sectors / 2 + it + sectors) % sectors] }.average().toFloat()
        return DirectionEstimate(bearing, c - opposite)
    }
}
