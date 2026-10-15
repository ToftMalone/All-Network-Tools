package com.allnetworktools.data.sdr

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a satellite is in the receiver's pipeline. */
enum class GpsStage { PullIn, Tracking, BitSync, Frame, Ephemeris }

class GpsChannelInfo(
    val prn: Int,
    val stage: GpsStage,
    val cn0: Double,
    val dopplerHz: Double,
    val elevation: Double?,
    val azimuth: Double?,
    val used: Boolean,
)

class GpsStatus(
    val channels: List<GpsChannelInfo>,
    val fix: Gps.Fix?,
    /** Full GPS week of the fix, when a subframe 1 gave it. */
    val week: Int?,
    /** Frequency error of the receiver's crystal, from the satellites' Doppler once a fix is known. */
    val clockPpm: Double?,
    val searching: Boolean,
    val fixes: Int,
)

/**
 * Software GPS L1 C/A receiver for 8-bit I/Q at [FS] (2.048 MS/s) centred on 1575.42 MHz, after the classic design
 * of Borre et al. ("A Software-Defined GPS and Galileo Receiver"):
 *
 * - Acquisition: 1 ms circular correlation by FFT for every PRN and Doppler bin, 10 ms summed non-coherently.
 * - Tracking: one code period per update, early/prompt/late correlators half a chip apart, a frequency-locked pull-in
 *   then a Costas phase-locked loop, and a delay-locked loop aided by the carrier.
 * - Data: bit edges from the sign changes, subframes from the preamble and the word parity, ephemeris from
 *   subframes 1–3.
 * - Every second, pseudoranges at a common receive instant and a least-squares fix.
 *
 * [acquire] runs the (slow) acquisition: on a worker thread in the app, inline in tests.
 */
