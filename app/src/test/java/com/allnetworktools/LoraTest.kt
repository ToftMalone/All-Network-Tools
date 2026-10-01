package com.allnetworktools

import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.Meshtastic
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transmit chain ported from gr-lora_sdr (whitening, header, add_crc, hamming_enc, interleaver, gray_demap, modulate). */
object LoraTx {
    fun symbols(payload: ByteArray, sf: Int, cr: Int, hasCrc: Boolean = true): IntArray {
        val nib = ArrayList<Int>()
        // header
        val len = payload.size
        val h0 = len shr 4
        val h1 = len and 0x0F
        val h2 = (cr shl 1) or (if (hasCrc) 1 else 0)
        fun b(x: Int, bit: Int) = (x shr bit) and 1
        val c4 = b(h0, 3) xor b(h0, 2) xor b(h0, 1) xor b(h0, 0)
        val c3 = b(h0, 3) xor b(h1, 3) xor b(h1, 2) xor b(h1, 1) xor b(h2, 0)
        val c2 = b(h0, 2) xor b(h1, 3) xor b(h1, 0) xor b(h2, 3) xor b(h2, 1)
        val c1 = b(h0, 1) xor b(h1, 2) xor b(h1, 0) xor b(h2, 2) xor b(h2, 1) xor b(h2, 0)
        val c0 = b(h0, 0) xor b(h1, 1) xor b(h2, 3) xor b(h2, 2) xor b(h2, 1) xor b(h2, 0)
        nib += listOf(h0, h1, h2, c4, (c3 shl 3) or (c2 shl 2) or (c1 shl 1) or c0)
        // whitened payload, low nibble first
        payload.forEachIndexed { i, x ->
            val w = (x.toInt() and 0xFF) xor LoraPhy.whitening[i]
            nib += w and 0x0F; nib += w shr 4
        }
        if (hasCrc) {
            val crc = LoraPhy.payloadCrc(payload)
            nib += listOf(crc and 0xF, (crc shr 4) and 0xF, (crc shr 8) and 0xF, (crc shr 12) and 0xF)
        }
        // hamming: data LSB first, then parity
        fun ham(x: Int, crApp: Int): Int {
            val d = intArrayOf(b(x, 3), b(x, 2), b(x, 1), b(x, 0)) // msb first
            return if (crApp != 1) {
                val p0 = d[3] xor d[2] xor d[1]
                val p1 = d[2] xor d[1] xor d[0]
                val p2 = d[3] xor d[2] xor d[0]
                val p3 = d[3] xor d[1] xor d[0]
                ((d[3] shl 7) or (d[2] shl 6) or (d[1] shl 5) or (d[0] shl 4) or (p0 shl 3) or (p1 shl 2) or (p2 shl 1) or p3) shr (4 - crApp)
            } else {
                (d[3] shl 4) or (d[2] shl 3) or (d[1] shl 2) or (d[0] shl 1) or (d[0] xor d[1] xor d[2] xor d[3])
            }
        }
        val out = ArrayList<Int>()
        var i = 0
        var first = true
        while (i < nib.size) {
            val sfApp = if (first) sf - 2 else sf
            val crApp = if (first) 4 else cr
            val cwLen = crApp + 4
            val cw = IntArray(sfApp) { k -> if (i + k < nib.size) ham(nib[i + k], crApp) else 0 }
            i += sfApp
            for (s in 0 until cwLen) {
                val bits = IntArray(sf)
                for (j in 0 until sfApp) bits[j] = (cw[Math.floorMod(s - j - 1, sfApp)] shr (cwLen - 1 - s)) and 1
                if (first) bits[sfApp] = bits.take(sfApp).sum() % 2
                var v = 0
                for (j in 0 until sf) v = (v shl 1) or bits[j]
                // gray demap then +1
                var g = v
                for (j in 1 until sf) g = g xor (v shr j)
                out += Math.floorMod(g + 1, 1 shl sf)
            }
            first = false
        }
        return out.toIntArray()
    }

    /** Frame structure in symbols: value ≥ 0 upchirp, -1 downchirp, -2 quarter downchirp. */
    fun frame(payloadSymbols: IntArray, syncWord: Int, preamble: Int = 16): IntArray =
        IntArray(preamble) { 0 } + intArrayOf(((syncWord shr 4) and 0xF) * 8, (syncWord and 0xF) * 8, -1, -1, -2) + payloadSymbols

