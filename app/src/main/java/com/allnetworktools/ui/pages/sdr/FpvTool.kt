package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.TrackerDetect
import com.allnetworktools.data.drone.RemoteDrone
import com.allnetworktools.data.drone.RemoteId
import com.allnetworktools.data.drone.RemoteIdScanner
import com.allnetworktools.data.drone.RemoteIdTracker
import com.allnetworktools.data.drone.RidRadios
import com.allnetworktools.data.sdr.AnalogDrone
import com.allnetworktools.data.sdr.AnalogDroneTracker
import com.allnetworktools.data.sdr.AnalogVideoDecoder
import com.allnetworktools.data.sdr.DjiDetection
import com.allnetworktools.data.sdr.DjiTracker
import com.allnetworktools.data.sdr.DroneBands
import com.allnetworktools.data.sdr.DroneIdDetector
import com.allnetworktools.data.sdr.DroneWindow
import com.allnetworktools.data.sdr.FpvChannel
import com.allnetworktools.data.sdr.FpvChannels
import com.allnetworktools.data.sdr.FpvResult
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.iqPowerDb
import com.allnetworktools.data.sdr.levelTrend
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Sparkline
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.plural
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FpvTab(val label: String) { Analog("Analogique"), Dji("DJI"), RemoteId("Remote ID") }

