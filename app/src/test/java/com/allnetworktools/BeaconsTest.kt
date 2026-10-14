package com.allnetworktools

import com.allnetworktools.data.Ad
import com.allnetworktools.data.BeaconKind
import com.allnetworktools.data.Beacons
import com.allnetworktools.data.Earbuds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconsTest {
    @Test fun iBeacon() {
        val b = Beacons.parse(Ad.parseHex("1AFF4C000215" + "B9407F30F5F8466EAFF925556B57FE6D" + "0001" + "0002" + "C5"))!!
        assertEquals(BeaconKind.IBeacon, b.kind)
        assertEquals("B9407F30-F5F8-466E-AFF9-25556B57FE6D · 1 / 2", b.id)
        assertEquals("Estimote", b.maker)
        assertEquals(-59, b.txAt1m)
        assertEquals(1.0, b.distanceM(-59)!!, 1e-9)
        assertEquals(10.0, b.distanceM(-79)!!, 1e-9)
    }

    @Test fun eddystoneFrames() {
        val url = Beacons.parse(Ad.parseHex("0D16AAFE10E703676F6F676C6507"))!!
        assertEquals(BeaconKind.EddystoneUrl, url.kind)
        assertEquals("https://google.com", url.id)
        assertEquals(-25 - 41, url.txAt1m)
        val uid = Beacons.parse(Ad.parseHex("1516AAFE00E8" + "0102030405060708090A" + "0B0C0D0E0F10"))!!
        assertEquals(BeaconKind.EddystoneUid, uid.kind)
        assertEquals("0102030405060708090A · 0B0C0D0E0F10", uid.id)
        val tlm = Beacons.telemetry(Ad.parseHex("1116AAFE2000" + "0BB8" + "1880" + "00000064" + "00000E10"))!!
        assertEquals(3000, tlm.batteryMv)
        assertEquals(24.5, tlm.temperatureC!!, 1e-9)
        assertEquals(100L, tlm.advCount)
        assertEquals(360L, tlm.uptimeS)
        // Google Find Hub trackers use the same service but are not beacons.
        assertNull(Beacons.parse(Ad.parseHex("0516AAFE4100")))
    }

    @Test fun altBeacon() {
        val b = Beacons.parse(Ad.parseHex("1BFF1801BEAC" + "00112233445566778899AABBCCDDEEFF" + "0007" + "0009" + "C5" + "00"))!!
        assertEquals(BeaconKind.AltBeacon, b.kind)
        assertTrue(b.id.endsWith("· 7 / 9"))
    }

    @Test fun airPodsBattery() {
        val e = Earbuds.parse(Ad.parseHex("0CFF4C0007190" + "10E2000984500"))!!
        assertEquals("AirPods Pro", e.model)
        assertEquals(90, e.left!!.percent)
        assertEquals(80, e.right!!.percent)
        assertEquals(50, e.case!!.percent)
        assertTrue(e.case!!.charging)
        assertTrue(!e.left!!.charging)
        // Unknown level (15) stays unknown.
        assertNull(Earbuds.parse(Ad.parseHex("0CFF4C00071901" + "0E20209F4500"))!!.left!!.percent)
    }
}

class CoverageTest {
    @Test fun interpolatesBetweenMeasurements() {
        val pts = listOf(
            com.allnetworktools.ui.pages.wifi.CoveragePoint(0f, 0f, -50),
            com.allnetworktools.ui.pages.wifi.CoveragePoint(1f, 0f, -90),
        )
        val c = com.allnetworktools.ui.pages.wifi.Coverage
        assertEquals(-50f, c.estimate(pts, 0f, 0f)!!, 1e-4f)
        assertEquals(-70f, c.estimate(pts, 0.5f, 0f)!!, 1e-3f)
        assertTrue(c.estimate(pts, 0.1f, 0f)!! > -55f)
        assertNull(c.estimate(emptyList(), 0.5f, 0.5f))
        assertEquals(1f, c.level(-40f)); assertEquals(0f, c.level(-95f))
    }
}
