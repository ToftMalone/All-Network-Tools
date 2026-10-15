package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS L1 C/A as described by IS-GPS-200: the PRN codes, the navigation message (parity, subframes, ephemeris), the
 * satellite orbit and clock, and the least-squares position fix.
 */
object Gps {
    const val L1_HZ = 1_575_420_000.0
    const val CHIP_RATE = 1_023_000.0
    const val CODE_LENGTH = 1023
    const val C = 299_792_458.0
    private const val MU = 3.986005e14
    const val OMEGA_E = 7.2921151467e-5
    private const val F_REL = -4.442807633e-10
    const val SECONDS_PER_WEEK = 604_800.0

    // ---- C/A codes --------------------------------------------------------------------------------------

    /** G2 delays in chips for PRN 1–32 (IS-GPS-200 table 3-Ia). */
    private val G2_DELAY = intArrayOf(
        5, 6, 7, 8, 17, 18, 139, 140, 141, 251, 252, 254, 255, 256, 257, 258,
        469, 470, 471, 472, 473, 474, 509, 512, 513, 514, 515, 516, 859, 860, 861, 862,
    )

    private val g1: IntArray by lazy { lfsr(intArrayOf(3, 10)) }
    private val g2: IntArray by lazy { lfsr(intArrayOf(2, 3, 6, 8, 9, 10)) }

    /** 1023 chips of a 10-stage shift register seeded with ones; [taps] are 1-based stages fed back. */
    private fun lfsr(taps: IntArray): IntArray {
        val r = IntArray(11) { 1 }
        return IntArray(CODE_LENGTH) {
            val out = r[10]
            var fb = 0
            for (t in taps) fb = fb xor r[t]
            for (i in 10 downTo 2) r[i] = r[i - 1]
            r[1] = fb
            out
        }
    }

    /** The C/A code of [prn] (1–32) as chips 0/1. */
    fun caBits(prn: Int): IntArray {
        val d = G2_DELAY[prn - 1]
        return IntArray(CODE_LENGTH) { i -> g1[i] xor g2[Math.floorMod(i - d, CODE_LENGTH)] }
    }

    /** The C/A code of [prn] as ±1 (chip 0 → +1). */
    fun ca(prn: Int): FloatArray = caBits(prn).let { b -> FloatArray(CODE_LENGTH) { if (b[it] == 0) 1f else -1f } }

    // ---- navigation message -----------------------------------------------------------------------------

    /**
     * Checks the parity of one 30-bit word ([bits] from [at]) given the last two bits of the previous word, and
     * returns its 24 data bits with the D30* inversion undone, or null.
     */
    fun word(bits: IntArray, at: Int, d29: Int, d30: Int): IntArray? {
        val d = IntArray(25) // 1-based source data bits
        for (i in 1..24) d[i] = bits[at + i - 1] xor d30
        fun x(vararg idx: Int): Int { var v = 0; for (i in idx) v = v xor d[i]; return v }
        val p = intArrayOf(
            d29 xor x(1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23),
            d30 xor x(2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24),
            d29 xor x(1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22),
            d30 xor x(2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23),
            d30 xor x(1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24),
            d29 xor x(3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24),
        )
        for (k in 0 until 6) if (bits[at + 24 + k] != p[k]) return null
        return IntArray(24) { d[it + 1] }
    }

    /**
     * A 300-bit subframe starting at [at] in [bits] (with at least two bits before it), as 240 data bits with parity
     * checked, or null. Data bit b (1–24) of word w (1–10) is at index (w − 1) × 24 + b − 1.
     */
    fun subframe(bits: IntArray, at: Int): IntArray? {
        if (at < 2 || at + 300 > bits.size) return null
        val out = IntArray(240)
        for (w in 0 until 10) {
            val start = at + 30 * w
            val data = word(bits, start, bits[start - 2], bits[start - 1]) ?: return null
            data.copyInto(out, 24 * w)
        }
        if (bitsU(out, 1, 8) != 0x8BL) return null
        return out
    }

