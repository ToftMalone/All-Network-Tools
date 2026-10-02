package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A 5.8 GHz analogue FPV channel (the six standard bands of 8 channels). */
data class FpvChannel(val band: Char, val number: Int, val mhz: Int) {
    val name: String get() = "$band$number"
}

object FpvChannels {
    private val table = mapOf(
        'A' to intArrayOf(5865, 5845, 5825, 5805, 5785, 5765, 5745, 5725),
        'B' to intArrayOf(5733, 5752, 5771, 5790, 5809, 5828, 5847, 5866),
        'E' to intArrayOf(5705, 5685, 5665, 5645, 5885, 5905, 5925, 5945),
        'F' to intArrayOf(5740, 5760, 5780, 5800, 5820, 5840, 5860, 5880),
        'R' to intArrayOf(5658, 5695, 5732, 5769, 5806, 5843, 5880, 5917),
        'L' to intArrayOf(5362, 5399, 5436, 5473, 5510, 5547, 5584, 5621),
    )

    val bands: List<Char> = listOf('R', 'F', 'E', 'A', 'B', 'L')

    val all: List<FpvChannel> = bands.flatMap { b -> table.getValue(b).mapIndexed { i, f -> FpvChannel(b, i + 1, f) } }

    fun byName(name: String): FpvChannel? = all.firstOrNull { it.name == name }

    /** Channels sorted by frequency, for the scan order. */
    val byFrequency: List<FpvChannel> = all.sortedBy { it.mhz }
}

/** Atan2 to about 1e-4 rad: the discriminator runs at every sample of a 16 MS/s stream. */
internal fun fastAtan2(y: Float, x: Float): Float {
    val ax = abs(x)
    val ay = abs(y)
    val mx = max(ax, ay)
    if (mx < 1e-20f) return 0f
    val a = min(ax, ay) / mx
    val s = a * a
    var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
    if (ay > ax) r = 1.57079637f - r
    if (x < 0) r = 3.14159274f - r
    return if (y < 0) -r else r
}

/** What the analogue video decoder has seen so far. */
class VideoStats {
    var hPulses = 0
        internal set
    var hSpaced = 0 // pulses whose spacing is a line period
        internal set
    var vSyncs = 0
        internal set
    var fields = 0
        internal set
    var lineUs = 0.0
        internal set
    var linesPerField = 0
        internal set
    var polarity = 0 // +1: sync is the lowest frequency, −1: the highest, 0: unknown
        internal set

    /** Share of sync pulses that came one line after the previous one: close to 1 for real video, low for anything else. */
    val syncQuality: Double get() = if (hPulses < 8) 0.0 else hSpaced.toDouble() / hPulses

    val standard: String? get() = when {
        lineUs <= 0 -> null
        abs(lineUs - 64.0) < 0.5 -> "PAL"
        abs(lineUs - 63.56) < 0.35 -> "NTSC"
        else -> null
    }
}

/**
 * Analogue FPV video: the signal is frequency-modulated, with the picture as the baseband. From complex I/Q (signed
 * bytes) at 8 or 16 MS/s, centred on the channel: FM discriminator, 3.2 MHz low-pass (drops the colour subcarrier and keeps
 * luminance), decimation to 8 MS/s, then line and field synchronisation from the sync pulses (4.7 µs each line, half-line
 * wide pulses at the field). Colour is not decoded; the picture is the luminance, [WIDTH] pixels per line.
 */
