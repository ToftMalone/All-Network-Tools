package com.allnetworktools

import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellRecorder
import com.allnetworktools.data.CellState
import com.allnetworktools.data.HistoryStore
import com.allnetworktools.data.RadioTech
import com.allnetworktools.data.SignalSample
import com.allnetworktools.ui.pages.bt.heatOf
import com.allnetworktools.ui.pages.bt.parsePayload
import com.allnetworktools.ui.pages.bt.rssiToMeters
import com.allnetworktools.ui.pages.bt.Heat
import com.allnetworktools.ui.pages.cell.HistMetric
import com.allnetworktools.ui.pages.cell.downsample
import com.allnetworktools.ui.pages.gnss.NmeaLine
import com.allnetworktools.data.GattNames
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CellLogicTest {
    private fun m(tech: RadioTech, pci: Int, band: String, node: Long? = null) = CellMeasure(
        tech, true, band, null, 100, pci, 1, null, node, null, "208", "01", -95, -11, 10, null, null, null, null, null,
    )

    private fun st(serving: CellMeasure?, service: Boolean = true) =
        CellState("Orange", 0, "", "", "", false, true, serving, emptyList(), emptyList(), 3, service)

    private val rec = CellRecorder(HistoryStore(ApplicationProvider.getApplicationContext()))

    @Test fun detectsTechnologyDrop() {
        val e = rec.diff(st(m(RadioTech.NR, 412, "n78")), st(m(RadioTech.LTE, 97, "B20")), 0)!!
        assertEquals("down", e.kind)
        assertEquals("5G → 4G", e.title)
        assertEquals("PCI 412 → 97 · n78 → B20", e.detail)
        assertEquals("LTE", e.tech)
    }

    @Test fun detectsHandoverOnSameNode() {
        val e = rec.diff(st(m(RadioTech.NR, 412, "n78", 574187)), st(m(RadioTech.NR, 187, "n78", 574187)), 0)!!
        assertEquals("handover", e.kind)
        assertEquals("Même gNB 574187", e.note)
    }

    @Test fun ignoresSameCellAndReportsServiceLoss() {
        assertNull(rec.diff(st(m(RadioTech.LTE, 1, "B3")), st(m(RadioTech.LTE, 1, "B3")), 0))
        assertEquals("lost", rec.diff(st(m(RadioTech.LTE, 1, "B3")), st(null, service = false), 0)!!.kind)
    }

    @Test fun downsamplesWithGaps() {
        val s = listOf(SignalSample(0, "NR", -90, null, null), SignalSample(10, "NR", -94, null, null), SignalSample(90, "LTE", -100, null, null))
        val p = downsample(s, HistMetric.Rsrp, 0, 100, buckets = 10)
        assertEquals(-90f, p[0].value)
        assertNull(p[5].value)
        assertEquals("LTE", p[9].tech)
    }

    @Test fun parsesNmeaAndChecksum() {
        val l = NmeaLine.parse(0, "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")!!
        assertEquals("GGA", l.type)
        assertEquals("\$GPGGA", l.header)
        assertTrue(l.valid)
        assertFalse(NmeaLine.parse(0, "\$GPGGA,123519,4807.038,N*00")!!.valid)
        assertEquals("Autres", NmeaLine.parse(0, "\$PGLOR,1,FIX,1.0*2B")!!.type)
    }

    @Test fun decodesGattValues() {
        assertEquals("80 %", GattNames.decode(GattNames.uuid(0x2A19), byteArrayOf(80)))
        assertEquals("72 bpm", GattNames.decode(GattNames.uuid(0x2A37), byteArrayOf(0, 72)))
        assertEquals("« Pixel »", GattNames.decode(GattNames.uuid(0x2A29), "Pixel".toByteArray()))
        assertArrayEquals(byteArrayOf(1, 0xA0.toByte()), parsePayload("01 a0", hex = true))
        assertNull(parsePayload("0x1", hex = true))
    }

    @Test fun proximityModel() {
        assertEquals(1f, rssiToMeters(-59f), 0.01f)
        assertEquals(Heat.Found, heatOf(-45f))
        assertEquals(Heat.Cold, heatOf(-80f))
    }

    @Test fun subSatellitePoint() {
        // Straight overhead: the satellite is above us.
        val (lat, lon) = com.allnetworktools.data.SatGeo.subPoint(48.85, 2.35, 0.0, 90.0, 20_180.0)
        assertEquals(48.85, lat, 0.01)
        assertEquals(2.35, lon, 0.01)
        // On the horizon due south a GEO satellite sits ~81° of arc away (on the equator or below it).
        val (lat2, _) = com.allnetworktools.data.SatGeo.subPoint(0.0, 0.0, 180.0, 0.0, 35_786.0)
        assertEquals(-81.3, lat2, 0.3)
    }

    @Test fun countryLookup() {
        val world = com.allnetworktools.data.WorldMap.parse(
            ApplicationProvider.getApplicationContext<android.content.Context>().assets.open("world/countries.txt").bufferedReader().readText(),
        )
        assertEquals("France", com.allnetworktools.data.WorldMap.countryAt(world, 46.5, 2.5))
        assertEquals("Brésil", com.allnetworktools.data.WorldMap.countryAt(world, -10.0, -52.0))
        assertNull(com.allnetworktools.data.WorldMap.countryAt(world, 0.0, -30.0))
        assertEquals("Océan Atlantique", com.allnetworktools.data.WorldMap.oceanAt(0.0, -30.0))
    }

    @Test fun bleFilters() {
        fun dev(name: String?, rssi: Int, company: Int?, raw: String?, services: List<String> = emptyList()) =
            com.allnetworktools.data.BleDevice("AA:BB:CC:DD:EE:0${rssi and 7}", name, rssi, null, com.allnetworktools.data.BleKind.Unknown, null, true, 0, services, companyId = company, raw = raw)
        val apple = dev(null, -60, 0x004C, "02011A0AFF4C0010050B1C8E3D21")
        val pixel = dev("Pixel Buds", -75, 0x00E0, null, listOf("0xFE2C"))
        val mesh = dev("Node", -90, null, "020106030328180B2A0011")
        val f = com.allnetworktools.ui.pages.bt.BleFilterState()
        assertTrue(listOf(apple, pixel, mesh).all(f::matches))
        assertEquals(setOf(com.allnetworktools.data.BleVendor.Mesh), mesh.vendors)
        f.raw = "0xff 4c00"
        assertEquals(listOf(apple), listOf(apple, pixel, mesh).filter(f::matches))
        f.raw = ""; f.nameMode = com.allnetworktools.ui.pages.bt.NameMode.Named; f.minRssi = -80
        assertEquals(listOf(pixel), listOf(apple, pixel, mesh).filter(f::matches))
        f.nameMode = com.allnetworktools.ui.pages.bt.NameMode.All; f.minRssi = com.allnetworktools.ui.pages.bt.RssiOff
        f.toggleExclude(com.allnetworktools.data.BleVendor.Google)
        assertEquals(listOf(apple, mesh), listOf(apple, pixel, mesh).filter(f::matches))
        f.toggleInclude(com.allnetworktools.data.BleVendor.Google)
        assertTrue(f.exclude.isEmpty())
        assertEquals(listOf(pixel), listOf(apple, pixel, mesh).filter(f::matches))
        f.text = "ee:05"
        assertTrue(f.matches(pixel))
    }
}
