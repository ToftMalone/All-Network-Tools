package com.allnetworktools.ui.pages.sdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Meshtastic packet as listed: repeated receptions of the same (sender, id) are folded together. */
class MeshRow(val key: Pair<Long, Long>, val packet: Meshtastic.Packet) {
    var receptions by mutableIntStateOf(1)
    var bestSnr by mutableStateOf(packet.snrDb)
    var minHops by mutableStateOf(packet.header.hops)
}

/** What has been learnt about a node from the packets it sent. */
class MeshNode(val num: Long) {
    var longName by mutableStateOf<String?>(null)
    var shortName by mutableStateOf<String?>(null)
    var hwModel by mutableStateOf<Int?>(null)
    var lastHeardMs by mutableStateOf(0L)
    var snr by mutableStateOf<Double?>(null)
    var hops by mutableStateOf<Int?>(null)
    var lat by mutableStateOf<Double?>(null)
    var lon by mutableStateOf<Double?>(null)
    var battery by mutableStateOf<Int?>(null)
    var voltage by mutableStateOf<Float?>(null)
    var packets by mutableIntStateOf(0)

    val label: String get() = longName ?: Meshtastic.nodeId(num)
}

/**
 * Receive-only Meshtastic listener on a HackRF: 2 MS/s around the channel, decimated to 500 kS/s, LoRa
 * SF11 / 250 kHz / CR 4/5 (LongFast), sync word 0x2B, then the channel key.
 */
class MeshtasticController(private val repo: SdrRepository, private val scope: CoroutineScope) {
    var frequencyMhz by mutableStateOf("869.525")
    var keyBase64 by mutableStateOf("AQ==")
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(false)

    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var board by mutableStateOf<String?>(null)
        private set
    var firmware by mutableStateOf<String?>(null)
        private set
    var listeningHz by mutableStateOf<Long?>(null)
        private set

    var framesOk by mutableIntStateOf(0)
        private set
    var framesBad by mutableIntStateOf(0)
        private set
    var otherChannel by mutableIntStateOf(0)
        private set
    var dropped by mutableIntStateOf(0)
        private set
    var startedAtMs by mutableStateOf(0L)
        private set

    val rows = mutableStateListOf<MeshRow>()
    val nodes = mutableStateMapOf<Long, MeshNode>()

    private var radio: HackRf? = null
    private var dsp: Thread? = null
    @Volatile private var dspRunning = false

    /** Parsed settings, or why they cannot be used. */
    fun settings(): Pair<Settings?, String?> {
        val mhz = frequencyMhz.trim().replace(',', '.').toDoubleOrNull() ?: return null to "Fréquence invalide"
        if (mhz !in 1.0..6000.0) return null to "Le HackRF reçoit de 1 à 6000 MHz"
        val psk = runCatching { java.util.Base64.getDecoder().decode(keyBase64.trim()) }.getOrNull()
            ?: return null to "Clé invalide : elle doit être en Base64 (ex. AQ==)"
        if (psk.size !in listOf(0, 1, 16, 32)) return null to "La clé doit faire 1, 16 ou 32 octets une fois décodée"
        val key = Meshtastic.expandKey(psk)
        return Settings((mhz * 1e6).toLong(), key, Meshtastic.channelHash(CHANNEL_NAME, key)) to null
    }

    class Settings(val hz: Long, val key: ByteArray?, val hash: Int)

