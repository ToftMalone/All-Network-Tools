package com.allnetworktools.data.orbit

import com.allnetworktools.data.Constellation
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** GNSS system of an element set, read from the CelesTrak naming ("GPS BIIF-1 (PRN 25)", "BEIDOU-3 M1 (C19)"…). */
enum class GnssSystem(val label: String, val constellation: Constellation) {
    GPS("GPS", Constellation.GPS),
    Galileo("Galileo", Constellation.Galileo),
    Glonass("GLONASS", Constellation.Glonass),
    BeiDou("BeiDou", Constellation.BeiDou),
    QZSS("QZSS", Constellation.QZSS),
    NavIC("NavIC", Constellation.Other),
    SBAS("SBAS", Constellation.Other),
}

data class OrbitSat(val tle: Tle, val system: GnssSystem, val name: String, val prn: String?) {
    val sgp4 by lazy { Sgp4(tle) }

    companion object {
        private val prnRe = Regex("""\(PRN (\d+)\)""")
        private val qzsRe = Regex("""QZSS/PRN (\d+)""")
        private val bdsRe = Regex("""\((C\d+)\)""")
        private val sbasRe = Regex("""\((?:EGNOS|WAAS|GAGAN|SDCM|MSAS|BDSBAS|KASS|SOUTHPAN)/PRN (\d+)\)""")

        fun of(tle: Tle): OrbitSat {
            val n = tle.name.trim()
            val u = n.uppercase()
            val sbas = sbasRe.find(u)
            return when {
                sbas != null || "(EGN" in u || "(W*" in u || "SOUTHPA" in u -> OrbitSat(tle, GnssSystem.SBAS, n.substringBefore(" (").trim(), sbas?.groupValues?.get(1))
                u.startsWith("GPS ") -> OrbitSat(tle, GnssSystem.GPS, n.substringBefore(" (").trim(), prnRe.find(u)?.groupValues?.get(1)?.let { "G" + it.padStart(2, '0') })
                "GALILEO" in u -> OrbitSat(tle, GnssSystem.Galileo, n.substringAfter("(").substringBefore(")").ifBlank { n }.trim().replace("GALILEO", "Galileo"), null)
                u.startsWith("COSMOS") -> OrbitSat(tle, GnssSystem.Glonass, n.substringBefore(" (").trim().replace("COSMOS", "Cosmos"), null)
                u.startsWith("BEIDOU") -> OrbitSat(tle, GnssSystem.BeiDou, n.substringBefore(" (").trim().replace("BEIDOU", "BeiDou"), bdsRe.find(u)?.groupValues?.get(1))
                "QZS" in u -> OrbitSat(tle, GnssSystem.QZSS, n.substringBefore(" (").trim(), qzsRe.find(u)?.groupValues?.get(1)?.toIntOrNull()?.let { "J%02d".format(it - 192) })
                u.startsWith("IRNSS") || u.startsWith("NVS") -> OrbitSat(tle, GnssSystem.NavIC, n.substringBefore(" (").trim(), null)
                else -> OrbitSat(tle, GnssSystem.SBAS, n.substringBefore(" (").trim(), null)
            }
        }
    }
}

/** Direction of a satellite seen from the observer. */
data class Look(val azimuth: Double, val elevation: Double, val rangeKm: Double)

/** One pass above the elevation mask. [rise] is null when the satellite was already up at the start of the window. */
data class Pass(
    val sat: OrbitSat,
    val rise: Long?,
    val set: Long?,
    val maxAt: Long,
    val maxElevation: Double,
    val riseAzimuth: Double?,
    val setAzimuth: Double?,
)

data class Observer(val latDeg: Double, val lonDeg: Double, val altM: Double = 0.0) {
    private val lat = Math.toRadians(latDeg)
    private val lon = Math.toRadians(lonDeg)
    private val a = 6378.137
    private val f = 1 / 298.257223563
    private val e2 = f * (2 - f)
    private val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
    private val h = altM / 1000.0
    val ecef = doubleArrayOf((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1 - e2) + h) * sin(lat))

    /** Azimuth / elevation of a TEME position at Julian date [jd] (polar motion neglected: well under 0.01°). */
    fun look(temeKm: DoubleArray, jd: Double): Look {
        val g = Sgp4.gstime(jd)
        val x = cos(g) * temeKm[0] + sin(g) * temeKm[1]
        val y = -sin(g) * temeKm[0] + cos(g) * temeKm[1]
        val z = temeKm[2]
        val rx = x - ecef[0]
        val ry = y - ecef[1]
        val rz = z - ecef[2]
        val s = sin(lat) * cos(lon) * rx + sin(lat) * sin(lon) * ry - cos(lat) * rz
        val e = -sin(lon) * rx + cos(lon) * ry
        val zz = cos(lat) * cos(lon) * rx + cos(lat) * sin(lon) * ry + sin(lat) * rz
        val range = sqrt(rx * rx + ry * ry + rz * rz)
        val el = Math.toDegrees(asin(zz / range))
        var az = Math.toDegrees(atan2(e, -s))
        if (az < 0) az += 360.0
        return Look(az, el, range)
    }
}

