package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/** One burst with the shape of a DJI DroneID transmission. */
class DroneBurst(
    val timeS: Double,
    val durationUs: Double,
    val bandwidthMHz: Double,
    /** Centre of the burst relative to the tuned frequency. */
    val offsetMHz: Double,
    val levelDb: Double,
    val flatnessDb: Double,
)

/**
 * Looks for the radio signature of DJI's DroneID broadcast, from complex I/Q (signed bytes) at [rate] samples/s, any rate
 * from about 12 MS/s up: a flat OFDM burst about 9–10 MHz wide lasting 560–720 µs, well above the noise, that comes back
 * every few hundred milliseconds (published measurements: Ruhr-Universität Bochum, DroneSecurity).
 *
 * This is presence detection from the power spectrum only. Nothing in the burst is demodulated or decoded, so nothing
 * about the drone or its pilot is learnt beyond "a transmitter of this kind is active nearby".
 */
class DroneIdDetector(private val rate: Double, private val onBurst: (DroneBurst) -> Unit) {
    private val blk = (BLOCK_S * rate).toInt()
    private val ringMask = (1 shl 17) - 1
    private val ringRe = FloatArray(ringMask + 1)
    private val ringIm = FloatArray(ringMask + 1)
    private var total = 0L

    private var acc = 0.0
    private var inBlock = 0
    private val power = DoubleArray(FLOOR_BLOCKS) // recent block powers, for the floor
    private var powerN = 0
    private var blockIndex = 0L
    private val recent = DoubleArray(3)
    private var floor = 0.0
    private var floorAge = 0
    private val sorted = DoubleArray(FLOOR_BLOCKS)

    private var runStart = -1L // block index where the signal rose above the threshold
    private var runPower = 0.0
    private var runBlocks = 0

    private val nfft = 1024
    private val fft = Fft(nfft)
    private val win = FloatArray(nfft) { (0.5 - 0.5 * cos(2 * PI * it / nfft)).toFloat() }
    private val fr = FloatArray(nfft)
    private val fi = FloatArray(nfft)
    private val psd = DoubleArray(nfft)

    var bursts = 0
        private set

    fun feed(buf: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            val re = buf[i] / 128f
            val im = buf[i + 1] / 128f
            i += 2
            val k = (total and ringMask.toLong()).toInt()
            ringRe[k] = re; ringIm[k] = im
            total++
            acc += (re * re + im * im).toDouble()
            if (++inBlock == blk) { block(acc / blk); acc = 0.0; inBlock = 0 }
        }
    }

    private fun block(p: Double) {
        val idx = blockIndex++
        power[(idx % FLOOR_BLOCKS).toInt()] = p
        if (powerN < FLOOR_BLOCKS) powerN++
        recent[(idx % 3).toInt()] = p
        if (idx < 2) return
        // Smoothed over three blocks, centred on the one before: rides over the dips of an OFDM envelope.
        val smooth = (recent[0] + recent[1] + recent[2]) / 3
        if (++floorAge >= 500 || floor == 0.0) { floorAge = 0; refreshFloor() }
        if (powerN < FLOOR_BLOCKS / 4) return
        val on = smooth > floor * RISE
        val centreBlock = idx - 1
        if (on) {
            if (runStart < 0) { runStart = centreBlock; runPower = 0.0; runBlocks = 0 }
            runPower += smooth; runBlocks++
        } else if (runStart >= 0) {
            val duration = (centreBlock - runStart) * BLOCK_S
            if (duration in MIN_S..MAX_S) analyse(runStart, centreBlock, runPower / runBlocks)
            runStart = -1
        }
        // A run that never ends is a carrier or a Wi-Fi stream, not a burst.
        if (runStart >= 0 && (centreBlock - runStart) * BLOCK_S > MAX_S) runStart = -1
    }

    /** The 10th percentile of the recent block powers: the noise floor, immune to the bursts themselves. */
    private fun refreshFloor() {
        val n = powerN
        System.arraycopy(power, 0, sorted, 0, n)
        java.util.Arrays.sort(sorted, 0, n)
        floor = sorted[n / 10].coerceAtLeast(1e-9)
    }

    private fun analyse(startBlock: Long, endBlock: Long, meanPower: Double) {
        val from = startBlock * blk
        val to = endBlock * blk
        if (total - from > ringMask) return
        val segments = ((to - from - nfft) / (nfft / 2)).toInt() + 1
        if (segments < 4) return
        psd.fill(0.0)
        for (s in 0 until segments) {
            val base = from + s.toLong() * (nfft / 2)
            for (j in 0 until nfft) {
                val k = ((base + j) and ringMask.toLong()).toInt()
                fr[j] = ringRe[k] * win[j]; fi[j] = ringIm[k] * win[j]
            }
            fft.forward(fr, fi)
            for (j in 0 until nfft) psd[j] += (fr[j] * fr[j] + fi[j] * fi[j]).toDouble()
        }
        // Natural frequency order, then the widest stretch above the mean.
        val p = DoubleArray(nfft) { psd[(it + nfft / 2) % nfft] / segments }
        val mean = p.average()
        var bestLen = 0
        var bestStart = 0
        var st = -1
        for (j in 0..nfft) {
            val above = j < nfft && p[j] > mean
            if (above && st < 0) st = j
            if (!above && st >= 0) {
                if (j - st > bestLen) { bestLen = j - st; bestStart = st }
                st = -1
            }
        }
        if (bestLen == 0) return
        val binHz = rate / nfft
        val bw = bestLen * binHz / 1e6
        val centre = (bestStart + bestLen / 2.0 - nfft / 2) * binHz / 1e6
        var sum = 0.0
        var sumSq = 0.0
        for (j in bestStart until bestStart + bestLen) { val d = 10 * log10(p[j] + 1e-30); sum += d; sumSq += d * d }
        val m = sum / bestLen
        val flat = sqrt((sumSq / bestLen - m * m).coerceAtLeast(0.0))
        val level = 10 * log10(meanPower / floor)
        val inside = abs(centre) + bw / 2 < rate / 2e6 - 0.4 // the burst must sit wholly inside the captured window
        if (bw in MIN_BW..MAX_BW && flat <= MAX_FLAT && level >= MIN_LEVEL_DB && inside) {
            bursts++
            onBurst(DroneBurst((startBlock * blk) / rate, (endBlock - startBlock) * BLOCK_S * 1e6, bw, centre, level, flat))
        }
    }

    companion object {
        private const val BLOCK_S = 2e-6
        private const val FLOOR_BLOCKS = 10_000 // 20 ms
        private const val RISE = 6.3 // 8 dB
        private const val MIN_S = 560e-6
        private const val MAX_S = 720e-6
        private const val MIN_BW = 7.5
        private const val MAX_BW = 11.0
        private const val MAX_FLAT = 3.0
        private const val MIN_LEVEL_DB = 8.0
    }
}

