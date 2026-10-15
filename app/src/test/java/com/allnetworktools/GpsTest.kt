package com.allnetworktools

import com.allnetworktools.data.sdr.Gps
import com.allnetworktools.data.sdr.GpsAcquisition
import com.allnetworktools.data.sdr.GpsReceiver
import com.allnetworktools.data.sdr.GpsStatus
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTest {
    private val lyon = Gps.ecef(45.7640, 4.8357, 250.0)
    private val week = 2400
    private val t0 = 345_595.0 // five seconds before a subframe 1 (TOW multiple of 30 s): time to lock first
    private val toe = 345_600.0

    @Test fun caCodesStartAsTheIcdSays() {
        // First ten chips in octal, IS-GPS-200 table 3-Ia.
        val expected = mapOf(1 to "1440", 2 to "1620", 3 to "1710", 4 to "1744", 5 to "1133", 6 to "1455", 7 to "1131", 8 to "1454", 9 to "1626", 10 to "1504")
        for ((prn, oct) in expected) {
            val b = Gps.caBits(prn)
            val v = (0 until 10).fold(0) { a, i -> (a shl 1) or b[i] }
            assertEquals("PRN $prn", oct, Integer.toOctalString(v))
        }
    }

    @Test fun navigationMessageRoundTrip() {
        val eph = GpsSim.constellation(toe)[6]
        val sim = GpsSim(lyon, t0, week, listOf(eph))
        val k = 345_600L / 6 // subframe 1 at TOW 345600
        // Three subframes in a row, as received, with the previous word's last bits in front.
        val stream = IntArray(2) + sim.subframeBits(eph, k) + sim.subframeBits(eph, k + 1) + sim.subframeBits(eph, k + 2)
        val s1 = Gps.subframe(stream, 2)!!
        val s2 = Gps.subframe(stream, 302)!!
        val s3 = Gps.subframe(stream, 602)!!
        assertEquals(1, Gps.subframeId(s1)); assertEquals(2, Gps.subframeId(s2)); assertEquals(3, Gps.subframeId(s3))
        assertEquals(345_606.0, Gps.towNext(s1), 0.0)
        val c = Gps.subframe1(s1)
        val o = Gps.orbit(s2, s3)!!
        assertEquals(week % 1024, c.week)
        assertEquals(eph.clock.af0, c.af0, 1e-15); assertEquals(eph.clock.tgd, c.tgd, 1e-15); assertEquals(eph.clock.iodc, c.iodc)
        assertEquals(eph.orbit.sqrtA, o.sqrtA, 1e-9); assertEquals(eph.orbit.e, o.e, 1e-12); assertEquals(eph.orbit.m0, o.m0, 1e-12)
        assertEquals(eph.orbit.omega0, o.omega0, 1e-12); assertEquals(eph.orbit.i0, o.i0, 1e-12); assertEquals(eph.orbit.w, o.w, 1e-12)
        assertEquals(eph.orbit.omegaDot, o.omegaDot, 1e-18); assertEquals(eph.orbit.crs, o.crs, 1e-9); assertEquals(eph.orbit.toe, o.toe, 0.0)
        // An inverted stream (Costas ambiguity) decodes the same.
        val inv = IntArray(stream.size) { 1 - stream[it] }
        assertEquals(o.sqrtA, Gps.orbit(Gps.subframe(inv, 302)!!, Gps.subframe(inv, 602)!!)!!.sqrtA, 0.0)
        // A flipped bit breaks the parity.
        stream[400] = 1 - stream[400]
        assertEquals(null, Gps.subframe(stream, 302))
    }

    private fun visible(): List<Gps.Ephemeris> = GpsSim.constellation(toe)
        .filter { Gps.elevationAzimuth(lyon, Gps.satPosition(it, t0))[0] > 15 }

    @Test fun solvesFromExactPseudoranges() {
        val sats = visible()
        assertTrue("${sats.size} visible", sats.size >= 5)
        val sim = GpsSim(lyon, t0, week, sats)
        // Transmit times for a receive instant, through the simulator's own model.
        val rx = t0 + 10.0
        val delta = GpsSim::class.java.getDeclaredMethod("delta", Gps.Ephemeris::class.java, Double::class.javaPrimitiveType).apply { isAccessible = true }
        val ms = sats.map { Gps.Measurement(it.prn, it, rx + delta.invoke(sim, it, rx) as Double) }
        val fix = Gps.solve(ms)!!
        val err = sqrt((0 until 3).sumOf { (fix.ecef[it] - lyon[it]) * (fix.ecef[it] - lyon[it]) })
        assertTrue("error $err m", err < 1) 
        assertEquals(rx, fix.tow, 1e-6)
    }

    @Test fun acquisitionFindsSatellitesAndClockOffset() {
        val sats = visible().take(5)
        val sim = GpsSim(lyon, t0, week, sats, ppm = 9.0)
        val b = sim.next(20480)
        val re = FloatArray(20480) { b[2 * it].toFloat() }
        val im = FloatArray(20480) { b[2 * it + 1].toFloat() }
        val hits = GpsAcquisition(GpsReceiver.FS).search(re, im, 0, (1..32).toList(), 0.0, 40_000.0)
        assertEquals(sats.map { it.prn }.toSet(), hits.map { it.prn }.toSet())
        // 9 ppm of a fast crystal pulls every carrier about 14 kHz down.
        hits.forEach { assertEquals(-9e-6 * Gps.L1_HZ, it.dopplerHz, 6_000.0) }
    }

    @Test fun fullReceiverGetsAFix() {
        val sats = visible().take(7)
        val unix = ((315_964_800.0 + week * Gps.SECONDS_PER_WEEK + t0 - 18) * 1000).toLong()
        var last: GpsStatus? = null
        val rx = GpsReceiver(nowMillis = { unix }) { last = it }
        val sim = GpsSim(lyon, t0, week, sats, ppm = 7.5, cn0 = 44.0)
        repeat(28 * 16) { // 28 s in 1/16 s chunks
            val b = sim.next(128_000)
            rx.feed(b, b.size)
        }
        val s = last!!
        assertEquals(sats.size, s.channels.size)
        val fix = s.fix
        assertNotNull("no fix; stages ${s.channels.map { "${it.prn}:${it.stage}:${"%.0f".format(it.cn0)}" }}", fix)
        val err = sqrt((0 until 3).sumOf { (fix!!.ecef[it] - lyon[it]) * (fix.ecef[it] - lyon[it]) })
        assertTrue("error $err m, PDOP ${fix!!.pdop}, rms ${fix.residualRms}, used ${fix.used}", err < 40)
        assertEquals(45.764, fix!!.lat, 0.001)
        assertEquals(week, s.week)
        assertEquals(7.5, s.clockPpm!!, 0.3)
        s.channels.forEach { assertTrue("C/N0 ${it.cn0}", it.cn0 in 39.0..49.0) }
    }
}