object PassPredictor {
    fun look(sat: OrbitSat, obs: Observer, timeMs: Long): Look? {
        val jd = Sgp4.jdFromUnixMs(timeMs)
        val r = sat.sgp4.position((jd - sat.sgp4.jdEpoch) * 1440.0) ?: return null
        return obs.look(r, jd)
    }

    /**
     * Passes above [maskDeg] between [startMs] and [endMs], sampled every [stepMs] then refined to
     * the second by bisection. A satellite up for the whole window gives one pass with no rise nor set.
     */
    fun passes(sat: OrbitSat, obs: Observer, startMs: Long, endMs: Long, maskDeg: Double, stepMs: Long = 120_000L): List<Pass> {
        fun el(t: Long) = look(sat, obs, t)?.elevation
        val out = mutableListOf<Pass>()
        var prevT = startMs
        var prevEl = el(startMs) ?: return emptyList()
        var up = prevEl >= maskDeg
        var rise: Long? = null
        var maxEl = if (up) prevEl else -90.0
        var maxAt = startMs
        var t = startMs
        while (t < endMs) {
            t = minOf(t + stepMs, endMs)
            val e = el(t) ?: return out
            if (up && e > maxEl) { maxEl = e; maxAt = t }
            if (!up && e >= maskDeg) {
                rise = crossing(prevT, t, maskDeg, ::el)
                up = true
                maxEl = e; maxAt = t
            } else if (up && e < maskDeg) {
                val set = crossing(prevT, t, maskDeg, ::el)
                out += finish(sat, obs, rise, set, maxAt, maxEl, startMs)
                up = false
                rise = null
            }
            prevT = t
            prevEl = e
        }
        if (up) out += finish(sat, obs, rise, null, maxAt, maxEl, startMs)
        return out
    }

    private fun finish(sat: OrbitSat, obs: Observer, rise: Long?, set: Long?, maxAt: Long, maxEl: Double, startMs: Long): Pass {
        // Refine the culmination around the best sample (golden-section on ±2 steps).
        var a = maxOf(startMs, maxAt - 120_000L)
        var b = maxAt + 120_000L
        if (set != null) b = minOf(b, set)
        repeat(20) {
            val m1 = a + (b - a) / 3
            val m2 = b - (b - a) / 3
            val e1 = look(sat, obs, m1)?.elevation ?: -90.0
            val e2 = look(sat, obs, m2)?.elevation ?: -90.0
            if (e1 < e2) a = m1 else b = m2
        }
        val best = (a + b) / 2
        val bestEl = maxOf(maxEl, look(sat, obs, best)?.elevation ?: maxEl)
        return Pass(
            sat, rise, set, if (bestEl > maxEl) best else maxAt, bestEl,
            rise?.let { look(sat, obs, it)?.azimuth }, set?.let { look(sat, obs, it)?.azimuth },
        )
    }

    /** Time (ms) in [a, b] where the elevation crosses [mask], to about one second. */
    private fun crossing(a0: Long, b0: Long, mask: Double, el: (Long) -> Double?): Long {
        var a = a0
        var b = b0
        val rising = (el(a) ?: -90.0) < mask
        while (b - a > 1000) {
            val m = (a + b) / 2
            val above = (el(m) ?: -90.0) >= mask
            if (above == rising) b = m else a = m
        }
        return (a + b) / 2
    }

    /** Number of satellites above the mask at each sample time. */
    fun visibleCounts(sats: List<OrbitSat>, obs: Observer, startMs: Long, endMs: Long, maskDeg: Double, stepMs: Long): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        var t = startMs
        while (t <= endMs) {
            val time = t
            out += time to sats.count { (look(it, obs, time)?.elevation ?: -90.0) >= maskDeg }
            t += stepMs
        }
        return out
    }
}
