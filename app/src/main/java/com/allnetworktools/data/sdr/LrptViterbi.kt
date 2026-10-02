package com.allnetworktools.data.sdr

/**
 * Soft-decision Viterbi decoder for the CCSDS / NASA K = 7, rate 1/2 convolutional code (generators 171 and 133 octal,
 * no inversion). The encoder register is r = (u << 6) | state with outputs parity(r & 0x79), parity(r & 0x5B), and
 * the next state is r >> 1. Decisions are kept per step and traced back in blocks.
 *
 * Input: one pair of soft values per decoded bit, in −127…127, positive for a coded 1.
 */
class ViterbiK7(private val onBit: (Int) -> Unit) {
    private val pm = IntArray(64)
    private val next = IntArray(64)
    private val steps = BLOCK + DEPTH
    private val decisions = LongArray(steps)
    private var count = 0
    private var started = false

    fun reset() {
        pm.fill(0); count = 0; started = false
    }

    /** Adds one step: [s1] and [s2] are the soft values of the first and second coded bit. */
    fun push(s1: Int, s2: Int) {
        // Branch metric per expected pair (e1, e2): correlation with the received signs.
        val bm0 = -s1 - s2 // 00
        val bm1 = -s1 + s2 // 01
        val bm2 = s1 - s2 // 10
        val bm3 = s1 + s2 // 11
        var dec = 0L
        for (ns in 0 until 64) {
            val u = ns shr 5
            val p0 = (ns shl 1) and 63
            val p1 = p0 or 1
            val m0 = pm[p0] + metric(u, p0, bm0, bm1, bm2, bm3)
            val m1 = pm[p1] + metric(u, p1, bm0, bm1, bm2, bm3)
            if (m1 > m0) { next[ns] = m1; dec = dec or (1L shl ns) } else next[ns] = m0
        }
        decisions[count++] = dec
        var best = Int.MIN_VALUE
        for (i in 0 until 64) { pm[i] = next[i]; if (next[i] > best) best = next[i] }
        for (i in 0 until 64) pm[i] -= best // keep the metrics in range
        if (count == steps) flush()
    }

    private fun metric(u: Int, p: Int, bm0: Int, bm1: Int, bm2: Int, bm3: Int): Int = when (EXPECT[(u shl 6) or p]) {
        0 -> bm0; 1 -> bm1; 2 -> bm2; else -> bm3
    }

    /** Traces back from the best state over all pending steps and releases the oldest BLOCK of them. */
    private fun flush() {
        var best = 0
        for (i in 1 until 64) if (pm[i] > pm[best]) best = i
        var state = best
        val out = IntArray(steps)
        for (t in steps - 1 downTo 0) {
            out[t] = state shr 5
            val b = ((decisions[t] shr state) and 1L).toInt()
            state = ((state shl 1) and 63) or b
        }
        for (t in 0 until BLOCK) onBit(out[t])
        System.arraycopy(decisions, BLOCK, decisions, 0, DEPTH)
        count = DEPTH
    }

    companion object {
        const val BLOCK = 128
        const val DEPTH = 64 // ten constraint lengths

        /** Expected coded pair (first bit in bit 1) for each encoder register value. */
        private val EXPECT = IntArray(128) { r ->
            ((Integer.bitCount(r and 0x79) and 1) shl 1) or (Integer.bitCount(r and 0x5B) and 1)
        }

        fun encodePair(r: Int) = EXPECT[r]
    }
}

/**
 * Finds frames in a stream of decoded bits: the 32-bit attached sync marker 0x1ACFFC1D, confirmed by a second one exactly
 * one frame later, then a flywheel that keeps cutting 1024-byte frames while the marker keeps matching (a few errors
 * allowed). Emits the 1020 bytes following each marker.
 */
class FrameSync(private val onFrame: (ByteArray) -> Unit) {
    private var reg = 0L
    private var pos = 0L // index of the bit just pushed
    private val candidates = ArrayDeque<Long>() // positions of recent marker-like patterns
    var locked = false
        private set
    private var nextAsm = 0L
    private var misses = 0
    private val frame = ByteArray(FRAME_BYTES)
    private var collecting = false
    private var collected = 0 // bits

    var framesEmitted = 0
        private set

    fun reset() {
        reg = 0; pos = 0; candidates.clear(); locked = false; misses = 0; collecting = false; collected = 0
    }

    fun push(bit: Int) {
        pos++
        reg = ((reg shl 1) or bit.toLong()) and 0xFFFFFFFFL
        if (collecting) {
            val i = collected++
            if (bit == 1) frame[i shr 3] = (frame[i shr 3].toInt() or (0x80 shr (i and 7))).toByte()
            if (collected == FRAME_BYTES * 8) {
                collecting = false
                framesEmitted++
                onFrame(frame.copyOf())
            }
        }
        val errors = Integer.bitCount((reg xor ASM).toInt())
        if (locked) {
            if (pos == nextAsm) {
                if (errors <= TOLERANCE) misses = 0 else if (++misses > MAX_MISSES) { locked = false; candidates.clear(); collecting = false; return }
                nextAsm += FRAME_BITS
                startFrame()
            }
        } else if (pos >= 32 && errors <= SEARCH_TOLERANCE) {
            // A random pattern inside the data matches about once in 10⁵ bits, so keep every candidate of the last frame
            // instead of only the latest: one false match must not hide the real marker.
            while (candidates.isNotEmpty() && candidates.first() < pos - FRAME_BITS) candidates.removeFirst()
            if (candidates.contains(pos - FRAME_BITS)) {
                locked = true; misses = 0; candidates.clear()
                nextAsm = pos + FRAME_BITS
                startFrame()
            } else {
                candidates.addLast(pos)
            }
        }
    }

    private fun startFrame() {
        frame.fill(0)
        collected = 0
        collecting = true
    }

    companion object {
        const val ASM = 0x1ACFFC1DL
        const val FRAME_BYTES = 1020 // after the marker
        const val FRAME_BITS = 1024L * 8
        private const val SEARCH_TOLERANCE = 4
        private const val TOLERANCE = 10
        private const val MAX_MISSES = 4
    }
}
