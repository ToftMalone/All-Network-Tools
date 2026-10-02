package com.allnetworktools.ui.pages.sdr

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.allnetworktools.data.sdr.AnalogVideoDecoder
import com.allnetworktools.data.sdr.DroneBands
import com.allnetworktools.data.sdr.DroneIdDetector
import com.allnetworktools.data.sdr.DroneWindow
import com.allnetworktools.data.sdr.FpvChannel
import com.allnetworktools.data.sdr.FpvChannels
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.iqPowerDb
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FpvTab(val label: String) { Analog("Analogique"), Digital("Numérique DJI") }

/** What a pass over one analogue channel found. */
data class FpvResult(val channel: FpvChannel, val powerDb: Double, val syncQuality: Double, val hPulses: Int, val standard: String?, val atMs: Long) {
    /** Line and field sync pulses at the right spacing: that is video, not just a transmitter. */
    val isVideo: Boolean get() = hPulses >= 40 && syncQuality >= 0.5
}

/** A window of the 2.4 / 5.8 GHz bands where DroneID-shaped bursts were seen. */
data class DroneHit(val centreMhz: Double, val count: Int, val periodic: Boolean, val bandwidthMHz: Double, val levelDb: Double, val lastSeenMs: Long)

/**
 * Drones on 5.8 GHz analogue video (scan of the 48 channels, then the picture of the chosen one) and DJI's DroneID broadcast
 * (presence of the burst only). Receive only; nothing is recorded and no digital link is decoded.
 */
