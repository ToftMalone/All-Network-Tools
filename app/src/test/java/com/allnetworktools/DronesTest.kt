package com.allnetworktools

import com.allnetworktools.data.drone.RemoteId
import com.allnetworktools.data.drone.RemoteIdTracker
import com.allnetworktools.data.drone.RidFrame
import com.allnetworktools.data.drone.RidMessage
import com.allnetworktools.data.drone.RidTransport
import com.allnetworktools.data.sdr.AnalogDroneTracker
import com.allnetworktools.data.sdr.DjiTracker
import com.allnetworktools.data.sdr.DroneBands
import com.allnetworktools.data.sdr.DroneBurst
import com.allnetworktools.data.sdr.FpvChannels
import com.allnetworktools.data.sdr.FpvResult
import com.allnetworktools.data.sdr.levelTrend
import com.allnetworktools.ui.pages.sdr.Plane
import com.allnetworktools.util.Export
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote ID messages built byte by byte after the OpenDroneID encoder (opendroneid-core-c), then decoded. */
class DronesTest {
    private fun msg(type: Int, fill: (ByteArray) -> Unit) = ByteArray(25).also { it[0] = ((type shl 4) or 2).toByte(); fill(it) }
    private fun ByteArray.le16(i: Int, v: Int) { this[i] = v.toByte(); this[i + 1] = (v shr 8).toByte() }
    private fun ByteArray.le32(i: Int, v: Int) { for (k in 0 until 4) this[i + k] = (v shr (8 * k)).toByte() }
    private fun alt(m: Double) = ((m + 1000) / 0.5).roundToInt()

    private fun basic(id: String) = msg(0) { b ->
        b[1] = ((1 shl 4) or 2).toByte() // serial number, multirotor
        id.toByteArray().copyInto(b, 2)
    }

    private fun location() = msg(1) { b ->
        b[1] = ((2 shl 4) or 0x2 or 0x1).toByte() // airborne, east-west half (+180°), speed multiplier
        b[2] = 90 // 90 + 180 = 270°
        b[3] = 10 // 10 × 0.75 + 63.75 = 71.25 m/s
        b[4] = (-3).toByte() // −1.5 m/s
        b.le32(5, 488_582_000); b.le32(9, 22_945_000)
        b.le16(13, alt(150.0)); b.le16(15, alt(152.0)); b.le16(17, alt(85.0))
    }

    private fun system() = msg(4) { b ->
        b[1] = 1
        b.le32(2, 488_566_000); b.le32(6, 22_920_000)
        b[17] = ((1 shl 4) or 2).toByte() // open category, class C1
        b.le16(18, alt(66.0))
    }

    private fun operator(id: String) = msg(5) { b -> id.toByteArray().copyInto(b, 2) }

    private fun pack(vararg m: ByteArray) = byteArrayOf(0xF2.toByte(), 25, m.size.toByte()) + m.fold(ByteArray(0)) { a, x -> a + x }

    @Test fun decodesEveryMessageField() {
        val msgs = RemoteId.decode(pack(basic("1581F5FHD23170001"), location(), system(), operator("FRA87ag3k5ht1lm")))
        assertEquals(4, msgs.size)
        val b = msgs[0] as RidMessage.BasicId
        assertEquals("1581F5FHD23170001", b.id); assertEquals(1, b.idType); assertEquals(2, b.uaType)
        val l = msgs[1] as RidMessage.Location
        assertEquals(2, l.status)
        assertEquals(270.0, l.directionDeg!!, 1e-9)
        assertEquals(71.25, l.speedMs!!, 1e-9)
        assertEquals(-1.5, l.verticalMs!!, 1e-9)
        assertEquals(48.8582, l.lat!!, 1e-7); assertEquals(2.2945, l.lon!!, 1e-7)
        assertEquals(150.0, l.altBaroM!!, 1e-9); assertEquals(152.0, l.altGeoM!!, 1e-9); assertEquals(85.0, l.heightM!!, 1e-9)
        assertFalse(l.heightAgl)
        val s = msgs[2] as RidMessage.System
        assertEquals(48.8566, s.operatorLat!!, 1e-7); assertEquals(66.0, s.operatorAltM!!, 1e-9)
        assertEquals("Ouverte · C1", RemoteId.euLabel(s.categoryEu, s.classEu))
        assertEquals("FRA87ag3k5ht1lm", (msgs[3] as RidMessage.OperatorId).id)
    }

    @Test fun unknownValuesDecodeToNull() {
        val l = RemoteId.decode(msg(1) { b -> b[1] = 0x3; b[2] = 181.toByte(); b[3] = 255.toByte(); b[4] = 126 })[0] as RidMessage.Location
        assertNull(l.directionDeg); assertNull(l.speedMs); assertNull(l.verticalMs)
        assertNull(l.lat); assertNull(l.altGeoM); assertNull(l.heightM)
    }

    @Test fun unwrapsBluetoothAndWifiTransports() {
        val ble = byteArrayOf(0x0D, 7) + basic("1581F5FHD23170001")
        assertEquals("1581F5FHD23170001", (RemoteId.fromBleServiceData(ble).single() as RidMessage.BasicId).id)
        assertTrue(RemoteId.fromBleServiceData(byteArrayOf(0x0C, 7) + basic("X")).isEmpty())
        val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 3) + pack(basic("1581F5FHD23170001"), location())
        assertEquals(2, RemoteId.fromWifiVendorElement(ie).size)
        assertTrue(RemoteId.fromWifiVendorElement(byteArrayOf(0x50, 0x6F, 0x9A.toByte(), 0x0D, 3) + pack(location())).isEmpty())
        assertTrue(RemoteId.decode(ByteArray(10)).isEmpty())
    }

    @Test fun trackerMergesTransportsByIdentifier() {
        val t = RemoteIdTracker()
        val msgs = RemoteId.decode(pack(basic("1581F5FHD23170001"), location(), system()))
        t.add(RidFrame("AA:BB", RidTransport.Bluetooth, -60, msgs), 1_000)
        t.add(RidFrame("60:60:1F:00:00:01", RidTransport.Wifi, -70, msgs), 2_000)
        t.add(RidFrame("CC:DD", RidTransport.Bluetooth, -80, RemoteId.decode(location())), 2_500)
        val list = t.snapshot(3_000)
        assertEquals(2, list.size)
        val dji = list.first { it.id != null }
        assertTrue(dji.isDji)
        assertEquals(setOf(RidTransport.Bluetooth, RidTransport.Wifi), dji.transports)
        assertEquals(48.8566, dji.operatorLat!!, 1e-7)
        assertEquals(2, dji.messages)
        assertTrue(t.snapshot(200_000).isEmpty())
    }

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
