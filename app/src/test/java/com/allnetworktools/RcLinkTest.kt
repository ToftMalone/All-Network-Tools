package com.allnetworktools

import com.allnetworktools.data.sdr.RcBurst
import com.allnetworktools.data.sdr.RcBurstDetector
import com.allnetworktools.data.sdr.RcLink
import com.allnetworktools.data.sdr.RcLinkTracker
import com.allnetworktools.data.sdr.RcModulation
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote-control links synthesised from their published parameters (packet rate, modulation, bandwidth, hopping), fed
 * as 8-bit I/Q at 20 MS/s through the burst detector and the link tracker.
 */
class RcLinkTest {
    private val fs = 20e6

    /** Instantaneous frequency (Hz, relative to the packet centre) at time t into the packet, or null once it is over. */
    private fun interface Packet { fun freq(t: Double): Double? }

    private fun lora(bw: Double, sf: Int, symbols: Int, rnd: Random): Packet {
        val n = 1 shl sf
        val ts = n / bw
        val values = IntArray(symbols) { if (it < 8) 0 else rnd.nextInt(n) } // preamble of upchirps, then data
        return Packet { t ->
            val s = (t / ts).toInt()
            if (s >= symbols) null else {
                val x = (values[s].toDouble() / n + (t - s * ts) / ts) % 1.0
                -bw / 2 + x * bw
            }
        }
    }

    private fun fsk(bitRate: Double, dev: Double, bits: Int, rnd: Random): Packet {
        val b = BooleanArray(bits) { rnd.nextBoolean() }
        return Packet { t -> val i = (t * bitRate).toInt(); if (i >= bits) null else if (b[i]) dev else -dev }
    }

    /**
     * A hopping link around [windowHz]: one packet every [periodS] on a channel drawn from [channels]; packets on
     * channels outside the 20 MHz capture are not heard, as with a real band wider than the window. DSSS packets are
     * random ±1 chips at [chipRate] instead of a frequency.
     */
    private fun run(
        windowHz: Double, channels: List<Double>, periodS: Double, seconds: Double, make: (Random) -> Packet?,
        chipRate: Double = 0.0, chipsLen: Double = 0.0, seed: Long = 7, amp: Double = 0.4,
    ): Pair<List<RcBurst>, List<RcLink>> {
        val rnd = Random(seed)
        val bursts = ArrayList<RcBurst>()
        val det = RcBurstDetector(fs, windowHz) { bursts += it }
        val buf = ByteArray(131072)
        var fill = 0
        var phase = 0.0
        var pktStart = 0.02 // the detector learns the noise floor first
        var pkt: Packet? = make(rnd)
        var pktOffset = channels[rnd.nextInt(channels.size)] - windowHz
        var chip = 1.0
        var chipIdx = -1
        val total = (seconds * fs).toLong()
        for (i in 0 until total) {
            val t = i / fs
            if (t - pktStart >= periodS) {
                pktStart += periodS; pkt = make(rnd); pktOffset = channels[rnd.nextInt(channels.size)] - windowHz; chipIdx = -1
            }
            val dt = t - pktStart
            if (dt < 0) {
                buf[fill++] = (rnd.nextGaussian() * 0.02 * 127).toInt().toByte(); buf[fill++] = (rnd.nextGaussian() * 0.02 * 127).toInt().toByte()
                if (fill == buf.size) { det.feed(buf, fill); fill = 0 }
                continue
            }
            var re = rnd.nextGaussian() * 0.02
            var im = rnd.nextGaussian() * 0.02
            val inside = kotlin.math.abs(pktOffset) < 9.5e6
            if (chipRate > 0 && dt < chipsLen && inside) {
                val ci = (dt * chipRate).toInt()
                if (ci != chipIdx) { chipIdx = ci; chip = if (rnd.nextBoolean()) 1.0 else -1.0 }
                phase += 2 * PI * pktOffset / fs
                re += amp * chip * cos(phase); im += amp * chip * sin(phase)
            } else {
                val f = pkt?.freq(dt)
                if (f != null && inside) {
                    phase += 2 * PI * (pktOffset + f) / fs
                    re += amp * cos(phase); im += amp * sin(phase)
                }
            }
            buf[fill++] = (re * 127).toInt().coerceIn(-127, 127).toByte()
            buf[fill++] = (im * 127).toInt().coerceIn(-127, 127).toByte()
            if (fill == buf.size) { det.feed(buf, fill); fill = 0 }
        }
        det.feed(buf, fill)
        val tracker = RcLinkTracker()
        val band = when { windowHz > 2e9 -> 24; windowHz > 900e6 -> 915; windowHz > 800e6 -> 868; else -> 433 }
        bursts.forEach { tracker.add(band, 0, it, (it.timeS * 1000).toLong()) }
        val links = tracker.links((seconds * 1000).toLong())
        return bursts to links
    }