/** A frequency window where DroneID-like bursts were seen, with whether they repeat at the expected rhythm. */
class DroneWindow(val centreMhz: Double) {
    var count = 0
        private set
    var lastBw = 0.0
        private set
    var lastLevel = 0.0
        private set
    var lastSeenMs = 0L
        private set
    private val times = ArrayList<Double>()

    fun add(b: DroneBurst, nowMs: Long, windowStartS: Double) {
        count++
        lastBw = b.bandwidthMHz; lastLevel = b.levelDb; lastSeenMs = nowMs
        times += windowStartS + b.timeS
    }

    /** Two bursts spaced by one to four times about 600 ms: the rhythm of the broadcast, which ordinary traffic lacks. */
    val periodic: Boolean get() = times.indices.any { i ->
        (i + 1 until times.size).any { j ->
            val d = times[j] - times[i]
            (1..4).any { m -> abs(d - 0.6 * m) < 0.06 * m + 0.02 }
        }
    }
}

object DroneBands {
    /**
     * Centres where DroneID bursts have been recorded (proto17/dji_droneid notes: 2399.5 to 2459.5 MHz every 15 MHz,
     * 5756.5 to 5796.5 MHz every 20 MHz), extended by one step at each end. Each 20 MS/s capture is centred on one, so a
     * 10 MHz burst always falls wholly inside it.
     */
    fun windows(band: Int): List<Double> = when (band) {
        24 -> (0 until 6).map { 2399.5 + 15 * it }
        58 -> (0 until 6).map { 5736.5 + 20 * it }
        else -> windows(24) + windows(58)
    }
}

/** Where DJI's DroneID stands after the bursts heard so far. */
data class DjiDetection(
    val bursts: Int,
    val confirmed: Boolean,
    val lastCentreMhz: Double,
    val lastLevelDb: Double,
    val levels: List<Double>,
    val frequencies: List<Double>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/**
 * Pools the DroneID-shaped bursts of every window. The broadcast hops between frequencies, so a single window rarely
 * hears two in a row: three bursts within [WINDOW_MS], or two at the 600 ms rhythm in one window, confirm a DJI drone.
 */
class DjiTracker {
    private class Hit(val atMs: Long, val centreMhz: Double, val level: Double)

    private val hits = ArrayList<Hit>()
    private var periodicAtMs = -1L
    private var firstMs = -1L

    fun add(windowMhz: Double, b: DroneBurst, atMs: Long) {
        if (firstMs < 0) firstMs = atMs
        hits += Hit(atMs, windowMhz + b.offsetMHz, b.levelDb)
        if (hits.size > 200) hits.removeAt(0)
    }

    /** A window reported two bursts at the broadcast's rhythm. */
    fun periodic(atMs: Long) { periodicAtMs = atMs }

    fun state(nowMs: Long): DjiDetection? {
        hits.removeAll { nowMs - it.atMs > FORGET_MS }
        val last = hits.lastOrNull() ?: run { firstMs = -1; return null }
        val recent = hits.count { nowMs - it.atMs <= WINDOW_MS }
        val confirmed = recent >= 3 || (periodicAtMs >= 0 && nowMs - periodicAtMs <= WINDOW_MS)
        return DjiDetection(
            bursts = hits.size, confirmed = confirmed, lastCentreMhz = last.centreMhz, lastLevelDb = last.level,
            levels = hits.takeLast(30).map { it.level },
            frequencies = hits.map { Math.round(it.centreMhz * 2) / 2.0 }.distinct().sorted(),
            firstSeenMs = firstMs, lastSeenMs = last.atMs,
        )
    }

    fun clear() { hits.clear(); periodicAtMs = -1; firstMs = -1 }

    private companion object {
        const val WINDOW_MS = 20_000L
        const val FORGET_MS = 60_000L
    }
}
