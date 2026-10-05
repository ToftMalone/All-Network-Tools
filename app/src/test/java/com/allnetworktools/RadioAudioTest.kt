package com.allnetworktools

import com.allnetworktools.data.radio.AudioAnalyzer
import com.allnetworktools.data.radio.AudioFrame
import com.allnetworktools.data.radio.DtmfDetector
import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.AprsReport
import com.allnetworktools.data.sdr.Ax25
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioAudioTest {
    private val rate = 48_000

    private fun tone(out: MutableList<Float>, hz: List<Double>, ms: Int, amp: Float) {
        val n = rate * ms / 1000
        val base = out.size
        for (i in 0 until n) out += hz.sumOf { sin(2 * PI * it * (base + i) / rate) }.toFloat() * amp / hz.size
    }

    private fun silence(out: MutableList<Float>, ms: Int) { repeat(rate * ms / 1000) { out += 0f } }

    private val rows = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
    private val cols = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)
    private val keys = arrayOf("123A", "456B", "789C", "*0#D")

    private fun dtmf(sequence: String, amp: Float = 0.4f, noise: Float = 0f): FloatArray {
        val out = ArrayList<Float>()
        silence(out, 100)
        for (k in sequence) {
            val r = keys.indexOfFirst { k in it }
            tone(out, listOf(rows[r], cols[keys[r].indexOf(k)]), 80, amp)
            silence(out, 80)
        }
        val rnd = Random(3)
        return FloatArray(out.size) { out[it] + noise * (rnd.nextFloat() - 0.5f) }
    }

    private fun heard(x: FloatArray): String {
        val got = StringBuilder()
        val d = DtmfDetector(rate) { got.append(it) }
        var i = 0
        while (i < x.size) { val n = minOf(1000, x.size - i); d.feed(x.copyOfRange(i, i + n), n); i += n }
        return got.toString()
    }

    @Test fun decodesDtmfKeys() {
        assertEquals("5#1A*0", heard(dtmf("5#1A*0")))
    }

    @Test fun repeatedKeysAreSeenOncePerPress() {
        assertEquals("112", heard(dtmf("112")))
    }

    @Test fun dtmfSurvivesNoise() {
        assertEquals("4793", heard(dtmf("4793", amp = 0.3f, noise = 0.12f)))
    }

    @Test fun noiseAndSingleTonesAreNotKeys() {
        val rnd = Random(9)
        assertEquals("", heard(FloatArray(rate * 3) { 0.3f * (rnd.nextFloat() - 0.5f) }))
        val out = ArrayList<Float>(); tone(out, listOf(1000.0), 2000, 0.5f)
        assertEquals("", heard(out.toFloatArray()))
        val two = ArrayList<Float>(); tone(two, listOf(697.0, 770.0), 500, 0.5f) // two tones of the same group
        assertEquals("", heard(two.toFloatArray()))
    }

    @Test fun levelAndSpectrumOfASine() {
        val frames = ArrayList<AudioFrame>()
        val a = AudioAnalyzer(rate, { frames += it }, {}, {})
        val out = ArrayList<Float>(); tone(out, listOf(1000.0), 1000, 0.1f)
        a.feed(out.toFloatArray(), out.size)
        assertTrue(frames.size >= 15)
        val f = frames.last()
        assertEquals(-23.0, f.rmsDb.toDouble(), 0.5) // 0.1 / sqrt(2)
        val peakBin = f.spectrumDb.indices.maxBy { f.spectrumDb[it] }
        assertEquals(1000.0, peakBin * rate / AudioAnalyzer.FFT_SIZE.toDouble(), 30.0)
        assertEquals(-20.0, f.spectrumDb[peakBin].toDouble(), 3.0)
    }

    @Test fun silenceReadsAsVeryLow() {
        val frames = ArrayList<AudioFrame>()
        val a = AudioAnalyzer(rate, { frames += it }, {}, {})
        a.feed(FloatArray(rate), rate)
        assertTrue(frames.last().rmsDb < -80f)
    }

    // ---- APRS from audio ---------------------------------------------------------------------

    private fun addr(call: String, last: Boolean): ByteArray {
        val (c, ssid) = call.split("-").let { it[0] to (it.getOrNull(1)?.toInt() ?: 0) }
        val out = ByteArray(7)
        for (i in 0 until 6) out[i] = ((c.getOrElse(i) { ' ' }.code) shl 1).toByte()
        out[6] = (0x60 or (ssid shl 1) or (if (last) 1 else 0)).toByte()
        return out
    }

    private fun ax25(src: String, info: String): ByteArray =
        addr("APRS", false) + addr(src, true) + byteArrayOf(0x03, 0xF0.toByte()) + info.toByteArray(Charsets.ISO_8859_1)

    private fun lineBits(frames: List<ByteArray>): IntArray {
        val out = ArrayList<Int>()
        repeat(40) { for (b in intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)) out += b }
        for (f in frames) {
            val data = ArrayList<Int>()
            for (byte in f) for (k in 0 until 8) data += (byte.toInt() shr k) and 1
            var crc = 0xFFFF
            for (x in data) crc = if ((crc xor x) and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            crc = crc xor 0xFFFF
            for (k in 0 until 16) data += (crc shr k) and 1
            var ones = 0
            for (x in data) { out += x; if (x == 1) { if (++ones == 5) { out += 0; ones = 0 } } else ones = 0 }
            repeat(12) { for (b in intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)) out += b }
        }
        var level = 1
        return IntArray(out.size) { if (out[it] == 0) level = 1 - level; level }
    }

    /** What a receiver's speaker plays: continuous-phase Bell 202 tones, the 2200 Hz one weaker (de-emphasis). */
    private fun afskAudio(line: IntArray, noise: Float): FloatArray {
        val spb = rate / 1200.0
        val n = ((line.size + 30) * spb).toInt()
        val rnd = Random(5)
        var phase = 0.0
        return FloatArray(n) { i ->
            val bit = (i / spb).toInt()
            val space = bit < line.size && line[bit] == 0
            phase += 2 * PI * (if (space) 2200.0 else 1200.0) / rate
            (sin(phase) * (if (space) 0.2 else 0.35)).toFloat() + noise * (rnd.nextFloat() - 0.5f)
        }
    }

    private fun decodeAprs(audio: FloatArray): List<AprsReport> {
        val got = ArrayList<AprsReport>()
        val a = AudioAnalyzer(rate, {}, {}, { b -> Ax25.parse(b)?.let(Aprs::decode)?.let { got += it } })
        var i = 0
        while (i < audio.size) { val n = minOf(2400, audio.size - i); a.feed(audio.copyOfRange(i, i + n), n); i += n }
        return got
    }

    @Test fun decodesAprsFromAudio() {
        val frames = listOf(ax25("F4ABC-9", "!4903.50N/07201.75W>Test 001234"), ax25("F5XYZ", "=4851.40N/00221.13E-Maison"))
        val got = decodeAprs(afskAudio(lineBits(frames), noise = 0.05f))
        assertEquals(listOf("F4ABC-9", "F5XYZ"), got.map { it.src })
        assertEquals(49.0583, got[0].lat!!, 1e-4)
    }

    @Test fun aprsAudioNoiseAloneDecodesNothing() {
        val rnd = Random(11)
        assertTrue(decodeAprs(FloatArray(rate * 5) { 0.6f * (rnd.nextFloat() - 0.5f) }).isEmpty())
        assertTrue(log10(1.0) == 0.0)
    }
}
