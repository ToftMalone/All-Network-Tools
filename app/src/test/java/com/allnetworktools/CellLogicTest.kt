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
}