    /** Unsigned field of [n] bits starting at subframe bit [from] (1-based, counting the 24 data bits of each word). */
    private fun bitsU(d: IntArray, from: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 1) or d[from - 1 + i].toLong()
        return v
    }

    /** Subframe bit number (1–300, as the ICD counts) to data index (1-based over 240). */
    private fun sb(icdBit: Int): Int = (icdBit - 1) / 30 * 24 + (icdBit - 1) % 30 + 1

    private fun u(d: IntArray, icdBit: Int, n: Int): Long = bitsU(d, sb(icdBit), n)

    private fun s(d: IntArray, icdBit: Int, n: Int): Long { val v = u(d, icdBit, n); return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v }

    /** A field split over two words: [n1] bits at [a] then [n2] bits at [b]. */
    private fun u2(d: IntArray, a: Int, n1: Int, b: Int, n2: Int): Long = (u(d, a, n1) shl n2) or u(d, b, n2)

    private fun s2(d: IntArray, a: Int, n1: Int, b: Int, n2: Int): Long {
        val n = n1 + n2
        val v = u2(d, a, n1, b, n2)
        return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v
    }

    private fun p2(e: Int) = Math.scalb(1.0, e)

    fun subframeId(d: IntArray): Int = u(d, 50, 3).toInt()

    /** Time of week at the start of the next subframe, in seconds (HOW: TOW count × 6 s). */
    fun towNext(d: IntArray): Double = u(d, 31, 17) * 6.0

    class Clock(val week: Int, val health: Int, val iodc: Int, val tgd: Double, val toc: Double, val af0: Double, val af1: Double, val af2: Double)

    class Orbit(
        val iode: Int, val crs: Double, val dn: Double, val m0: Double, val cuc: Double, val e: Double, val cus: Double,
        val sqrtA: Double, val toe: Double, val cic: Double, val omega0: Double, val cis: Double, val i0: Double, val crc: Double,
        val w: Double, val omegaDot: Double, val idot: Double,
    )

    fun subframe1(d: IntArray) = Clock(
        week = u(d, 61, 10).toInt(), health = u(d, 77, 6).toInt(), iodc = u2(d, 83, 2, 211, 8).toInt(),
        tgd = s(d, 197, 8) * p2(-31), toc = u(d, 219, 16) * 16.0,
        af2 = s(d, 241, 8) * p2(-55), af1 = s(d, 249, 16) * p2(-43), af0 = s(d, 271, 22) * p2(-31),
    )

    /** Subframe 2 fields, then subframe 3's, combined into an orbit when their IODE match. */
    fun orbit(sf2: IntArray, sf3: IntArray): Orbit? {
        val iode2 = u(sf2, 61, 8).toInt()
        val iode3 = u(sf3, 271, 8).toInt()
        if (iode2 != iode3) return null
        return Orbit(
            iode = iode2, crs = s(sf2, 69, 16) * p2(-5), dn = s(sf2, 91, 16) * p2(-43) * PI,
            m0 = s2(sf2, 107, 8, 121, 24) * p2(-31) * PI, cuc = s(sf2, 151, 16) * p2(-29),
            e = u2(sf2, 167, 8, 181, 24) * p2(-33), cus = s(sf2, 211, 16) * p2(-29),
            sqrtA = u2(sf2, 227, 8, 241, 24) * p2(-19), toe = u(sf2, 271, 16) * 16.0,
            cic = s(sf3, 61, 16) * p2(-29), omega0 = s2(sf3, 77, 8, 91, 24) * p2(-31) * PI, cis = s(sf3, 121, 16) * p2(-29),
            i0 = s2(sf3, 137, 8, 151, 24) * p2(-31) * PI, crc = s(sf3, 181, 16) * p2(-5),
            w = s2(sf3, 197, 8, 211, 24) * p2(-31) * PI, omegaDot = s(sf3, 241, 24) * p2(-43) * PI, idot = s(sf3, 279, 14) * p2(-43) * PI,
        )
    }

    class Ephemeris(val prn: Int, val clock: Clock, val orbit: Orbit) {
        val healthy: Boolean get() = clock.health == 0
    }

    // ---- orbit and clock --------------------------------------------------------------------------------

    private fun wrapWeek(t: Double): Double = when {
        t > SECONDS_PER_WEEK / 2 -> t - SECONDS_PER_WEEK
        t < -SECONDS_PER_WEEK / 2 -> t + SECONDS_PER_WEEK
        else -> t
    }

    private fun eccentricAnomaly(o: Orbit, t: Double): Double {
        val a = o.sqrtA * o.sqrtA
        val n = sqrt(MU / (a * a * a)) + o.dn
        val m = o.m0 + n * wrapWeek(t - o.toe)
        var e = m
        repeat(10) { e = m + o.e * sin(e) }
        return e
    }

    /** Satellite clock offset at GPS time [t] (seconds of week), relativistic term and group delay included. */
    fun satClock(eph: Ephemeris, t: Double): Double {
        val c = eph.clock
        val dt = wrapWeek(t - c.toc)
        val ek = eccentricAnomaly(eph.orbit, t)
        return c.af0 + c.af1 * dt + c.af2 * dt * dt + F_REL * eph.orbit.e * eph.orbit.sqrtA * sin(ek) - c.tgd
    }

    /** Satellite position (ECEF, metres) at GPS time [t] (seconds of week). */
    fun satPosition(eph: Ephemeris, t: Double): DoubleArray {
        val o = eph.orbit
        val tk = wrapWeek(t - o.toe)
        val a = o.sqrtA * o.sqrtA
        val ek = eccentricAnomaly(o, t)
        val v = atan2(sqrt(1 - o.e * o.e) * sin(ek), cos(ek) - o.e)
        val phi = v + o.w
        val s2 = sin(2 * phi)
        val c2 = cos(2 * phi)
        val uu = phi + o.cus * s2 + o.cuc * c2
        val r = a * (1 - o.e * cos(ek)) + o.crs * s2 + o.crc * c2
        val i = o.i0 + o.cis * s2 + o.cic * c2 + o.idot * tk
        val xp = r * cos(uu)
        val yp = r * sin(uu)
        val om = o.omega0 + (o.omegaDot - OMEGA_E) * tk - OMEGA_E * o.toe
        return doubleArrayOf(
            xp * cos(om) - yp * cos(i) * sin(om),
            xp * sin(om) + yp * cos(i) * cos(om),
            yp * sin(i),
        )
    }

    // ---- coordinates ------------------------------------------------------------------------------------

    private const val WGS_A = 6_378_137.0
    private const val WGS_F = 1 / 298.257223563
    private const val WGS_E2 = WGS_F * (2 - WGS_F)

    /** ECEF to latitude, longitude (degrees) and height above the ellipsoid (m). */
    fun geodetic(x: Double, y: Double, z: Double): DoubleArray {
        val p = hypot(x, y)
        var lat = atan2(z, p * (1 - WGS_E2))
        var h = 0.0
        repeat(8) {
            val n = WGS_A / sqrt(1 - WGS_E2 * sin(lat) * sin(lat))
            h = p / cos(lat) - n
            lat = atan2(z, p * (1 - WGS_E2 * n / (n + h)))
        }
        return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(atan2(y, x)), h)
    }

    fun ecef(latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = WGS_A / sqrt(1 - WGS_E2 * sin(lat) * sin(lat))
        return doubleArrayOf((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1 - WGS_E2) + h) * sin(lat))
    }

    /** Elevation and azimuth (degrees) of [sat] seen from [rx], both ECEF. */
    fun elevationAzimuth(rx: DoubleArray, sat: DoubleArray): DoubleArray {
        val g = geodetic(rx[0], rx[1], rx[2])
        val lat = Math.toRadians(g[0])
        val lon = Math.toRadians(g[1])
        val dx = sat[0] - rx[0]; val dy = sat[1] - rx[1]; val dz = sat[2] - rx[2]
        val e = -sin(lon) * dx + cos(lon) * dy
        val n = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
        val el = Math.toDegrees(asin(up / sqrt(dx * dx + dy * dy + dz * dz)))
        val az = (Math.toDegrees(atan2(e, n)) + 360) % 360
        return doubleArrayOf(el, az)
    }

    // ---- position fix -----------------------------------------------------------------------------------

    /** One pseudorange: the satellite's transmit time by its own clock, and when it was received. */
    class Measurement(val prn: Int, val eph: Ephemeris, val txTime: Double)

    class Fix(
        val ecef: DoubleArray,
        val lat: Double,
        val lon: Double,
        val height: Double,
        /** GPS time of week at the receive instant. */
        val tow: Double,
        val pdop: Double,
        val used: List<Int>,
        /** Elevation and azimuth of each satellite used, by PRN. */
        val sky: Map<Int, DoubleArray>,
        val residualRms: Double,
    )

    /**
     * Least-squares fix from at least four measurements taken at the same receive instant. Satellites are placed at
     * their transmit time and rotated with the Earth during the flight; a simple tropospheric delay is removed once the
     * elevation is known, and satellites below [minElevation] are dropped.
     */
    fun solve(ms: List<Measurement>, minElevation: Double = 5.0): Fix? {
        if (ms.size < 4) return null
        // Receive time guess: the latest transmit plus a typical flight time; the clock bias absorbs the error.
        val rxGuess = ms.maxOf { it.txTime } + 0.075
        class Sat(val prn: Int, val pos: DoubleArray, val pr: Double)
        val sats = ms.map { m ->
            var t = m.txTime
            repeat(2) { t = m.txTime - satClock(m.eph, t) }
            val pr = (rxGuess - m.txTime) * C + satClock(m.eph, t) * C
            Sat(m.prn, satPosition(m.eph, t), pr)
        }
        var x = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var use = sats
        var sky = emptyMap<Int, DoubleArray>()
        var pdop = 99.0
        var rms = 0.0
        for (pass in 0 until 2) {
            if (use.size < 4) return null
            for (iter in 0 until 10) {
                val n = use.size
                val h = Array(n) { DoubleArray(4) }
                val dy = DoubleArray(n)
                use.forEachIndexed { k, s ->
                    var range = 0.0
                    var p = s.pos
                    repeat(2) {
                        val tau = range / C
                        val th = OMEGA_E * tau
                        p = doubleArrayOf(cos(th) * s.pos[0] + sin(th) * s.pos[1], -sin(th) * s.pos[0] + cos(th) * s.pos[1], s.pos[2])
                        range = sqrt((p[0] - x[0]) * (p[0] - x[0]) + (p[1] - x[1]) * (p[1] - x[1]) + (p[2] - x[2]) * (p[2] - x[2]))
                    }
                    val tropo = if (iter > 3 && hypot(x[0], hypot(x[1], x[2])) > 6e6) {
                        val el = Math.toRadians(elevationAzimuth(x, p)[0]).coerceAtLeast(0.05)
                        2.47 / (sin(el) + 0.0121)
                    } else 0.0
                    dy[k] = s.pr - (range + x[3] + tropo)
                    for (j in 0 until 3) h[k][j] = -(p[j] - x[j]) / range
                    h[k][3] = 1.0
                }
                val q = invert4(normal(h)) ?: return null
                val g = DoubleArray(4) { i -> (0 until n).sumOf { k -> h[k][i] * dy[k] } }
                val dx = DoubleArray(4) { i -> (0 until 4).sumOf { j -> q[i][j] * g[j] } }
                x = DoubleArray(4) { x[it] + dx[it] }
                pdop = sqrt(q[0][0] + q[1][1] + q[2][2])
                rms = sqrt(dy.sumOf { it * it } / n)
                if (abs(dx[0]) + abs(dx[1]) + abs(dx[2]) < 1e-3) break
            }
            sky = use.associate { it.prn to elevationAzimuth(x, it.pos) }
            val keep = use.filter { (sky[it.prn]?.get(0) ?: 90.0) >= minElevation }
            if (keep.size == use.size) break
            use = keep
        }
        val pos = doubleArrayOf(x[0], x[1], x[2])
        val g = geodetic(x[0], x[1], x[2])
        if (abs(g[2]) > 100_000) return null
        val tow = rxGuess - x[3] / C
        return Fix(pos, g[0], g[1], g[2], (tow % SECONDS_PER_WEEK + SECONDS_PER_WEEK) % SECONDS_PER_WEEK, pdop, use.map { it.prn }, sky, rms)
    }

    private fun normal(h: Array<DoubleArray>): Array<DoubleArray> =
        Array(4) { i -> DoubleArray(4) { j -> h.sumOf { it[i] * it[j] } } }

    private fun invert4(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = 4
        val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) m[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { abs(a[it][c]) }
            if (abs(a[piv][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            val d = a[c][c]
            for (j in 0 until 2 * n) a[c][j] /= d
            for (r in 0 until n) if (r != c) {
                val f = a[r][c]
                if (f != 0.0) for (j in 0 until 2 * n) a[r][j] -= f * a[c][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
    }
}
