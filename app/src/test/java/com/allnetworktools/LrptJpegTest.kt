package com.allnetworktools

import com.allnetworktools.data.sdr.LrptJpeg
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bit writer and block encoder, written from the JPEG specification independently of the decoder. */
object JpegTx {
    class Bits {
        val bytes = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun put(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                cur = (cur shl 1) or ((value shr i) and 1); n++
                if (n == 8) { bytes.write(cur); cur = 0; n = 0 }
            }
        }
        fun finish(): ByteArray { while (n != 0) put(1, 1); return bytes.toByteArray() }
    }

    private fun category(v: Int): Int { var a = abs(v); var c = 0; while (a != 0) { c++; a = a shr 1 }; return c }
    private fun bitsOf(v: Int, c: Int) = if (v >= 0) v else v + (1 shl c) - 1

    /** Encodes blocks of 64 pixels with quality [q]; returns the bytes and the pixels a decoder should reconstruct. */
    fun encode(blocks: List<IntArray>, q: Int): Pair<ByteArray, List<ByteArray>> {
        val qt = LrptJpeg.quantTable(q)
        val w = Bits()
        var pred = 0
        val recon = ArrayList<ByteArray>()
        for (b in blocks) {
            val f = LrptJpeg.fdct(b)
            val qc = IntArray(64) { (f[it] / qt[it]).roundToInt() }
            val dcDiff = qc[0] - pred
            pred = qc[0]
            val c = category(dcDiff)
            w.put(LrptJpeg.dc.codeOf[c], LrptJpeg.dc.lenOf[c])
            if (c > 0) w.put(bitsOf(dcDiff, c), c)
            var run = 0
            var last = 63
            while (last > 0 && qc[LrptJpeg.zigzag[last]] == 0) last--
            for (k in 1..last) {
                val v = qc[LrptJpeg.zigzag[k]]
                if (v == 0) { run++; continue }
                while (run > 15) { w.put(LrptJpeg.ac.codeOf[0xF0], LrptJpeg.ac.lenOf[0xF0]); run -= 16 }
                val s = category(v)
                val sym = (run shl 4) or s
                w.put(LrptJpeg.ac.codeOf[sym], LrptJpeg.ac.lenOf[sym])
                w.put(bitsOf(v, s), s)
                run = 0
            }
            if (last < 63) w.put(LrptJpeg.ac.codeOf[0x00], LrptJpeg.ac.lenOf[0x00])
            recon += LrptJpeg.idct(DoubleArray(64) { qc[it].toDouble() * qt[it] })
        }
        return w.finish() to recon
    }

    /** A smooth test picture with some detail: block (bx, by) of the image. */
    fun picture(bx: Int, by: Int): IntArray = IntArray(64) { i ->
        val x = bx * 8 + i % 8
        val y = by * 8 + i / 8
        (128 + 70 * Math.sin(x / 23.0) * Math.cos(y / 17.0) + 30 * Math.sin((x + y) / 5.0) + (if ((x / 16 + y / 16) % 2 == 0) 20 else -20)).roundToInt().coerceIn(0, 255)
    }
}

class LrptJpegTest {
    @Test fun huffmanTablesAreCanonical() {
        // Well-known codes of the standard luminance tables.
        assertEquals(4, LrptJpeg.ac.lenOf[0x00]); assertEquals(0b1010, LrptJpeg.ac.codeOf[0x00]) // end of block
        assertEquals(11, LrptJpeg.ac.lenOf[0xF0]); assertEquals(0b11111111001, LrptJpeg.ac.codeOf[0xF0]) // 16 zeros
        assertEquals(2, LrptJpeg.ac.lenOf[0x01]); assertEquals(0b00, LrptJpeg.ac.codeOf[0x01])
        assertEquals(16, LrptJpeg.ac.lenOf[0xFA]); assertEquals(0xFFFE, LrptJpeg.ac.codeOf[0xFA])
        assertEquals(2, LrptJpeg.dc.lenOf[0]); assertEquals(0b00, LrptJpeg.dc.codeOf[0])
        assertEquals(9, LrptJpeg.dc.lenOf[11]); assertEquals(0b111111110, LrptJpeg.dc.codeOf[11])
        // 162 AC symbols, each with a distinct code.
        assertEquals(162, (0 until 256).count { LrptJpeg.ac.lenOf[it] > 0 })
    }

    @Test fun quantisationFollowsTheLibjpegScaling() {
        assertEquals(16, LrptJpeg.quantTable(50)[0])
        assertEquals(8, LrptJpeg.quantTable(75)[0])
        assertEquals(1, LrptJpeg.quantTable(100)[0])
        assertEquals(255, LrptJpeg.quantTable(1)[63]) // clamped
    }

    @Test fun idctInvertsTheDct() {
        val px = JpegTx.picture(3, 2)
        val back = LrptJpeg.idct(LrptJpeg.fdct(px))
        for (i in 0 until 64) assertEquals(px[i], back[i].toInt() and 0xFF)
    }

    @Test fun decodesWhatTheReferenceEncoderWrote() {
        val blocks = (0 until 14).map { JpegTx.picture(it, 5) }
        for (q in listOf(40, 75, 95)) {
            val (bytes, recon) = JpegTx.encode(blocks, q)
            val tail = IntArray(1)
            val got = LrptJpeg.decodeBlocks(bytes, 0, bytes.size, 14, LrptJpeg.quantTable(q), tail)
            assertNotNull("q=$q", got)
            for (b in 0 until 14) for (i in 0 until 64) assertEquals("q=$q block $b px $i", recon[b][i].toInt() and 0xFF, got!![b][i].toInt() and 0xFF)
            assertTrue("padding of ${tail[0]} bits", tail[0] in 0..7)
            // And it still looks like the picture (quantisation error only).
            var err = 0.0
            for (b in 0 until 14) for (i in 0 until 64) err += abs((got!![b][i].toInt() and 0xFF) - blocks[b][i])
            assertTrue("q=$q mean error ${err / (14 * 64)}", err / (14 * 64) < if (q >= 75) 6.0 else 14.0)
        }
    }

    @Test fun rejectsTruncatedOrWrongData() {
        val blocks = (0 until 14).map { JpegTx.picture(it, 1) }
        val (bytes, _) = JpegTx.encode(blocks, 80)
        assertNull(LrptJpeg.decodeBlocks(bytes, 0, bytes.size / 2, 14, LrptJpeg.quantTable(80)))
        // Starting in the middle of the data: it must not decode into 14 clean blocks with a short tail.
        val tail = IntArray(1)
        val shifted = LrptJpeg.decodeBlocks(bytes, 3, bytes.size, 14, LrptJpeg.quantTable(80), tail)
        assertTrue(shifted == null || tail[0] > 7 || shifted.zip(blocks).any { (a, b) -> (0 until 64).any { (a[it].toInt() and 0xFF) != b[it] } })
    }
}
