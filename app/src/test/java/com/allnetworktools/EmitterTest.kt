package com.allnetworktools

import com.allnetworktools.data.sdr.BandPlan
import com.allnetworktools.data.sdr.EmitterKind
import com.allnetworktools.data.sdr.EmitterScanner
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmitterTest {
    private val fs = 4_000_000.0
    private val tune = 433_000_000.0

    private fun capture(seconds: Double, noise: Double, seed: Long, signals: (Double) -> DoubleArray): ByteArray {
        val rnd = Random(seed)
        val n = (seconds * fs).toInt()
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val t = i / fs
            val s = signals(t) // re, im
            iq[2 * i] = (s[0] + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (s[1] + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
        }
        return iq
    }

    private fun scan(iq: ByteArray): EmitterScanner {
        val sc = EmitterScanner(2048, tune, fs)
        var i = 0
        while (i < iq.size) { val len = minOf(131072, iq.size - i); sc.feed(iq.copyOfRange(i, i + len), len); i += len }
        return sc
    }

    @Test fun findsBurstCarrierAndWideSignals() {
        val out = DoubleArray(2)
        var chirp = 0.0
        val iq = capture(1.6, 6.0, 1) { t ->
            var re = 0.0
            var im = 0.0
            // OOK remote at 433.92 MHz: three 30 ms bursts.
            if (t in 0.2..0.23 || t in 0.6..0.63 || t in 1.0..1.03) { val a = 2 * PI * 920_000 * t; re += 20 * cos(a); im += 20 * sin(a) }
            // Continuous carrier at 432.5 MHz, from 0.1 s.
            if (t > 0.1) { val a = 2 * PI * -500_000 * t; re += 15 * cos(a); im += 15 * sin(a) }
            // 125 kHz chirps (LoRa-like) around 434.2 MHz, 1 ms sweep, between 0.3 and 0.5 s.
            if (t in 0.3..0.5) {
                val u = (t * 1000) % 1.0
                chirp += 2 * PI * (1_200_000 - 62_500 + 125_000 * u) / fs
                re += 18 * cos(chirp); im += 18 * sin(chirp)
            }
            out[0] = re; out[1] = im
            out
        }
        val list = scan(iq).emitters()
        val remote = list.firstOrNull { abs(it.freqHz - 433_920_000) < 15_000 }
        assertNotNull(remote)
        assertEquals(3, remote!!.bursts)
        assertEquals(EmitterKind.Burst, remote.kind)
        assertNotNull(remote.band)
        val carrier = list.firstOrNull { abs(it.freqHz - 432_500_000) < 15_000 }
        assertNotNull(carrier)
        assertEquals(EmitterKind.Carrier, carrier!!.kind)
        assertTrue(carrier.active)
        assertTrue(list.any { it.kind == EmitterKind.Spread || it.kind == EmitterKind.Wide })
        // Burst levels are well above the floor.
        assertTrue(remote.peakDb > -60f)
    }

    @Test fun noiseAloneFindsNothing() {
        val iq = capture(1.0, 10.0, 3) { doubleArrayOf(0.0, 0.0) }
        assertTrue(scan(iq).emitters().isEmpty())
    }

    @Test fun bandPlan() {
        assertEquals("ISM 433,92 : télécommandes, stations météo, capteurs", BandPlan.describe(433.92e6))
        assertTrue(BandPlan.describe(869.525e6)!!.contains("Meshtastic"))
        assertNull(BandPlan.describe(100e6))
    }
}
