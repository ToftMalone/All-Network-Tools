package com.allnetworktools.ui.pages.talkie

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.radio.AudioAnalyzer
import com.allnetworktools.data.radio.AudioCapture
import com.allnetworktools.data.radio.AudioFrame
import com.allnetworktools.data.radio.AudioInput
import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.AprsTracker
import com.allnetworktools.data.sdr.Ax25

/** An APRS station heard through the radio's audio. */
class HeardStation(
    val call: String,
    val symbol: String?,
    val lat: Double?,
    val lon: Double?,
    val speedKn: Double?,
    val comment: String?,
    val via: String?,
    val packets: Int,
    val lastSeenMs: Long,
)

/**
 * The radio's audio, from the microphone held to its speaker or from a USB / wired audio interface: loudness, a 0–4 kHz
 * spectrum with its waterfall, DTMF keys and APRS packets. Nothing is recorded: samples are analysed and dropped.
 */
class TalkieAudioController(private val capture: AudioCapture) {
    var inputs by mutableStateOf<List<AudioInput>>(emptyList())
        private set
    var selected by mutableStateOf<AudioInput?>(null)
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var rmsDb by mutableFloatStateOf(SILENCE_DB)
        private set
    var peakDb by mutableFloatStateOf(SILENCE_DB)
        private set
    var levelHistory by mutableStateOf<List<Float>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var occupancy by mutableFloatStateOf(0f)
        private set
    var spectrum by mutableStateOf<FloatArray?>(null)
        private set

    /** Keys heard, oldest first. */
    var digits by mutableStateOf("")
        private set

    var stations by mutableStateOf<List<HeardStation>>(emptyList())
        private set
    var packets by mutableIntStateOf(0)
        private set

    /** Bumped on every waterfall row, so the canvas redraws. */
    var rows by mutableIntStateOf(0)
        private set
    val waterfall: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(COLD) }
    private val pixels = IntArray(WIDTH * HEIGHT) { COLD }

    private var analyzer: AudioAnalyzer? = null
    private val tracker = AprsTracker()
    private val smooth = ArrayList<Float>()
    private var historyTick = 0

    fun refreshInputs() {
        inputs = capture.inputs()
        if (selected == null || inputs.none { it.id == selected?.id }) selected = inputs.firstOrNull()
    }

    fun start() {
        if (running) return
        refreshInputs()
        error = null
        val a = AudioAnalyzer(RATE, ::onFrame, ::onDigit, ::onPacket)
        analyzer = a
        val failure = capture.start(selected, RATE, { buf, n -> a.feed(buf, n) }, { msg -> error = msg; stop() })
        if (failure != null) { error = failure; analyzer = null; return }
        running = true
    }

    fun stop() {
        capture.stop()
        analyzer = null
        running = false
        busy = false
    }

    fun clear() {
        digits = ""
        tracker.stations.clear()
        stations = emptyList()
        packets = 0
        levelHistory = emptyList()
        smooth.clear()
        occupancy = 0f
        pixels.fill(COLD)
        waterfall.eraseColor(COLD)
        rows++
    }

    private fun onDigit(c: Char) {
        digits = (digits + c).takeLast(32)
    }

    private fun onPacket(bytes: ByteArray) {
        val report = Ax25.parse(bytes)?.let(Aprs::decode) ?: return
        val now = System.currentTimeMillis()
        tracker.update(report, now)
        tracker.prune(now)
        packets = tracker.total
        stations = tracker.stations.values.map {
            HeardStation(it.call, it.symbol, it.lat, it.lon, it.speedKn, it.comment ?: it.status, it.via, it.packets, it.lastSeenMs)
        }.sortedByDescending { it.lastSeenMs }
    }

    private fun onFrame(f: AudioFrame) {
        rmsDb = f.rmsDb
        peakDb = f.peakDb
        spectrum = f.spectrumDb
        System.arraycopy(pixels, 0, pixels, WIDTH, WIDTH * (HEIGHT - 1))
        for (x in 0 until WIDTH) pixels[x] = heat(f.spectrumDb.getOrElse(x) { -120f })
        waterfall.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        rows++

        // One history point every half second: the mean of ten frames.
        smooth += f.rmsDb
        if (++historyTick >= 10) {
            historyTick = 0
            val point = smooth.average().toFloat()
            smooth.clear()
            levelHistory = (levelHistory + point).takeLast(HISTORY)
            val floor = levelHistory.min()
            val threshold = maxOf(floor + 10f, -70f)
            busy = point > threshold
            occupancy = levelHistory.count { it > threshold } / levelHistory.size.toFloat()
        }
    }

    /** Sample state for screenshots and tests. */
    internal fun setForTest(db: Float, history: List<Float>, digits: String, heard: List<HeardStation>) {
        rmsDb = db; peakDb = db + 8f
        levelHistory = history
        val floor = history.min()
        val threshold = maxOf(floor + 10f, -70f)
        busy = db > threshold
        occupancy = history.count { it > threshold } / history.size.toFloat()
        this.digits = digits
        stations = heard
        packets = heard.sumOf { it.packets }
        running = true
        // A voice-like spectrum: formants between 300 Hz and 3 kHz over a flat noise floor.
        repeat(HEIGHT) { y ->
            System.arraycopy(pixels, 0, pixels, WIDTH, WIDTH * (HEIGHT - 1))
            val spec = FloatArray(WIDTH) { x ->
                val hz = x * RATE.toFloat() / AudioAnalyzer.FFT_SIZE
                val voiced = if (y % 17 < 11) 38f * kotlin.math.exp(-((hz - 700f) / 350f).let { it * it }) + 26f * kotlin.math.exp(-((hz - 1800f) / 500f).let { it * it }) else 0f
                -78f + voiced + ((x * 7 + y * 13) % 5)
            }
            for (x in 0 until WIDTH) pixels[x] = heat(spec[x])
            if (y == HEIGHT - 1) spectrum = spec
        }
        waterfall.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        rows++
    }

    companion object {
        const val RATE = 48_000
        const val SILENCE_DB = -90f
        const val HISTORY = 120
        const val WIDTH = AudioAnalyzer.TOP_HZ * AudioAnalyzer.FFT_SIZE / RATE
        const val HEIGHT = 90
        private const val COLD = 0xFF0B1026.toInt()

        /** -95 dBFS (floor) to -25 dBFS (loud) mapped to a dark-blue → cyan → yellow → white ramp. */
        internal fun heat(db: Float): Int {
            val v = ((db + 95f) / 70f).coerceIn(0f, 1f)
            val r = (255 * (3f * v - 2f).coerceIn(0f, 1f) + 11 * (1f - v)).toInt().coerceIn(0, 255)
            val g = (255 * (2.2f * v - 0.35f).coerceIn(0f, 1f) + 16 * (1f - v)).toInt().coerceIn(0, 255)
            val b = (255 * (0.55f + 0.45f * kotlin.math.sin(Math.PI * v).toFloat()).coerceIn(0f, 1f) * (0.2f + 0.8f * v) + 38 * (1f - v)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
