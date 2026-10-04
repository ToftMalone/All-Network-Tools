package com.allnetworktools

import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.mesh.ChatMessage
import com.allnetworktools.data.mesh.MeshClient
import com.allnetworktools.data.mesh.MeshConn
import com.allnetworktools.data.mesh.MeshLink
import com.allnetworktools.data.mesh.MeshProto
import com.allnetworktools.data.mesh.MeshStreamFraming
import com.allnetworktools.data.mesh.MsgStatus
import com.allnetworktools.data.mesh.ProtoMsg
import com.allnetworktools.data.mesh.ProtoWriter
import com.allnetworktools.data.mesh.RadioConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FromRadio messages built field by field from meshtastic/protobufs, as a node would send them. */
object FromRadioSamples {
    const val ME = 0x1a2b3c4dL
    const val ALICE = 0xdeadbeefL

    fun myInfo(num: Long) = ProtoWriter().message(3) { varint(1, num); int(8, 4) }.toByteArray()

    fun node(num: Long, long: String, short: String, lat: Double? = null, lon: Double? = null, battery: Int? = null, heardS: Long = 1_760_000_000L, hops: Int? = null) =
        ProtoWriter().message(4) {
            varint(1, num)
            message(2) { string(1, MeshProto.nodeId(num)); string(2, long); string(3, short); int(5, 43); int(7, 0) }
            if (lat != null && lon != null) message(3) { fixed32(1, (lat * 1e7).toLong().toInt().toLong() and 0xFFFFFFFFL); fixed32(2, (lon * 1e7).toLong().toInt().toLong() and 0xFFFFFFFFL); int(3, 120) }
            float(4, 6.5f)
            fixed32(5, heardS)
            battery?.let { b -> message(6) { int(1, b); float(2, 4.01f); float(3, 12.5f); float(4, 1.2f); int(5, 3600) } }
            hops?.let { int(9, it) }
        }.toByteArray()

    fun channel(index: Int, role: Int, name: String, psk: ByteArray) =
        ProtoWriter().message(10) { int(1, index); message(2) { bytes(2, psk); string(3, name); message(7) { int(1, 13) } }; int(3, role) }.toByteArray()

    fun lora(region: Int, preset: Int, hops: Int) =
        ProtoWriter().message(5) { message(6) { bool(1, true); int(2, preset); int(7, region); int(8, hops); bool(9, true) } }.toByteArray()

    fun complete(nonce: Long) = ProtoWriter().varint(7, nonce).toByteArray()

    fun text(from: Long, to: Long, channel: Int, id: Long, text: String, snr: Float = 7.25f, hopStart: Int = 3, hopLimit: Int = 2) =
        ProtoWriter().message(2) {
            fixed32(1, from); fixed32(2, to); if (channel != 0) int(3, channel)
            message(4) { int(1, 1); bytes(2, text.toByteArray()) }
            fixed32(6, id); fixed32(7, 1_760_000_100L); float(8, snr); int(12, -97); int(9, hopLimit); int(15, hopStart)
        }.toByteArray()

    fun routing(from: Long, to: Long, requestId: Long, error: Int) =
        ProtoWriter().message(2) {
            fixed32(1, from); fixed32(2, to)
            message(4) { int(1, 5); bytes(2, ProtoWriter().int(3, error).toByteArray()); fixed32(6, requestId) }
            fixed32(6, 99)
        }.toByteArray()
}

class MeshProtoTest {
    @Test fun toRadioMessagesFollowTheProtobufs() {
        val want = ProtoMsg.parse(MeshProto.wantConfig(123456))!!
        assertEquals(123456L, want.long(3))
        val hb = ProtoMsg.parse(MeshProto.heartbeat(7))!!.msg(7)!!
        assertEquals(7L, hb.long(1))
        val pkt = ProtoMsg.parse(MeshProto.packet(MeshProto.BROADCAST, 2, 0x55667788L, MeshProto.PORT_TEXT, "Salut".toByteArray(), wantAck = true))!!.msg(1)!!
        assertEquals(MeshProto.BROADCAST, pkt.uint(2))
        assertEquals(2, pkt.int(3))
        assertEquals(0x55667788L, pkt.uint(6))
        assertTrue(pkt.bool(10))
        val data = pkt.msg(4)!!
        assertEquals(1, data.int(1))
        assertEquals("Salut", data.str(2))
    }