/** Remote ID heard by the phone's Bluetooth and Wi-Fi: identity, position and pilot of nearby drones. */
class RidController(private val scanner: RemoteIdScanner, private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set
    var drones by mutableStateOf<List<RemoteDrone>>(emptyList())
        private set
    var radios by mutableStateOf<RidRadios?>(null)
        private set

    private val tracker = RemoteIdTracker()
    private var job: Job? = null

    fun start() {
        if (running) return
        radios = scanner.radios()
        running = true
        job = scope.launch {
            launch { scanner.frames().collect { tracker.add(it, System.currentTimeMillis()) } }
            while (true) {
                drones = tracker.snapshot(System.currentTimeMillis())
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    fun clear() { tracker.clear(); drones = emptyList() }

    internal fun setForTest(list: List<RemoteDrone>, running: Boolean) { drones = list; this.running = running; radios = RidRadios(true, true) }
}

/**
 * Drones: analogue FPV video (5.8 and 2.4 GHz, a drone is a channel carrying real video sync), DJI (the DroneID burst
 * heard by the HackRF, plus the Remote ID DJI drones send over Wi-Fi) and Remote ID from any brand. Only drones are
 * shown, never raw signals. Receive only; nothing is recorded.
 */
class FpvController(
    private val repo: SdrRepository,
    scanner: RemoteIdScanner,
    private val scope: CoroutineScope,
    private val onAcquire: () -> Unit = {},
) {
    val rid = RidController(scanner, scope)

    var tab by mutableStateOf(FpvTab.Analog)
    var lnaGain by mutableIntStateOf(24)
    var vgaGain by mutableIntStateOf(24)
    var amp by mutableStateOf(false)
    var band by mutableIntStateOf(0) // DroneID: 24, 58 or 0 for both
    var analogBand by mutableIntStateOf(58) // analogue scan: 24, 58 or 0 for both

    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usbMBps by mutableStateOf(0.0)
        private set

    // Analogue.
    var drones by mutableStateOf<List<AnalogDrone>>(emptyList())
        private set
    var scanning by mutableStateOf<String?>(null)
        private set
    var sweeps by mutableIntStateOf(0)
        private set
    var watching by mutableStateOf<FpvChannel?>(null)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var frameTick by mutableIntStateOf(0)
        private set

    // DJI.
    var dji by mutableStateOf<DjiDetection?>(null)
        private set
    var windowMhz by mutableStateOf<Double?>(null)
        private set
    var digitalSweeps by mutableIntStateOf(0)
        private set

    private val analog = AnalogDroneTracker()
    private val djiTracker = DjiTracker()
    private var radio: HackRf? = null
    @Volatile private var dspRunning = false
    @Volatile private var mode: Any = Mode.Scan
    private val usbBytes = java.util.concurrent.atomic.AtomicLong()

    private enum class Mode { Scan, Digital }
    private class Watch(val channel: FpvChannel)

    /** Something is listening: the HackRF or the phone's Remote ID scan. */
    val active: Boolean get() = running || starting || rid.running

    /** DJI drones from Remote ID; the DroneID burst only says one is there. */
    val djiRemote: List<RemoteDrone> get() = rid.drones.filter { it.isDji }

    /** Starts what the current tab needs. [device] may be null on the Remote ID tab and on the DJI tab. */
    fun start(device: SdrDevice?) {
        when (tab) {
            FpvTab.Analog -> device?.let { begin(it, Mode.Scan) }
            FpvTab.Dji -> { rid.start(); device?.let { begin(it, Mode.Digital) } }
            FpvTab.RemoteId -> rid.start()
        }
    }

    /** Shows the picture of [ch]; starts the radio if needed. */
    fun watch(device: SdrDevice, ch: FpvChannel) {
        tab = FpvTab.Analog
        if (running) { mode = Watch(ch); watching = ch; frame = null; return }
        begin(device, Watch(ch))
    }

    fun backToScan() {
        if (!running) return
        watching = null
        mode = Mode.Scan
    }

    private fun begin(device: SdrDevice, m: Any) {
        if (running || starting) return
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = "Accès USB au HackRF refusé"; return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = "Impossible d'ouvrir le HackRF"; return@launch }
                val rate = if (m == Mode.Digital) DIGITAL_RATE else ANALOG_RATE
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = "Le HackRF n'a pas accepté la configuration"; return@launch }
                radio = r
                mode = m
                watching = (m as? Watch)?.channel
                frame = null
                startPipeline(r, rate)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private class Pipe(val free: ArrayBlockingQueue<ByteArray>, val full: ArrayBlockingQueue<Pair<ByteArray, Int>>) {
        fun take(ms: Long): Pair<ByteArray, Int>? = full.poll(ms, TimeUnit.MILLISECONDS)
        fun give(b: ByteArray) { free.offer(b) }

        /** Throws away what arrived while the radio was still retuning. */
        fun flush(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) take(10)?.let { give(it.first) }
        }
    }

    private fun startPipeline(r: HackRf, rate: Int) {
        val pipe = Pipe(ArrayBlockingQueue(POOL), ArrayBlockingQueue(POOL))
        repeat(POOL) { pipe.free.add(ByteArray(131072)) }
        usbBytes.set(0)
        dspRunning = true
        Thread({
            try {
                if (rate == DIGITAL_RATE) digitalLoop(r, pipe) else analogLoop(r, pipe, rate)
            } catch (e: Exception) {
                scope.launch { error = e.message ?: "Erreur de traitement" }
            }
        }, "sdr-drones").start()
        r.startRx(
            onSamples = { data, len ->
                usbBytes.addAndGet(len.toLong())
                pipe.free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); pipe.full.offer(b to len) }
            },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    private fun tune(r: HackRf, mhz: Double, pipe: Pipe) {
        r.setFrequency((mhz * 1e6).toLong())
        pipe.flush(SETTLE_MS)
    }

    private var lastTick = 0L
    private var lastBytes = 0L

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick >= 1000) {
            val b = usbBytes.get()
            val mb = (b - lastBytes) / ((now - lastTick) / 1000.0) / 1e6
            lastTick = now; lastBytes = b
            scope.launch { usbMBps = mb }
        }
    }

    /** Publishes the drone list; the tracker is only touched from the processing thread. */
    private fun publish() {
        val list = analog.list()
        scope.launch { drones = list }
    }

    private fun analogLoop(r: HackRf, pipe: Pipe, rate: Int) {
        val pixels = IntArray(PIC_W * PIC_H)
        val bitmaps = arrayOf(createBitmap(PIC_W, PIC_H), createBitmap(PIC_W, PIC_H))
        var flip = 0
        var fieldCount = 0
        fun newDecoder(show: Boolean) = AnalogVideoDecoder(
            rate.toDouble(),
            onLine = { field, line, p ->
                if (show) {
                    val y = 2 * (line - FIRST_LINE) + (field and 1)
                    if (y in 0 until PIC_H) for (x in 0 until PIC_W) { val g = p[x].toInt() and 0xFF; pixels[y * PIC_W + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                }
            },
            onField = {
                if (show && ++fieldCount % 3 == 0) {
                    val bmp = bitmaps[flip]
                    flip = flip xor 1
                    bmp.setPixels(pixels, 0, PIC_W, 0, 0, PIC_W, PIC_H)
                    val img = bmp.asImageBitmap()
                    scope.launch { frame = img; frameTick++ }
                }
            },
        )

        /** Listens to [ch] for [ms] and says what is there. */
        fun measure(ch: FpvChannel, ms: Long, m: Any): FpvResult {
            val dec = newDecoder(false)
            var sum = 0.0
            var n = 0
            val until = System.currentTimeMillis() + ms
            while (dspRunning && mode === m && System.currentTimeMillis() < until) {
                val item = pipe.take(100) ?: continue
                sum += iqPowerDb(item.first, item.second); n++
                dec.feed(item.first, item.second)
                pipe.give(item.first)
            }
            val st = dec.stats
            return FpvResult(ch, if (n > 0) sum / n else -120.0, st.syncQuality, st.hPulses, st.standard, System.currentTimeMillis())
        }

        lastTick = System.currentTimeMillis(); lastBytes = 0L
        var tunedTo: Any? = null
        var decoder = newDecoder(true)
        while (dspRunning) {
            val m = mode
            if (m is Watch) {
                if (tunedTo !== m) {
                    tune(r, m.channel.mhz.toDouble(), pipe)
                    pixels.fill(0xFF000000.toInt())
                    decoder = newDecoder(true)
                    tunedTo = m
                }
                var sum = 0.0
                var n = 0
                val until = System.currentTimeMillis() + 500
                while (dspRunning && mode === m && System.currentTimeMillis() < until) {
                    val item = pipe.take(100) ?: continue
                    sum += iqPowerDb(item.first, item.second); n++
                    decoder.feed(item.first, item.second)
                    pipe.give(item.first)
                }
                if (n > 0) {
                    val st = decoder.stats
                    analog.watched(m.channel, sum / n, st.standard, st.syncQuality, System.currentTimeMillis())
                    publish()
                }
                tick()
            } else {
                tunedTo = null
                var complete = true
                for (ch in FpvChannels.scan(analogBand)) {
                    if (!dspRunning || mode !== m) { complete = false; break }
                    scope.launch { scanning = ch.name }
                    tune(r, ch.mhz.toDouble(), pipe)
                    var res = measure(ch, SCAN_DWELL_MS, m)
                    // A hint of sync: a weak or distant drone. A longer listen settles it.
                    if (res.isCandidate) res = measure(ch, RECHECK_DWELL_MS, m)
                    if (analog.found(res, System.currentTimeMillis())) publish()
                    tick()
                }
                if (complete && dspRunning) {
                    analog.endSweep()
                    publish()
                    scope.launch { scanning = null; sweeps++ }
                }
            }
        }
    }

    private fun digitalLoop(r: HackRf, pipe: Pipe) {
        lastTick = System.currentTimeMillis(); lastBytes = 0L
        fun publishDji() {
            val st = djiTracker.state(System.currentTimeMillis())
            scope.launch { dji = st }
        }
        while (dspRunning) {
            for (centre in DroneBands.windows(band)) {
                if (!dspRunning) return
                scope.launch { windowMhz = centre }
                tune(r, centre, pipe)
                val visit = DroneWindow(centre)
                val det = DroneIdDetector(DIGITAL_RATE.toDouble()) { b ->
                    val now = System.currentTimeMillis()
                    visit.add(b, now, 0.0)
                    djiTracker.add(centre, b, now)
                }
                var until = System.currentTimeMillis() + DIGITAL_DWELL_MS
                var extended = false
                while (dspRunning && System.currentTimeMillis() < until) {
                    val item = pipe.take(100) ?: continue
                    det.feed(item.first, item.second)
                    pipe.give(item.first)
                    // A burst here: stay a little longer to catch the next one at the broadcast's rhythm.
                    if (!extended && visit.count > 0) { extended = true; until += DIGITAL_DWELL_MS }
                    tick()
                }
                if (visit.periodic) djiTracker.periodic(System.currentTimeMillis())
                publishDji()
            }
            scope.launch { digitalSweeps++ }
        }
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    fun clear() {
        if (running) return
        analog.clear(); djiTracker.clear(); rid.clear()
        drones = emptyList(); dji = null; sweeps = 0; digitalSweeps = 0
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        scanning = null
        windowMhz = null
        watching = null
        rid.stop()
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(list: List<AnalogDrone>, running: Boolean, watching: FpvChannel? = null, image: ImageBitmap? = null, dji: DjiDetection? = null) {
        drones = list; this.running = running; this.watching = watching; frame = image; this.dji = dji
    }

    companion object {
        const val ANALOG_RATE = 16_000_000
        const val DIGITAL_RATE = 20_000_000
        private const val POOL = 64
        private const val SETTLE_MS = 30L
        private const val SCAN_DWELL_MS = 140L
        private const val RECHECK_DWELL_MS = 450L
        private const val DIGITAL_DWELL_MS = 1200L
        const val PIC_W = AnalogVideoDecoder.WIDTH
        const val PIC_H = 576
        private const val FIRST_LINE = 16
    }
}

private fun fmt(v: Double, decimals: Int = 0) = "%.${decimals}f".format(Locale.FRANCE, v)

/** "à l'instant", "il y a 12 s", "il y a 3 min". */
private fun ago(ms: Long, now: Long): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 3 -> "à l'instant"
        s < 60 -> "il y a $s s"
        else -> "il y a ${s / 60} min"
    }
}

private fun duration(fromMs: Long, toMs: Long): String {
    val s = ((toMs - fromMs) / 1000).coerceAtLeast(0)
    return if (s < 60) "$s s" else "${s / 60} min ${"%02d".format(s % 60)} s"
}

private fun trendLabel(levels: List<Double>) = when (levelTrend(levels)) {
    1 -> "Se rapproche (signal en hausse)"
    -1 -> "S'éloigne (signal en baisse)"
    else -> "Stable"
}

private fun distanceText(m: Double) = if (m < 1000) "${fmt(m)} m" else "${fmt(m / 1000, 1)} km"

@Composable
fun FpvTool(vm: MainViewModel) {
    val c = vm.tools.fpv
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    // The phone's position only gives distances to the drone and its pilot; it is read while this screen is open.
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val d = device
    val now = System.currentTimeMillis()
    val tab = c.tab
    val count = when (tab) {
        FpvTab.Analog -> c.drones.count { !it.lost }
        FpvTab.Dji -> maxOf(c.djiRemote.size, if (c.dji?.confirmed == true) 1 else 0)
        FpvTab.RemoteId -> c.rid.drones.size
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$count", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(count, "drone détecté", "drones détectés"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                when (tab) {
                    FpvTab.Analog -> c.scanning?.let { "Recherche sur le canal $it" }
                        ?: if (c.sweeps > 0) "${c.sweeps} ${plural(c.sweeps, "balayage terminé", "balayages terminés")}"
                        else when (c.analogBand) { 24 -> "Vidéo analogique 2,4 GHz"; 0 -> "Vidéo analogique 2,4 et 5,8 GHz"; else -> "Vidéo analogique 5,8 GHz" }
                    FpvTab.Dji -> c.windowMhz?.let { "DroneID autour de ${fmt(it, 1)} MHz · Remote ID Wi-Fi" } ?: "DroneID par le HackRF, Remote ID par le téléphone"
                    FpvTab.RemoteId -> "Bluetooth et Wi-Fi du téléphone"
                },
                style = rf(14, 20, tnum = true),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip("Démarrage du HackRF…", AntTheme.net.fair, blink = true)
                    c.active && count > 0 -> HeroChip(if (count == 1) "Drone à proximité" else "$count drones à proximité", AntTheme.net.good, blink = true)
                    tab == FpvTab.Dji && c.dji != null -> HeroChip("Rafale DroneID, confirmation en cours", AntTheme.net.fair, blink = true)
                    c.active -> HeroChip("Aucun drone pour l'instant", AntTheme.net.fair, blink = true)
                    tab == FpvTab.Analog && d == null -> HeroChip("Aucun HackRF branché", AntTheme.net.poor)
                    tab == FpvTab.RemoteId || d == null -> HeroChip("Prêt : Bluetooth et Wi-Fi", AntTheme.net.good)
                    else -> HeroChip("${d.name} prêt", AntTheme.net.good)
                }
            }
        }
        SegmentedRow(FpvTab.entries.map { it to it.label }, c.tab, { if (!c.active) c.tab = it }, Modifier.fillMaxWidth(), height = 36.dp)
        if (c.active) StartButton("Arrêter", Sym.Stop) { c.stop() }
        else StartButton(
            when (tab) { FpvTab.Analog -> "Chercher des drones"; FpvTab.Dji -> "Chercher un drone DJI"; FpvTab.RemoteId -> "Écouter le Remote ID" },
            Sym.PlayArrow, enabled = tab != FpvTab.Analog || d != null,
        ) { c.start(d) }
        when (tab) {
            FpvTab.Analog -> AnalogPage(c, d, now)
            FpvTab.Dji -> DjiPage(c, me, now)
            FpvTab.RemoteId -> RemotePage(c, me, now)
        }
        if (tab != FpvTab.RemoteId) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bande à surveiller", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tab == FpvTab.Analog) listOf(58 to "5,8 GHz", 24 to "2,4 GHz", 0 to "Les deux").forEach { (b, l) -> AntFilterChip(l, c.analogBand == b, { if (!c.running) c.analogBand = b }) }
                        else listOf(0 to "2,4 + 5,8 GHz", 24 to "2,4 GHz", 58 to "5,8 GHz").forEach { (b, l) -> AntFilterChip(l, c.band == b, { if (!c.running) c.band = b }) }
                    }
                    GainSettings(
                        c.lnaGain, listOf(0, 8, 16, 24, 32, 40), { c.lnaGain = it; c.applyGains() },
                        c.vgaGain, listOf(10, 20, 30, 40), { c.vgaGain = it; c.applyGains() },
                        c.amp, { c.amp = !c.amp; c.applyGains() },
                    )
                    if (c.running) Text("Flux USB %.1f Mo/s (attendu %d)".format(Locale.FRANCE, c.usbMBps, if (tab == FpvTab.Dji) 40 else 32), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    when (tab) {
                        FpvTab.Analog ->
                            "Un drone n'est signalé que si son canal porte une vraie vidéo (impulsions de synchronisation PAL ou NTSC), jamais pour un simple signal. " +
                                "Une vidéo faible est réécoutée plus longtemps avant d'être écartée. Les drones analogiques n'envoient pas de télémétrie à part : " +
                                "altitude, batterie ou GPS n'apparaissent qu'incrustés dans l'image (OSD). Rien n'est enregistré."
                        FpvTab.Dji ->
                            "Le HackRF écoute les fréquences où DJI envoie son DroneID (une rafale de 0,6 ms, 10 MHz de large, qui change de fréquence) : trois rafales en 20 s confirment un drone. " +
                                "La télémétrie vient du Remote ID que les DJI récents (Mini 3 et 4, Air 3, Mavic 3…) diffusent en Wi-Fi : position, altitude, vitesse et position du pilote. " +
                                "La vidéo DJI (OcuSync, O3, O4) est numérique et chiffrée : elle n'est pas affichable."
                        FpvTab.RemoteId ->
                            "Le Remote ID est l'identification électronique obligatoire des drones (classes C1 à C6 en Europe, Remote ID aux États-Unis), diffusée en clair pour que chacun puisse la lire. " +
                                "Le téléphone l'écoute en Bluetooth (quelques centaines de mètres) et dans les balises Wi-Fi (Android limite à une recherche toutes les 30 s). Rien n'est enregistré."
                    },
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Header of a drone: icon, name, and the three answers (a drone is there, is there video, is there telemetry). */
@Composable
private fun DroneHeader(icon: String, title: String, subtitle: String, onAir: Boolean, video: String, videoOk: Boolean, telemetry: Boolean) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LeadingIcon(icon, if (onAir) AntTheme.accent.accent else AntTheme.accent.container, if (onAir) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
                Column(Modifier.weight(1f)) {
                    Text(title, style = rf(16, 22, 600), maxLines = 1)
                    Text(subtitle, style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val on = AntTheme.accent
                val offBg = cs.surfaceContainerHighest
                val offFg = cs.onSurfaceVariant
                if (onAir) TechChip("Drone présent", on.accent, on.onAccent) else TechChip("Signal perdu", offBg, offFg)
                TechChip(video, if (videoOk) on.container else offBg, if (videoOk) on.onContainer else offFg)
                TechChip(if (telemetry) "Télémétrie reçue" else "Pas de télémétrie", if (telemetry) on.container else offBg, if (telemetry) on.onContainer else offFg)
            }
        }
    }
}

