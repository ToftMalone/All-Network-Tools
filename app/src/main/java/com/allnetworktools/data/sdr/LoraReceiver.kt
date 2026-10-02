package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin

/** A LoRa frame whose header decoded; [crcOk] says whether the payload survived. */
class LoraFrame(
    val payload: ByteArray,
    val crcOk: Boolean,
    val hasCrc: Boolean,
    val cr: Int,
    /** LoRa-style SNR (signal over in-band noise), median over the corrected payload symbols. */
    val snrDb: Double,
    /** Frequency offset between the transmitter and the tuned frequency. */
    val cfoHz: Double,
)

/**
 * Software LoRa receiver for complex baseband at two samples per chip (sample rate = 2 × bandwidth).
 *
 * 1. Preamble: consecutive chip-spaced windows whose dechirped FFT peaks on the same bin.
 * 2. Start of frame: the strongest window matching a downchirp. Up- and downchirp peaks give
 *    kUp = cfo − d and kDown = cfo + d, separating the frequency offset from the timing offset d.
 * 3. Fine timing: a fractional timing error does not move the peak, it breaks the chirp's phase where it
 *    folds, so the symbol boundary is refined to 1/8 chip by maximising the peak energy, then every symbol is
 *    read at its exact position by interpolation.
 * 4. Sync word: the two upchirps before the downchirps must carry the network id (0x2B for Meshtastic).
 * 5. Payload: symbol by symbol with the frequency offset removed and the timing drift it implies (carrier and
 *    chip clock come from the same crystal), then the bit-level decoding of [LoraPhy].
 */
