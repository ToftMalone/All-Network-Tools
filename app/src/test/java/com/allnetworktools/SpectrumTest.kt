package com.allnetworktools

import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SpectrumAnalyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {
    /** Interleaved int8 I/Q of a complex tone at [f] (fraction of the sample rate) with amplitude [a] (of full scale). */
    private fun tone(samples: Int, f: Double, a: Double, dc: Int = 0) = ByteArray(samples * 2) { i ->
        val n = i / 2
        val ph = 2 * PI * f * n
        ((if (i % 2 == 0) cos(ph) else sin(ph)) * a * 127 + dc).toInt().coerceIn(-128, 127).toByte()
    }

    @Test fun toneLandsOnItsBinAtItsLevel() {
        val sa = SpectrumAnalyzer(1024)
        // +1.25 MHz at 10 MS/s = +0.125 fs → bin 512 + 128 after the shift. Half scale → about −6 dBFS.
        val buf = tone(65536, 0.125, 0.5, dc = 9)
        sa.accumulate(buf, buf.size)
        val db = sa.frame()!!
        val peak = db.indices.maxBy { db[it] }
        assertEquals(640, peak)
        assertEquals(-6.0, db[peak].toDouble(), 0.6)
        // The DC offset is removed, and the window keeps leakage far down.
        assertTrue(db[512] < -60)
        assertTrue(db[100] < -70)
        assertNull(sa.frame())
    }

    @Test fun negativeFrequenciesAreOnTheLeft() {
        val sa = SpectrumAnalyzer(1024)
        val buf = tone(8192, -0.25, 0.3)
        sa.accumulate(buf, buf.size)
        val db = sa.frame()!!
        assertEquals(256, db.indices.maxBy { db[it] })
    }

    @Test fun basebandFilterFollowsLibhackrf() {
        assertEquals(1_750_000, HackRf.filterFor(2_000_000))
        assertEquals(3_500_000, HackRf.filterFor(5_000_000))
        assertEquals(7_000_000, HackRf.filterFor(10_000_000))
        assertEquals(15_000_000, HackRf.filterFor(20_000_000))
    }
}
