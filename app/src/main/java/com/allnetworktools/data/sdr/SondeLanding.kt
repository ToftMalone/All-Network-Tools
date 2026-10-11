package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/** Where and when a descending sonde should land. */
data class Landing(val lat: Double, val lon: Double, val inSeconds: Double)

/**
 * Landing prediction for a sonde under its parachute. The descent rate scales with air density (drag balances weight,
 * so v ∝ 1/√ρ): the rate measured now gives the sea-level rate, and the density follows an exponential atmosphere with a
 * 7.2 km scale height. The sonde drifts with the wind, taken at each height from what the sonde itself did there: during
 * the ascent the balloon moved with the air, so its horizontal velocity is the wind. Heights never flown keep the
 * nearest known wind, or the current drift when there is none.
 */
object SondeLanding {
    private const val SCALE_HEIGHT = 7200.0
    private const val STEP_M = 50.0

    /** Wind samples: altitude (m), east and north speed (m/s). */
    fun predict(lat: Double, lon: Double, altM: Double, climbMs: Double, winds: List<DoubleArray>, groundM: Double = 0.0): Landing? {
        if (climbMs > -1.5 || altM <= groundM) return null // still climbing, or not clearly falling
        val v0 = -climbMs * exp(-altM / (2 * SCALE_HEIGHT)) // sea-level-equivalent descent rate
        val sorted = winds.sortedBy { it[0] }
        fun wind(h: Double): DoubleArray? {
            if (sorted.isEmpty()) return null
            var lo = 0
            var hi = sorted.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (sorted[mid][0] <= h) lo = mid else hi = mid }
            return if (kotlin.math.abs(sorted[lo][0] - h) <= kotlin.math.abs(sorted[hi][0] - h)) sorted[lo] else sorted[hi]
        }
        var h = altM
        var east = 0.0
        var north = 0.0
        var t = 0.0
        while (h > groundM) {
            val dz = minOf(STEP_M, h - groundM)
            val v = v0 * exp((h - dz / 2) / (2 * SCALE_HEIGHT))
            val dt = dz / v
            val w = wind(h - dz / 2)
            if (w != null) { east += w[1] * dt; north += w[2] * dt }
            t += dt
            h -= dz
        }
        val dLat = north / 6_371_000.0 * 180 / PI
        val dLon = east / (6_371_000.0 * cos(lat * PI / 180)) * 180 / PI
        return Landing(lat + dLat, lon + dLon, t)
    }

    /** East and north components of a horizontal speed and course. */
    fun components(speedMs: Double, courseDeg: Double): Pair<Double, Double> {
        val a = courseDeg * PI / 180
        return speedMs * kotlin.math.sin(a) to speedMs * cos(a)
    }
}
