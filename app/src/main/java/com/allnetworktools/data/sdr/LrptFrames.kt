package com.allnetworktools.data.sdr

/** CCSDS pseudo-randomiser: x^8 + x^7 + x^5 + x^3 + 1, all ones, period 255 bits (starts FF 48 0E C0 9A 0D 70 BC). */
object LrptPn {
    val sequence = ByteArray(255).also { out ->
        val s = IntArray(8) { 1 }
        for (byte in 0 until 255) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or s[7]
                val fb = s[7] xor s[4] xor s[2] xor s[0]
                for (i in 7 downTo 1) s[i] = s[i - 1]
                s[0] = fb
            }
            out[byte] = v.toByte()
        }
    }

    fun apply(frame: ByteArray) {
        for (i in frame.indices) frame[i] = (frame[i].toInt() xor sequence[i % 255].toInt()).toByte()
    }
}

/**
 * Turns the 1020 bytes that follow a sync marker into the 892 data bytes of a virtual channel data unit: removes the
 * randomisation, then corrects the four interleaved Reed-Solomon (255, 223) codewords. Whether the symbols are in the
 * dual or the conventional basis is found from the first frames that decode.
 */
class FrameDecoder {
    /** Null until a frame has decoded, then whichever basis worked. */
    var dualBasis: Boolean? = null
        private set
    var corrected = 0L
        private set

    class Result(val data: ByteArray, val corrected: Int)

    fun decode(frame: ByteArray): Result? {
        val f = frame.copyOf()
        LrptPn.apply(f)
        val modes = when (dualBasis) { true -> listOf(true); false -> listOf(false); null -> listOf(true, false) }
        for (dual in modes) {
            val cws = Array(4) { j -> ByteArray(Rs255.N) { i -> f[4 * i + j] } }
            var total = 0
            var ok = true
            for (cw in cws) {
                val r = Rs255.decode(cw, dual)
                if (r < 0) { ok = false; break }
                total += r
            }
            if (!ok) continue
            dualBasis = dual
            corrected += total
            val data = ByteArray(4 * Rs255.K)
            for (j in 0 until 4) for (i in 0 until Rs255.K) data[4 * i + j] = cws[j][i]
            return Result(data, total)
        }
        return null
    }

    companion object {
        const val DATA_BYTES = 4 * Rs255.K // 892
    }
}

/** A CCSDS source packet cut out of the virtual channel stream. */
class LrptPacket(val apid: Int, val seq: Int, val field: ByteArray)

/**
 * Reassembles source packets from the M_PDU packet zone of successive frames. Each virtual channel counts its frames, a
 * gap throws away the packet in progress. [zoneStart] is where the packet zone begins in the 892 data bytes, 10 when the
 * frame carries the 2-byte insert zone (header 6 + insert zone 2 + M_PDU header 2).
 */
class PacketAssembler(private val zoneStart: Int, private val onPacket: (LrptPacket) -> Unit) {
    private class Channel {
        val buf = ByteArray(8192)
        var len = 0
        var synced = false
        var lastCounter = -1
    }

    private val channels = HashMap<Int, Channel>()

    var framesSeen = 0
        private set
    var gaps = 0
        private set

    fun frame(data: ByteArray) {
        framesSeen++
        val vcid = data[1].toInt() and 0x3F
        val counter = ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val ch = channels.getOrPut(vcid) { Channel() }
        if (ch.lastCounter >= 0 && counter != (ch.lastCounter + 1) and 0xFFFFFF) { ch.len = 0; ch.synced = false; gaps++ }
        ch.lastCounter = counter
        val hdr = zoneStart - 2
        val pointer = ((data[hdr].toInt() and 7) shl 8) or (data[hdr + 1].toInt() and 0xFF)
        val zoneLen = data.size - zoneStart
        if (pointer == 0x7FF) {
            if (ch.synced) append(ch, data, zoneStart, zoneLen)
        } else {
            if (pointer > zoneLen) { ch.len = 0; ch.synced = false; return }
            if (ch.synced) { append(ch, data, zoneStart, pointer); drain(ch) }
            ch.len = 0
            ch.synced = true
            append(ch, data, zoneStart + pointer, zoneLen - pointer)
        }
        drain(ch)
    }

    private fun append(ch: Channel, src: ByteArray, from: Int, n: Int) {
        if (ch.len + n > ch.buf.size) { ch.len = 0; ch.synced = false; return }
        System.arraycopy(src, from, ch.buf, ch.len, n)
        ch.len += n
    }

    private fun drain(ch: Channel) {
        var pos = 0
        while (ch.len - pos >= 6) {
            val total = (((ch.buf[pos + 4].toInt() and 0xFF) shl 8) or (ch.buf[pos + 5].toInt() and 0xFF)) + 7
            if (total > 2048) { ch.len = 0; ch.synced = false; return }
            if (ch.len - pos < total) break
            val apid = ((ch.buf[pos].toInt() and 7) shl 8) or (ch.buf[pos + 1].toInt() and 0xFF)
            val seq = ((ch.buf[pos + 2].toInt() and 0x3F) shl 8) or (ch.buf[pos + 3].toInt() and 0xFF)
            if (apid != IDLE_APID) onPacket(LrptPacket(apid, seq, ch.buf.copyOfRange(pos + 6, pos + total)))
            pos += total
        }
        if (pos > 0) { System.arraycopy(ch.buf, pos, ch.buf, 0, ch.len - pos); ch.len -= pos }
    }