    fun start(device: SdrDevice) {
        if (running || starting) return
        val (s, err) = settings()
        if (s == null) { error = err; return }
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) {
                    error = "Accès USB au HackRF refusé"
                    return@launch
                }
                val r = withContext(Dispatchers.IO) { repo.open(device) }
                if (r == null) { error = "Impossible d'ouvrir le HackRF"; return@launch }
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val (ok, info) = withContext(Dispatchers.IO) {
                    val info = (HackRf.boardName(r.boardId()) ?: device.name) to r.version()
                    // The channel sits fs/4 above the tuned frequency, away from the HackRF's DC spike.
                    val ok = r.setSampleRate(SAMPLE_RATE, 1_750_000) &&
                        r.setFrequency(s.hz - SAMPLE_RATE / 4) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                    ok to info
                }
                board = info.first
                firmware = info.second
                if (!ok) { r.close(); error = "Le HackRF n'a pas accepté la configuration"; return@launch }
                radio = r
                listeningHz = s.hz
                startedAtMs = System.currentTimeMillis()
                startPipeline(r, s)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: HackRf, s: Settings) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val decimator = Decimator(4, 0.07, taps = 48)
        val rx = LoraReceiver(11, 250_000.0, s.hz.toDouble(), 0x2B) { f -> onFrame(f, s) }
        val outRe = FloatArray(131072 / 2 / 4 + 8)
        val outIm = FloatArray(outRe.size)
        dspRunning = true
        dsp = Thread({
            while (dspRunning) {
                val (buf, len) = full.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val k = decimator.process(buf, len, outRe, outIm, 0)
                free.offer(buf)
                rx.feed(outRe, outIm, k)
            }
        }, "lora-dsp").apply { start() }
        r.startRx(
            onSamples = { data, len ->
                val b = free.poll()
                if (b == null) {
                    scope.launch { dropped++ } // the phone could not keep up: those samples are lost
                } else {
                    System.arraycopy(data, 0, b, 0, len)
                    full.offer(b to len)
                }
            },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    private fun onFrame(f: LoraFrame, s: Settings) {
        val now = System.currentTimeMillis()
        val packet = if (f.crcOk) Meshtastic.decode(f.payload, s.key, s.hash, now, f.snrDb) else null
        scope.launch {
            if (!f.crcOk) { framesBad++; return@launch }
            framesOk++
            if (packet == null) return@launch
            if (packet.otherChannel) otherChannel++
            record(packet)
        }
    }

    private fun record(p: Meshtastic.Packet) {
        val key = p.header.from to p.header.id
        val existing = rows.firstOrNull { it.key == key }
        if (existing != null) {
            existing.receptions++
            if (p.snrDb > existing.bestSnr) existing.bestSnr = p.snrDb
            val h = p.header.hops
            if (h != null && (existing.minHops == null || h < existing.minHops!!)) existing.minHops = h
        } else {
            rows.add(0, MeshRow(key, p))
            while (rows.size > MAX_ROWS) rows.removeAt(rows.lastIndex)
        }
        val node = nodes.getOrPut(p.header.from) { MeshNode(p.header.from) }
        node.lastHeardMs = p.atMs
        node.snr = p.snrDb
        node.hops = p.header.hops
        node.packets++
        when (val c = p.data?.content) {
            is Meshtastic.Content.NodeInfo -> {
                c.longName?.takeIf { it.isNotBlank() }?.let { node.longName = it }
                c.shortName?.takeIf { it.isNotBlank() }?.let { node.shortName = it }
                c.hwModel?.let { node.hwModel = it }
            }
            is Meshtastic.Content.Position -> if (c.lat != null && c.lon != null && (c.lat != 0.0 || c.lon != 0.0)) {
                node.lat = c.lat; node.lon = c.lon
            }
            is Meshtastic.Content.Telemetry -> {
                c.battery?.let { node.battery = it }
                c.voltage?.let { node.voltage = it }
            }
            else -> Unit
        }
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        listeningHz = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    fun clear() {
        rows.clear()
        nodes.clear()
        framesOk = 0; framesBad = 0; otherChannel = 0; dropped = 0
    }

    fun nodeName(num: Long): String = when (num) {
        Meshtastic.BROADCAST -> "Tous"
        else -> nodes[num]?.label ?: Meshtastic.nodeId(num)
    }

    internal fun recordForTest(p: Meshtastic.Packet) = record(p)

    internal fun setStatsForTest(ok: Int, bad: Int, running: Boolean, hz: Long?) {
        framesOk = ok; framesBad = bad; this.running = running; listeningHz = hz
        board = "HackRF One (r9)"; firmware = "2024.02.1"
    }

    companion object {
        const val CHANNEL_NAME = "LongFast"
        private const val SAMPLE_RATE = 2_000_000
        private const val POOL = 48
        private const val MAX_ROWS = 300
    }
}