    @Test fun adminMessagesTargetOurNodeOnTheAdminPort() {
        val pkt = ProtoMsg.parse(MeshProto.admin(FromRadioSamples.ME, 42) { bool(64, true) })!!.msg(1)!!
        assertEquals(FromRadioSamples.ME, pkt.uint(2))
        val data = pkt.msg(4)!!
        assertEquals(MeshProto.PORT_ADMIN, data.int(1))
        assertTrue(data.bool(3)) // want_response
        assertTrue(ProtoMsg.parse(data.bytes(2)!!)!!.bool(64))
    }

    @Test fun decodesNodeInfoWithPositionAndMetrics() {
        val f = MeshProto.decode(FromRadioSamples.node(FromRadioSamples.ALICE, "Alice Base", "ALI", 48.8566, 2.3522, battery = 87, hops = 2)) as MeshProto.FromRadio.Node
        val n = f.n
        assertEquals(FromRadioSamples.ALICE, n.num)
        assertEquals("Alice Base", n.user!!.longName)
        assertEquals("HELTEC V3", MeshProto.hwModel(n.user!!.hwModel))
        assertEquals(48.8566, n.position!!.lat, 1e-6)
        assertEquals(2.3522, n.position!!.lon, 1e-6)
        assertEquals(120, n.position!!.altitude)
        assertEquals(87, n.metrics!!.battery)
        assertEquals(6.5f, n.snr, 1e-6f)
        assertEquals(2, n.hopsAway)
    }

    @Test fun negativeCoordinatesSurviveSfixed32() {
        val f = MeshProto.decode(FromRadioSamples.node(5L, "Sud-Ouest", "SO", -33.45, -70.66)) as MeshProto.FromRadio.Node
        assertEquals(-33.45, f.n.position!!.lat, 1e-6)
        assertEquals(-70.66, f.n.position!!.lon, 1e-6)
    }

    @Test fun overridingAFieldKeepsTheOthers() {
        // LoRa config as the node sent it, then our change appended: protobuf keeps the last value of a field.
        val raw = ProtoWriter().bool(1, true).int(2, 0).int(7, 3).int(8, 3).int(10, 27).toByteArray()
        val patched = ProtoMsg.parse(ProtoWriter().rawBytes(raw).int(8, 5).toByteArray())!!
        assertEquals(5, patched.int(8))
        assertEquals(3, patched.int(7))
        assertEquals(27, patched.int(10))
    }

