package com.allnetworktools

import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.FloatDecimator
import com.allnetworktools.data.sdr.FmReceiver
import com.allnetworktools.data.sdr.Rds
import com.allnetworktools.data.sdr.RdsInfo
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RdsTest {
    private fun group0(pi: Int, addr: Int, c1: Char, c2: Char) =
        intArrayOf(pi, (0 shl 12) or (1 shl 10) or (10 shl 5) or addr, 0xE0E0, (c1.code shl 8) or c2.code)

    private fun group2a(pi: Int, addr: Int, t: String) =
        intArrayOf(pi, (2 shl 12) or (1 shl 10) or (10 shl 5) or addr, (t[0].code shl 8) or t[1].code, (t[2].code shl 8) or t[3].code)

    /** Information bits of whole groups, block by block with their offset words. */
    private fun bits(groups: List<IntArray>): IntArray {
        val out = ArrayList<Int>()
        for (g in groups) for (b in 0 until 4) {
            val w = Rds.block(g[b], if (b == 2) 2 else if (b == 3) 4 else b)
            for (i in 25 downTo 0) out += (w shr i) and 1
        }
        return out.toIntArray()
    }

    private val pi = 0xF201
    private val ps = "DEMO FM1"
    private val text = "Bonjour monde\r  "

    private fun cycle(): List<IntArray> {
        val gs = ArrayList<IntArray>()
        for (a in 0 until 4) gs += group0(pi, a, ps[2 * a], ps[2 * a + 1])
        for (a in 0 until 4) gs += group2a(pi, a, text.substring(4 * a, 4 * a + 4))
        return gs
    }

    @Test fun crcAndOffsetWords() {
        // The check word of a block always equals crc10 xor offset: a flipped bit breaks it.
        val w = Rds.block(0xF201, 0)
        assertEquals(0xF201, w shr 10)
        assertEquals(Rds.crc10(0xF201) xor 0x0FC, w and 0x3FF)
        assertTrue(Rds.crc10(0xF201) != Rds.crc10(0xF200))
    }

    @Test fun characters() {
        assertEquals('A', Rds.char(0x41))
        assertEquals('é', Rds.char(0x82))
        assertEquals('ç', Rds.char(0x9B))
        assertEquals(' ', Rds.char(0x05))
    }

    /** Composite audio + pilot + RDS, frequency-modulated, as 8-bit HackRF samples at 1.9 MS/s. */
    private fun waveform(data: IntArray, noise: Double, seed: Long): ByteArray {
        val rnd = Random(seed)
        val fs = 1_900_000.0
        val samplesPerBit = fs / Rds.BIT_RATE
        val lead = (0.3 * fs).toInt()
        val n = lead + (data.size * samplesPerBit).toInt() + (0.1 * fs).toInt()
        // Differential coding then biphase: a 1 is sent as (+,−), a 0 as (−,+).
        val tx = IntArray(data.size)
        var prev = 0
        for (i in data.indices) { tx[i] = data[i] xor prev; prev = tx[i] }
        var psi = 0.0
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val t = i / fs
            var rdsLevel = 0.0
            if (i >= lead) {
                val pos = (i - lead) / samplesPerBit
                val bit = tx[pos.toInt().coerceAtMost(tx.size - 1)]
                val first = (pos - pos.toInt()) < 0.5
                rdsLevel = if ((bit == 1) == first) 1.0 else -1.0
            }
            val c = 0.35 * sin(2 * PI * 1000 * t) + 0.09 * sin(2 * PI * 19_000 * t) + 0.04 * rdsLevel * sin(2 * PI * 57_000 * t + 0.7)
            psi += 2 * PI * (475_000.0 - 600 + 75_000 * c) / fs
            iq[2 * i] = (cos(psi) * 40 + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (sin(psi) * 40 + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
        }
        return iq
    }

    private fun run(iq: ByteArray, onAudio: (ShortArray, Int) -> Unit = { _, _ -> }): RdsInfo {
        var last = RdsInfo()
        val fm = FmReceiver(onAudio) { last = it }
        val d1 = Decimator(4, 0.09, taps = 48)
        val d2 = FloatDecimator(2, 0.21, 48)
        val aRe = FloatArray(32768); val aIm = FloatArray(32768)
        val bRe = FloatArray(16384); val bIm = FloatArray(16384)
        var i = 0
        while (i < iq.size) {
            val len = minOf(131072, iq.size - i)
            val k = d1.process(iq.copyOfRange(i, i + len), len, aRe, aIm, 0)
            val m = d2.process(aRe, aIm, k, bRe, bIm)
            fm.feed(bRe, bIm, m)
            i += len
        }
        return last
    }

    @Test fun decodesStationNameAndRadioTextFromHackRfSamples() {
        val gs = ArrayList<IntArray>()
        repeat(4) { gs += cycle() }
        val audio = ArrayList<Short>()
        val info = run(waveform(bits(gs), noise = 8.0, seed = 7)) { a, n -> for (i in 0 until n) audio += a[i] }
        assertEquals(pi, info.pi)
        assertEquals("DEMO FM1", info.ps)
        assertEquals("Bonjour monde", info.radioText)
        assertEquals(10, info.pty)
        assertTrue(info.tp)
        // The 1 kHz test tone comes out of the audio chain, much louder than 2.5 kHz.
        val x = audio.drop(audio.size / 2).map { it / 32768.0 }
        fun g(f: Double): Double {
            var re = 0.0; var im = 0.0
            x.forEachIndexed { i, v -> val a = 2 * PI * f * i / 47_500.0; re += v * cos(a); im += v * sin(a) }
            return re * re + im * im
        }
        assertTrue(g(1000.0) > 100 * g(2500.0))
    }

    @Test fun noiseAloneDecodesNothing() {
        val rnd = Random(11)
        val iq = ByteArray(2 * 1_900_000) { (rnd.nextGaussian() * 30).toInt().coerceIn(-127, 127).toByte() }
        val info = run(iq)
        assertNull(info.ps)
        assertNull(info.radioText)
        assertEquals(0, info.groups)
        assertNotNull(info)
    }
}
