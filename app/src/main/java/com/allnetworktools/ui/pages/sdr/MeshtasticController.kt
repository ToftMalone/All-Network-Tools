package com.allnetworktools.ui.pages.sdr

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.MeshPreset
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.MeshRegion
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pages of the Meshtastic tool. */
enum class MeshPage(val label: String) { Listen("Écoute"), Nodes("Nœuds"), Map("Carte"), Channels("Canaux"), Node("Nœud") }

/** A channel as the user configures it: a name and a pre-shared key in Base64 (AQ== is the public default key). */
data class MeshChannel(val name: String, val keyBase64: String)

/** A channel ready to use: effective name, expanded key and the hash byte that identifies it on the air. */
class ResolvedChannel(val name: String, val key: ByteArray?, val hash: Int)

/** Everything the user can set, as stored between sessions. */
data class MeshConfig(
    val frequencyMhz: String,
    val preset: MeshPreset,
    val region: MeshRegion,
    val channels: List<MeshChannel>,
    val longName: String,
    val shortName: String,
    val nodeNum: Long,
    val lna: Int,
    val vga: Int,
    val amp: Boolean,
)

/** Keeps the [MeshConfig] in the app's private preferences. */
class MeshStore(context: Context?) {
    private val prefs = context?.getSharedPreferences("meshtastic", Context.MODE_PRIVATE)

    fun load(): MeshConfig? {
        val p = prefs ?: return null
        if (!p.contains("freq")) return null
        val channels = (p.getString("channels", "") ?: "").lines().filter { it.isNotBlank() }.map { l ->
            val parts = l.split('\t')
            MeshChannel(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "AQ==" })
        }
        return MeshConfig(
            frequencyMhz = p.getString("freq", "869.525")!!,
            preset = runCatching { MeshPreset.valueOf(p.getString("preset", "")!!) }.getOrDefault(MeshPreset.LongFast),
            region = runCatching { MeshRegion.valueOf(p.getString("region", "")!!) }.getOrDefault(MeshRegion.Eu868),
            channels = channels.ifEmpty { listOf(MeshChannel("LongFast", "AQ==")) },
            longName = p.getString("longName", "")!!,
            shortName = p.getString("shortName", "")!!,
            nodeNum = p.getLong("nodeNum", 0L),
            lna = p.getInt("lna", 32), vga = p.getInt("vga", 30), amp = p.getBoolean("amp", false),
        )
    }

    fun save(c: MeshConfig) {
        prefs?.edit {
            putString("freq", c.frequencyMhz); putString("preset", c.preset.name); putString("region", c.region.name)
            putString("channels", c.channels.joinToString("\n") { "${it.name.replace('\n', ' ').replace('\t', ' ')}\t${it.keyBase64}" })
            putString("longName", c.longName); putString("shortName", c.shortName); putLong("nodeNum", c.nodeNum)
            putInt("lna", c.lna); putInt("vga", c.vga); putBoolean("amp", c.amp)
        }
    }
}

/** One Meshtastic packet as listed: repeated receptions of the same (sender, id) are folded together. */
class MeshRow(val key: Pair<Long, Long>, val packet: Meshtastic.Packet, val channelName: String? = null) {
    var receptions by mutableIntStateOf(1)
    var bestSnr by mutableDoubleStateOf(packet.snrDb)
    var minHops by mutableStateOf(packet.header.hops)
}

/** What has been learnt about a node from the packets it sent. */
class MeshNode(val num: Long) {
    var longName by mutableStateOf<String?>(null)
    var shortName by mutableStateOf<String?>(null)
    var hwModel by mutableStateOf<Int?>(null)
    var lastHeardMs by mutableLongStateOf(0L)
    var snr by mutableStateOf<Double?>(null)
    var hops by mutableStateOf<Int?>(null)
    var lat by mutableStateOf<Double?>(null)
    var lon by mutableStateOf<Double?>(null)
    var altitude by mutableStateOf<Int?>(null)
    var positionAtMs by mutableLongStateOf(0L)
    var battery by mutableStateOf<Int?>(null)
    var voltage by mutableStateOf<Float?>(null)
    var packets by mutableIntStateOf(0)

    val label: String get() = longName ?: Meshtastic.nodeId(num)
}

