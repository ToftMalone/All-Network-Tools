package com.allnetworktools

import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.FloatDecimator
import com.allnetworktools.data.sdr.Rs41
import com.allnetworktools.data.sdr.Rs41Demodulator
import com.allnetworktools.data.sdr.Rs41Frame
import com.allnetworktools.data.sdr.SondeTracker
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rs41Test {
    private fun geoToEcef(lat: Double, lon: Double, h: Double): DoubleArray {
        val a = 6378137.0
        val e2 = 6.69437999014e-3
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lon)
        val n = a / sqrt(1 - e2 * sin(p) * sin(p))
        return doubleArrayOf((n + h) * cos(p) * cos(l), (n + h) * cos(p) * sin(l), (n * (1 - e2) + h) * sin(p))
    }

    private fun putBlock(f: ByteArray, pos: Int, id: Int, data: ByteArray): Int {
        f[pos] = id.toByte(); f[pos + 1] = data.size.toByte()
        data.copyInto(f, pos + 2)
        val crc = Rs41.crc16(f, pos + 2, data.size)
        f[pos + 2 + data.size] = crc.toByte(); f[pos + 3 + data.size] = (crc shr 8).toByte()
        return pos + 4 + data.size
    }

    /** A descrambled standard frame: status block (frame number, serial, battery) and GPS position block. */
    private fun frame(nb: Int, lat: Double, lon: Double, alt: Double, climb: Double): ByteArray {
        val f = ByteArray(Rs41.STD_LEN)
        intArrayOf(0x86, 0x35, 0xf4, 0x40, 0x93, 0xdf, 0x1a, 0x60).forEachIndexed { i, b -> f[i] = b.toByte() }
        f[0x38] = 0x0F
        val status = ByteArray(40)
        status[0] = nb.toByte(); status[1] = (nb shr 8).toByte()
        "V3420117".toByteArray().copyInto(status, 2)
        status[10] = 29
        var p = putBlock(f, 0x39, 0x79, status)
        val x = geoToEcef(lat, lon, alt)
        // Velocity: straight up by [climb] m/s, i.e. along the local vertical.
        val up = doubleArrayOf(cos(Math.toRadians(lat)) * cos(Math.toRadians(lon)), cos(Math.toRadians(lat)) * sin(Math.toRadians(lon)), sin(Math.toRadians(lat)))
        val gps = ByteArray(21)
        fun put32(o: Int, v: Int) { for (k in 0 until 4) gps[o + k] = (v shr (8 * k)).toByte() }
        fun put16(o: Int, v: Int) { gps[o] = v.toByte(); gps[o + 1] = (v shr 8).toByte() }
        for (k in 0 until 3) { put32(4 * k, Math.round(x[k] * 100).toInt()); put16(12 + 2 * k, Math.round(up[k] * climb * 100).toInt()) }
        gps[18] = 9
        p = putBlock(f, p + 0x2C, 0x7B, gps) // leave room as real frames have PTU/GPS1 blocks in between
        return f
    }

    @Test fun headerBitsMatchScrambledHeader() {
        val bits = StringBuilder()
        intArrayOf(0x86, 0x35, 0xf4, 0x40, 0x93, 0xdf, 0x1a, 0x60).forEachIndexed { i, b ->
            val raw = b xor Rs41.MASK[i]
            for (k in 0 until 8) bits.append((raw shr k) and 1)
        }
        assertEquals(Rs41.HEADER_BITS, bits.toString())
    }

    @Test fun ecefConversion() {
        val x = geoToEcef(48.7745, 2.0097, 12_000.0)
        val (lat, lon, h) = Rs41.ecefToGeo(x[0], x[1], x[2])
        assertEquals(48.7745, lat, 1e-6)
        assertEquals(2.0097, lon, 1e-6)
        assertEquals(12_000.0, h, 0.01)
    }

    @Test fun parsesBlocksAndRejectsBadCrc() {
        val f = frame(4321, 48.7745, 2.0097, 12_000.0, 5.2)
        val r = Rs41.parse(f)!!
        assertEquals("V3420117", r.serial)
        assertEquals(4321, r.frameNumber)
        assertEquals(2.9, r.batteryV!!, 1e-9)
        assertEquals(48.7745, r.lat!!, 1e-5)
        assertEquals(12_000.0, r.altitudeM!!, 0.5)
        assertEquals(5.2, r.climbMs!!, 0.05)
        assertEquals(0.0, r.speedMs!!, 0.05)
        assertEquals(9, r.satellites)
        f[0x39 + 5] = (f[0x39 + 5] + 1).toByte() // corrupt the status block
        val r2 = Rs41.parse(f)!!
        assertNull(r2.serial)
        assertNotNull(r2.lat)
    }

    @Test fun demodulatesFromHackRfSamples() {
        val frames = (0 until 3).map { frame(100 + it, 48.80 + it * 0.001, 2.10, 15_000.0 + it * 5, 5.0) }
        val bits = ArrayList<Int>()
        repeat(400) { bits += it % 2 } // carrier and preamble-like filler before the first frame
        frames.forEach { f ->
            for (i in f.indices) {
                val raw = (f[i].toInt() and 0xFF) xor Rs41.MASK[i % 64]
                for (k in 0 until 8) bits += (raw shr k) and 1
            }
            repeat(37) { bits += 1 }
        }
        repeat(800) { bits += it % 2 }
        val fs = 2_000_000.0
        val sps = fs / Rs41.BAUD
        val rnd = Random(3)
        val n = (bits.size * sps).toInt()
        // FSK ±2.4 kHz around +500 kHz − 1.3 kHz of tuning error, 8-bit, noisy.
        var ph = 0.0
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val b = bits[(i / sps).toInt().coerceAtMost(bits.size - 1)]
            ph += 2 * PI * (500_000.0 - 1300 + (if (b == 1) 2400.0 else -2400.0)) / fs
            iq[2 * i] = (cos(ph) * 40 + rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (sin(ph) * 40 + rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte()
        }
        val got = ArrayList<Rs41Frame>()
        val demod = Rs41Demodulator(50_000.0) { f -> Rs41.parse(f)?.let { got += it } }
        val d1 = Decimator(4, 0.07, taps = 48)
        val d2 = FloatDecimator(10, 0.024)
        val aRe = FloatArray(32768); val aIm = FloatArray(32768)
        val bRe = FloatArray(4096); val bIm = FloatArray(4096)
        var i = 0
        while (i < iq.size) {
            val len = minOf(131072, iq.size - i)
            val k = d1.process(iq.copyOfRange(i, i + len), len, aRe, aIm, 0)
            val m = d2.process(aRe, aIm, k, bRe, bIm)
            demod.feed(bRe, bIm, m)
            i += len
        }
        assertEquals(listOf(100, 101, 102), got.map { it.frameNumber })
        assertTrue(got.all { it.serial == "V3420117" })
        assertEquals(48.802, got[2].lat!!, 1e-5)
        assertEquals(15_010.0, got[2].altitudeM!!, 0.5)
    }

    @Test fun noiseAloneDecodesNothing() {
        val rnd = Random(5)
        val re = FloatArray(200_000) { rnd.nextGaussian().toFloat() }
        val im = FloatArray(200_000) { rnd.nextGaussian().toFloat() }
        val got = ArrayList<Rs41Frame>()
        Rs41Demodulator(50_000.0) { f -> Rs41.parse(f)?.let { got += it } }.feed(re, im, re.size)
        assertTrue(got.isEmpty())
    }

    @Test fun trackerGroupsBySerialAndKeepsTheTrack() {
        val t = SondeTracker()
        fun fr(serial: String?, alt: Double) = Rs41Frame(serial, 1, 2.9, 48.8, 2.1, alt, 3.0, 90.0, 5.0, 8)
        t.update(fr("S1234567", 1000.0), 0)
        t.update(fr(null, 1005.0), 1000) // status block lost: same sonde as before
        t.update(fr("S1234567", 1020.0), 2000)
        t.update(fr("T7654321", 500.0), 3000)
        assertEquals(listOf("S1234567", "T7654321"), t.sondes.keys.toList())
        val s = t.sondes["S1234567"]!!
        assertEquals(3, s.frames)
        assertEquals(1020.0, s.maxAltitudeM!!, 1e-9)
        assertEquals(3, s.track.size)
        t.prune(30 * 60_000L + 2500)
        assertEquals(listOf("T7654321"), t.sondes.keys.toList())
    }
}
