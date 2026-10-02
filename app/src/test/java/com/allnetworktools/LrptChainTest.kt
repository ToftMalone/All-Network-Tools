package com.allnetworktools

import com.allnetworktools.data.sdr.ChannelImage
import com.allnetworktools.data.sdr.FrameDecoder
import com.allnetworktools.data.sdr.FrameSync
import com.allnetworktools.data.sdr.LrptJpeg
import com.allnetworktools.data.sdr.LrptLink
import com.allnetworktools.data.sdr.LrptPacket
import com.allnetworktools.data.sdr.LrptPn
import com.allnetworktools.data.sdr.LrptReceiver
import com.allnetworktools.data.sdr.PacketAssembler
import com.allnetworktools.data.sdr.QpskDemodulator
import com.allnetworktools.data.sdr.Rs255
import com.allnetworktools.data.sdr.SegmentDecoder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transmitter side of the Meteor-M LRPT link, written from the format description, independent of the receiver code. */
object LrptTx {
    /** A smooth, compressible picture so packets stay short and a short test carries several strips. */
    fun picture(bx: Int, by: Int): IntArray = IntArray(64) { i ->
        val x = bx * 8 + i % 8
        val y = by * 8 + i / 8
        (120 + 60 * Math.sin(x / 40.0) * Math.cos(y / 30.0) + (if ((x / 64 + y / 64) % 2 == 0) 25 else -25)).toInt().coerceIn(0, 255)
    }

    fun primaryHeader(apid: Int, seq: Int, dataLen: Int) = byteArrayOf(
        ((apid shr 8) and 7).toByte(), apid.toByte(), (0xC0 or (seq shr 8)).toByte(), seq.toByte(), ((dataLen - 1) shr 8).toByte(), (dataLen - 1).toByte(),
    )

    /** One image packet: time stamp, first MCU, two parameter bytes, quality, then the coded blocks. */
    fun segmentPacket(apid: Int, seq: Int, mcuId: Int, q: Int, blocks: List<IntArray>, ms: Long): ByteArray {
        val coded = JpegTx.encode(blocks, q).first
        val field = ByteArray(12 + coded.size)
        field[0] = 0x2D; field[1] = 0x10 // day
        for (k in 0 until 4) field[2 + k] = (ms shr (8 * (3 - k))).toByte()
        field[8] = mcuId.toByte(); field[11] = q.toByte()
        coded.copyInto(field, 12)
        return primaryHeader(apid, seq, field.size) + field
    }

    /** The 14 packets of strip [strip] (8 lines of 1568 pixels) for [apid], numbered from [firstSeq]. */
    fun stripPackets(apid: Int, firstSeq: Int, strip: Int, q: Int): List<ByteArray> = (0 until 14).map { j ->
        segmentPacket(apid, (firstSeq + strip * 14 + j) and 0x3FFF, j * 14, q, (0 until 14).map { picture(j * 14 + it, strip) }, 40_000_000L + strip * 1333L)
    }

    fun idlePacket(total: Int): ByteArray = primaryHeader(0x7FF, 0, total - 6) + ByteArray(total - 6)