/**
 * Meshtastic on a HackRF: 2 MS/s around the channel, decimated to two samples per chip, then LoRa demodulation with the
 * spreading factor and bandwidth of the chosen modem preset (LongFast by default), sync word 0x2B, then the keys of
 * the configured channels.
 */
class MeshtasticController(
    private val repo: SdrRepository,
    private val scope: CoroutineScope,
    private val store: MeshStore? = null,
    private val onAcquire: () -> Unit = {},
) {
    var page by mutableStateOf(MeshPage.Listen)

    // Radio.
    var frequencyMhz by mutableStateOf("869.525")
    var preset by mutableStateOf(MeshPreset.LongFast)
        private set
    var region by mutableStateOf(MeshRegion.Eu868)
        private set
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(false)

    // Channels: the first one is the primary channel.
    val channels = mutableStateListOf(MeshChannel("LongFast", "AQ=="))

    // This node's identity.
    var nodeNum by mutableLongStateOf(randomNodeNum())
        private set
    var longName by mutableStateOf("")
    var shortName by mutableStateOf("")

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
    var startedAtMs by mutableLongStateOf(0L)
        private set

    // Reception diagnostics, refreshed once a second while listening.
    var usbMBps by mutableDoubleStateOf(0.0)
        private set
    var levelDb by mutableStateOf<Double?>(null)
        private set
    var noiseDb by mutableStateOf<Double?>(null)
        private set
    var peakDb by mutableStateOf<Double?>(null)
        private set
    var preambles by mutableIntStateOf(0)
        private set
    var syncMismatches by mutableIntStateOf(0)
        private set
    var headerErrors by mutableIntStateOf(0)
        private set
    var lastSyncSeen by mutableIntStateOf(-1)
        private set

    val rows = mutableStateListOf<MeshRow>()
    val nodes = mutableStateMapOf<Long, MeshNode>()

    /** Packets decoded per channel hash. */
    val channelCounts = mutableStateMapOf<Int, Int>()

    private var radio: HackRf? = null
    private var dsp: Thread? = null
    @Volatile private var dspRunning = false
    private val usbBytes = AtomicLong()

    init {
        store?.load()?.let { c ->
            frequencyMhz = c.frequencyMhz; preset = c.preset; region = c.region
            channels.clear(); channels.addAll(c.channels)
            if (c.nodeNum >= MIN_NODE_NUM) nodeNum = c.nodeNum
            longName = c.longName; shortName = c.shortName
            lnaGain = c.lna; vgaGain = c.vga; amp = c.amp
        }
        if (longName.isBlank()) longName = "HackRF ${nodeId.takeLast(4)}"
        if (shortName.isBlank()) shortName = nodeId.takeLast(4)
    }

    val nodeId: String get() = Meshtastic.nodeId(nodeNum)

    /** The channel plan's default frequency for the current region and preset, if the preset fits the band. */
    val planMhz: Double? get() = MeshRadio.frequencyMhz(region, preset)

    /** Packets that could be read with one of the configured channels. */
    val decoded: Int get() = channelCounts.values.sum()

    fun verdict(): MeshVerdict = MeshDiagnosis.verdict(
        MeshDiagInput(running, usbMBps, levelDb, noiseDb, peakDb, preambles, syncMismatches, headerErrors, lastSyncSeen, framesOk, framesBad, decoded, otherChannel),
    )

    /** Saves the configuration; call after every change the user makes. */
    fun persist() {
        store?.save(MeshConfig(frequencyMhz, preset, region, channels.toList(), longName, shortName, nodeNum, lnaGain, vgaGain, amp))
    }

    fun selectPreset(p: MeshPreset) {
        if (running || starting) return
        val old = preset
        preset = p
        // The primary channel follows the preset's default name as long as it still carries the previous default one.
        if (channels.isNotEmpty() && channels[0].name == old.channelName) channels[0] = channels[0].copy(name = p.channelName)
        planMhz?.let { frequencyMhz = "%.3f".format(java.util.Locale.US, it) }
        persist()
    }

    fun selectRegion(r: MeshRegion) {
        if (running || starting) return
        region = r
        planMhz?.let { frequencyMhz = "%.3f".format(java.util.Locale.US, it) }
        persist()
    }

    fun addChannel() {
        if (channels.size >= MAX_CHANNELS) return
        channels += MeshChannel("", "AQ==")
        persist()
    }

    fun setChannel(i: Int, c: MeshChannel) {
        if (i in channels.indices) { channels[i] = c; persist() }
    }

    fun removeChannel(i: Int) {
        if (channels.size > 1 && i in channels.indices) { channels.removeAt(i); persist() }
    }

    fun resetChannels() {
        channels.clear(); channels += MeshChannel(preset.channelName, "AQ=="); persist()
    }

    fun regenerateNodeId() {
        nodeNum = randomNodeNum()
        longName = "HackRF ${nodeId.takeLast(4)}"
        shortName = nodeId.takeLast(4)
        persist()
    }

    /** Checks and resolves a channel; the reason is given in French when it cannot be used. */
    fun resolve(c: MeshChannel): Pair<ResolvedChannel?, String?> {
        val psk = runCatching { java.util.Base64.getDecoder().decode(c.keyBase64.trim()) }.getOrNull()
            ?: return null to "Clé invalide : elle doit être en Base64 (ex. AQ==)"
        if (psk.size !in listOf(0, 1, 16, 32)) return null to "La clé doit faire 1, 16 ou 32 octets une fois décodée"
        val key = Meshtastic.expandKey(psk)
        val name = c.name.ifBlank { preset.channelName }
        return ResolvedChannel(name, key, Meshtastic.channelHash(name, key)) to null
    }

    /** Parsed settings, or why they cannot be used. */
    fun settings(): Pair<Settings?, String?> {
        val mhz = frequencyMhz.trim().replace(',', '.').toDoubleOrNull() ?: return null to "Fréquence invalide"
        if (mhz !in 1.0..6000.0) return null to "Le HackRF reçoit de 1 à 6000 MHz"
        val resolved = ArrayList<ResolvedChannel>()
        for ((i, c) in channels.withIndex()) {
            val (r, err) = resolve(c)
            if (r == null) return null to "Canal ${i + 1} : $err"
            resolved += r
        }
        return Settings((mhz * 1e6).toLong(), preset, resolved) to null
    }

    class Settings(val hz: Long, val preset: MeshPreset, val channels: List<ResolvedChannel>)

    fun start(device: SdrDevice) {
        if (running || starting) return
        val (s, err) = settings()
        if (s == null) { error = err; return }
        persist()
        onAcquire()
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

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        persist()
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    private fun startPipeline(r: HackRf, s: Settings) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        // Two samples per chip. The filter passes the band plus a little margin, with the same relative transition width
        // whatever the decimation.
        val decim = s.preset.decimation
        val decimator = Decimator(decim, 0.56 * s.preset.bandwidthHz / SAMPLE_RATE, taps = 12 * decim)
        val rx = LoraReceiver(s.preset.sf, s.preset.bandwidthHz.toDouble(), s.hz.toDouble(), 0x2B) { f -> onFrame(f, s) }
        val outRe = FloatArray(131072 / 2 / decim + 8)
        val outIm = FloatArray(outRe.size)
        usbBytes.set(0)
        dspRunning = true
        dsp = Thread({
            var sumSq = 0.0
            var count = 0L
            var lastTick = System.currentTimeMillis()
            var lastBytes = 0L
            val levels = ArrayDeque<Double>()
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    val (buf, len) = item
                    val k = decimator.process(buf, len, outRe, outIm, 0)
                    free.offer(buf)
                    for (i in 0 until k) sumSq += outRe[i] * outRe[i] + outIm[i] * outIm[i]
                    count += k
                    rx.feed(outRe, outIm, k)
                }
                val now = System.currentTimeMillis()
                if (now - lastTick >= 1000) {
                    val seconds = (now - lastTick) / 1000.0
                    val bytes = usbBytes.get()
                    val level = if (count > 0) 10 * log10(sumSq / count + 1e-12) else null
                    if (level != null) { levels.addLast(level); while (levels.size > 60) levels.removeFirst() }
                    val noise = levels.minOrNull()
                    val peak = levels.maxOrNull()
                    val rate = (bytes - lastBytes) / seconds / 1e6
                    val pre = rx.preambles; val mis = rx.syncMismatches; val hdr = rx.headerErrors; val seen = rx.lastSyncSeen
                    lastTick = now; lastBytes = bytes; sumSq = 0.0; count = 0
                    scope.launch {
                        usbMBps = rate; levelDb = level; noiseDb = noise; peakDb = peak
                        preambles = pre; syncMismatches = mis; headerErrors = hdr; lastSyncSeen = seen
                    }
                }
            }
        }, "lora-dsp").apply { start() }
        r.startRx(
            onSamples = { data, len ->
                usbBytes.addAndGet(len.toLong())
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
        val packet = if (f.crcOk) decodePacket(f.payload, s.channels, now, f.snrDb) else null
        scope.launch {
            if (!f.crcOk) { framesBad++; return@launch }
            framesOk++
            if (packet == null) return@launch
            val ch = packet.second
            if (ch == null) otherChannel++ else channelCounts.merge(ch.hash, 1, Int::plus)
            record(packet.first, ch?.name)
        }
    }

    /** Tries the channels whose hash matches the packet's; the first whose key makes sense wins. */
    internal fun decodePacket(frame: ByteArray, resolved: List<ResolvedChannel>, atMs: Long, snrDb: Double): Pair<Meshtastic.Packet, ResolvedChannel?>? {
        val h = Meshtastic.header(frame) ?: return null
        val candidates = resolved.filter { it.hash == h.channel }
        if (candidates.isEmpty()) return (Meshtastic.decode(frame, null, -1, atMs, snrDb) ?: return null) to null
        var last: Pair<Meshtastic.Packet, ResolvedChannel?>? = null
        for (c in candidates) {
            val p = Meshtastic.decode(frame, c.key, c.hash, atMs, snrDb) ?: continue
            if (p.data != null) return p to c
            last = p to c
        }
        return last
    }

    internal fun record(p: Meshtastic.Packet, channelName: String? = null) {
        val key = p.header.from to p.header.id
        val existing = rows.firstOrNull { it.key == key }
        if (existing != null) {
            existing.receptions++
            if (p.snrDb > existing.bestSnr) existing.bestSnr = p.snrDb
            val h = p.header.hops
            if (h != null && (existing.minHops == null || h < existing.minHops!!)) existing.minHops = h
        } else {
            rows.add(0, MeshRow(key, p, channelName))
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
                node.lat = c.lat; node.lon = c.lon; node.altitude = c.altitude; node.positionAtMs = p.atMs
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
        channelCounts.clear()
        framesOk = 0; framesBad = 0; otherChannel = 0; dropped = 0
    }

    fun nodeName(num: Long): String = when (num) {
        Meshtastic.BROADCAST -> "Tous"
        else -> nodes[num]?.label ?: Meshtastic.nodeId(num)
    }

    internal fun recordForTest(p: Meshtastic.Packet) = record(p)

    internal fun setPresetForTest(p: MeshPreset, r: MeshRegion) { preset = p; region = r; planMhz?.let { frequencyMhz = "%.3f".format(java.util.Locale.US, it) } }

    internal fun setStatsForTest(ok: Int, bad: Int, running: Boolean, hz: Long?) {
        framesOk = ok; framesBad = bad; this.running = running; listeningHz = hz
        board = "HackRF One (r9)"; firmware = "2024.02.1"
    }

    internal fun setDiagForTest(usb: Double, level: Double?, noise: Double?, pre: Int, mismatches: Int, headers: Int, seen: Int) {
        usbMBps = usb; levelDb = level; noiseDb = noise; peakDb = level; preambles = pre; syncMismatches = mismatches; headerErrors = headers; lastSyncSeen = seen
    }

    companion object {
        private const val SAMPLE_RATE = 2_000_000
        private const val POOL = 48
        private const val MAX_ROWS = 300
        const val MAX_CHANNELS = 8
        private const val MIN_NODE_NUM = 0x100L

        /** A node number in the range devices use: 32 bits, never a reserved low value and never the broadcast address. */
        fun randomNodeNum(): Long = MIN_NODE_NUM + (java.util.Random().nextLong() ushr 1) % (0xFFFFFFFEL - MIN_NODE_NUM)
    }
}