    @Test fun streamFramingSkipsDebugTextAndSplitsFrames() {
        val a = MeshProto.wantConfig(1)
        val b = MeshProto.heartbeat(2)
        val stream = "INFO | boot\r\n".toByteArray() + MeshStreamFraming.frame(a) + byteArrayOf(0x94.toByte(), 0x00) + MeshStreamFraming.frame(b)
        val got = ArrayList<ByteArray>()
        val p = MeshStreamFraming.Parser { got += it }
        stream.toList().chunked(3).forEach { c -> p.feed(c.toByteArray(), c.size) }
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshClientTest {
    private fun client(): MeshClient {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(ctx.filesDir, "meshtastic").deleteRecursively()
        return MeshClient(ctx)
    }

    @Test fun handshakeFillsNodesChannelsAndConfigThenConnects() {
        val c = client()
        val link = MeshLink(MeshLink.Kind.Tcp, "192.168.1.50", "192.168.1.50")
        c.setLinkForTest(link, 777_777)
        c.feedForTest(FromRadioSamples.myInfo(FromRadioSamples.ME))
        c.feedForTest(FromRadioSamples.node(FromRadioSamples.ME, "Mon nœud", "MOI"))
        c.feedForTest(FromRadioSamples.node(FromRadioSamples.ALICE, "Alice", "ALI", 48.85, 2.35, 90))
        c.feedForTest(FromRadioSamples.channel(0, 1, "", byteArrayOf(1)))
        c.feedForTest(FromRadioSamples.channel(1, 2, "Amis", ByteArray(32) { it.toByte() }))
        c.feedForTest(FromRadioSamples.lora(3, 0, 3))
        assertTrue(c.conn.value !is MeshConn.Connected)
        c.feedForTest(FromRadioSamples.complete(777_777))
        assertEquals(MeshConn.Connected(link), c.conn.value)
        assertEquals(FromRadioSamples.ME, c.myNum.value)
        assertEquals(2, c.nodes.value.size)
        assertEquals(listOf(0, 1), c.channels.value.map { it.index })
        assertEquals("Clé par défaut", MeshProto.keyKind(c.channels.value[0].psk))
        assertEquals("AES-256", MeshProto.keyKind(c.channels.value[1].psk))
        assertEquals(3, c.config.value.lora!!.int(7))
    }

    @Test fun textsGoToTheirConversation() {
        val c = client()
        c.feedForTest(FromRadioSamples.myInfo(FromRadioSamples.ME))
        c.feedForTest(FromRadioSamples.text(FromRadioSamples.ALICE, MeshProto.BROADCAST, 0, 1001, "Bonjour le maillage"))
        c.feedForTest(FromRadioSamples.text(FromRadioSamples.ALICE, FromRadioSamples.ME, 0, 1002, "Salut toi"))
        c.feedForTest(FromRadioSamples.text(FromRadioSamples.ALICE, MeshProto.BROADCAST, 0, 1001, "Bonjour le maillage")) // duplicate
        val m = c.messages.value
        assertEquals(2, m.size)
        assertEquals("c0", m[0].key)
        assertEquals("d${FromRadioSamples.ALICE}", m[1].key)
        assertEquals(1, m[0].hops)
        assertEquals(7.25f, m[0].snr!!, 1e-6f)
        assertEquals(1, c.unread("c0"))
        c.markRead("c0")
        assertEquals(0, c.unread("c0"))
        // The sender is now known as heard.
        assertNotNull(c.nodes.value[FromRadioSamples.ALICE])
    }

    @Test fun routingAcksUpdateDeliveryStatus() {
        val c = client()
        val dm = ChatMessage(5001, "d${FromRadioSamples.ALICE}", FromRadioSamples.ME, FromRadioSamples.ALICE, 0, "Tu es là ?", 1L, mine = true, status = MsgStatus.Sent)
        val bc = ChatMessage(5002, "c0", FromRadioSamples.ME, MeshProto.BROADCAST, 0, "Tout le monde", 2L, mine = true, status = MsgStatus.Sent)
        val lost = ChatMessage(5003, "d${FromRadioSamples.ALICE}", FromRadioSamples.ME, FromRadioSamples.ALICE, 0, "Perdu", 3L, mine = true, status = MsgStatus.Sent)
        c.setForTest(FromRadioSamples.ME, emptyList(), emptyList(), RadioConfig(), listOf(dm, bc, lost), MeshConn.Disconnected)
        c.feedForTest(FromRadioSamples.routing(FromRadioSamples.ALICE, FromRadioSamples.ME, 5001, 0))
        c.feedForTest(FromRadioSamples.routing(FromRadioSamples.ME, FromRadioSamples.ME, 5002, 0))
        c.feedForTest(FromRadioSamples.routing(FromRadioSamples.ME, FromRadioSamples.ME, 5003, 5))
        val byId = c.messages.value.associateBy { it.id }
        assertEquals(MsgStatus.Delivered, byId[5001]!!.status)
        assertEquals(MsgStatus.Relayed, byId[5002]!!.status)
        assertEquals(MsgStatus.Failed, byId[5003]!!.status)
        assertEquals("Nombre maximal de tentatives atteint", byId[5003]!!.error)
    }

    @Test fun conversationsSurviveARestart() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(ctx.filesDir, "meshtastic").deleteRecursively()
        val c = MeshClient(ctx)
        c.feedForTest(FromRadioSamples.myInfo(FromRadioSamples.ME))
        c.feedForTest(FromRadioSamples.text(FromRadioSamples.ALICE, MeshProto.BROADCAST, 1, 77, "Gardé"))
        Thread.sleep(1500) // the store is written half a second after the last change
        val again = MeshClient(ctx)
        assertEquals("Gardé", again.messages.value.single().text)
        assertEquals("c1", again.messages.value.single().key)
    }
}