    /** Packs [packets] into 892-byte virtual channel data units: header, insert zone, M_PDU header, packet zone. */
    fun dataUnits(packets: List<ByteArray>, vcid: Int, startCounter: Int, zoneStart: Int = 10): List<ByteArray> {
        val zoneLen = 892 - zoneStart
        val stream = ArrayList<Byte>()
        val starts = ArrayList<Int>() // stream offsets of packet headers
        for (p in packets) { starts += stream.size; stream += p.toList() }
        var counter = startCounter
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < stream.size) {
            val take = minOf(zoneLen, stream.size - pos)
            val frame = ByteArray(892)
            frame[0] = (0x40 or (0x1F shr 2)).toByte(); frame[1] = (((0x1F and 3) shl 6) or vcid).toByte()
            frame[2] = (counter shr 16).toByte(); frame[3] = (counter shr 8).toByte(); frame[4] = counter.toByte()
            counter = (counter + 1) and 0xFFFFFF
            val first = starts.firstOrNull { it >= pos && it < pos + zoneLen }
            val pointer = when {
                first != null -> first - pos
                else -> 0x7FF
            }
            frame[zoneStart - 2] = ((pointer shr 8) and 7).toByte(); frame[zoneStart - 1] = pointer.toByte()
            for (i in 0 until take) frame[zoneStart + i] = stream[pos + i]
            if (take < zoneLen) { // fill the rest with an idle packet (at least 7 bytes)
                val rest = zoneLen - take
                require(rest >= 7) { "rest $rest" }
                idlePacket(rest).copyInto(frame, zoneStart + take)
                if (first == null) { frame[zoneStart - 2] = ((take shr 8) and 7).toByte(); frame[zoneStart - 1] = take.toByte() }
            }
            out += frame
            pos += take
        }
        return out
    }

    /** RS(255,223) ×4 in the dual basis, interleaved, randomised: the 1020 bytes that follow the marker. */
    fun transmissionFrame(data892: ByteArray, dual: Boolean = true): ByteArray {
        val words = Array(4) { j ->
            val d = ByteArray(Rs255.K) { i -> data892[4 * i + j] }
            d + Rs255.encode(d, dual)
        }
        val f = ByteArray(1020) { words[it % 4][it / 4] }
        LrptPn.apply(f)
        return f
    }

    /** Marker + frame bits for each frame, as one bit stream. */
    fun bitStream(frames: List<ByteArray>): IntArray {
        val bits = ArrayList<Int>()
        for (f in frames) {
            for (i in 31 downTo 0) bits += ((FrameSync.ASM shr i) and 1L).toInt()
            for (b in f) for (k in 7 downTo 0) bits += (b.toInt() shr k) and 1
        }
        return bits.toIntArray()
    }
}

class LrptChainTest {
    private fun image(stripCount: Int, apid: Int = 65, q: Int = 85) = (0 until stripCount).flatMap { LrptTx.stripPackets(apid, 100, it, q) }

    @Test fun transmissionFramesSurviveErrorsAndDecode() {
        val packets = image(3)
        val units = LrptTx.dataUnits(packets, 5, 1000)
        val tx = LrptTx.transmissionFrame(units[0])
        val rnd = Random(1)
        val bad = tx.copyOf()
        for (p in (0 until 1020).shuffled(rnd).take(40)) bad[p] = (bad[p].toInt() xor (1 + rnd.nextInt(255))).toByte() // ~10 per codeword
        val dec = FrameDecoder()
        val r = dec.decode(bad)
        assertNotNull(r)
        assertArrayEquals(units[0], r!!.data)
        assertTrue(dec.dualBasis == true)
        assertTrue(r.corrected in 30..40)
        // A frame in the conventional basis is recognised as such by a fresh decoder.
        val conv = FrameDecoder()
        assertNotNull(conv.decode(LrptTx.transmissionFrame(units[1], dual = false)))
        assertTrue(conv.dualBasis == false)
    }

    @Test fun packetsAreReassembledAcrossFramesAndGapsAreHandled() {
        val packets = image(4)
        val units = LrptTx.dataUnits(packets, 5, 70, zoneStart = 10)
        val got = ArrayList<LrptPacket>()
        val asm = PacketAssembler(10) { got += it }
        units.forEach { asm.frame(it) }
        // The last unit is padded with idle packets, which are dropped.
        assertEquals(packets.size, got.size)
        for ((i, p) in packets.withIndex()) {
            assertEquals(65, got[i].apid)
            assertArrayEquals(p.copyOfRange(6, p.size), got[i].field)
        }
        // A missing frame breaks the packet in progress but the stream picks up again at the next packet start.
        val got2 = ArrayList<LrptPacket>()
        val asm2 = PacketAssembler(10) { got2 += it }
        units.forEachIndexed { i, u -> if (i != 2) asm2.frame(u) }
        assertEquals(1, asm2.gaps)
        // One frame carries about packets/frames packets; losing it costs those (plus the two it shares with its neighbours).
        val perFrame = (packets.size + units.size - 1) / units.size + 2
        assertTrue("${got2.size} of ${packets.size} (frame holds ~$perFrame)", got2.size in (packets.size - perFrame - 2) until packets.size)
        for (p in got2) assertTrue(packets.any { it.copyOfRange(6, it.size).contentEquals(p.field) })
    }