class GpsReceiver(
    private val acquire: (Runnable) -> Unit = { it.run() },
    /** Runs the channels' tracking; the app spreads them over several cores. */
    private val parallel: (List<Runnable>) -> Unit = { tasks -> tasks.forEach { it.run() } },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onStatus: (GpsStatus) -> Unit = {},
) {
    companion object {
        const val FS = 2_048_000.0
        const val SAMPLES_PER_MS = 2048
        const val MAX_CHANNELS = 12
        private const val CAP = 1 shl 22
        private const val MASK = CAP - 1
        private const val TABLE_BITS = 12
        private const val TABLE = 1 shl TABLE_BITS
        private const val ONE_CHIP = 1048576.0
        private const val HALF_CHIP = 1 shl 19
        private const val LEAP_SECONDS = 18
    }

    // Sample ring, interleaved 8-bit I/Q with the DC offset removed.
    private val ring = ByteArray(2 * CAP)
    @Volatile private var total = 0L
    private var dcI = 0f
    private var dcQ = 0f

    private val cosT = FloatArray(TABLE) { cos(2 * PI * it / TABLE).toFloat() }
    private val sinT = FloatArray(TABLE) { sin(2 * PI * it / TABLE).toFloat() }

    private val channels = ArrayList<Channel>()
    private val ephemerides = java.util.concurrent.ConcurrentHashMap<Int, Gps.Ephemeris>()
    private val pending = ConcurrentLinkedQueue<GpsAcquisition.Hit>()
    @Volatile private var acquiring = false
    private var nextAcquisition = 0L
    private var nextEpoch = (FS * 2).toLong()
    private var nextStatus = 0L
    private var lastFix: Gps.Fix? = null
    @Volatile private var week: Int? = null
    private var clockPpm: Double? = null
    private var fixes = 0
    private val acq by lazy { GpsAcquisition(FS) }

    /** Receiver clock offset as seen on the carriers (Hz): narrows the search once one satellite is found. */
    private var clockHz: Double? = null

    /** Raw HackRF bytes (I, Q, I, Q…). */
    fun feed(buf: ByteArray, len: Int) {
        val n = len / 2
        // DC offset of the converter: follow it slowly and remove it.
        var si = 0L
        var sq = 0L
        for (k in 0 until n) { si += buf[2 * k]; sq += buf[2 * k + 1] }
        if (n > 0) { dcI += 0.1f * (si.toFloat() / n - dcI); dcQ += 0.1f * (sq.toFloat() / n - dcQ) }
        val oi = Math.round(dcI)
        val oq = Math.round(dcQ)
        var w = ((total and MASK.toLong()) * 2).toInt()
        for (k in 0 until n) {
            ring[w] = (buf[2 * k] - oi).coerceIn(-128, 127).toByte()
            ring[w + 1] = (buf[2 * k + 1] - oq).coerceIn(-128, 127).toByte()
            w = (w + 2) and (2 * CAP - 1)
        }
        total += n
        while (true) {
            val hit = pending.poll() ?: break
            startChannel(hit)
        }
        parallel(channels.map { c -> Runnable { while (c.step()) Unit } })
        channels.removeAll { it.lost }
        if (total >= nextAcquisition && !acquiring && channels.size < MAX_CHANNELS) startAcquisition()
        if (total >= nextEpoch) { nextEpoch += FS.toLong(); epoch() }
        if (total >= nextStatus) { nextStatus = total + (FS / 2).toLong(); onStatus(status()) }
    }

    // ---- acquisition ------------------------------------------------------------------------------------

    private fun startAcquisition() {
        val len = 41 * SAMPLES_PER_MS
        if (total < len) return
        val s0 = total - len
        val re = FloatArray(len)
        val im = FloatArray(len)
        for (k in 0 until len) {
            val i = (((s0 + k) and MASK.toLong()) * 2).toInt()
            re[k] = ring[i].toFloat(); im[k] = ring[i + 1].toFloat()
        }
        val busy = channels.map { it.prn }.toSet()
        val prns = (1..32).filter { it !in busy }
        val center = clockHz ?: 0.0
        val span = if (clockHz == null) 40_000.0 else 10_000.0
        // Weak signals (a passive whip antenna) need long sums: 20 ms over the wide search, 40 ms once it narrows.
        val blocks = if (clockHz == null) 20 else 40
        acquiring = true
        // Search often until enough satellites are tracked, then only now and then for rising ones.
        nextAcquisition = total + (FS * when { clockHz == null -> 1.0; channels.size < 5 -> 3.0; else -> 15.0 }).toLong()
        acquire(Runnable {
            try {
                acq.search(re, im, s0, prns, center, span, blocks).forEach { pending += it }
            } finally {
                acquiring = false
            }
        })
    }

    private fun startChannel(h: GpsAcquisition.Hit) {
        if (channels.size >= MAX_CHANNELS || channels.any { it.prn == h.prn }) return
        val codeFreq = Gps.CHIP_RATE + h.dopplerHz / 1540.0
        val period = FS * Gps.CODE_LENGTH / codeFreq
        // Carry the code start found in the snapshot forward to recent samples.
        val target = (total - 4 * SAMPLES_PER_MS).toDouble()
        var exact = h.codeStart
        if (exact < target) exact += ceil((target - exact) / period) * period
        val p = ceil(exact).toLong()
        val rem = (p - exact) * codeFreq / FS
        channels += Channel(h.prn, p, rem, h.dopplerHz)
        if (clockHz == null) clockHz = h.dopplerHz
    }

    // ---- tracking ---------------------------------------------------------------------------------------

    private inner class Channel(val prn: Int, var pos: Long, var rem: Double, doppler: Double) {
        private val code: FloatArray = Gps.ca(prn).let { c -> FloatArray(Gps.CODE_LENGTH + 4) { i -> c[Math.floorMod(i - 2, Gps.CODE_LENGTH)] } }
        var carrFreq = doppler
        private var carrBasis = doppler
        private var carrPhase = 0 // cycles × 2³²
        private var codeFreq = Gps.CHIP_RATE + doppler / 1540.0
        private var carrNco = 0.0
        private var carrErrOld = 0.0
        private var codeNco = 0.0
        private var codeErrOld = 0.0
        var ms = 0L
        private var pullIn = 0
        private var prevI = 0f
        private var prevQ = 0f
        var lost = false

        // Last block, for measurements.
        var blockPos = 0L
        var blockRem = 0.0
        var blockMs = 0L
        var blockCodeFreq = Gps.CHIP_RATE

        // Lock and C/N0 over 100 ms.
        private var m2 = 0.0
        private var m4 = 0.0
        private var sumI2 = 0.0
        private var sumQ2 = 0.0
        private var count = 0
        var cn0 = 0.0
        var locked = false
        private var weakMs = 0
        private var lockedMs = 0

        // Bits.
        private val edges = IntArray(20)
        private var transitions = 0
        var bitPhase = -1
        private var bitSum = 0f
        val bits = ArrayList<Int>()
        val bitMs = ArrayList<Long>()
        private var frameStart = -1
        private var frameMisses = 0
        val subframes = HashMap<Int, IntArray>()

        // Transmit time reference: subframe start (ms index) and its time of week.
        var refMs = -1L
        var refTow = 0.0

        val stage: GpsStage
            get() = when {
                ephemerides[prn] != null && refMs >= 0 -> GpsStage.Ephemeris
                frameStart >= 0 -> GpsStage.Frame
                bitPhase >= 0 -> GpsStage.BitSync
                pullIn >= PULL_IN_MS -> GpsStage.Tracking
                else -> GpsStage.PullIn
            }

        /** Processes one code period if the samples are there. */
        fun step(): Boolean {
            if (lost) return false
            // Code phase in 2⁻²⁰ chip and carrier phase in 2⁻³² cycle, both fixed point.
            val dt = (codeFreq / FS * ONE_CHIP).toInt()
            val stepC = dt / ONE_CHIP
            val n = ceil((Gps.CODE_LENGTH - rem) / stepC).toInt()
            if (pos + n + 4 > total) return false
            if (pos < total - CAP + 8) { lost = true; return false }
            var ie = 0f; var qe = 0f; var ip = 0f; var qp = 0f; var il = 0f; var ql = 0f
            var t = ((rem + 2) * ONE_CHIP).toInt()
            var ph = carrPhase
            val phStep = (carrFreq / FS * 4294967296.0).toLong().toInt()
            var idx = ((pos and MASK.toLong()) * 2).toInt()
            val ring = ring
            val code = code
            val cosT = cosT
            val sinT = sinT
            for (k in 0 until n) {
                val xr = ring[idx].toFloat()
                val xi = ring[idx + 1].toFloat()
                idx = (idx + 2) and (2 * CAP - 1)
                val ti = ph ushr (32 - TABLE_BITS)
                ph += phStep
                val c = cosT[ti]
                val s = sinT[ti]
                val br = xr * c + xi * s
                val bi = xi * c - xr * s
                val e = code[(t - HALF_CHIP) ushr 20]
                val p = code[t ushr 20]
                val l = code[(t + HALF_CHIP) ushr 20]
                ie += br * e; qe += bi * e
                ip += br * p; qp += bi * p
                il += br * l; ql += bi * l
                t += dt
            }
            blockPos = pos; blockRem = rem; blockMs = ms; blockCodeFreq = stepC * FS
            rem += n * stepC - Gps.CODE_LENGTH
            carrPhase = ph
            pos += n
            ms++
            loops(ie, qe, ip, qp, il, ql)
            return true
        }

        private fun loops(ie: Float, qe: Float, ip: Float, qp: Float, il: Float, ql: Float) {
            val t = 1e-3
            if (pullIn < PULL_IN_MS) {
                // Frequency pull-in: the phase turn between two code periods, insensitive to data bits.
                if (pullIn > 0) {
                    val dot = prevI * ip + prevQ * qp
                    val cross = prevI * qp - ip * prevQ
                    if (dot != 0f) carrBasis += 0.15 * atan((cross / dot).toDouble()) / (2 * PI * t)
                }
                carrFreq = carrBasis
                pullIn++
                if (pullIn == PULL_IN_MS) { carrNco = 0.0; carrErrOld = 0.0 }
            } else {
                // Costas PLL, 2nd order, 20 Hz.
                val err = if (ip != 0f) atan((qp / ip).toDouble()) / (2 * PI) else 0.0
                carrNco += PLL_K1 * (err - carrErrOld) + err * PLL_K2
                carrErrOld = err
                carrFreq = carrBasis + carrNco
            }
            prevI = ip; prevQ = qp
            // DLL, 2nd order, 2 Hz, aided by the carrier.
            val e = sqrt((ie * ie + qe * qe).toDouble())
            val l = sqrt((il * il + ql * ql).toDouble())
            val codeErr = if (e + l > 0) (e - l) / (e + l) else 0.0
            codeNco += DLL_K1 * (codeErr - codeErrOld) + codeErr * DLL_K2
            codeErrOld = codeErr
            codeFreq = Gps.CHIP_RATE + carrFreq / 1540.0 - codeNco

            // C/N0 (moments method) and phase lock over 100 ms.
            val pw = (ip * ip + qp * qp).toDouble()
            m2 += pw; m4 += pw * pw
            sumI2 += ip.toDouble() * ip; sumQ2 += qp.toDouble() * qp
            if (++count == 100) {
                val a2 = m2 / count
                val a4 = m4 / count
                val pd = sqrt(max(2 * a2 * a2 - a4, 0.0))
                val pn = a2 - pd
                cn0 = if (pd > 0 && pn > 0) 10 * log10(pd / pn / t) else 0.0
                locked = pullIn >= PULL_IN_MS && (sumI2 - sumQ2) / (sumI2 + sumQ2) > 0.3
                m2 = 0.0; m4 = 0.0; sumI2 = 0.0; sumQ2 = 0.0; count = 0
                weakMs = if (cn0 < 23) weakMs + 100 else 0
                if (weakMs >= 4000) lost = true
                lockedMs = if (locked) lockedMs + 100 else 0
                // No bit edges after 8 s: locked beside the carrier (±500 Hz); search it again.
                if (ms > 8000 && bitPhase < 0) lost = true
            }
            bitsFrom(ip)
        }

        private fun bitsFrom(ip: Float) {
            if (bitPhase < 0) {
                if (lockedMs < 200) return
                if ((ip > 0) != (prevSign > 0)) { edges[((ms - 1) % 20).toInt()]++; transitions++ }
                prevSign = ip
                if (transitions >= 16) {
                    val best = edges.indices.maxBy { edges[it] }
                    if (edges[best] >= transitions * 0.75) bitPhase = best
                    else { edges.fill(0); transitions = 0 }
                }
                return
            }
            // The block just processed is code period ms − 1.
            val period = ms - 1
            if (Math.floorMod(period - bitPhase, 20L) == 0L) bitSum = 0f
            bitSum += ip
            if (Math.floorMod(period - bitPhase, 20L) == 19L) {
                bits += if (bitSum > 0) 1 else 0
                bitMs += period - 19
                frame()
            }
        }

        private var prevSign = 0f

        private fun frame() {
            if (bits.size > 1500) {
                val drop = bits.size - 900
                bits.subList(0, drop).clear(); bitMs.subList(0, drop).clear()
                if (frameStart >= 0) frameStart -= drop
            }
            val end = bits.size
            if (frameStart < 0) {
                val at = end - 300
                if (at < 2) return
                val arr = bits.toIntArray()
                val d = Gps.subframe(arr, at) ?: return
                frameStart = at
                subframe(d, at)
                return
            }
            if ((end - frameStart) % 300 != 0) return
            val at = end - 300
            val d = Gps.subframe(bits.toIntArray(), at)
            if (d == null) {
                if (++frameMisses >= 3) { frameStart = -1; frameMisses = 0 }
                return
            }
            frameMisses = 0
            frameStart = at
            subframe(d, at)
        }

        private fun subframe(d: IntArray, at: Int) {
            val id = Gps.subframeId(d)
            if (id !in 1..5) return
            refMs = bitMs[at]
            refTow = Gps.towNext(d) - 6.0
            subframes[id] = d
            val s1 = subframes[1] ?: return
            val s2 = subframes[2] ?: return
            val s3 = subframes[3] ?: return
            val orbit = Gps.orbit(s2, s3) ?: return
            val clock = Gps.subframe1(s1)
            if ((clock.iodc and 0xFF) != orbit.iode) return
            ephemerides[prn] = Gps.Ephemeris(prn, clock, orbit)
            val nowWeek = ((nowMillis() / 1000.0 - 315_964_800 + LEAP_SECONDS) / Gps.SECONDS_PER_WEEK).toInt()
            week = clock.week + 1024 * Math.round((nowWeek - clock.week) / 1024.0).toInt()
        }

        /** Satellite transmit time (its own clock, seconds of week) at sample [s]. */
        fun txTime(s: Long): Double? {
            if (refMs < 0) return null
            val chips = blockRem + (s - blockPos) * blockCodeFreq / FS
            return refTow + (blockMs - refMs) * 1e-3 + chips / Gps.CHIP_RATE
        }
    }

    private val PULL_IN_MS = 300
    private val PLL_K1: Double
    private val PLL_K2: Double
    private val DLL_K1: Double
    private val DLL_K2: Double

    init {
        fun coef(bw: Double, zeta: Double, k: Double): Pair<Double, Double> {
            val wn = bw * 8 * zeta / (4 * zeta * zeta + 1)
            val tau1 = k / (wn * wn)
            val tau2 = 2 * zeta / wn
            return (tau2 / tau1) to (1e-3 / tau1)
        }
        val (p1, p2) = coef(15.0, 0.7, 0.25)
        val (d1, d2) = coef(2.0, 0.7, 1.0)
        PLL_K1 = p1; PLL_K2 = p2; DLL_K1 = d1; DLL_K2 = d2
    }

    // ---- measurements and fix --------------------------------------------------------------------------

    private fun epoch() {
        val ready = channels.filter { !it.lost && it.refMs >= 0 && ephemerides[it.prn]?.healthy == true && it.locked }
        if (ready.size < 4) { lastFix = null; return }
        val s = ready.minOf { it.pos }
        val ms = ready.mapNotNull { c -> c.txTime(s)?.let { Gps.Measurement(c.prn, ephemerides[c.prn]!!, it) } }
        val fix = Gps.solve(ms) ?: run { lastFix = null; return }
        if (fix.residualRms > 300) { lastFix = null; return }
        lastFix = fix
        fixes++
        // The receiver's crystal error: measured carrier minus the Doppler the satellites' motion explains.
        val offsets = ready.filter { it.prn in fix.used }.map { c ->
            val e = ephemerides[c.prn]!!
            val t = fix.tow
            val p1 = Gps.satPosition(e, t - 0.5)
            val p2 = Gps.satPosition(e, t + 0.5)
            val los = DoubleArray(3) { p1[it] / 2 + p2[it] / 2 - fix.ecef[it] }
            val r = sqrt(los.sumOf { it * it })
            val rangeRate = (0 until 3).sumOf { (p2[it] - p1[it]) * los[it] / r }
            c.carrFreq - (-rangeRate / Gps.C * Gps.L1_HZ)
        }.sorted()
        if (offsets.isNotEmpty()) {
            val hz = offsets[offsets.size / 2]
            clockPpm = -hz / Gps.L1_HZ * 1e6
            clockHz = hz
        }
    }

    fun status(): GpsStatus {
        val fix = lastFix
        return GpsStatus(
            channels = channels.filter { !it.lost }.sortedByDescending { it.cn0 }.map { c ->
                val sky = fix?.sky?.get(c.prn) ?: fix?.let { f -> ephemerides[c.prn]?.let { Gps.elevationAzimuth(f.ecef, Gps.satPosition(it, f.tow)) } }
                GpsChannelInfo(c.prn, c.stage, c.cn0, c.carrFreq, sky?.get(0), sky?.get(1), fix?.used?.contains(c.prn) == true)
            },
            fix = fix, week = week, clockPpm = clockPpm, searching = acquiring, fixes = fixes,
        )
    }

    /** UTC time of a fix, in milliseconds since 1970, when the week is known. */
    fun utcMillis(fix: Gps.Fix, week: Int): Long =
        ((315_964_800.0 + week * Gps.SECONDS_PER_WEEK + fix.tow - LEAP_SECONDS) * 1000).toLong()
}

