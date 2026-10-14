package com.allnetworktools

import com.allnetworktools.data.sdr.Acars
import com.allnetworktools.data.sdr.AcarsDecoder
import com.allnetworktools.data.sdr.AcarsMessage
import com.allnetworktools.data.sdr.AcarsReceiver
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcarsTest {
    /** Bits of the frame, least significant bit first, after a pre-key of ones. */
    private fun bits(frame: ByteArray): IntArray {
        val out = ArrayList<Int>()
        repeat(40) { out += 1 }
        for (b in frame) for (i in 0 until 8) out += (b.toInt() shr i) and 1
        repeat(16) { out += 1 }
        return out.toIntArray()
    }

    /** MSK audio at [fs]: 2400 Hz or 1200 Hz per bit, continuous phase. [differential] codes a 0 as a tone change. */
    private fun audio(bits: IntArray, fs: Double, differential: Boolean, noise: Double, seed: Long = 1): FloatArray {
        val spb = fs / 2400
        val rnd = Random(seed)
        val out = FloatArray(((bits.size + 10) * spb).toInt() + 2000)
        var phase = 0.0
        var tone = 1
        var t = 1000.0
        for (b in bits) {
            tone = if (differential) (if (b == 1) tone else 1 - tone) else b
            val f = if (tone == 1) 2400.0 else 1200.0
            val end = t + spb
            while (t < end) {
                val i = t.toInt()
                out[i] = (sin(phase) + rnd.nextGaussian() * noise).toFloat()
                phase += 2 * PI * f / fs
                t += 1.0
            }
        }
        return out
    }

    private val sample = Acars.frame('2', ".F-GKXA", 'N', "H1", '3', "M01AAF1234#DFBPOS N48123E002456")

    @Test fun crcAndParsing() {
        val body = sample.copyOfRange(5, sample.size - 3)
        val c = Acars.crc(body, 0, body.size)
        assertEquals(c and 0xFF, sample[sample.size - 3].toInt() and 0xFF)
        val m = Acars.parse(body, body.size, 131.525, 0)!!
        assertEquals("F-GKXA", m.registration)
        assertEquals("H1", m.label)
        assertEquals("M01A", m.msgNo)
        assertEquals("AF1234", m.flight)
        assertEquals("#DFBPOS N48123E002456", m.text)
        assertTrue(m.downlink)
    }

    @Test fun decodesBothToneConventions() {
        for (diff in listOf(false, true)) {
            val got = ArrayList<AcarsMessage>()
            val d = AcarsDecoder(48_000.0, 131.725) { got += it }
            val a = audio(bits(sample), 48_000.0, diff, 0.3)
            var i = 0
            while (i < a.size) { val n = minOf(1000, a.size - i); d.feed(a.copyOfRange(i, i + n), n); i += n }
            assertEquals("differential=$diff", 1, got.size)
            assertEquals("AF1234", got[0].flight)
        }
    }

    @Test fun receivesThroughTheHackRfChain() {
        val fs = AcarsReceiver.SAMPLE_RATE.toDouble()
        val a = audio(bits(Acars.frame('2', ".N123AB", 'N', "Q0", 'S', null)), 48_000.0, false, 0.0)
        // AM on the 131.825 MHz channel (+200 kHz from the centre), upsampled by holding each audio sample 50 times.
        val n = a.size * 50
        val re = FloatArray(n)
        val im = FloatArray(n)
        val rnd = Random(4)
        val w = 2 * PI * 200_000.0 / fs
        for (i in 0 until n) {
            val env = 0.4 * (1 + 0.6 * a[i / 50])
            re[i] = (env * cos(w * i) + rnd.nextGaussian() * 0.02).toFloat()
            im[i] = (env * sin(w * i) + rnd.nextGaussian() * 0.02).toFloat()
        }
        val got = ArrayList<AcarsMessage>()
        val rx = AcarsReceiver { got += it }
        var i = 0
        while (i < n) { val k = minOf(65536, n - i); rx.feedFloat(re.copyOfRange(i, i + k), im.copyOfRange(i, i + k), k); i += k }
        assertEquals(1, got.size)
        assertEquals("N123AB", got[0].registration)
        assertEquals(131.825, got[0].freqMhz, 1e-9)
        assertEquals("Q0", got[0].label)
    }
}
