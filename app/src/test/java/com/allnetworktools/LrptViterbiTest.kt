package com.allnetworktools

import com.allnetworktools.data.sdr.FrameSync
import com.allnetworktools.data.sdr.ViterbiK7
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The K = 7 convolutional encoder, written independently from the decoder's tables. */
object ConvTx {
    fun encode(bits: IntArray, startState: Int = 0): IntArray {
        var state = startState
        val out = IntArray(bits.size * 2)
        for ((i, u) in bits.withIndex()) {
            val r = (u shl 6) or state
            out[2 * i] = Integer.bitCount(r and 0x79) and 1
            out[2 * i + 1] = Integer.bitCount(r and 0x5B) and 1
            state = r shr 1
        }
        return out
    }
}

class LrptViterbiTest {
    private fun bitsOf(v: Long, n: Int) = IntArray(n) { ((v shr (n - 1 - it)) and 1L).toInt() }
    private fun num(b: IntArray): Long = b.fold(0L) { a, x -> (a shl 1) or x.toLong() }

    @Test fun codedSyncMarkerMatchesTheKnownConstants() {
        // The coded form of the attached sync marker from a zero state (published LRPT correlator words).
        val coded = ConvTx.encode(bitsOf(0x1ACFFC1DL, 32))
        assertEquals(0x035D49C24FF2686BL, num(coded))
        assertEquals(0xFCA2B63DB00D9794UL.toLong(), num(coded.map { 1 - it }.toIntArray()))
    }

    private fun decode(coded: IntArray, soft: (Int, Random) -> Int, rnd: Random): IntArray {
        val out = ArrayList<Int>()
        val v = ViterbiK7 { out += it }
        var i = 0
        while (i + 1 < coded.size) { v.push(soft(coded[i], rnd), soft(coded[i + 1], rnd)); i += 2 }
        return out.toIntArray()
    }

    @Test fun decodesCleanAndNoisyStreams() {
        val rnd = Random(1)
        val n = 20_000
        val bits = IntArray(n) { rnd.nextInt(2) }
        val coded = ConvTx.encode(bits)
        val clean = decode(coded, { b, _ -> if (b == 1) 100 else -100 }, rnd)
        for (i in 0 until clean.size) assertEquals("bit $i", bits[i], clean[i])
        assertTrue(clean.size >= n - 256)
        // Es/N0 about 2 dB per coded bit: well inside what the code corrects.
        val noisy = decode(coded, { b, r -> ((if (b == 1) 60.0 else -60.0) + r.nextGaussian() * 45).toInt().coerceIn(-127, 127) }, rnd)
        var errors = 0
        for (i in 0 until noisy.size) if (noisy[i] != bits[i]) errors++
        assertTrue("$errors errors in ${noisy.size}", errors < noisy.size / 500)
    }

    @Test fun invertedInputGivesTheComplementedBits() {
        val rnd = Random(2)
        val bits = IntArray(4000) { rnd.nextInt(2) }
        val coded = ConvTx.encode(bits)
        val out = decode(coded, { b, _ -> if (b == 1) -100 else 100 }, rnd)
        var same = 0
        for (i in 600 until out.size) if (out[i] == 1 - bits[i]) same++
        // Both generators have an odd number of taps, so a fully inverted stream decodes to the inverted data.
        assertTrue("$same of ${out.size - 600}", same > (out.size - 600) * 99 / 100)
    }

    @Test fun frameSyncLocksOnTwoMarkersAndCutsFrames() {
        val rnd = Random(3)
        val frames = (0 until 4).map { ByteArray(FrameSync.FRAME_BYTES).also { rnd.nextBytes(it) } }
        val stream = ArrayList<Int>()
        repeat(3000) { stream += rnd.nextInt(2) } // arbitrary lead-in, not a multiple of a byte
        for (f in frames) {
            for (i in 31 downTo 0) stream += ((FrameSync.ASM shr i) and 1L).toInt()
            for (b in f) for (k in 7 downTo 0) stream += (b.toInt() shr k) and 1
        }
        for (i in 31 downTo 0) stream += ((FrameSync.ASM shr i) and 1L).toInt() // marker of the next frame
        val got = ArrayList<ByteArray>()
        val fs = FrameSync { got += it }
        stream.forEach { fs.push(it) }
        // The first frame is spent confirming the lock; the other three are delivered intact.
        assertEquals(3, got.size)
        for (i in 0 until 3) assertTrue(got[i].contentEquals(frames[i + 1]))
    }

    @Test fun frameSyncLosesLockWhenTheMarkersStop() {
        val rnd = Random(4)
        val stream = ArrayList<Int>()
        repeat(2) {
            for (i in 31 downTo 0) stream += ((FrameSync.ASM shr i) and 1L).toInt()
            repeat(FrameSync.FRAME_BYTES * 8) { stream += rnd.nextInt(2) }
        }
        repeat(6 * 8192) { stream += rnd.nextInt(2) }
        val fs = FrameSync { }
        stream.forEach { fs.push(it) }
        assertTrue(!fs.locked)
    }

    /** A marker-like pattern inside the data must not hide the real marker one frame earlier. */
    @Test fun aFalseMarkerInsideTheDataDoesNotDelayTheLock() {
        val rnd = Random(5)
        val stream = ArrayList<Int>()
        fun marker() { for (i in 31 downTo 0) stream += ((FrameSync.ASM shr i) and 1L).toInt() }
        repeat(100) { stream += rnd.nextInt(2) }
        marker()
        repeat(2000) { stream += rnd.nextInt(2) }
        marker() // a lookalike in the middle of frame 0
        repeat(FrameSync.FRAME_BYTES * 8 - 2032) { stream += rnd.nextInt(2) }
        marker()
        repeat(FrameSync.FRAME_BYTES * 8) { stream += rnd.nextInt(2) }
        marker()
        val got = ArrayList<ByteArray>()
        val fs = FrameSync { got += it }
        stream.forEach { fs.push(it) }
        assertEquals(1, got.size) // locked on the second real marker, so the frame after it is delivered
    }
}
