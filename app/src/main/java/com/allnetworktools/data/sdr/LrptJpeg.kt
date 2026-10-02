package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** MSB-first reader over a byte range. */
class BitReader(private val data: ByteArray, from: Int, private val end: Int) {
    private var pos = from * 8
    private val endBit = end * 8

    /** The next bit, or −1 past the end. */
    fun bit(): Int {
        if (pos >= endBit) return -1
        val b = (data[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
        pos++
        return b
    }

    /** [n] bits as an unsigned value, or −1 if the range ends first. */
    fun bits(n: Int): Int {
        var v = 0
        repeat(n) { val b = bit(); if (b < 0) return -1; v = (v shl 1) or b }
        return v
    }

    val bitsLeft: Int get() = endBit - pos
}

/** A JPEG Huffman table (Annex C), usable both ways: [codeOf] and [lenOf] to encode, [decode] to decode. */
class HuffTable(counts: IntArray, private val values: IntArray) {
    val codeOf = IntArray(256)
    val lenOf = IntArray(256)
    private val minCode = IntArray(17)
    private val maxCode = IntArray(17) { -1 }
    private val valPtr = IntArray(17)

    init {
        var code = 0
        var k = 0
        for (len in 1..16) {
            valPtr[len] = k
            minCode[len] = code
            repeat(counts[len - 1]) {
                codeOf[values[k]] = code; lenOf[values[k]] = len
                code++; k++
            }
            maxCode[len] = if (counts[len - 1] > 0) code - 1 else -1
            code = code shl 1
        }
    }

    /** The next symbol, or −1 on a code that does not exist (or the end of the data). */
    fun decode(r: BitReader): Int {
        var code = 0
        for (len in 1..16) {
            val b = r.bit()
            if (b < 0) return -1
            code = (code shl 1) or b
            if (maxCode[len] >= 0 && code <= maxCode[len] && code >= minCode[len]) return values[valPtr[len] + code - minCode[len]]
        }
        return -1
    }
}

/** The JPEG tables LRPT uses: Annex K luminance Huffman tables and quantisation matrix, with the usual quality scaling. */
object LrptJpeg {
    val dc = HuffTable(
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
        IntArray(12) { it },
    )

    val ac = HuffTable(
        intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d),
        intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
            0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
            0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
            0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
            0xF9, 0xFA,
        ),
    )

    /** Zig-zag scan order: position in the scan → index in the 8×8 block (row-major). */
    val zigzag = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    private val standardQ = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** The quantisation matrix for quality [q] (1…100), scaled the way libjpeg does. */
    fun quantTable(q: Int): IntArray {
        val quality = q.coerceIn(1, 100)
        val scale = if (quality < 50) 5000 / quality else 200 - 2 * quality
        return IntArray(64) { ((standardQ[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    // Row-major basis: a[x][u] = C(u)/2 · cos((2x+1)uπ/16).
    private val basis = Array(8) { x -> DoubleArray(8) { u -> (if (u == 0) 1 / sqrt(2.0) else 1.0) / 2 * cos((2 * x + 1) * u * PI / 16) } }

    /** Inverse DCT of dequantised [coef] (row-major), level-shifted to 0…255. */
    fun idct(coef: DoubleArray): ByteArray {
        val tmp = DoubleArray(64)
        for (x in 0 until 8) for (v in 0 until 8) {
            var s = 0.0
            for (u in 0 until 8) s += basis[x][u] * coef[v * 8 + u] // along rows: coef index = v*8 + u
            tmp[v * 8 + x] = s
        }
        val out = ByteArray(64)
        for (x in 0 until 8) for (y in 0 until 8) {
            var s = 0.0
            for (v in 0 until 8) s += basis[y][v] * tmp[v * 8 + x]
            out[y * 8 + x] = (s + 128).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Forward DCT of 64 level-shifted samples (row-major), the reference for the tests. */
    fun fdct(pixels: IntArray): DoubleArray {
        val tmp = DoubleArray(64)
        for (y in 0 until 8) for (u in 0 until 8) {
            var s = 0.0
            for (x in 0 until 8) s += basis[x][u] * (pixels[y * 8 + x] - 128)
            tmp[y * 8 + u] = s
        }
        val out = DoubleArray(64)
        for (u in 0 until 8) for (v in 0 until 8) {
            var s = 0.0
            for (y in 0 until 8) s += basis[y][v] * tmp[y * 8 + u]
            out[v * 8 + u] = s
        }
        return out
    }

    /**
     * Decodes [mcus] consecutive 8×8 blocks from [data] (bits of [from] until [end]) with quantisation matrix [qt]. The DC
     * value is coded as a difference from the previous block, starting from 0. Returns null when the bits do not form
     * that many valid blocks; [tailBits] receives the number of unused bits after the last block.
     */
    fun decodeBlocks(data: ByteArray, from: Int, end: Int, mcus: Int, qt: IntArray, tailBits: IntArray? = null): List<ByteArray>? {
        val r = BitReader(data, from, end)
        val out = ArrayList<ByteArray>(mcus)
        var dcPred = 0
        val coef = DoubleArray(64)
        repeat(mcus) {
            coef.fill(0.0)
            val cat = dc.decode(r)
            if (cat < 0 || cat > 11) return null
            var diff = 0
            if (cat > 0) {
                val v = r.bits(cat)
                if (v < 0) return null
                diff = if (v < (1 shl (cat - 1))) v - (1 shl cat) + 1 else v
            }
            dcPred += diff
            coef[0] = dcPred.toDouble() * qt[0]
            var k = 1
            while (k < 64) {
                val rs = ac.decode(r)
                if (rs < 0) return null
                val run = rs shr 4
                val size = rs and 15
                if (size == 0) {
                    if (run == 15) { k += 16; continue }
                    break // end of block
                }
                k += run
                if (k > 63) return null
                val v = r.bits(size)
                if (v < 0) return null
                val value = if (v < (1 shl (size - 1))) v - (1 shl size) + 1 else v
                val idx = zigzag[k]
                coef[idx] = value.toDouble() * qt[idx]
                k++
            }
            out += idct(coef)
        }
        tailBits?.set(0, r.bitsLeft)
        return out
    }
}