/** Parallel code-phase search over Doppler bins, by FFT. */
class GpsAcquisition(private val fs: Double, private val z: Double = 6.2) {
    class Hit(val prn: Int, val codeStart: Double, val dopplerHz: Double, val ratio: Double)

    private val n = (fs / 1000).toInt()
    private val fft = Fft(n)
    private val codes = HashMap<Int, Pair<FloatArray, FloatArray>>()

    private fun codeSpectrum(prn: Int): Pair<FloatArray, FloatArray> = codes.getOrPut(prn) {
        val ca = Gps.ca(prn)
        val re = FloatArray(n) { ca[((it * Gps.CHIP_RATE / fs).toInt()) % Gps.CODE_LENGTH] }
        val im = FloatArray(n)
        fft.forward(re, im)
        for (k in 0 until n) im[k] = -im[k] // conjugate
        re to im
    }

    /**
     * Searches [prns] in the snapshot ([re], [im], starting at absolute sample [s0], a whole number of ms) over
     * [center] ± [span] Hz in 500 Hz steps, summing [sum] ms; returns the satellites found, strongest first, with
     * their carrier refined to a few hertz on the whole snapshot.
     */
    fun search(re: FloatArray, im: FloatArray, s0: Long, prns: List<Int>, center: Double, span: Double, sum: Int = 10): List<Hit> {
        val blocks = minOf(sum, re.size / n)
        // Spectra of each 1 ms block, as is and shifted by half a bin (500 Hz).
        val spec = Array(2) { h ->
            Array(blocks) { b ->
                val xr = FloatArray(n)
                val xi = FloatArray(n)
                for (k in 0 until n) {
                    val ph = -2 * PI * 500.0 * h * k / fs
                    val c = cos(ph).toFloat(); val s = sin(ph).toFloat()
                    val a = re[b * n + k]; val q = im[b * n + k]
                    xr[k] = a * c - q * s; xi[k] = a * s + q * c
                }
                fft.forward(xr, xi)
                xr to xi
            }
        }
        val hits = ArrayList<Hit>()
        val wr = FloatArray(n)
        val wi = FloatArray(n)
        val acc = FloatArray(n)
        val steps = (span / 500).toInt()
        val c0 = Math.round(center / 500.0).toInt()
        for (prn in prns) {
            val (cr, ci) = codeSpectrum(prn)
            var best = 0f
            var bestTau = 0
            var bestDop = 0.0
            var noise = 0f
            for (j in c0 - steps..c0 + steps) {
                val half = Math.floorMod(j, 2)
                val shift = Math.floorMod(Math.floorDiv(j, 2), n) // whole kHz = bins
                acc.fill(0f)
                for (b in 0 until blocks) {
                    val (xr, xi) = spec[half][b]
                    // Y[k] = X[k + shift], in two runs instead of a modulo per bin.
                    val split = n - shift
                    for (k in 0 until split) {
                        val a = xr[k + shift]; val q = xi[k + shift]
                        wr[k] = a * cr[k] - q * ci[k]
                        wi[k] = -(a * ci[k] + q * cr[k])
                    }
                    for (k in split until n) {
                        val a = xr[k - split]; val q = xi[k - split]
                        wr[k] = a * cr[k] - q * ci[k]
                        wi[k] = -(a * ci[k] + q * cr[k])
                    }
                    fft.forward(wr, wi) // inverse up to conjugation and scale: magnitudes are what matter
                    for (k in 0 until n) acc[k] += wr[k] * wr[k] + wi[k] * wi[k]
                }
                var pk = 0f
                var tau = 0
                for (k in 0 until n) if (acc[k] > pk) { pk = acc[k]; tau = k }
                if (pk > best) {
                    // Noise level of this row, away from the peak by more than a chip.
                    var sum = 0.0
                    var cells = 0
                    for (k in 0 until n) {
                        val d = abs(Math.floorMod(k - tau + n / 2, n) - n / 2)
                        if (d > 3) { sum += acc[k]; cells++ }
                    }
                    best = pk; bestTau = tau; bestDop = j * 500.0; noise = (sum / cells).toFloat()
                }
            }
            val ratio = if (noise > 0) (best / noise).toDouble() else 0.0
            if (ratio > threshold(blocks)) hits += Hit(prn, (s0 + bestTau).toDouble(), refine(re, im, prn, bestTau, bestDop), ratio)
        }
        return hits.sortedByDescending { it.ratio }
    }

