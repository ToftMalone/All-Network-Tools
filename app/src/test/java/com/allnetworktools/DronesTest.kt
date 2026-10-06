package com.allnetworktools

import com.allnetworktools.data.sdr.AnalogDroneTracker
import com.allnetworktools.data.sdr.DjiTracker
import com.allnetworktools.data.sdr.DroneBands
import com.allnetworktools.data.sdr.DroneBurst
import com.allnetworktools.data.sdr.FpvChannels
import com.allnetworktools.data.sdr.FpvResult
import com.allnetworktools.data.sdr.levelTrend
import com.allnetworktools.ui.pages.sdr.Plane
import com.allnetworktools.util.Export
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DronesTest {
    private fun res(name: String, db: Double, q: Double) = FpvResult(FpvChannels.byName(name)!!, db, q, if (q > 0) 900 else 0, "PAL", 0)

    @Test fun analogTrackerKeepsOneDronePerTransmitter() {
        val t = AnalogDroneTracker()
        // R7 and F8 are both 5880 MHz, E5 is 5885: one transmitter. A3 carries no video.
        t.sweep(listOf(res("R7", -40.0, 0.95), res("F8", -40.5, 0.94), res("E5", -44.0, 0.9), res("A3", -50.0, 0.1), res("R1", -60.0, 0.8)), 1_000)
        val l = t.list()
        assertEquals(2, l.size)
        assertEquals(5880, l[0].channel.mhz)
        assertEquals(5658, l[1].channel.mhz)
        repeat(2) { t.sweep(listOf(res("R1", -58.0, 0.8)), 2_000) }
        assertTrue(t.list().first { it.channel.mhz == 5880 }.lost)
        repeat(3) { t.sweep(emptyList(), 3_000) }
        assertTrue(t.list().none { it.channel.mhz == 5880 })
    }

    @Test fun trendFollowsTheLevel() {
        assertEquals(1, levelTrend(listOf(-70.0, -69.0, -70.0, -60.0, -59.0, -58.0)))
        assertEquals(-1, levelTrend(listOf(-50.0, -51.0, -50.0, -60.0, -61.0, -62.0)))
        assertEquals(0, levelTrend(listOf(-50.0, -51.0)))
    }

    @Test fun djiNeedsThreeBurstsOrTheRhythm() {
        val t = DjiTracker()
        fun burst() = DroneBurst(0.0, 640.0, 9.2, 0.1, 18.0, 0.8)
        t.add(2414.5, burst(), 1_000); t.add(2444.5, burst(), 4_000)
        assertFalse(t.state(5_000)!!.confirmed)
        t.add(2429.5, burst(), 9_000)
        val st = t.state(10_000)!!
        assertTrue(st.confirmed)
        assertEquals(listOf(2414.5, 2429.5, 2444.5), st.frequencies)
        assertNull(t.state(200_000))
        t.add(2399.5, burst(), 300_000); t.periodic(300_000)
        assertTrue(t.state(301_000)!!.confirmed)
    }

    @Test fun droneIdWindowsSitOnTheKnownCentres() {
        assertTrue(2399.5 in DroneBands.windows(24) && 2459.5 in DroneBands.windows(24))
        assertTrue(listOf(5756.5, 5776.5, 5796.5).all { it in DroneBands.windows(58) })
        assertEquals(12, DroneBands.windows(0).size)
    }

    @Test fun exportsCsvWithQuotingAndUtcTimes() {
        val p = Plane(0x3C6444, "3C6444", "DLH4AB, X", 36000, 450.0, 90.0, 0, 48.5, 2.25, 10, 42, -12.5)
        val csv = Export.planes(listOf(p), 1_700_000_010_000)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("dernier_message_utc,icao,indicatif"))
        assertEquals("2023-11-14T22:13:20Z,3C6444,\"DLH4AB, X\",48.5,2.25,36000,450,90,0,42,-12.5", lines[1])
        assertNotNull(Export.stamp(0))
    }
}