    private val ism24 = (0 until 80).map { 2400.5e6 + it * 1e6 }

    @Test fun expressLrsLora24() {
        val (bursts, links) = run(2430e6, ism24, 1 / 250.0, 1.6, { lora(812.5e3, 6, 14, it) })
        assertTrue("bursts ${bursts.size}", bursts.size >= 40)
        assertTrue(bursts.count { it.modulation == RcModulation.Chirp } > bursts.size / 2)
        val l = links.single()
        assertEquals("ExpressLRS 2,4 GHz (LoRa)", l.protocol)
        assertEquals(250.0, l.rateHz!!, 1.0)
        assertTrue(l.channels >= 4)
    }

    @Test fun expressLrsFlrc() {
        val (_, links) = run(2450e6, ism24, 1 / 1000.0, 0.6, { fsk(1.3e6, 330e3, 120, it) })
        val l = links.single()
        assertEquals("ExpressLRS 2,4 GHz (FLRC)", l.protocol)
        assertEquals(1000.0, l.rateHz!!, 5.0)
    }

    @Test fun frskyAccst() {
        val ch = (0 until 47).map { 2404e6 + it * 1.5e6 }
        val (_, links) = run(2430e6, ch, 0.009, 3.0, { fsk(250e3, 57e3, 240, it) })
        val l = links.single()
        assertEquals("FrSky ACCST / ACCESS 2,4 GHz", l.protocol)
        assertEquals(111.1, l.rateHz!!, 1.0)
    }

    @Test fun spektrumDsmx() {
        val ch = (0 until 23).map { 2403e6 + it * 3e6 }
        val (_, links) = run(2430e6, ch, 0.011, 3.0, { null }, chipRate = 1e6, chipsLen = 0.8e-3, amp = 0.1)
        val l = links.single()
        assertEquals(RcModulation.Dsss, l.modulation)
        assertEquals("Spektrum DSMX / DSM2", l.protocol)
    }

    @Test fun expressLrs900Lora() {
        val ch = (0 until 40).map { 903.5e6 + it * 0.6e6 }
        val (_, links) = run(910e6, ch, 0.01, 2.5, { lora(500e3, 7, 14, it) })
        val l = links.single()
        assertEquals("ExpressLRS 868/915 MHz (LoRa)", l.protocol)
        assertEquals(100.0, l.rateHz!!, 1.0)
    }

    @Test fun crossfire150() {
        val ch = (0 until 20).map { 860.2e6 + it * 0.5e6 }
        val (_, links) = run(868e6, ch, 1 / 150.0, 1.5, { fsk(85e3, 50e3, 100, it) })
        val l = links.single()
        assertEquals("TBS Crossfire (150 Hz)", l.protocol)
        assertEquals(150.0, l.rateHz!!, 2.0)
    }

    @Test fun fixedFrequencyBurstsAreNotRemoteControls() {
        // LoRaWAN-like: chirps, but always on one frequency.
        val (bursts, links) = run(868e6, listOf(868.1e6), 0.05, 1.5, { lora(125e3, 7, 20, it) })
        assertNotNull(bursts.firstOrNull())
        assertTrue(links.isEmpty())
    }

    @Test fun wideOrEndlessSignalsAreDropped() {
        // A 20 µs-chip DSSS 16 MHz wide (Wi-Fi-like) is too wide; nothing else is on the air.
        val (bursts, links) = run(2430e6, listOf(2430e6), 0.004, 0.5, { null }, chipRate = 16e6, chipsLen = 1.5e-3)
        assertTrue(bursts.isEmpty())
        assertTrue(links.isEmpty())
    }
}