    /**
     * Continuous-time LoRa waveform sampled at [fs], starting [delay] seconds in, with carrier offset [cfoHz] and a
     * transmitter clock [ppm] off (chip rate and carrier shifted together), plus complex noise at [snrDb] in [bw].
     */
    fun waveform(frame: IntArray, sf: Int, bw: Double, fs: Double, delay: Double, cfoHz: Double, ppm: Double, snrDb: Double, seed: Long = 1): Pair<FloatArray, FloatArray> {
        val n = 1 shl sf
        val chip = 1 / (bw * (1 + ppm * 1e-6))
        val tSym = n * chip
        var duration = 0.0
        frame.forEach { duration += if (it == -2) tSym / 4 else tSym }
        val total = ((delay + duration + 3 * tSym) * fs).toInt()
        val re = FloatArray(total)
        val im = FloatArray(total)
        val rnd = Random(seed)
        val sigma = sqrt(fs / bw / Math.pow(10.0, snrDb / 10) / 2)
        // phase at each symbol start
        val starts = DoubleArray(frame.size + 1)
        for (k in frame.indices) starts[k + 1] = starts[k] + if (frame[k] == -2) tSym / 4 else tSym
        val phase0 = DoubleArray(frame.size + 1)
        fun inSym(v: Int, u: Double): Double { // N * F(u) in cycles
            if (v < 0) return -n * (u * u / 2 - u / 2)
            val x0 = v.toDouble() / n
            var f = x0 * u + u * u / 2 - u / 2
            if (x0 + u >= 1) f -= u - (1 - x0)
            return n * f
        }
        for (k in frame.indices) {
            val u = if (frame[k] == -2) 0.25 else 1.0
            phase0[k + 1] = phase0[k] + inSym(frame[k], u)
        }
        var k = 0
        for (i in 0 until total) {
            val t = i / fs - delay
            var sr = 0.0
            var si = 0.0
            if (t >= 0 && t < duration) {
                while (k < frame.size - 1 && t >= starts[k + 1]) k++
                val u = (t - starts[k]) / tSym
                val cyc = phase0[k] + inSym(frame[k], u) + cfoHz * t
                val ph = 2 * PI * (cyc - floor(cyc))
                sr = cos(ph); si = sin(ph)
            }
            re[i] = (sr + rnd.nextGaussian() * sigma).toFloat()
            im[i] = (si + rnd.nextGaussian() * sigma).toFloat()
        }
        return re to im
    }
}

class LoraTest {
    private val sf = 11
    private val bw = 250_000.0
    private val fc = 869_525_000.0

    private fun receive(re: FloatArray, im: FloatArray): List<LoraFrame> {
        val got = ArrayList<LoraFrame>()
        val rx = LoraReceiver(sf, bw, fc, 0x2B) { got += it }
        var i = 0
        while (i < re.size) {
            val c = minOf(5000, re.size - i)
            rx.feed(re.copyOfRange(i, i + c), im.copyOfRange(i, i + c), c)
            i += c
        }
        return got
    }

    private fun meshPacket(text: String, from: Long = 0x1a2b3c4dL, id: Long = 0x0badf00dL): ByteArray {
        val key = Meshtastic.expandKey(byteArrayOf(1))!!
        val data = byteArrayOf(0x08, 0x01, 0x12, text.toByteArray().size.toByte()) + text.toByteArray()
        val enc = Meshtastic.crypt(key, from, id, data)
        val hdr = ByteArray(16)
        fun put(o: Int, v: Long) { for (k in 0 until 4) hdr[o + k] = (v shr (8 * k)).toByte() }
        put(0, Meshtastic.BROADCAST); put(4, from); put(8, id)
        hdr[12] = ((3 shl 5) or 2).toByte() // hop_start 3, hop_limit 2
        hdr[13] = 0x08
        return hdr + enc
    }

    @Test fun headerChecksumAndHammingRoundTrip() {
        val payload = "Bonjour LoRa".toByteArray()
        val syms = LoraTx.symbols(payload, sf, 1)
        val vals = IntArray(8) { LoraPhy.binToValue(syms[it], sf, true) }
        val nib = LoraPhy.deinterleave(vals, sf - 2, 8).map { LoraPhy.hammingDecode(it, 4) }
        val h = LoraPhy.header(nib.take(5).toIntArray())
        assertNotNull(h)
        assertEquals(payload.size, h!!.payloadLen)
        assertEquals(1, h.cr)
        assertTrue(h.hasCrc)
        assertEquals(8 + LoraPhy.payloadSymbols(sf, payload.size, 1, true), syms.size)
    }

    @Test fun hammingCorrectsSingleErrorsAtCr48() {
        // Codeword of nibble 0b1011 at CR 4/8 from the transmitter's encoder, then one flipped bit.
        val syms = LoraTx.symbols(byteArrayOf(0x5A), sf, 4)
        val vals = IntArray(8) { LoraPhy.binToValue(syms[it], sf, true) }
        val cws = LoraPhy.deinterleave(vals, sf - 2, 8)
        for (cw in cws) {
            val clean = LoraPhy.hammingDecode(cw, 4)
            for (bit in 4 until 8) assertEquals(clean, LoraPhy.hammingDecode(cw xor (1 shl bit), 4))
        }
    }

    @Test fun decodesCleanFrame() {
        val payload = meshPacket("Salut le mesh !")
        val frame = LoraTx.frame(LoraTx.symbols(payload, sf, 1), 0x2B)
        val (re, im) = LoraTx.waveform(frame, sf, bw, 2 * bw, delay = 0.0123, cfoHz = 0.0, ppm = 0.0, snrDb = 20.0)
        val got = receive(re, im)
        assertEquals(1, got.size)
        assertTrue(got[0].crcOk)
        assertArrayEquals(payload, got[0].payload)
    }

