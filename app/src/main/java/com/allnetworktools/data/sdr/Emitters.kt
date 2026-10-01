package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How a transmission behaves, judged from its width and its on/off pattern only; nothing is decoded. */
enum class EmitterKind(val label: String) {
    Wide("Large bande (Wi-Fi, vidéo, DECT…)"),
    Spread("Modulation large (LoRa, FSK large)"),
    Carrier("Porteuse continue"),
    Burst("Rafales courtes (télécommande, capteur)"),
    Narrow("Émission étroite en rafales"),
}

/** Licence-free and notable allocations in the bands the detector offers, with their usual users. */
object BandPlan {
    private class Slot(val fromMhz: Double, val toMhz: Double, val label: String)

    // Narrowest first, so the first match is the most specific.
    private val slots = listOf(
        Slot(433.82, 434.02, "ISM 433,92 : télécommandes, stations météo, capteurs"),
        Slot(433.05, 434.79, "ISM 433 MHz"),
        Slot(869.4, 869.65, "SRD 869,4–869,65 : Meshtastic et LoRa (500 mW)"),
        Slot(868.0, 868.6, "SRD 868,0–868,6 : capteurs, alarmes, LoRa"),
        Slot(868.7, 869.2, "SRD 868,7–869,2 : alarmes, balises"),
        Slot(869.7, 870.0, "SRD 869,7–870"),
        Slot(865.0, 868.0, "SRD 865–868 : RFID, LoRa"),
        Slot(863.0, 865.0, "SRD 863–865 : audio sans fil, LoRa"),
        Slot(169.4, 169.475, "Télérelevé de compteurs (Wize)"),
        Slot(446.0, 446.2, "PMR446 : talkies sans licence"),
    )

    fun describe(hz: Double): String? = slots.firstOrNull { hz / 1e6 in it.fromMhz..it.toMhz }?.label
}

/** Immutable view of one emitter for the UI. */
data class EmitterInfo(
    val freqHz: Double,
    val bwHz: Double,
    val peakDb: Float,
    val levelDb: Float,
    val bursts: Int,
    val activeS: Double,
    val dutyPct: Int,
    val agoS: Double,
    val active: Boolean,
    val kind: EmitterKind,
    val band: String?,
)

private class Emitter(var freqHz: Double, var bwHz: Double, t: Double) {
    var peakDb = -200f
    var levelDb = -200f
    var firstT = t
    var lastT = t
    var activeFrames = 0
    var activeTime = 0.0
    var bursts = 0
    var activeNow = false
}

/**
 * Finds transmissions in a stream of averaged spectra. A per-bin noise floor follows the quiet bins; a bin 8 dB above it
 * is active. Adjacent active bins form one emitter, which is tracked by frequency, with how wide it is, how strong, and how
 * it switches on and off. Only the power spectrum is used.
 */
class EmitterDetector(private val size: Int, private val centerHz: Double, private val sampleRate: Double) {
    private val binHz = sampleRate / size
    private val floor = FloatArray(size)
    private var calibrated = false
    private val emitters = ArrayList<Emitter>()
    private var t = 0.0
    private var frameIndex = 0L
    private val first = (size * (0.5 - USABLE / 2)).toInt()
    private val last = (size * (0.5 + USABLE / 2)).toInt()
    private val mergeBins = max(2, (15_000 / binHz).roundToInt())

    var lastFloor: FloatArray = floor
        private set

    private fun freqOf(bin: Double) = centerHz + (bin - size / 2) * binHz