    /**
     * Peak over mean noise for [blocks] 1 ms correlations summed, from the χ² law with 2 × blocks degrees of freedom
     * (Wilson–Hilferty approximation). z = 6.2 rather than the textbook 5.3 because the largest of the ~10⁷ cells of a
     * search was measured above the theory: 3.1 for 20 ms (noise reached 2.86), 2.33 for 40 ms (noise reached 2.03).
     */
    private fun threshold(blocks: Int): Double {
        val v = 2.0 / (9 * 2 * blocks)
        val q = 1 - v + z * sqrt(v)
        return q * q * q
    }

    /**
     * The carrier to within a few hertz: 1 ms correlations with the code wiped off, then the frequency that adds them up
     * best over each 10 ms half (a data bit can flip between halves, never inside one at most once).
     */
    private fun refine(re: FloatArray, im: FloatArray, prn: Int, tau: Int, doppler: Double): Double {
        val ca = Gps.ca(prn)
        val ms = (re.size - tau) / n
        if (ms < 4) return doppler
        val pr = DoubleArray(ms)
        val pi = DoubleArray(ms)
        for (m in 0 until ms) {
            var a = 0.0
            var b = 0.0
            for (k in 0 until n) {
                val idx = tau + m * n + k
                val ph = -2 * PI * doppler * idx / fs
                val c = ca[((k * Gps.CHIP_RATE / fs).toInt()) % Gps.CODE_LENGTH]
                val xr = re[idx] * c
                val xi = im[idx] * c
                a += xr * cos(ph) - xi * sin(ph)
                b += xr * sin(ph) + xi * cos(ph)
            }
            pr[m] = a; pi[m] = b
        }
        val half = ms / 2
        var best = 0.0
        var bestP = -1.0
        var d = -600.0
        while (d <= 600.0) {
            var p = 0.0
            for (h in 0 until 2) {
                var sr = 0.0
                var si = 0.0
                for (m in h * half until (h + 1) * half) {
                    val ph = -2 * PI * d * m * 1e-3
                    sr += pr[m] * cos(ph) - pi[m] * sin(ph)
                    si += pr[m] * sin(ph) + pi[m] * cos(ph)
                }
                p += sr * sr + si * si
            }
            if (p > bestP) { bestP = p; best = d }
            d += 10.0
        }
        return doppler + best
    }
}