    @Test fun survivesFrequencyOffsetClockDriftAndNoise() {
        val payload = meshPacket("Message assez long pour durer plusieurs centaines de millisecondes sur LongFast, avec de la dérive.")
        val frame = LoraTx.frame(LoraTx.symbols(payload, sf, 1), 0x2B)
        // 25 ppm crystal error: −21.7 kHz carrier offset and the matching chip-rate error; −10 dB SNR.
        val ppm = 25.0
        val (re, im) = LoraTx.waveform(frame, sf, bw, 2 * bw, delay = 0.0377, cfoHz = fc * ppm * 1e-6, ppm = ppm, snrDb = -10.0, seed = 7)
        val got = receive(re, im)
        assertEquals(1, got.size)
        assertTrue(got[0].crcOk)
        assertArrayEquals(payload, got[0].payload)
        assertEquals(fc * ppm * 1e-6, got[0].cfoHz, 200.0)
    }

    @Test fun decodesAtAnyFractionalTimingAndCommonClockErrors() {
        val payload = meshPacket("Test de robustesse")
        val frame = LoraTx.frame(LoraTx.symbols(payload, sf, 1), 0x2B)
        for (f in listOf(0.13, 0.5, 0.77)) {
            val (re, im) = LoraTx.waveform(frame, sf, bw, 2 * bw, delay = 0.02 + f / bw, cfoHz = 0.0, ppm = 0.0, snrDb = 20.0)
            assertArrayEquals("delay $f chip", payload, receive(re, im).single { it.crcOk }.payload)
        }
        for (ppm in listOf(-20.0, -12.0, 7.0, 20.0)) {
            val (re, im) = LoraTx.waveform(frame, sf, bw, 2 * bw, delay = 0.0411 + 0.31 / bw, cfoHz = fc * ppm * 1e-6, ppm = ppm, snrDb = -8.0, seed = 11)
            val got = receive(re, im).single()
            assertTrue("ppm $ppm", got.crcOk)
            assertEquals(fc * ppm * 1e-6, got.cfoHz, 150.0)
        }
    }

    @Test fun decodesNearSensitivityLimit() {
        val payload = meshPacket("-14 dB")
        val frame = LoraTx.frame(LoraTx.symbols(payload, sf, 1), 0x2B)
        val (re, im) = LoraTx.waveform(frame, sf, bw, 2 * bw, delay = 0.033, cfoHz = 2500.0, ppm = 2500 / fc * 1e6, snrDb = -14.0, seed = 5)
        assertTrue(receive(re, im).single().crcOk)
    }

    @Test fun ignoresOtherSyncWords() {
        val payload = "LoRaWAN".toByteArray()
        val frame = LoraTx.frame(LoraTx.symbols(payload, sf, 1), 0x34)
        val (re, im) = LoraTx.waveform(frame, sf, bw, 2 * bw, delay = 0.01, cfoHz = 3000.0, ppm = 0.0, snrDb = 10.0)
        assertTrue(receive(re, im).isEmpty())
    }

    @Test fun fullChainFromHackRfSamples() {
        // 2 Msps, channel at +500 kHz from the tuned frequency, 8-bit quantised like the HackRF.
        val payload = meshPacket("Reçu par le HackRF")
        val frame = LoraTx.frame(LoraTx.symbols(payload, sf, 1), 0x2B)
        val fs = 2_000_000.0
        val (re, im) = LoraTx.waveform(frame, sf, bw, fs, delay = 0.02, cfoHz = 500_000.0 - 8_000.0, ppm = -9.2, snrDb = 0.0, seed = 3)
        val iq = ByteArray(re.size * 2) { i ->
            val v = (if (i % 2 == 0) re[i / 2] else im[i / 2]) * 25f
            v.toInt().coerceIn(-127, 127).toByte()
        }
        val dec = Decimator(4, 0.07, taps = 48)
        val got = ArrayList<LoraFrame>()
        val rx = LoraReceiver(sf, bw, fc, 0x2B) { got += it }
        val oRe = FloatArray(65536 / 2 / 4 + 8)
        val oIm = FloatArray(oRe.size)
        var i = 0
        while (i < iq.size) {
            val len = minOf(65536, iq.size - i)
            val chunk = iq.copyOfRange(i, i + len)
            val k = dec.process(chunk, len, oRe, oIm, 0)
            rx.feed(oRe, oIm, k)
            i += len
        }
        assertEquals(1, got.size)
        assertTrue(got[0].crcOk)
        val p = Meshtastic.decode(got[0].payload, Meshtastic.expandKey(byteArrayOf(1)), 8, 0, got[0].snrDb)!!
        assertEquals(Meshtastic.Content.Text("Reçu par le HackRF"), p.data!!.content)
        assertEquals(0x1a2b3c4dL, p.header.from)
        assertEquals(1, p.header.hops)
    }
}
