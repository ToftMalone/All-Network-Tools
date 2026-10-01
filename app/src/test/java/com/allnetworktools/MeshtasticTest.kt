package com.allnetworktools

import com.allnetworktools.data.sdr.Meshtastic
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal protobuf writer for building test packets. */
class Pb {
    private val out = ByteArrayOutputStream()
    private fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
    fun int(f: Int, v: Long) = apply { varint((f shl 3).toLong()); varint(v) }
    fun bytes(f: Int, b: ByteArray) = apply { varint(((f shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
    fun str(f: Int, s: String) = bytes(f, s.toByteArray())
    fun fixed32(f: Int, v: Int) = apply { varint(((f shl 3) or 5).toLong()); for (k in 0 until 4) out.write((v shr (8 * k)) and 0xFF) }
    fun float(f: Int, v: Float) = fixed32(f, java.lang.Float.floatToIntBits(v))
    fun build(): ByteArray = out.toByteArray()
}

class MeshtasticTest {
    private val key = Meshtastic.expandKey(byteArrayOf(1))!!

    private fun packet(from: Long, id: Long, data: ByteArray, channel: Int = 8, to: Long = Meshtastic.BROADCAST, flags: Int = (3 shl 5) or 3): ByteArray {
        val h = ByteArray(16)
        fun put(o: Int, v: Long) { for (k in 0 until 4) h[o + k] = (v shr (8 * k)).toByte() }
        put(0, to); put(4, from); put(8, id)
        h[12] = flags.toByte(); h[13] = channel.toByte()
        return h + Meshtastic.crypt(key, from, id, data)
    }

    @Test fun defaultKeyAndChannelHash() {
        assertEquals(0xd4.toByte(), key[0])
        assertEquals(0x01.toByte(), key[15])
        // "AQ==" on the LongFast preset hashes to 8, the channel number seen on public Meshtastic gateways.
        assertEquals(8, Meshtastic.channelHash("LongFast", key))
        assertEquals(0x02.toByte(), Meshtastic.expandKey(byteArrayOf(2))!![15])
        assertNull(Meshtastic.expandKey(byteArrayOf(0)))
        assertEquals(16, Meshtastic.expandKey(ByteArray(5) { 1 })!!.size)
    }

    @Test fun aesCtrIsItsOwnInverseAndUsesTheNonce() {
        val plain = "0123456789abcdef0123456789".toByteArray()
        val enc = Meshtastic.crypt(key, 0x11223344, 99, plain)
        assertArrayEquals(plain, Meshtastic.crypt(key, 0x11223344, 99, enc))
        // Different sender or packet id: different keystream.
        assertTrue(!Meshtastic.crypt(key, 0x11223345, 99, plain).contentEquals(enc))
        assertTrue(!Meshtastic.crypt(key, 0x11223344, 100, plain).contentEquals(enc))
    }

    @Test fun decodesTextNodeInfoPositionAndTelemetry() {
        val text = Meshtastic.decode(packet(0xa1b2c3d4, 1, Pb().int(1, 1).str(2, "Salut 👋").build()), key, 8, 0, 3.5)!!
        assertEquals(Meshtastic.Content.Text("Salut 👋"), text.data!!.content)
        assertEquals("!a1b2c3d4", Meshtastic.nodeId(text.header.from))
        assertEquals(0, text.header.hops)

        val user = Pb().str(1, "!a1b2c3d4").str(2, "Relais Montmartre").str(3, "RMM").int(5, 43).build()
        val info = Meshtastic.decode(packet(0xa1b2c3d4, 2, Pb().int(1, 4).bytes(2, user).build()), key, 8, 0, 0.0)!!
        assertEquals(Meshtastic.Content.NodeInfo("!a1b2c3d4", "Relais Montmartre", "RMM", 43, null), info.data!!.content)

        val pos = Pb().fixed32(1, 488_867_000).fixed32(2, 23_431_000).int(3, 130).int(19, 9).build()
        val p = Meshtastic.decode(packet(0xa1b2c3d4, 3, Pb().int(1, 3).bytes(2, pos).build()), key, 8, 0, 0.0)!!
        val c = p.data!!.content as Meshtastic.Content.Position
        assertEquals(48.8867, c.lat!!, 1e-6)
        assertEquals(2.3431, c.lon!!, 1e-6)
        assertEquals(130, c.altitude)
        assertEquals(9, c.sats)

        // Negative coordinates are sfixed32.
        val west = Pb().fixed32(1, -337_000_000).fixed32(2, -703_000_000).build()
        val w = Meshtastic.decode(packet(5, 4, Pb().int(1, 3).bytes(2, west).build()), key, 8, 0, 0.0)!!.data!!.content as Meshtastic.Content.Position
        assertEquals(-33.7, w.lat!!, 1e-6)

        val dev = Pb().int(1, 87).float(2, 4.01f).float(3, 12.5f).build()
        val tel = Pb().fixed32(1, 1_790_000_000).bytes(2, dev).build()
        val t = Meshtastic.decode(packet(5, 5, Pb().int(1, 67).bytes(2, tel).build()), key, 8, 0, 0.0)!!.data!!.content as Meshtastic.Content.Telemetry
        assertEquals(87, t.battery)
        assertEquals(4.01f, t.voltage!!, 1e-6f)
        assertEquals(12.5f, t.chUtil!!, 1e-6f)
    }

    @Test fun otherChannelsPrivateMessagesAndWrongKeys() {
        val other = Meshtastic.decode(packet(7, 1, Pb().int(1, 1).str(2, "x").build(), channel = 0x31), key, 8, 0, 0.0)!!
        assertNull(other.data)
        assertTrue(other.otherChannel)
        // Same hash but encrypted with another key: does not parse as a Data message.
        val wrongKey = Meshtastic.expandKey(byteArrayOf(9))!!
        val h = ByteArray(16).also { it[13] = 8 }
        val garbled = Meshtastic.decode(h + Meshtastic.crypt(wrongKey, 0, 0, Pb().int(1, 1).str(2, "secret secret secret").build()), key, 8, 0, 0.0)!!
        assertTrue(garbled.data == null || garbled.data!!.content != Meshtastic.Content.Text("secret secret secret"))
        assertNull(Meshtastic.decode(ByteArray(10), key, 8, 0, 0.0))
    }

    @Test fun headerFlags() {
        val h = Meshtastic.header(packet(1, 2, ByteArray(0), flags = (7 shl 5) or 0x10 or 0x08 or 4))!!
        assertEquals(4, h.hopLimit)
        assertEquals(7, h.hopStart)
        assertEquals(3, h.hops)
        assertTrue(h.wantAck)
        assertTrue(h.viaMqtt)
        assertNotNull(Meshtastic.portName(70))
    }
}