class AnalogVideoDecoder(
    private val inputRate: Double,
    private val onLine: (field: Int, line: Int, pixels: ByteArray) -> Unit = { _, _, _ -> },
    private val onField: () -> Unit = {},
) {
    private val dec = (inputRate / RATE).toInt()
    private val taps = if (dec == 2) 31 else 15
    private val h: FloatArray = FloatArray(taps).also { c ->
        val fc = 3.2e6 / inputRate
        val m = (taps - 1) / 2.0
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - m
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            c[i] = (sinc * w).toFloat(); sum += sinc * w
        }
        for (i in 0 until taps) c[i] = (c[i] / sum).toFloat()
    }
    private val hist = FloatArray(2 * taps)
    private var hPos = 0
    private var phase = 0

    private var dcI = 0f
    private var dcQ = 0f
    private var prevI = 0f
    private var prevQ = 0f

    val stats = VideoStats()

    // The 8 MS/s luminance stream is analysed in blocks.
    private val block = FloatArray(BLOCK + 2 * LINE)
    private var fill = 0
    private var absStart = 0L // absolute index of block[0]

    private var polarity = 0
    private var lastPulseEnd = -1L
    private var lastHStart = -1L
    private var broadCount = 0
    private var lastBroadEnd = -1L
    private var fieldLine = -1
    private var field = 0
    private val pixels = ByteArray(WIDTH)
    private var black = 0f
    private var white = 1f

    /** The last demodulated level range, used to scale pixels; exposed for diagnostics. */
    var lo = 0f
        private set
    var hi = 1f
        private set

    fun feed(buf: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            var re = buf[i].toFloat() / 128f
            var im = buf[i + 1].toFloat() / 128f
            i += 2
            dcI += (re - dcI) * 1e-4f; dcQ += (im - dcQ) * 1e-4f
            re -= dcI; im -= dcQ
            // Frequency: phase step between consecutive samples, in units of the input rate / 2π.
            val d = fastAtan2(im * prevI - re * prevQ, re * prevI + im * prevQ)
            prevI = re; prevQ = im
            hist[hPos] = d; hist[hPos + taps] = d
            hPos++
            if (hPos == taps) hPos = 0
            if (++phase == dec) {
                phase = 0
                var acc = 0f
                for (k in 0 until taps) acc += h[k] * hist[hPos + k]
                push(acc)
            }
        }
    }

    private fun push(v: Float) {
        block[fill++] = v
        if (fill == BLOCK + LINE) analyse()
    }

    private val sortBuf = FloatArray(BLOCK / 8)

    /** Cuts the block into sync pulses and lines; keeps the last line's worth of samples for the next block. */
    private fun analyse() {
        val n = fill
        // Levels from a subsample of the block.
        var k = 0
        var j = 0
        while (j < n && k < sortBuf.size) { sortBuf[k++] = block[j]; j += 8 }
        java.util.Arrays.sort(sortBuf, 0, k)
        val p2 = sortBuf[(k * 0.02).toInt()]
        val p98 = sortBuf[(k * 0.98).toInt().coerceAtMost(k - 1)]
        if (p98 - p2 < 1e-3f) { carry(n); return } // nothing but noise or a carrier
        if (polarity == 0 || (absStart / BLOCK) % 200 == 0L) polarity = choosePolarity(n, p2, p98)
        val sgn = polarity.toFloat().let { if (it == 0f) 1f else it }
        stats.polarity = polarity
        // Work on s = sgn·z: sync tips are the lowest values.
        val sLo = if (sgn > 0) p2 else -p98
        val sHi = if (sgn > 0) p98 else -p2
        lo = sLo; hi = sHi
        val thr = sLo + 0.25f * (sHi - sLo)
        var idx = 0
        while (idx < n) {
            if (sgn * block[idx] < thr) {
                var e = idx
                while (e < n && sgn * block[e] < thr) e++
                if (e >= n) break // pulse continues in the next block: stop before it
                pulse(idx, e - idx, sgn, sLo, sHi)
                idx = e
            } else idx++
        }
        carry(n)
    }

    private fun carry(n: Int) {
        val keep = LINE + 64
        val from = max(0, n - keep)
        System.arraycopy(block, from, block, 0, n - from)
        absStart += from
        fill = n - from
    }

    private fun choosePolarity(n: Int, p2: Float, p98: Float): Int {
        var best = 0
        var bestScore = 0
        for (sg in intArrayOf(1, -1)) {
            val sLo = if (sg > 0) p2 else -p98
            val sHi = if (sg > 0) p98 else -p2
            val thr = sLo + 0.25f * (sHi - sLo)
            var count = 0
            var idx = 0
            while (idx < n) {
                if (sg * block[idx] < thr) {
                    var e = idx
                    while (e < n && sg * block[e] < thr) e++
                    val us = (e - idx) / 8.0
                    if (us in 3.3..6.5) count++
                    idx = e
                } else idx++
            }
            if (count > bestScore) { bestScore = count; best = sg }
        }
        return if (bestScore >= 8) best else polarity
    }

    private fun pulse(start: Int, width: Int, sgn: Float, sLo: Float, sHi: Float) {
        val us = width / 8.0
        val s = absStart + start
        when {
            us in 3.3..6.5 -> hSync(start, s, sgn, sLo, sHi)
            us in 18.0..36.0 -> {
                // Broad pulses of the field sync: three or more close together mean a new field.
                if (broadCount == 0 || s - lastBroadEnd > 700) broadCount = 0
                broadCount++
                lastBroadEnd = s + width
                if (broadCount == 3) {
                    stats.vSyncs++
                    if (fieldLine > 100) { stats.linesPerField = fieldLine; stats.fields++; field++; onField() }
                    fieldLine = -1 // the next normal line is line 0
                }
            }
        }
    }

    private fun hSync(start: Int, s: Long, sgn: Float, sLo: Float, sHi: Float) {
        stats.hPulses++
        val gap = if (lastHStart >= 0) (s - lastHStart) / 8.0 else 0.0
        // Ignore the half-line pulses of the field sync, which are not at line spacing.
        val atLine = gap in 62.0..66.0
        if (atLine) {
            stats.hSpaced++
            stats.lineUs += (gap - stats.lineUs) * (if (stats.lineUs == 0.0) 1.0 else 0.02)
        }
        lastHStart = s
        if (!atLine && lastHStart >= 0 && gap > 0 && gap < 62.0) return
        if (fieldLine < 0) fieldLine = 0 else fieldLine++
        // The line's pixels follow the sync and the back porch: 10.5 µs after the start of the pulse.
        val first = start + (10.5 * 8).toInt()
        if (first + WIDTH <= fill) {
            val level = 0.0f
            // Black level from the back porch, white from the top of the range.
            val bpStart = start + (6.0 * 8).toInt()
            var bp = 0f
            for (q in 0 until 24) bp += sgn * block[bpStart + q]
            black = bp / 24
            white = max(sHi, black + 1e-3f)
            for (x in 0 until WIDTH) {
                val v = (sgn * block[first + x] - black) / (white - black)
                pixels[x] = (v.coerceIn(0f, 1f) * 255f + level).toInt().toByte()
            }
            onLine(field, fieldLine, pixels)
        }
    }

    companion object {
        const val RATE = 8e6
        const val WIDTH = 416 // 52 µs of picture at 8 MS/s
        private const val LINE = 512 // 64 µs
        private const val BLOCK = 32768 // 4 ms
    }
}

/** Power of the channel in dBFS over [len] bytes of I/Q. */
fun iqPowerDb(buf: ByteArray, len: Int): Double {
    var sum = 0.0
    var i = 0
    while (i + 1 < len) { val a = buf[i] / 128.0; val b = buf[i + 1] / 128.0; sum += a * a + b * b; i += 2 }
    val n = len / 2
    return if (n == 0) -120.0 else 10 * log10(sum / n + 1e-12)
}