    companion object {
        const val IDLE_APID = 0x7FF

        /** Does the first packet header of this frame look like a Meteor image packet (APID 64…69) or an idle one? */
        fun looksRight(data: ByteArray, zoneStart: Int): Boolean {
            val hdr = zoneStart - 2
            val pointer = ((data[hdr].toInt() and 7) shl 8) or (data[hdr + 1].toInt() and 0xFF)
            if (pointer == 0x7FF || zoneStart + pointer + 6 > data.size) return false
            val b0 = data[zoneStart + pointer].toInt() and 0xFF
            val b1 = data[zoneStart + pointer + 1].toInt() and 0xFF
            return (b0 == 0x00 && b1 in 0x40..0x45) || (b0 == 0x07 && b1 == 0xFF)
        }
    }
}

/** One image packet: 14 consecutive 8×8 blocks of a strip. */
class LrptSegment(
    val apid: Int,
    val seq: Int,
    val mcuId: Int,
    val quality: Int,
    val blocks: List<ByteArray>,
    val day: Int,
    val msOfDay: Long,
)

/**
 * Decodes image packets. The packet data field starts with a time stamp (day, millisecond of day, microsecond: 8 bytes),
 * the number of the first MCU, a few bytes of parameters ending with the quality factor, then the Huffman-coded blocks.
 * Where exactly the coded data starts is checked rather than assumed: the right offset is the one for which the bits
 * decode into 14 valid blocks and end within a byte of the packet's end.
 */
class SegmentDecoder {
    /** Offset of the coded data in the data field once settled, or null while it is being worked out. */
    var layout: Int? = null
        private set
    private val votes = IntArray(CANDIDATES.size)
    var good = 0
        private set
    var bad = 0
        private set

    fun decode(p: LrptPacket): LrptSegment? {
        val l = layout
        if (l != null) {
            val s = parse(p, l)
            if (s != null) good++ else bad++
            return s
        }
        for ((i, cand) in CANDIDATES.withIndex()) {
            val s = parse(p, cand) ?: continue
            votes[i]++
            if (votes[i] >= 3 && votes[i] >= 2 * (votes.sum() - votes[i])) { layout = cand }
            good++
            return s
        }
        bad++
        return null
    }

    private fun parse(p: LrptPacket, dataStart: Int): LrptSegment? {
        val f = p.field
        if (f.size < dataStart + 4) return null
        val mcuId = f[8].toInt() and 0xFF
        val q = f[dataStart - 1].toInt() and 0xFF
        if (mcuId % 14 != 0 || mcuId > 14 * 13 || q !in 1..100) return null
        val tail = IntArray(1)
        val blocks = LrptJpeg.decodeBlocks(f, dataStart, f.size, 14, LrptJpeg.quantTable(q), tail) ?: return null
        if (tail[0] > 15) return null
        val day = ((f[0].toInt() and 0xFF) shl 8) or (f[1].toInt() and 0xFF)
        val ms = ((f[2].toLong() and 0xFF) shl 24) or ((f[3].toLong() and 0xFF) shl 16) or ((f[4].toLong() and 0xFF) shl 8) or (f[5].toLong() and 0xFF)
        return LrptSegment(p.apid, p.seq, mcuId, q, blocks, day, ms)
    }

    companion object {
        /** Candidate offsets of the coded data in the data field; the first is the one expected. */
        val CANDIDATES = intArrayOf(12, 10, 11, 13, 9)
    }
}

/**
 * One channel of the image, kept at half resolution (784 × 4 pixels per strip of 8 lines, from 2×2 averages) so a whole
 * pass fits comfortably in memory. Strips are placed by packet sequence number: 14 packets make a strip.
 */
class ChannelImage(val apid: Int) {
    val strips = ArrayList<ByteArray?>()
    private var origin = Long.MIN_VALUE
    private var lastSeq = -1
    private var wraps = 0
    var segments = 0
        private set

    /** Places [s]; returns the strip index it landed in, or null when it could not be placed. */
    fun add(s: LrptSegment): Int? {
        if (lastSeq >= 0 && s.seq < lastSeq - 8192) wraps++
        lastSeq = s.seq
        val u = s.seq.toLong() + wraps * 16384L - s.mcuId / 14
        if (origin == Long.MIN_VALUE) origin = u
        val row = Math.floorDiv(u - origin + 7, 14L).toInt()
        if (row < 0 || row > MAX_STRIPS) return null
        while (strips.size <= row) strips.add(null)
        val strip = strips[row] ?: ByteArray(WIDTH * 4).also { strips[row] = it }
        for ((j, block) in s.blocks.withIndex()) {
            val x0 = (s.mcuId + j) * 4
            if (x0 + 4 > WIDTH) break
            for (y in 0 until 4) for (x in 0 until 4) {
                val a = block[(2 * y) * 8 + 2 * x].toInt() and 0xFF
                val b = block[(2 * y) * 8 + 2 * x + 1].toInt() and 0xFF
                val c = block[(2 * y + 1) * 8 + 2 * x].toInt() and 0xFF
                val d = block[(2 * y + 1) * 8 + 2 * x + 1].toInt() and 0xFF
                strip[y * WIDTH + x0 + x] = ((a + b + c + d + 2) / 4).toByte()
            }
        }
        segments++
        return row
    }

    companion object {
        const val WIDTH = 784
        const val MAX_STRIPS = 2400
    }
}