@Composable
private fun LevelChart(levels: List<Double>) {
    if (levels.size < 2) return
    Sparkline(levels.map { it.toFloat() }, (levels.min() - 3).toFloat(), (levels.max() + 3).toFloat(), AntTheme.accent.accent, Modifier.fillMaxWidth().padding(vertical = 8.dp).height(48.dp))
}

@Composable
private fun AnalogPage(c: FpvController, d: SdrDevice?, now: Long) {
    val w = c.watching
    if (c.drones.isEmpty()) {
        Empty(
            if (c.running) "Aucune vidéo de drone pour l'instant. Le balayage continue : allume le drone ou rapproche-toi."
            else "Lance la recherche : la bande est balayée canal par canal et seuls les drones qui émettent une vidéo apparaissent ici.",
        )
        return
    }
    c.drones.forEach { dr ->
        val ch = dr.channel
        val chName = if (ch.band == 'G') "${ch.mhz} MHz · 2,4 GHz" else "Canal ${ch.name} · ${ch.mhz} MHz"
        val isWatched = c.running && w != null && kotlin.math.abs(w.mhz - ch.mhz) < 12
        DroneHeader(Sym.Videocam, "Drone FPV analogique", chName, !dr.lost, "Vidéo ${dr.standard ?: "analogique"}", true, false)
        if (isWatched) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tick = c.frameTick // redraw with each new picture
                    val img = c.frame
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                        if (img != null && tick >= 0) {
                            Canvas(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                                drawImage(img, IntOffset.Zero, IntSize(FpvController.PIC_W, FpvController.PIC_H), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
                            }
                        } else Text("Recherche de l'image…", style = rf(13, 18), color = Color.White.copy(alpha = 0.7f))
                    }
                    ToolButtons(ToolButton("Fermer la vidéo", Sym.Close, BtnKind.OutlineOnSurface) { c.backToScan() })
                }
            }
        } else if (!dr.lost && d != null) {
            ToolButtons(ToolButton("Voir la vidéo", Sym.Videocam, BtnKind.OutlineOnSurface) { c.watch(d, ch) })
        }
        InfoList("Télémétrie radio") {
            InfoRow("Signal", "${fmt(dr.levelDb)} dBFS")
            InfoRow("Tendance", trendLabel(dr.levels))
            InfoRow("Qualité de la synchro", "${fmt(dr.syncQuality * 100)} %")
            InfoRow("Vu pour la dernière fois", ago(dr.lastSeenMs, now))
            InfoRow("Présent depuis", duration(dr.firstSeenMs, dr.lastSeenMs))
            LevelChart(dr.levels)
        }
    }
    if (!c.running) ToolButtons(ToolButton("Effacer", Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}

@Composable
private fun DjiPage(c: FpvController, me: Pair<Double, Double>?, now: Long) {
    val remote = c.djiRemote
    val dj = c.dji
    if (remote.isEmpty() && dj?.confirmed != true) {
        Empty(
            when {
                !c.active -> "Lance la recherche : le HackRF écoute le DroneID de DJI et le téléphone le Remote ID qu'émettent les DJI récents. Sans HackRF, seul le Remote ID est écouté."
                dj != null -> "Une rafale DroneID a été entendue (${dj.bursts}). Il en faut trois en 20 s pour confirmer un drone DJI."
                else -> "Aucun drone DJI pour l'instant (${c.digitalSweeps} ${plural(c.digitalSweeps, "balayage", "balayages")}). Allume le drone et rapproche-toi."
            },
        )
        return
    }
    remote.forEach { RemoteDroneCard(it, me, now, dj?.takeIf { d -> d.confirmed }) }
    if (remote.isEmpty() && dj != null) {
        DroneHeader(
            Sym.Flight, "Drone DJI", "Reconnu à son DroneID · ${dj.bursts} ${plural(dj.bursts, "rafale", "rafales")}",
            now - dj.lastSeenMs < 30_000, "Vidéo numérique chiffrée", false, false,
        )
        InfoList("Télémétrie radio") {
            InfoRow("Dernière fréquence", "${fmt(dj.lastCentreMhz, 1)} MHz")
            InfoRow("Fréquences utilisées", dj.frequencies.joinToString(" · ") { fmt(it, 1) })
            InfoRow("Niveau", "+${fmt(dj.lastLevelDb)} dB au-dessus du bruit")
            InfoRow("Tendance", trendLabel(dj.levels))
            InfoRow("Vu pour la dernière fois", ago(dj.lastSeenMs, now))
            LevelChart(dj.levels)
        }
        Text(
            "Ce drone n'émet pas de Remote ID reçu par le téléphone (ancien modèle, ou trop loin) : sa position n'est pas disponible.",
            style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
    if (!c.active) ToolButtons(ToolButton("Effacer", Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}

@Composable
private fun RemotePage(c: FpvController, me: Pair<Double, Double>?, now: Long) {
    val r = c.rid.radios
    if (c.rid.running && r != null && !r.bluetooth && !r.wifi) Empty("Le Bluetooth et le Wi-Fi sont coupés : le téléphone ne peut pas entendre le Remote ID.")
    else if (c.rid.running && r != null && !r.bluetooth) Text("Bluetooth coupé : seul le Wi-Fi écoute (une mise à jour toutes les 30 s).", style = rf(12, 16), color = cs.onSurfaceVariant)
    if (c.rid.drones.isEmpty()) {
        Empty(if (c.rid.running) "Aucun Remote ID reçu pour l'instant. Les drones récents l'émettent dès qu'ils sont allumés." else "Lance l'écoute : le téléphone reçoit le Remote ID des drones alentour, sans HackRF.")
        return
    }
    c.rid.drones.forEach { RemoteDroneCard(it, me, now, null) }
    if (!c.active) ToolButtons(ToolButton("Effacer", Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}

@Composable
private fun RemoteDroneCard(dr: RemoteDrone, me: Pair<Double, Double>?, now: Long, droneId: DjiDetection?) {
    val brand = dr.manufacturer?.let { "Drone $it" } ?: "Drone"
    val type = RemoteId.uaTypeLabel(dr.uaType)
    DroneHeader(
        Sym.Flight, listOfNotNull(brand, type?.lowercase(Locale.FRANCE)).joinToString(" · "),
        dr.id?.let { "${RemoteId.idTypeLabel(dr.idType)} $it" } ?: "Identifiant pas encore reçu",
        now - dr.lastSeenMs < 15_000,
        if (dr.isDji) "Vidéo numérique chiffrée" else "Vidéo non détectable", false,
        dr.lat != null || dr.heightM != null,
    )
    InfoList("Télémétrie") {
        RemoteId.statusLabel(dr.status)?.let { InfoRow("État", it) }
        if (dr.lat != null && dr.lon != null) {
            InfoRow("Position", "${fmt(dr.lat, 5)}, ${fmt(dr.lon, 5)}")
            if (me != null) InfoRow("Distance", distanceText(TrackerDetect.distanceM(me.first, me.second, dr.lat, dr.lon)))
        }
        dr.heightM?.let { InfoRow(if (dr.heightAgl) "Hauteur sol" else "Hauteur / décollage", "${fmt(it)} m") }
        (dr.altGeoM ?: dr.altBaroM)?.let { InfoRow("Altitude", "${fmt(it)} m") }
        dr.speedMs?.let { InfoRow("Vitesse", "${fmt(it, 1)} m/s · ${fmt(it * 3.6)} km/h") }
        dr.verticalMs?.let { InfoRow("Vitesse verticale", "${if (it > 0) "+" else ""}${fmt(it, 1)} m/s") }
        dr.directionDeg?.let { InfoRow("Cap", "${fmt(it)}°") }
        if (dr.operatorLat != null && dr.operatorLon != null) {
            InfoRow("Pilote", "${fmt(dr.operatorLat, 5)}, ${fmt(dr.operatorLon, 5)}")
            if (me != null) InfoRow("Distance du pilote", distanceText(TrackerDetect.distanceM(me.first, me.second, dr.operatorLat, dr.operatorLon)))
        }
        dr.operatorId?.let { InfoRow("Exploitant", it) }
        dr.description?.let { InfoRow("Description", it) }
        RemoteId.euLabel(dr.categoryEu, dr.classEu)?.let { InfoRow("Catégorie UE", it) }
        InfoRow("Reçu par", dr.transports.joinToString(" et ") { it.label } + " · ${dr.rssi} dBm")
        InfoRow("Vu pour la dernière fois", ago(dr.lastSeenMs, now))
        if (droneId != null) InfoRow("DroneID (HackRF)", "${droneId.bursts} ${plural(droneId.bursts, "rafale", "rafales")} · ${fmt(droneId.lastCentreMhz, 1)} MHz")
    }
}
