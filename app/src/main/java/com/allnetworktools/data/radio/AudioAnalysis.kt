package com.allnetworktools.data.radio

import com.allnetworktools.data.sdr.AfskDemodulator
import com.allnetworktools.data.sdr.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * DTMF digits (two simultaneous tones, one per group) found with eight Goertzel filters over 30 ms blocks. A digit counts
 * once heard on two consecutive blocks and is reported once per key press.
 */
class DtmfDetector(private val rate: Int = 48_000, private val onDigit: (Char) -> Unit) {
    private val block = rate * 30 / 1000
    private val coeff = DoubleArray(8) { 2 * cos(2 * PI * FREQS[it] / rate) }
    private val s1 = DoubleArray(8)
    private val s2 = DoubleArray(8)
    private var n = 0
    private var energy = 0.0
    private var candidate: Char? = null
    private var candidateBlocks = 0
    private var reported: Char? = null
    private var quiet = 0

    fun feed(x: FloatArray, count: Int) {
        for (i in 0 until count) {
            val v = x[i].toDouble()
            energy += v * v
            for (k in 0 until 8) {
                val s = v + coeff[k] * s1[k] - s2[k]
                s2[k] = s1[k]
                s1[k] = s
            }
            if (++n == block) finishBlock()
        }
    }

    private fun finishBlock() {
        // Share of the block's energy carried by each tone: 1.0 for a pure tone.
        val frac = DoubleArray(8) {
            val p = s1[it] * s1[it] + s2[it] * s2[it] - coeff[it] * s1[it] * s2[it]
            if (energy > 0) 2 * p / (block * energy) else 0.0
        }
        val meanSquare = energy / block
        n = 0; energy = 0.0
        s1.fill(0.0); s2.fill(0.0)

        fun peak(from: Int): Int = (from until from + 4).maxBy { frac[it] }
        fun second(from: Int, best: Int): Double = (from until from + 4).filter { it != best }.maxOf { frac[it] }
        val lo = peak(0)
        val hi = peak(4)
        val twist = frac[hi] / frac[lo].coerceAtLeast(1e-12)
        val digit = if (
            meanSquare > 1e-5 && frac[lo] + frac[hi] > 0.55 &&
            frac[lo] > 4 * second(0, lo) && frac[hi] > 4 * second(4, hi) && twist in 0.12..8.0
        ) KEYS[lo][hi - 4] else null

        if (digit == null) {
            candidate = null; candidateBlocks = 0
            if (++quiet >= 2) reported = null
            return
        }
        quiet = 0
        if (digit == candidate) candidateBlocks++ else { candidate = digit; candidateBlocks = 1 }
        if (candidateBlocks >= 2 && digit != reported) {
            reported = digit
            onDigit(digit)
        }
    }

    private companion object {
        val FREQS = doubleArrayOf(697.0, 770.0, 852.0, 941.0, 1209.0, 1336.0, 1477.0, 1633.0)
        val KEYS = arrayOf(charArrayOf('1', '2', '3', 'A'), charArrayOf('4', '5', '6', 'B'), charArrayOf('7', '8', '9', 'C'), charArrayOf('*', '0', '#', 'D'))
    }
}

/** One 50 ms look at the audio: loudness, and the spectrum from 0 to [AudioAnalyzer.TOP_HZ]. */
class AudioFrame(val rmsDb: Float, val peakDb: Float, val spectrumDb: FloatArray)

/**
 * What a receiver says through its speaker or headphone output: loudness (is the squelch open?), a 0–4 kHz spectrum,
 * DTMF digits and AFSK packet frames. Mono floats in [-1, 1] at [rate] Hz.
 */
class AudioAnalyzer(
    val rate: Int = 48_000,
    private val onFrame: (AudioFrame) -> Unit,
    onDigit: (Char) -> Unit,
    onPacket: (ByteArray) -> Unit,
) {
    private val dtmf = DtmfDetector(rate, onDigit)
    private val afsk = AfskDemodulator(rate.toDouble(), onPacket)
    private val fft = Fft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val hop = rate / 20
    private var sinceFrame = 0
    private var sumSq = 0.0
    private var peak = 0f
    private var counted = 0
    private val bins = (TOP_HZ * FFT_SIZE / rate)

    fun feed(x: FloatArray, count: Int) {
        dtmf.feed(x, count)
        afsk.feedAudio(x, count)
        for (i in 0 until count) {
            val v = x[i]
            ring[ringPos] = v
            ringPos = (ringPos + 1) % FFT_SIZE
            sumSq += v * v
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            counted++
            if (++sinceFrame >= hop) { sinceFrame = 0; emit() }
        }
    }

    private fun emit() {
        for (i in 0 until FFT_SIZE) { re[i] = ring[(ringPos + i) % FFT_SIZE] * window[i]; im[i] = 0f }
        fft.forward(re, im)
        // A full-scale sine through a Hann window peaks at N/4 in magnitude.
        val ref = FFT_SIZE / 4f
        val spectrum = FloatArray(bins) { k ->
            val m = sqrt(re[k] * re[k] + im[k] * im[k]) / ref
            20f * log10(m.coerceAtLeast(1e-6f))
        }
        val rms = sqrt(sumSq / counted.coerceAtLeast(1)).toFloat()
        onFrame(AudioFrame(db(rms), db(peak), spectrum))
        sumSq = 0.0; peak = 0f; counted = 0
    }

    private fun db(v: Float) = 20f * log10(v.coerceAtLeast(1e-5f))

    companion object {
        const val FFT_SIZE = 2048
        const val TOP_HZ = 4000
    }
}