class FpvController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
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
    val results = mutableStateListOf<FpvResult>()
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
    var videoStandard by mutableStateOf<String?>(null)
        private set
    var videoQuality by mutableStateOf(0.0)
        private set
    var watchLevelDb by mutableStateOf<Double?>(null)
        private set

    // Digital.
    var hits by mutableStateOf<List<DroneHit>>(emptyList())
        private set
    var windowMhz by mutableStateOf<Double?>(null)
        private set
    var digitalSweeps by mutableIntStateOf(0)
        private set

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false
    @Volatile private var mode: Any = Mode.Scan
    private val usbBytes = java.util.concurrent.atomic.AtomicLong()

    private enum class Mode { Scan, Digital }
    private class Watch(val channel: FpvChannel)

    val videoChannels: List<FpvResult> get() = results.filter { it.isVideo }.sortedByDescending { it.powerDb }

    /** Starts the scan of the 48 channels, or of the DroneID windows on the Numérique tab. */
    fun start(device: SdrDevice) {
        begin(device, if (tab == FpvTab.Analog) Mode.Scan else Mode.Digital)
    }

    /** Shows the picture of [ch]; starts the radio if needed. */
    fun watch(device: SdrDevice, ch: FpvChannel) {
        tab = FpvTab.Analog
        if (running) { mode = Watch(ch); watching = ch; resetVideo(); return }
        begin(device, Watch(ch))
    }

    fun backToScan() {
        if (!running) return
        watching = null
        mode = Mode.Scan
    }

    private fun resetVideo() { videoStandard = null; videoQuality = 0.0; frame = null; watchLevelDb = null }

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
                if (m != Mode.Digital) resetVideo()
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
        }, "sdr-fpv").start()
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
        var lastTick = System.currentTimeMillis()
        var lastBytes = 0L
        fun tick() {
            val now = System.currentTimeMillis()
            if (now - lastTick >= 1000) {
                val b = usbBytes.get()
                val rate1 = (b - lastBytes) / ((now - lastTick) / 1000.0) / 1e6
                lastTick = now; lastBytes = b
                scope.launch { usbMBps = rate1 }
            }
        }
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
                    val level = sum / n
                    scope.launch { videoStandard = st.standard; videoQuality = st.syncQuality; watchLevelDb = level }
                }
                tick()
            } else {
                tunedTo = null
                val fresh = ArrayList<FpvResult>()
                for (ch in FpvChannels.scan(analogBand)) {
                    if (!dspRunning || mode !== m) break
                    scope.launch { scanning = ch.name }
                    tune(r, ch.mhz.toDouble(), pipe)
                    val dec = newDecoder(false)
                    var sum = 0.0
                    var n = 0
                    val until = System.currentTimeMillis() + SCAN_DWELL_MS
                    while (dspRunning && System.currentTimeMillis() < until) {
                        val item = pipe.take(100) ?: continue
                        sum += iqPowerDb(item.first, item.second); n++
                        dec.feed(item.first, item.second)
                        pipe.give(item.first)
                    }
                    val st = dec.stats
                    val res = FpvResult(ch, if (n > 0) sum / n else -120.0, st.syncQuality, st.hPulses, st.standard, System.currentTimeMillis())
                    fresh += res
                    scope.launch { results.removeAll { it.channel == ch }; results += res }
                    tick()
                }
                if (mode === m && dspRunning) scope.launch { scanning = null; sweeps++ }
            }
        }
    }

    private fun digitalLoop(r: HackRf, pipe: Pipe) {
        val start = System.currentTimeMillis()
        val found = HashMap<Double, DroneHit>()
        var lastTick = start
        var lastBytes = 0L
        while (dspRunning) {
            for (centre in DroneBands.windows(band)) {
                if (!dspRunning) return
                scope.launch { windowMhz = centre }
                tune(r, centre, pipe)
                val visit = DroneWindow(centre)
                val t0 = System.currentTimeMillis()
                val det = DroneIdDetector(DIGITAL_RATE.toDouble()) { b -> visit.add(b, System.currentTimeMillis(), 0.0) }
                while (dspRunning && System.currentTimeMillis() - t0 < DIGITAL_DWELL_MS) {
                    val item = pipe.take(100) ?: continue
                    det.feed(item.first, item.second)
                    pipe.give(item.first)
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 1000) {
                        val b = usbBytes.get()
                        val mb = (b - lastBytes) / ((now - lastTick) / 1000.0) / 1e6
                        lastTick = now; lastBytes = b
                        scope.launch { usbMBps = mb }
                    }
                }
                if (visit.count > 0) {
                    val old = found[centre]
                    found[centre] = DroneHit(centre, (old?.count ?: 0) + visit.count, visit.periodic || old?.periodic == true, visit.lastBw, visit.lastLevel, visit.lastSeenMs)
                    val list = found.values.sortedByDescending { it.lastSeenMs }
                    scope.launch { hits = list }
                }
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

    fun clear() { results.clear(); hits = emptyList(); sweeps = 0; digitalSweeps = 0 }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        scanning = null
        windowMhz = null
        watching = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(res: List<FpvResult>, hit: List<DroneHit>, running: Boolean, watching: FpvChannel? = null, image: ImageBitmap? = null) {
        results.clear(); results += res; hits = hit; this.running = running; this.watching = watching; frame = image
    }

    companion object {
        const val ANALOG_RATE = 16_000_000
        const val DIGITAL_RATE = 20_000_000
        private const val POOL = 64
        private const val SETTLE_MS = 30L
        private const val SCAN_DWELL_MS = 140L
        private const val DIGITAL_DWELL_MS = 1400L
        const val PIC_W = AnalogVideoDecoder.WIDTH
        const val PIC_H = 576
        private const val FIRST_LINE = 16
    }
}

private fun fmtMhz(v: Double) = "%.0f".format(Locale.FRANCE, v)

@Composable
fun FpvTool(vm: MainViewModel) {
    val c = vm.tools.fpv
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val d = device
    val videos = c.videoChannels
    val digital = c.tab == FpvTab.Digital
    val probable = c.hits.any { it.periodic }
    PageColumn {
        HeroCard {
            if (!digital) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${videos.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                    Text(plural(videos.size, "vidéo analogique", "vidéos analogiques"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
                }
                Text(
                    c.scanning?.let { "Balayage en cours · canal $it" } ?: if (c.sweeps > 0) "${c.sweeps} ${plural(c.sweeps, "balayage terminé", "balayages terminés")}" else when (c.analogBand) { 24 -> "2,4 GHz, de 2360 à 2520 MHz"; 0 -> "2,4 et 5,8 GHz"; else -> "5,8 GHz, bandes A B E F R L" },
                    style = rf(14, 20, tnum = true),
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${c.hits.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                    Text(plural(c.hits.size, "zone avec signal", "zones avec signal"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
                }
                Text(c.windowMhz?.let { "Écoute autour de ${fmtMhz(it)} MHz" } ?: "2,4 et 5,8 GHz, rafales DroneID de DJI", style = rf(14, 20, tnum = true))
            }
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip("Démarrage du HackRF…", AntTheme.net.fair, blink = true)
                    c.running && digital && probable -> HeroChip("Signal compatible DJI DroneID", AntTheme.net.good, blink = true)
                    c.running && digital -> HeroChip(if (c.hits.isEmpty()) "Aucun signal de ce type" else "Rafale isolée, à confirmer", AntTheme.net.fair, blink = true)
                    c.running && c.watching != null -> HeroChip("Vidéo ${c.watching!!.mhz} MHz", AntTheme.net.good, blink = true)
                    c.running -> HeroChip(if (videos.isEmpty()) "Aucune vidéo pour l'instant" else "${videos.size} ${plural(videos.size, "vidéo", "vidéos")} trouvée", if (videos.isEmpty()) AntTheme.net.fair else AntTheme.net.good, blink = true)
                    d == null -> HeroChip("Aucun HackRF branché", AntTheme.net.poor)
                    else -> HeroChip("${d.name} prêt", AntTheme.net.good)
                }
            }
        }
        SegmentedRow(FpvTab.entries.map { it to it.label }, c.tab, { if (!c.running && !c.starting) c.tab = it }, Modifier.fillMaxWidth(), height = 36.dp)
        if (c.running || c.starting) StartButton("Arrêter", Sym.Stop) { c.stop() }
        else StartButton(if (digital) "Chercher un drone DJI" else "Balayer la bande", Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (!digital) AnalogPage(c, d, videos) else DigitalPage(c)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!digital) {
                    Text("Bande à balayer", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(58 to "5,8 GHz", 24 to "2,4 GHz", 0 to "Les deux").forEach { (b, l) -> AntFilterChip(l, c.analogBand == b, { if (!c.running) c.analogBand = b }) }
                    }
                }
                if (digital) {
                    Text("Bande", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "2,4 + 5,8 GHz", 24 to "2,4 GHz", 58 to "5,8 GHz").forEach { (b, l) -> AntFilterChip(l, c.band == b, { if (!c.running) c.band = b }) }
                    }
                }
                Text("Gain LNA / VGA (dB)", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 8, 16, 24, 32, 40).forEach { g -> AntFilterChip("LNA $g", c.lnaGain == g, { c.lnaGain = g; c.applyGains() }) }
                    listOf(10, 20, 30, 40).forEach { g -> AntFilterChip("VGA $g", c.vgaGain == g, { c.vgaGain = g; c.applyGains() }) }
                    AntFilterChip("Ampli +14 dB", c.amp, { c.amp = !c.amp; c.applyGains() })
                }
                if (c.running) Text("Flux USB %.1f Mo/s (attendu %d)".format(Locale.FRANCE, c.usbMBps, if (digital) 40 else 32), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    if (digital)
                        "Le détecteur cherche la signature radio du DroneID de DJI : une rafale plate d'environ 9 MHz de large, longue de 0,6 ms, qui revient " +
                            "toutes les 0,6 s environ. Il ne lit rien dans cette rafale : ni identifiant, ni position du pilote. Il ne distingue pas O1, O2, O3 " +
                            "ou O4 et la liaison vidéo numérique n'est pas décodée. Mesuré sur des captures de Mini 2 et de Mavic Air 2 ; les modèles récents " +
                            "n'ont pas été vérifiés. La rafale ne passe que sur une fréquence à la fois : laisse tourner quelques balayages. Le Wi-Fi n'est pas détecté."
                    else
                        "Le balayage mesure les canaux (5,8 GHz) ou une grille de fréquences (2,4 GHz, où les émetteurs n'ont pas de plan de canaux commun) et reconnaît une vraie vidéo à ses impulsions de synchronisation (PAL ou NTSC), pas à sa seule puissance. " +
                            "L'image est la luminance en noir et blanc. Réserve l'affichage à tes propres drones ou à une surveillance autorisée : rien n'est enregistré. " +
                            "Le Wi-Fi à 2,4 GHz n'est pas pris pour de la vidéo : seule la synchro compte. Les liaisons numériques (DJI O3, O4, DJI FPV, HDZero, Walksnail) ne sont pas décodées. Réception seule.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnalogPage(c: FpvController, d: SdrDevice?, videos: List<FpvResult>) {
    val w = c.watching
    if (w != null && c.running) {
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
                Text(
                    (if (w.band == 'G') "${w.mhz} MHz" else "${w.name} · ${w.mhz} MHz") + (c.videoStandard?.let { " · $it" } ?: "") + " · synchro %.0f %%".format(Locale.FRANCE, c.videoQuality * 100) +
                        (c.watchLevelDb?.let { " · %.0f dBFS".format(Locale.FRANCE, it) } ?: ""),
                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                )
                ToolButtons(ToolButton("Retour au balayage", Sym.Radar, BtnKind.OutlineOnSurface) { c.backToScan() })
            }
        }
    }
    if (c.results.isEmpty()) {
        if (!c.running) Empty("Lance le balayage : la bande choisie est mesurée en quelques secondes (48 canaux à 5,8 GHz, 17 fréquences à 2,4 GHz), puis la liste des vidéos apparaît ici.")
        return
    }
    val rows = c.results.sortedByDescending { it.powerDb }.take(14)
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        rows.forEachIndexed { i, r ->
            if (i > 0) Hairline()
            ChannelRow(r) { d?.let { dev -> c.watch(dev, r.channel) } }
        }
    }
    if (videos.isNotEmpty()) Text("${videos.size} ${plural(videos.size, "canal porte", "canaux portent")} une vidéo. Touche une ligne pour voir l'image.", style = rf(12, 16), color = cs.onSurfaceVariant)
}