    /** One averaged spectrum (dBFS, lowest frequency first) covering [dtS] seconds. */
    fun update(db: FloatArray, dtS: Double) {
        t += dtS
        frameIndex++
        if (!calibrated) {
            val m = db.sortedArray()[size / 2] - 1f
            floor.fill(m)
            calibrated = true
        }
        val active = BooleanArray(size)
        val dc = size / 2
        for (i in 0 until size) {
            val inBand = i in first until last && abs(i - dc) > 3
            active[i] = inBand && db[i] > floor[i] + THRESHOLD_DB
            floor[i] += (db[i] - floor[i]) * (if (active[i]) 0.0002f else 0.02f)
        }
        var i = first
        while (i < last) {
            if (!active[i]) { i++; continue }
            var a = i
            var b = i
            var j = i + 1
            while (j < last && j - b <= mergeBins) { if (active[j]) b = j; j++ }
            i = b + 1
            var peak = -200f
            var peakBin = a
            for (k in a..b) if (db[k] > peak) { peak = db[k]; peakBin = k }
            // Width where the signal is within 20 dB of its peak; the centre is power-weighted there.
            var lo = peakBin
            var hi = peakBin
            val edge = max(peak - 20f, floor[peakBin] + THRESHOLD_DB)
            for (k in a..b) if (db[k] >= edge) { lo = minOf(lo, k); hi = max(hi, k) }
            var w = 0.0
            var wf = 0.0
            for (k in lo..hi) { val p = Math.pow(10.0, db[k] / 10.0); w += p; wf += p * k }
            val f = freqOf(wf / w)
            val bw = (hi - lo + 1) * binHz
            val idx = emitters.indices.filter { abs(emitters[it].freqHz - f) <= max(emitters[it].bwHz, bw) / 2 + 2 * binHz }
                .minByOrNull { abs(emitters[it].freqHz - f) }
            val e = if (idx != null) emitters[idx] else Emitter(f, bw, t).also { emitters += it }
            if (!e.activeNow && t - e.lastT > GAP_S) e.bursts++
            if (e.activeFrames == 0) e.bursts = 1
            e.activeNow = true
            e.activeFrames++
            e.activeTime += dtS
            e.lastT = t
            e.peakDb = max(e.peakDb, peak)
            e.levelDb = peak
            if (e.activeFrames > 1) { e.freqHz += (f - e.freqHz) * 0.1; e.bwHz += (bw - e.bwHz) * 0.1 }
            else { e.freqHz = f; e.bwHz = bw }
        }
        for (e in emitters) if (t - e.lastT > dtS * 1.5) e.activeNow = false
        // Candidates that never became real, and the oldest emitters beyond a cap.
        emitters.removeAll { it.activeFrames < CONFIRM_FRAMES && t - it.lastT > 1.0 }
        if (emitters.size > MAX_EMITTERS) {
            emitters.sortByDescending { it.lastT }
            while (emitters.size > MAX_EMITTERS) emitters.removeAt(emitters.size - 1)
        }
    }

    fun snapshot(): List<EmitterInfo> = emitters.filter { it.activeFrames >= CONFIRM_FRAMES }.sortedByDescending { it.lastT }.map { e ->
        val seenFor = max(t - e.firstT, 1e-3)
        val duty = (e.activeTime / seenFor).coerceIn(0.0, 1.0)
        val meanBurst = e.activeTime / max(e.bursts, 1)
        val kind = when {
            e.bwHz >= 600_000 -> EmitterKind.Wide
            e.bwHz >= 80_000 -> EmitterKind.Spread
            duty > 0.85 && e.activeTime > 1.0 -> EmitterKind.Carrier
            meanBurst < 0.5 -> EmitterKind.Burst
            else -> EmitterKind.Narrow
        }
        EmitterInfo(e.freqHz, e.bwHz, e.peakDb, e.levelDb, e.bursts, e.activeTime, (duty * 100).roundToInt(), t - e.lastT, e.activeNow, kind, BandPlan.describe(e.freqHz))
    }

    companion object {
        const val THRESHOLD_DB = 8f
        /** Fraction of the sample rate the HackRF's anti-alias filter leaves flat. */
        const val USABLE = 0.7
        private const val GAP_S = 0.03
        private const val CONFIRM_FRAMES = 3
        private const val MAX_EMITTERS = 60
    }
}

/** HackRF bytes in; spectra and emitters out. One averaged spectrum per USB buffer. */
class EmitterScanner(val size: Int, val centerHz: Double, val sampleRate: Double) {
    private val analyzer = SpectrumAnalyzer(size)
    private val detector = EmitterDetector(size, centerHz, sampleRate)

    /** Latest spectrum, and the noise floor under it. */
    var spectrum: FloatArray? = null
        private set
    val floor: FloatArray get() = detector.lastFloor

    fun feed(buf: ByteArray, len: Int) {
        analyzer.accumulate(buf, len, maxBlocks = 64)
        val db = analyzer.frame() ?: return
        spectrum = db
        detector.update(db, len / 2 / sampleRate)
    }

    fun emitters() = detector.snapshot()
}
