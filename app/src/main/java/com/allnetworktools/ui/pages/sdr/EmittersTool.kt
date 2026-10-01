package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.EmitterInfo
import com.allnetworktools.data.sdr.EmitterScanner
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.plural
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A band to watch. The tuning is placed so the HackRF's DC spike falls outside the interesting channels. */
enum class ScanBand(val label: String, val centerMhz: Double, val rateMs: Int) {
    Ism433("433 MHz", 433.0, 6),
    Srd868("868 MHz", 866.8, 8),
    Meters169("169 MHz", 169.0, 2),
    Pmr446("446 MHz", 445.8, 2),
}

/**
 * Watches a band and lists what transmits in it: frequency, width, level and on/off behaviour, with the band plan's usual
 * users. Only the power spectrum is analysed; no transmission is decoded.
 */
class EmittersController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var centerMhz by mutableStateOf("433.000")
    var rateMs by mutableIntStateOf(6)
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var emitters by mutableStateOf<List<EmitterInfo>>(emptyList())
        private set
    var spectrum by mutableStateOf<FloatArray?>(null)
        private set
    var floor by mutableStateOf<FloatArray?>(null)
        private set
    var tunedHz by mutableStateOf<Long?>(null)
        private set

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false

    fun parsedCenterHz(): Long? = centerMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..6000.0 }?.let { (it * 1e6).toLong() }

    fun select(b: ScanBand) {
        if (running || starting) return
        centerMhz = "%.3f".format(Locale.US, b.centerMhz)
        rateMs = b.rateMs
        emitters = emptyList(); spectrum = null; floor = null
    }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val hz = parsedCenterHz() ?: run { error = "Fréquence invalide (1 à 6000 MHz)"; return }
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = "Accès USB au HackRF refusé"; return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = "Impossible d'ouvrir le HackRF"; return@launch }
                val rate = rateMs * 1_000_000
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(hz) && r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = "Le HackRF n'a pas accepté la configuration"; return@launch }
                radio = r
                tunedHz = hz
                emitters = emptyList()
                startPipeline(r, hz, rate)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: HackRf, hz: Long, rate: Int) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val scanner = EmitterScanner(FFT, hz.toDouble(), rate.toDouble())
        dspRunning = true
        Thread({
            var last = 0L
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    scanner.feed(item.first, item.second)
                    free.offer(item.first)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 250) {
                    last = now
                    val list = scanner.emitters()
                    val sp = scanner.spectrum?.copyOf()
                    val fl = scanner.floor.copyOf()
                    scope.launch { emitters = list; spectrum = sp; floor = fl }
                }
            }
        }, "sdr-emitters").start()
        // Every transfer matters here: a dropped buffer is a gap in time where a burst can hide.
        r.startRx(
            onSamples = { data, len -> free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) } },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        tunedHz = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(list: List<EmitterInfo>, db: FloatArray, floorDb: FloatArray, centerHz: Long) {
        emitters = list; spectrum = db; floor = floorDb; tunedHz = centerHz; running = true
    }

    companion object {
        const val FFT = 2048
        private const val POOL = 24
    }
}

private fun mhz3(hz: Double) = "%.3f".format(Locale.FRANCE, hz / 1e6)
private fun width(hz: Double) = if (hz >= 1e6) "%.1f MHz".format(Locale.FRANCE, hz / 1e6) else "${(hz / 1e3).roundToInt()} kHz"

@Composable
fun EmittersTool(vm: MainViewModel) {
    val c = vm.tools.emitters
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val center = (c.tunedHz ?: c.parsedCenterHz() ?: 0L).toDouble()
    val half = c.rateMs * 1e6 * 0.35
    val activeNow = c.emitters.count { it.active }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.emitters.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(c.emitters.size, "émetteur", "émetteurs"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text("de ${mhz3(center - half)} à ${mhz3(center + half)} MHz", style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip("Démarrage du HackRF…", AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(if (activeNow > 0) "$activeNow ${plural(activeNow, "actif", "actifs")} en ce moment" else "Aucune émission en cours", if (activeNow > 0) AntTheme.net.good else AntTheme.net.fair, blink = true)
                    device == null -> HeroChip("Aucun HackRF branché", AntTheme.net.poor)
                    else -> HeroChip("${device!!.name} prêt", AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton("Arrêter", Sym.Stop) { c.stop() }
        else StartButton("Lancer la détection", Sym.PlayArrow, enabled = d != null && c.parsedCenterHz() != null) { d?.let(c::start) }
        SpectrumView(c.spectrum, c.floor, null)
        if (c.emitters.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.emitters.forEachIndexed { i, e ->
                    if (i > 0) Hairline()
                    EmitterRow(e)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bande à surveiller", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanBand.entries.forEach { b -> AntFilterChip(b.label, c.centerMhz.toDoubleOrNull() == b.centerMhz && c.rateMs == b.rateMs, { c.select(b) }) }
                }
                if (!c.running) {
                    HostInputField(c.centerMhz, { c.centerMhz = it }, "Fréquence centrale (MHz)", Sym.Stream, keyboardType = KeyboardType.Decimal)
                    Text("Largeur surveillée", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 6, 8, 10).forEach { m -> AntFilterChip("${(m * 0.7).roundToInt().coerceAtLeast(1)} MHz", c.rateMs == m, { c.rateMs = m }) }
                    }
                }
                Text("Gain LNA / VGA (dB)", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 8, 16, 24, 32, 40).forEach { g -> AntFilterChip("LNA $g", c.lnaGain == g, { c.lnaGain = g; c.applyGains() }) }
                    listOf(10, 20, 30, 40).forEach { g -> AntFilterChip("VGA $g", c.vgaGain == g, { c.vgaGain = g; c.applyGains() }) }
                    AntFilterChip("Ampli +14 dB", c.amp, { c.amp = !c.amp; c.applyGains() })
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Le détecteur repère ce qui émet dans la bande : un signal 8 dB au-dessus du bruit de fond (ligne ambre) est suivi, puis classé " +
                        "d'après sa largeur et son rythme d'émission. Il ne décode rien. Le trait central est le résidu du HackRF. " +
                        "Une porteuse continue finit par être absorbée dans le bruit de fond au bout d'une minute environ. Réception seule.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmitterRow(e: EmitterInfo) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (e.active) AntTheme.accent.accent else AntTheme.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(Sym.Radar, size = 22.dp, tint = if (e.active) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${mhz3(e.freqHz)} MHz", Modifier.weight(1f), style = rf(15, 20, 600, tnum = true), maxLines = 1)
                Text(
                    if (e.active) "en émission" else if (e.agoS < 120) "il y a ${e.agoS.roundToInt()} s" else "il y a ${(e.agoS / 60).roundToInt()} min",
                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                )
            }
            Text(e.kind.label, style = rf(13, 18), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.band != null) Text(e.band, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
            Text(
                "%.0f dBFS · largeur %s · %d %s · %d %% du temps".format(Locale.FRANCE, e.peakDb, width(e.bwHz), e.bursts, plural(e.bursts, "rafale", "rafales"), e.dutyPct),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}
