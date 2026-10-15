package com.allnetworktools

import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.MeshPreset
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.MeshRegion
import com.allnetworktools.data.sdr.Meshtastic
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshtasticTest {
    // ---- a tiny protobuf writer -------------------------------------------------------------------------
    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(n: Int, w: Int) = varint(((n shl 3) or w).toLong())
        fun int(n: Int, v: Long) { tag(n, 0); varint(v) }
        fun fixed32(n: Int, v: Long) { tag(n, 5); for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun float(n: Int, v: Float) = fixed32(n, java.lang.Float.floatToIntBits(v).toLong() and 0xFFFFFFFFL)
        fun bytes(n: Int, b: ByteArray) { tag(n, 2); varint(b.size.toLong()); out.write(b) }
        fun str(n: Int, s: String) = bytes(n, s.toByteArray())
        fun build() = out.toByteArray()
    }

    private fun data(port: Int, payload: ByteArray) = Pb().apply { int(1, port.toLong()); bytes(2, payload) }.build()

    private val publicKey = Meshtastic.expandKey(Meshtastic.parseKey("AQ==")!!)

    private fun packet(from: Long, id: Long, plain: ByteArray, key: ByteArray? = publicKey, hash: Int = 8, to: Long = Meshtastic.BROADCAST, flags: Int = 0x63): ByteArray {
        val h = ByteArray(16)
        for (i in 0 until 4) { h[i] = (to shr (8 * i)).toByte(); h[4 + i] = (from shr (8 * i)).toByte(); h[8 + i] = (id shr (8 * i)).toByte() }
        h[12] = flags.toByte(); h[13] = hash.toByte()
        return h + (key?.let { Meshtastic.crypt(it, from, id, plain) } ?: plain)
    }

    @Test fun defaultChannelPlan() {
        assertEquals(869.525, MeshRadio.frequencyMhz(MeshRegion.Eu868, MeshPreset.LongFast, MeshRadio.defaultSlot(MeshRegion.Eu868, MeshPreset.LongFast)!!)!!, 1e-9)
        assertEquals(906.875, MeshRadio.frequencyMhz(MeshRegion.Us, MeshPreset.LongFast, MeshRadio.defaultSlot(MeshRegion.Us, MeshPreset.LongFast)!!)!!, 1e-9)
        assertEquals(433.875, MeshRadio.frequencyMhz(MeshRegion.Eu433, MeshPreset.LongFast, MeshRadio.defaultSlot(MeshRegion.Eu433, MeshPreset.LongFast)!!)!!, 1e-9)
        assertEquals(104, MeshRadio.slots(MeshRegion.Us, MeshPreset.LongFast))
        assertTrue(MeshRadio.ldro(MeshPreset.LongSlow))
        assertTrue(MeshRadio.ldro(MeshPreset.LongModerate))
        assertTrue(!MeshRadio.ldro(MeshPreset.LongFast))
    }

    @Test fun publicChannelKeyAndHash() {
        assertEquals("d4f1bb3a20290759f0bcffabcf4e6901", publicKey!!.joinToString("") { "%02x".format(it) })
        // The well-known hash of the public LongFast channel.
        assertEquals(8, Meshtastic.Channel("", publicKey).hash(MeshPreset.LongFast))
        assertNull(Meshtastic.parseKey("pas une clé !"))
        assertNull(Meshtastic.expandKey(byteArrayOf(0)))
        assertEquals(32, Meshtastic.parseKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")!!.size)
    }

    @Test fun decryptsTextOnThePublicChannel() {
        val frame = packet(0x433c1a2bL, 0x12345678L, data(1, "Salut le mesh !".toByteArray()))
        val p = Meshtastic.decode(frame, listOf(Meshtastic.Channel("", publicKey)), MeshPreset.LongFast)!!
        assertEquals(Meshtastic.Status.Decoded, p.status)
        assertEquals("LongFast", p.channel)
        assertEquals(1, p.data!!.port)
        assertEquals("Salut le mesh !", (p.data!!.content as Meshtastic.Content.Text).text)
        assertEquals(0, p.header.hops) // hop start 3, hop limit 3
    }

    @Test fun otherChannelAndPrivateMessages() {
        val other = packet(1, 2, data(1, "x".toByteArray()), hash = 0x5A)
        assertEquals(Meshtastic.Status.OtherChannel, Meshtastic.decode(other, listOf(Meshtastic.Channel("", publicKey)), MeshPreset.LongFast)!!.status)
        val pki = packet(1, 3, ByteArray(30) { it.toByte() }, key = null, hash = 0, to = 0x1234)
        assertEquals(Meshtastic.Status.Private, Meshtastic.decode(pki, listOf(Meshtastic.Channel("", publicKey)), MeshPreset.LongFast)!!.status)
        // A private channel with its own key and name.
        val key = Meshtastic.parseKey("AAECAwQFBgcICQoLDA0ODw==")!!
        val ch = Meshtastic.Channel("Famille", key)
        val priv = packet(7, 9, data(1, "On mange à 20 h".toByteArray()), key = key, hash = ch.hash(MeshPreset.LongFast))
        val p = Meshtastic.decode(priv, listOf(Meshtastic.Channel("", publicKey), ch), MeshPreset.LongFast)!!
        assertEquals("Famille", p.channel)
        assertEquals("On mange à 20 h", (p.data!!.content as Meshtastic.Content.Text).text)
    }

    @Test fun decodesPositionNodeInfoAndTelemetry() {
        val pos = Pb().apply { fixed32(1, (48.8584 * 1e7).toLong()); fixed32(2, (2.2945 * 1e7).toLong()); int(3, 35); int(19, 9) }.build()
        val p = Meshtastic.data(data(3, pos))!!.content as Meshtastic.Content.Position
        assertEquals(48.8584, p.lat!!, 1e-7); assertEquals(2.2945, p.lon!!, 1e-7); assertEquals(35, p.altitude); assertEquals(9, p.sats)
        val south = Pb().apply { fixed32(1, (-33.8568 * 1e7).toLong() and 0xFFFFFFFFL); fixed32(2, (151.2153 * 1e7).toLong()) }.build()
        assertEquals(-33.8568, (Meshtastic.data(data(3, south))!!.content as Meshtastic.Content.Position).lat!!, 1e-7)

        val user = Pb().apply { str(1, "!433c1a2b"); str(2, "Thomas Mobile"); str(3, "TM"); int(5, 43); bytes(8, ByteArray(32)) }.build()
        val u = Meshtastic.data(data(4, user))!!.content as Meshtastic.Content.NodeInfo
        assertEquals("Thomas Mobile", u.longName); assertEquals("TM", u.shortName); assertEquals("Heltec V3", Meshtastic.hwModel(u.hwModel!!)); assertTrue(u.hasPublicKey)
        assertEquals("T-Echo", Meshtastic.hwModel(7))
        assertEquals("RAK4631", Meshtastic.hwModel(9))

        val dev = Pb().apply { int(1, 87); float(2, 4.02f); float(3, 12.5f); float(4, 1.25f); int(5, 3600) }.build()
        val tel = Pb().apply { fixed32(1, 1_700_000_000); bytes(2, dev) }.build()
        val t = Meshtastic.data(data(67, tel))!!.content as Meshtastic.Content.Telemetry
        assertEquals(87, t.battery); assertEquals(4.02f, t.voltage!!, 1e-6f); assertEquals(12.5f, t.chUtil!!, 1e-6f)

        val route = Pb().apply { fixed32(1, 0xAAAA0001L); fixed32(1, 0xAAAA0002L) }.build()
        assertEquals(listOf(0xAAAA0001L, 0xAAAA0002L), (Meshtastic.data(data(70, route))!!.content as Meshtastic.Content.Traceroute).route)
    }

    @Test fun wrongKeyDoesNotParse() {
        val frame = packet(5, 6, data(1, "secret".toByteArray()), key = Meshtastic.parseKey("AAECAwQFBgcICQoLDA0ODw==")!!)
        // Same hash as the public channel (8) but another key: the content is noise.
        val p = Meshtastic.decode(frame, listOf(Meshtastic.Channel("", publicKey)), MeshPreset.LongFast)!!
        assertTrue(p.status == Meshtastic.Status.WrongKey || p.data?.port != 1)
    }

    @Test fun overTheAirFromLoraWaveformToText() {
        val frame = packet(0xdeadbeefL, 0x0badcafeL, data(1, "Bonjour depuis le HackRF".toByteArray()))
        val sym = LoraTx.symbols(frame, 11, 1)
        val (re, im) = LoraTx.waveform(sym, 11, 250_000.0, 500_000.0, delayChips = 300.4, cfoHz = -6_000.0, ppm = -6_000.0 / 869.525e6 * 1e6)
        val got = ArrayList<LoraFrame>()
        val rx = LoraReceiver(11, 250_000.0, 869.525e6, LoraPhy.lowDataRate(11, 250_000.0)) { got += it }
        rx.feed(FloatArray(re.size) { re[it].toFloat() }, FloatArray(im.size) { im[it].toFloat() }, re.size)
        val f = got.single()
        assertTrue(f.crcOk)
        assertArrayEquals(frame, f.payload)
        val p = Meshtastic.decode(f.payload, listOf(Meshtastic.Channel("", publicKey)), MeshPreset.LongFast)!!
        assertEquals("Bonjour depuis le HackRF", (p.data!!.content as Meshtastic.Content.Text).text)
        assertEquals("!deadbeef", Meshtastic.nodeId(p.header.from))
    }
}