class LoraReceiver(
    private val sf: Int,
    bandwidthHz: Double,
    private val centerHz: Double,
    syncWord: Int,
    private val ldro: Boolean = LoraPhy.lowDataRate(sf, bandwidthHz),
    private val onFrame: (LoraFrame) -> Unit,
) {
    private val n = 1 shl sf
    private val bw = bandwidthHz
    private val fft = Fft(n)
    private val sync1 = ((syncWord shr 4) and 0x0F) * 8
    private val sync2 = (syncWord and 0x0F) * 8
    private val symLen = (OS * n).toDouble() // samples per symbol

    // e^{j(πm²/N − πm)}: the base upchirp at one sample per chip.
    private val upRe = FloatArray(n)
    private val upIm = FloatArray(n)

    // Windowed-sinc fractional interpolator: INTERP_PHASES fractional delays × 8 taps.
    private val interp = Array(INTERP_PHASES + 1) { p ->
        val mu = p.toDouble() / INTERP_PHASES
        val taps = DoubleArray(8) { k ->
            val x = (k - 3) - mu
            val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
            val w = 0.42 + 0.5 * cos(PI * x / 4.5) + 0.08 * cos(2 * PI * x / 4.5)
            sinc * w
        }
        val s = taps.sum()
        FloatArray(8) { (taps[it] / s).toFloat() }
    }

    init {
        for (m in 0 until n) {
            val ph = (PI * m.toDouble() * m / n - PI * m) % (2 * PI)
            upRe[m] = cos(ph).toFloat(); upIm[m] = sin(ph).toFloat()
        }
    }

    // Ring buffer of the last CAP samples, addressed by absolute sample index (≥ 4 s at 500 kS/s).
    private val cap = 1 shl 21
    private val mask = cap - 1
    private val bufRe = FloatArray(cap)
    private val bufIm = FloatArray(cap)
    private var total = 0L

    private val wRe = FloatArray(n)
    private val wIm = FloatArray(n)

    private enum class State { Detect, SeekSfd, Payload }

    private var state = State.Detect
    private var next = 0L // start of the next detection window
    private val recent = ArrayDeque<Peak>()
    private var preamble = mutableListOf<Peak>()
    private var seekCount = 0
    private val downs = mutableListOf<Pair<Long, Peak>>()

    // Payload state.
    private var cfo = 0.0
    private var rho = 1.0
    private var payloadExact = 0.0
    private val snrs = ArrayList<Double>()
    private val symbols = ArrayList<Int>()
    private var header: LoraPhy.Header? = null
    private var needed = 8
    private val nibbles = ArrayList<Int>()

    /** Frames whose header decoded, including those that later failed their CRC. */
    var framesSeen = 0
        private set

    /** Diagnostics: preambles found, those whose sync word did not match, headers that failed their checksum. */
    var preambles = 0
        private set
    var syncMismatches = 0
        private set
    var headerErrors = 0
        private set

    /** The sync word read off the air the last time it did not match (e.g. 0x34 for LoRaWAN), or −1. */
    var lastSyncSeen = -1
        private set

    private class Peak(val bin: Int, val frac: Double, val power: Double, val energy3: Double, val ratio: Double, val snrDb: Double)

    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        for (i in 0 until count) {
            val k = (total + i).toInt() and mask
            bufRe[k] = re[i]; bufIm[k] = im[i]
        }
        total += count
        while (step()) Unit
    }

    /**
     * Dechirps the symbol starting at sample [start] (fractional positions are interpolated), after removing
     * [shiftBins] of frequency offset. [down] matches downchirps instead of upchirps.
     */
    private fun analyse(start: Double, down: Boolean, shiftBins: Double): Peak {
        val base = floor(start)
        val fracIdx = (((start - base) * INTERP_PHASES) + 0.5).toInt()
        val exact = fracIdx == 0 || fracIdx == INTERP_PHASES
        val first = base.toLong() + if (fracIdx == INTERP_PHASES) 1 else 0
        val taps = interp[fracIdx]
        val stepPh = -2 * PI * shiftBins / n
        for (m in 0 until n) {
            val at = first + OS * m
            var xr: Float
            var xi: Float
            if (exact) {
                val k = at.toInt() and mask
                xr = bufRe[k]; xi = bufIm[k]
            } else {
                xr = 0f; xi = 0f
                for (t in 0 until 8) {
                    val k = (at + t - 3).toInt() and mask
                    xr += taps[t] * bufRe[k]
                    xi += taps[t] * bufIm[k]
                }
            }
            if (shiftBins != 0.0) {
                val ph = stepPh * m
                val c = cos(ph).toFloat()
                val s = sin(ph).toFloat()
                val r = xr * c - xi * s
                xi = xr * s + xi * c
                xr = r
            }
            val cr = upRe[m]
            val ci = if (down) upIm[m] else -upIm[m]
            wRe[m] = xr * cr - xi * ci
            wIm[m] = xr * ci + xi * cr
        }
        fft.forward(wRe, wIm)
        var best = 0
        var bestP = -1.0
        var sum = 0.0
        for (k in 0 until n) {
            val p = wRe[k].toDouble() * wRe[k] + wIm[k].toDouble() * wIm[k]
            sum += p
            if (p > bestP) { bestP = p; best = k }
        }
        val a = (best - 1 + n) % n
        val b = (best + 1) % n
        val pa = wRe[a].toDouble() * wRe[a] + wIm[a].toDouble() * wIm[a]
        val pb = wRe[b].toDouble() * wRe[b] + wIm[b].toDouble() * wIm[b]
        // Jacobsen's estimator for the fractional bin of a rectangular-window tone.
        val nr = (wRe[a] - wRe[b]).toDouble()
        val ni = (wIm[a] - wIm[b]).toDouble()
        val dr = (2 * wRe[best] - wRe[a] - wRe[b]).toDouble()
        val di = (2 * wIm[best] - wIm[a] - wIm[b]).toDouble()
        val den = dr * dr + di * di
        val fr = if (den > 0) ((nr * dr + ni * di) / den).coerceIn(-0.5, 0.5) else 0.0
        val noise = ((sum - bestP) / (n - 1)).coerceAtLeast(1e-12)
        return Peak(best, fr, bestP, bestP + pa + pb, bestP / (sum / n), 10 * log10((bestP / noise) / n))
    }

    private fun circDiff(a: Int, b: Int): Int {
        var d = Math.floorMod(a - b, n)
        if (d > n / 2) d -= n
        return d
    }

    private fun wrap(x: Double, period: Double): Double {
        var v = x % period
        if (v >= period / 2) v -= period
        if (v < -period / 2) v += period
        return v
    }

    private fun step(): Boolean = when (state) {
        State.Detect -> detect()
        State.SeekSfd -> seekSfd()
        State.Payload -> payload()
    }

    private val window: Long get() = OS.toLong() * n

    private fun detect(): Boolean {
        if (next < total - cap + 4 * window) next = total - window // fell behind: resync near the newest samples
        if (total < next + window) return false
        val p = analyse(next.toDouble(), down = false, shiftBins = 0.0)
        next += window
        recent.addLast(p)
        if (recent.size > PREAMBLE_WINDOWS) recent.removeFirst()
        if (recent.size == PREAMBLE_WINDOWS && recent.all { it.ratio > DETECT_RATIO && abs(circDiff(it.bin, recent.last().bin)) <= BIN_TOLERANCE }) {
            preamble = recent.toMutableList()
            recent.clear()
            downs.clear()
            seekCount = 0
            preambles++
            state = State.SeekSfd
        }
        return true
    }

    private fun seekSfd(): Boolean {
        if (total < next + window + 8) return false
        val start = next
        next += window
        val up = analyse(start.toDouble(), down = false, shiftBins = 0.0)
        val dn = analyse(start.toDouble(), down = true, shiftBins = 0.0)
        val isDown = dn.ratio > DETECT_RATIO && dn.power > up.power * 2
        if (isDown) {
            downs += start to dn
        } else if (downs.isNotEmpty()) {
            synchronise()
            return true
        } else if (up.ratio > DETECT_RATIO && abs(circDiff(up.bin, preamble.last().bin)) <= BIN_TOLERANCE) {
            preamble += up
        }
        if (downs.size >= 3) { synchronise(); return true }
        if (++seekCount > 48) restart(next)
        return true
    }

    private fun restart(from: Long) {
        state = State.Detect
        next = from
        recent.clear()
    }

    private fun synchronise() {
        val (wDown, dn) = downs.maxBy { it.second.power }
        // Coarse: integer peaks of the preamble and of the strongest downchirp window.
        val kUp = preamble.groupingBy { it.bin }.eachCount().maxBy { it.value }.key.toDouble()
        val kDown = dn.bin.toDouble()
        var c0 = wrap((kUp + kDown) / 2, n.toDouble())
        if (abs(c0) > n / 4.0) c0 = wrap(c0 + n / 2.0, n.toDouble())
        val d0 = ((c0 - kUp) % n + n) % n
        val g0 = wDown + OS * d0

        // Fine timing: the boundary that concentrates the most energy in the upchirps before the start of frame
        // and in its downchirps.
        fun metric(tau: Double): Double {
            val g = g0 + OS * tau
            var m = 0.0
            for (k in 3..6) m += analyse(g - k * symLen, down = false, shiftBins = c0).energy3
            m += maxOf(analyse(g - symLen, down = true, shiftBins = c0).energy3, analyse(g, down = true, shiftBins = c0).energy3)
            return m
        }
        // The energy is blind to whole-chip shifts (they only move the peak), so search ±0.75 chip here and
        // settle the integer part below with the up/down pair.
        var bestTau = 0.0
        var bestM = -1.0
        var tau = -0.75
        while (tau <= 0.75 + 1e-9) {
            val m = metric(tau)
            if (m > bestM) { bestM = m; bestTau = tau }
            tau += 0.125
        }
        val ml = metric(bestTau - 0.125)
        val mr = metric(bestTau + 0.125)
        val curv = ml - 2 * bestM + mr
        if (curv < 0) bestTau += (0.125 * 0.5 * (ml - mr) / curv).coerceIn(-0.0625, 0.0625)
        var g = g0 + OS * bestTau

        // Aligned to within a fraction of a chip but possibly k whole chips late: an upchirp then peaks at
        // cfo + k and a downchirp at cfo − k. Read both precisely (no correction applied) and solve.
        fun position(p: Peak) = p.bin + p.frac
        val downAt = if (analyse(g - symLen, down = true, shiftBins = 0.0).energy3 >= analyse(g, down = true, shiftBins = 0.0).energy3) g - symLen else g
        val dPos = position(analyse(downAt, down = true, shiftBins = 0.0))
        val refBin = Math.floorMod(Math.round(c0).toInt(), n)
        val ups = (4..8).map { analyse(g - it * symLen, down = false, shiftBins = 0.0) }
            .filter { abs(circDiff(it.bin, refBin)) <= 3 }
            .map { refBin + circDiff(it.bin, refBin) + it.frac }
        val uPos = if (ups.isNotEmpty()) ups.sorted()[ups.size / 2] else c0
        val half = wrap(uPos - dPos, n.toDouble()) / 2 // = k
        val c = wrap(dPos + half, n.toDouble())
        g -= OS * half
        cfo = c
        rho = 1 - (c * bw / n) / centerHz

        fun sym(at: Double): Pair<Boolean, Int> {
            val u = analyse(at, down = false, shiftBins = c)
            val w = analyse(at, down = true, shiftBins = c)
            return (w.power > u.power) to u.bin
        }
        val s0 = listOf(g - symLen, g - 2 * symLen, g, g - 3 * symLen).firstOrNull { b ->
            if (!sym(b).first) return@firstOrNull false
            val (d2, v2) = sym(b - symLen)
            val (d1, v1) = sym(b - 2 * symLen)
            !d1 && !d2 && abs(circDiff(v1, sync1)) <= 1 && abs(circDiff(v2, sync2)) <= 1
        }
        if (s0 == null) {
            syncMismatches++
            // What sync word is on the air? Read the two symbols before the first downchirp.
            listOf(g - symLen, g - 2 * symLen, g).firstOrNull { b -> sym(b).first }?.let { b ->
                val v1 = sym(b - 2 * symLen).second
                val v2 = sym(b - symLen).second
                lastSyncSeen = (((v1 + 4) / 8) and 0xF shl 4) or (((v2 + 4) / 8) and 0xF)
            }
            restart(wDown + window)
            return
        }
        payloadExact = s0 + 2.25 * symLen
        symbols.clear()
        nibbles.clear()
        snrs.clear()
        header = null
        needed = 8
        state = State.Payload
    }

    private fun payload(): Boolean {
        val i = symbols.size
        val exact = payloadExact + i * symLen * rho
        if (total < exact.toLong() + window + 8) return false
        if (exact < total - cap + 8) { restart(total - window); return true }
        val p = analyse(exact, down = false, shiftBins = cfo)
        snrs += p.snrDb
        symbols += p.bin
        val end = (exact + symLen).toLong()
        if (symbols.size == 8 && header == null) {
            val vals = IntArray(8) { LoraPhy.binToValue(symbols[it], sf, reduced = true) }
            val cws = LoraPhy.deinterleave(vals, sf - 2, 8)
            val nib = cws.map { LoraPhy.hammingDecode(it, 4) }
            val h = LoraPhy.header(nib.take(5).toIntArray())
            if (h == null) {
                headerErrors++
                restart(end)
                return true
            }
            framesSeen++
            header = h
            nibbles.addAll(nib.drop(5))
            needed = 8 + LoraPhy.payloadSymbols(sf, h.payloadLen, h.cr, h.hasCrc, ldro)
        } else if (symbols.size > 8) {
            val h = header!!
            val block = h.cr + 4
            if ((symbols.size - 8) % block == 0) {
                val from = symbols.size - block
                val vals = IntArray(block) { LoraPhy.binToValue(symbols[from + it], sf, reduced = ldro) }
                LoraPhy.deinterleave(vals, if (ldro) sf - 2 else sf, block).forEach { nibbles += LoraPhy.hammingDecode(it, h.cr) }
            }
        }
        if (header != null && symbols.size >= needed) {
            finish(header!!)
            restart(end)
        }
        return true
    }

    private fun finish(h: LoraPhy.Header) {
        val len = h.payloadLen
        if (nibbles.size < 2 * len + (if (h.hasCrc) 4 else 0)) return
        val payload = ByteArray(len) { i ->
            val w = LoraPhy.whitening[i]
            val lo = nibbles[2 * i] xor (w and 0x0F)
            val hi = nibbles[2 * i + 1] xor (w shr 4)
            ((hi shl 4) or lo).toByte()
        }
        val crcOk = if (h.hasCrc) {
            val got = nibbles[2 * len] or (nibbles[2 * len + 1] shl 4) or (nibbles[2 * len + 2] shl 8) or (nibbles[2 * len + 3] shl 12)
            got == LoraPhy.payloadCrc(payload)
        } else true
        val snr = snrs.sorted()[snrs.size / 2]
        onFrame(LoraFrame(payload, crcOk, h.hasCrc, h.cr, snr, cfo * bw / n))
    }

    companion object {
        /** Samples per chip expected on input. */
        const val OS = 2
        private const val PREAMBLE_WINDOWS = 5
        private const val DETECT_RATIO = 10.0

        /**
         * Preamble windows may peak this many bins apart. A fractional timing offset puts a phase jump in the
         * dechirped tone where the chirp folds; with the fold near the middle of the window the peak splits into two
         * lobes one bin either side of the true bin, and the stronger one flips from window to window.
         */
        private const val BIN_TOLERANCE = 2
        private const val INTERP_PHASES = 64
    }
}
