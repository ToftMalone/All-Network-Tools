package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size. */
class Fft(val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    fun forward(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    // e^{-j2πk/n}
                    val wr = cosT[k]
                    val wi = -sinT[k]
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}

/**
 * HackRF front end: interleaved signed 8-bit I/Q at [inRate], with the wanted channel [offsetQuarter] (+fs/4)
 * away from the tuned frequency. Shifts it down to 0 Hz, low-pass filters and decimates by [decim].
 * Tuning fs/4 away keeps the HackRF's DC spike out of the channel and makes the mixer a sign swap.
 */
class Decimator(private val decim: Int, cutoff: Double, taps: Int = 64) {
    private val h: FloatArray
    private val histRe = FloatArray(taps * 2)
    private val histIm = FloatArray(taps * 2)
    private val nTaps = taps
    private var pos = 0
    private var phase = 0
    private var mix = 0

    init {
        // Blackman-windowed sinc, unity gain at DC.
        val raw = DoubleArray(taps) { i ->
            val m = i - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            sinc * w
        }
        val sum = raw.sum()
        h = FloatArray(taps) { (raw[it] / sum).toFloat() }
    }

    /**
     * Consumes [len] bytes of I/Q from [buf] and appends decimated samples to [outRe]/[outIm] from [outOff].
     * Returns how many output samples were written.
     */
    fun process(buf: ByteArray, len: Int, outRe: FloatArray, outIm: FloatArray, outOff: Int): Int {
        var o = outOff
        var i = 0
        while (i + 1 < len) {
            val re = buf[i].toFloat()
            val im = buf[i + 1].toFloat()
            i += 2
            // Multiply by e^{-jπn/2}: 1, -j, -1, +j.
            val mr: Float
            val mi: Float
            when (mix) {
                0 -> { mr = re; mi = im }
                1 -> { mr = im; mi = -re }
                2 -> { mr = -re; mi = -im }
                else -> { mr = -im; mi = re }
            }
            mix = (mix + 1) and 3
            // Doubled history so a filter window is always contiguous.
            histRe[pos] = mr; histRe[pos + nTaps] = mr
            histIm[pos] = mi; histIm[pos + nTaps] = mi
            pos++
            if (pos == nTaps) pos = 0
            phase++
            if (phase == decim) {
                phase = 0
                var ar = 0f
                var ai = 0f
                // Oldest sample first: hist[pos .. pos + nTaps).
                for (k in 0 until nTaps) {
                    val c = h[k]
                    ar += c * histRe[pos + k]
                    ai += c * histIm[pos + k]
                }
                outRe[o] = ar / 128f
                outIm[o] = ai / 128f
                o++
            }
        }
        return o - outOff
    }
}
