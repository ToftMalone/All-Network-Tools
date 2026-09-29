package com.allnetworktools

import com.allnetworktools.data.orbit.GnssSystem
import com.allnetworktools.data.orbit.Observer
import com.allnetworktools.data.orbit.OrbitSat
import com.allnetworktools.data.orbit.PassPredictor
import com.allnetworktools.data.orbit.Sgp4
import com.allnetworktools.data.orbit.Tle
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitTest {
    /** Positions computed by the reference Python sgp4 2.27 (pure-Python propagator, WGS-72). */
    @Test
    fun sgp4MatchesReferenceImplementation() {
        val rows = javaClass.classLoader!!.getResource("sgp4ref.tsv")!!.readText().lines().filter { it.isNotBlank() }
        assertTrue(rows.size > 50)
        var worst = 0.0
        for (row in rows) {
            val f = row.split('\t')
            val sat = Sgp4(Tle(f[0], f[1], f[2]))
            val r = sat.position(f[3].toDouble())
            assertNotNull("${f[0]} @ ${f[3]}", r)
            for (k in 0..2) {
                val d = abs(r!![k] - f[4 + k].toDouble())
                worst = maxOf(worst, d)
                assertEquals("${f[0]} @ ${f[3]} axis $k", f[4 + k].toDouble(), r[k], 1e-6)
            }
        }
        println("SGP4 worst deviation from reference: $worst km")
    }

    /** Azimuth / elevation from Paris computed by Skyfield (full IAU frame chain) for the same element sets. */
    @Test
    fun lookAnglesMatchSkyfield() {
        val obs = Observer(48.8566, 2.3522, 35.0)
        val rows = javaClass.classLoader!!.getResource("lookref.tsv")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(24, rows.size)
        for (row in rows) {
            val f = row.split('\t')
            val sat = OrbitSat.of(Tle(f[0], f[1], f[2]))
            val l = PassPredictor.look(sat, obs, f[3].toLong())!!
            val daz = abs(((l.azimuth - f[4].toDouble() + 540) % 360) - 180)
            assertTrue("${f[0]} az ${l.azimuth} vs ${f[4]}", daz < 0.05)
            assertEquals("${f[0]} el", f[5].toDouble(), l.elevation, 0.05)
        }
    }

    /** GPS PRN 22 above 10° from Paris on 29–30 Sep 2026, events from Skyfield's find_events. */
    @Test
    fun passTimesMatchSkyfield() {
        val tle = Tle(
            "GPS BIIR-5  (PRN 22)",
            "1 26407U 00040A   26271.24814091  .00000027  00000+0  00000+0 0  9997",
            "2 26407  54.8357 211.0724 0117505 304.1161 233.1436  2.00558405192019",
        )
        val sat = OrbitSat.of(tle)
        assertEquals(GnssSystem.GPS, sat.system)
        assertEquals("G22", sat.prn)
        val start = 1790661600000L // 2026-09-29 06:00 UTC
        val passes = PassPredictor.passes(sat, Observer(48.8566, 2.3522, 35.0), start, start + 86_400_000L, 10.0)
        assertEquals(2, passes.size)
        assertEquals(1790680755708.0, passes[0].rise!!.toDouble(), 5000.0)
        assertEquals(1790689262328.0, passes[0].maxAt.toDouble(), 60_000.0)
        assertEquals(1790698325069.0, passes[0].set!!.toDouble(), 5000.0)
        assertEquals(1790735862818.0, passes[1].rise!!.toDouble(), 5000.0)
        assertEquals(1790745592875.0, passes[1].set!!.toDouble(), 5000.0)
    }

    @Test
    fun namesMapToSystems() {
        fun sys(n: String) = OrbitSat.of(Tle(n, "", "")).let { it.system to it.prn }
        assertEquals(GnssSystem.BeiDou to "C19", sys("BEIDOU-3 M1 (C19)"))
        assertEquals(GnssSystem.Galileo, sys("GSAT0201 (GALILEO 5)").first)
        assertEquals(GnssSystem.Glonass, sys("COSMOS 2433 (720)").first)
        assertEquals(GnssSystem.QZSS to "J02", sys("QZS-2 (QZSS/PRN 194)"))
        assertEquals(GnssSystem.SBAS to "123", sys("ASTRA 5B (EGNOS/PRN 123)"))
        assertEquals(GnssSystem.SBAS, sys("EUTELSAT 5 WEST B (EGN*)").first)
        assertEquals(GnssSystem.NavIC, sys("IRNSS-1B").first)
    }

    @Test
    fun parsesThreeLineFiles() {
        val text = """
            GPS BIIR-5  (PRN 22)
            1 26407U 00040A   26271.24814091  .00000027  00000+0  00000+0 0  9997
            2 26407  54.8357 211.0724 0117505 304.1161 233.1436  2.00558405192019
            broken
            BEIDOU-2 G8 (C01)
            1 44231U 19027A   26271.85930858 -.00000241  00000+0  00000+0 0  9993
            2 44231   1.9139  74.2395 0008828 276.6606 110.5607  1.00276103 27120
        """.trimIndent()
        val list = Tle.parseAll(text)
        assertEquals(2, list.size)
        assertEquals(26407, list[0].catalog)
        assertEquals("BEIDOU-2 G8 (C01)", list[1].name)
    }

    @Test
    fun gmstAtJ2000() {
        // 2000-01-01 12:00 UT1: GMST = 280.46062° (IAU-82).
        assertEquals(280.46062, Math.toDegrees(Sgp4.gstime(2451545.0)), 1e-4)
    }
}
