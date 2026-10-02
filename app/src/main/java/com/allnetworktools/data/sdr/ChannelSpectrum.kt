package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/** What the channel looked like over the last second: where the energy sits and how wide it is. */
class ChannelView(val bins: FloatArray, val offsetKHz: Double, val widthKHz: Double, val peakDb: Double, val floorDb: Double) {
    /** Strong enough over the floor to be a signal rather than noise. */
    val hasSignal: Boolean get() = peakDb - floorDb >= 8.0
}

/** Averaged spectrum of the decimated channel (complex, [rate] samples/s), one snapshot per [snapshot] call. */
class ChannelSpectrum(private val rate: Double) {
    private val n = 512
    private val fft = Fft(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val acc = DoubleArray(n)
    private val maxHold = DoubleArray(n)
    private var fill = 0
    private var blocks = 0

    fun feed(sRe: FloatArray, sIm: FloatArray, count: Int) {
        for (i in 0 until count) {
            re[fill] = sRe[i] * window[fill]; im[fill] = sIm[i] * window[fill]
            if (++fill == n) {
                fft.forward(re, im)
                for (k in 0 until n) acc[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
                // A packet fills only part of the second: the max-hold of 8-block averages keeps it visible.
                if (++blocks % 8 == 0) for (k in 0 until n) { if (acc[k] > maxHold[k]) maxHold[k] = acc[k]; acc[k] = 0.0 }
                fill = 0
            }
        }
    }

    /** Returns the view of the period since the previous call, or null if nothing was fed. */
    fun snapshot(): ChannelView? {
        if (blocks < 8) return null
        val src = maxHold
        val db = DoubleArray(n) { 10 * log10(src[it] / 8 / n + 1e-12) }
        // Natural order: −rate/2 … +rate/2.
        val raw = DoubleArray(n) { db[(it + n / 2) % n] }
        // A LoRa signal is flat across its band, so smooth the bins and take the floor low enough to stay under it.
        val ordered = DoubleArray(n) { i -> var a = 0.0; var c = 0; for (j in maxOf(0, i - 3)..minOf(n - 1, i + 3)) { a += raw[j]; c++ }; a / c }
        val floor = ordered.sorted()[n / 10]
        var pk = 0
        for (k in 1 until n) if (ordered[k] > ordered[pk]) pk = k
        val peak = ordered[pk]
        // Width: contiguous bins within 6 dB of the peak (the occupied part of the channel), bridging small dips.
        val thr = maxOf(peak - 6.0, floor + 4.0)
        var lo = pk
        var gap = 0
        var k = pk
        while (k > 0 && gap < 8) { k--; if (ordered[k] >= thr) { lo = k; gap = 0 } else gap++ }
        var hi = pk
        gap = 0; k = pk
        while (k < n - 1 && gap < 8) { k++; if (ordered[k] >= thr) { hi = k; gap = 0 } else gap++ }
        val binHz = rate / n
        val centre = (lo + hi) / 2.0
        val shown = FloatArray(64) { b ->
            var m = -200.0
            for (j in b * (n / 64) until (b + 1) * (n / 64)) if (ordered[j] > m) m = ordered[j]
            m.toFloat()
        }
        acc.fill(0.0); maxHold.fill(0.0); blocks = 0
        return ChannelView(shown, (centre - n / 2) * binHz / 1000, (hi - lo + 1) * binHz / 1000, peak, floor)
    }
}