@Composable
private fun ChannelRow(r: FpvResult, onWatch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = r.isVideo) { onWatch() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (r.isVideo) AntTheme.accent.accent else AntTheme.accent.container),
            contentAlignment = Alignment.Center,
        ) { Symbol(if (r.isVideo) Sym.Videocam else Sym.Radar, size = 22.dp, tint = if (r.isVideo) AntTheme.accent.onAccent else AntTheme.accent.onContainer) }
        Column(Modifier.weight(1f)) {
            Text(if (r.channel.band == 'G') "${r.channel.mhz} MHz · 2,4 GHz" else "${r.channel.name} · ${r.channel.mhz} MHz", style = rf(15, 20, 600, tnum = true), maxLines = 1)
            Text(
                if (r.isVideo) "Vidéo ${r.standard ?: "analogique"} · synchro %.0f %%".format(Locale.FRANCE, r.syncQuality * 100) else "Signal sans synchro vidéo",
                style = rf(13, 18),
            )
        }
        Text("%.0f dBFS".format(Locale.FRANCE, r.powerDb), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun DigitalPage(c: FpvController) {
    if (c.hits.isEmpty()) {
        Empty(if (c.running) "Aucune rafale de ce type pour l'instant (${c.digitalSweeps} ${plural(c.digitalSweeps, "balayage", "balayages")}). Allume le drone et rapproche-toi." else "Lance la recherche : le détecteur balaie les bandes 2,4 et 5,8 GHz fenêtre par fenêtre.")
        return
    }
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        c.hits.forEachIndexed { i, h ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (h.periodic) AntTheme.accent.accent else AntTheme.accent.container),
                    contentAlignment = Alignment.Center,
                ) { Symbol(Sym.Radar, size = 22.dp, tint = if (h.periodic) AntTheme.accent.onAccent else AntTheme.accent.onContainer) }
                Column(Modifier.weight(1f)) {
                    Text("Autour de ${fmtMhz(h.centreMhz)} MHz", style = rf(15, 20, 600, tnum = true), maxLines = 1)
                    Text(if (h.periodic) "Compatible DJI DroneID : rafales régulières" else "Rafale isolée de même forme, à confirmer", style = rf(13, 18))
                    Text(
                        "${h.count} ${plural(h.count, "rafale", "rafales")} · largeur %.1f MHz · +%.0f dB".format(Locale.FRANCE, h.bandwidthMHz, h.levelDb),
                        style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
    ToolButtons(ToolButton("Effacer", Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}