    @Test fun segmentLayoutIsFoundAndImageStripsAreAssembled() {
        val packets = LrptTx.stripPackets(66, 16370, 0, 80) + LrptTx.stripPackets(66, 16370, 1, 80) + LrptTx.stripPackets(66, 16370, 2, 80)
        val dec = SegmentDecoder()
        val img = ChannelImage(66)
        var rows = ArrayList<Int>()
        for (pk in packets) {
            val field = pk.copyOfRange(6, pk.size)
            val apid = ((pk[0].toInt() and 7) shl 8) or (pk[1].toInt() and 0xFF)
            val seq = ((pk[2].toInt() and 0x3F) shl 8) or (pk[3].toInt() and 0xFF)
            val s = dec.decode(LrptPacket(apid, seq, field)) ?: continue
            img.add(s)?.let { rows += it }
        }
        assertEquals(12, dec.layout) // settled
        assertEquals(42, dec.good)
        assertEquals(0, dec.bad)
        // The sequence number wraps (16370 + 3 × 14 > 16383) without disturbing the strips.
        assertEquals(listOf(0, 1, 2), rows.distinct())
        assertEquals(3, img.strips.count { it != null })
        // A strip looks like the picture it was made from (half resolution).
        val strip = img.strips[1]!!
        var err = 0.0
        for (x in 0 until ChannelImage.WIDTH step 7) for (y in 0 until 4) {
            val bx = x / 4
            val ref = LrptTx.picture(bx, 1)
            val a = ref[(2 * y) * 8 + 2 * (x % 4)] // the same 2×2 average, approximately
            err += Math.abs((strip[y * ChannelImage.WIDTH + x].toInt() and 0xFF) - a)
        }
        assertTrue("mean error ${err / (ChannelImage.WIDTH / 7 * 4)}", err / (ChannelImage.WIDTH / 7 * 4) < 12)
    }

    @Test fun anUnexpectedOffsetOfTheCodedDataIsDetected() {
        // Pretend the coded data starts two bytes earlier (offset 10): the decoder must find it, not assume 12.
        val packets = (0 until 14).map { j ->
            val coded = JpegTx.encode((0 until 14).map { LrptTx.picture(j * 14 + it, 0) }, 70).first
            val field = ByteArray(10 + coded.size)
            field[8] = (j * 14).toByte(); field[9] = 70
            coded.copyInto(field, 10)
            LrptTx.primaryHeader(64, 500 + j, field.size) + field
        }
        val dec = SegmentDecoder()
        for (pk in packets) dec.decode(LrptPacket(64, 0, pk.copyOfRange(6, pk.size)))
        assertEquals(10, dec.layout)
    }

    @Test fun symbolLevelChainRecoversTheFramesWhateverThePhase() {
        val packets = image(5)
        val units = LrptTx.dataUnits(packets, 5, 1)
        val frames = units.map { LrptTx.transmissionFrame(it) }
        val coded = ConvTx.encode(LrptTx.bitStream(frames))
        for ((swap, rot) in listOf(false to 0, false to 1, false to 2, true to 3, true to 0)) {
            val got = ArrayList<ByteArray>()
            val link = LrptLink { got += it }
            val rnd = Random(rot + (if (swap) 10L else 0L))
            var i = 0
            while (i + 1 < coded.size) {
                // Soft symbols with noise, then the phase ambiguity: a quarter-turn rotation and possibly an I/Q swap.
                var a = (if (coded[i] == 1) 60 else -60) + (rnd.nextGaussian() * 25).toInt()
                var b = (if (coded[i + 1] == 1) 60 else -60) + (rnd.nextGaussian() * 25).toInt()
                if (swap) { val t = a; a = b; b = t }
                repeat((4 - rot) % 4) { val na = -b; b = a; a = na } // the inverse of the receiver's rotation
                link.symbol(a.coerceIn(-127, 127), b.coerceIn(-127, 127))
                i += 2
            }
            assertTrue("swap=$swap rot=$rot: locked=${link.locked}", link.locked)
            assertTrue("swap=$swap rot=$rot: ${got.size} frames", got.size >= frames.size - 3)
            // Every delivered frame is one of those sent.
            for (g in got) assertTrue(frames.any { it.contentEquals(g) })
        }
    }
}
