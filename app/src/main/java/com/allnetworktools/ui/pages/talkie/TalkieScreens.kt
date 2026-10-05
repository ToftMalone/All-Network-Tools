package com.allnetworktools.ui.pages.talkie

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Sparkline
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.plural
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun TalkieDashboard(vm: MainViewModel) {
    val roles = AntTheme.net.talkie
    val cable by vm.radioCable.collectAsStateWithLifecycle()
    val channels = vm.tools.radioChannels
    val audio = vm.tools.talkieAudio
    val open = { t: Tool -> vm.navigate { it.copy(page = Page.ToolPage(t)) } }
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container)
                .clickable { open(Tool.RadioChannels) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(Sym.Radio, cookieShape(), 64.dp, roles.accent, roles.onAccent, 30.dp, spinMs = 24_000)
                if (cable != null) TechChip("Câble détecté", roles.accent, roles.onAccent)
            }
            Text("Canaux du talkie", Modifier.padding(top = 16.dp), style = gs(24, 30, 500), color = roles.onContainer)
            Text(
                "Lisez la mémoire d'un Baofeng UV-5R ou d'un Radtel RT-470X, modifiez les canaux, ajoutez des préréglages et réécrivez-les.",
                Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = roles.onContainer,
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Ouvrir", { open(Tool.RadioChannels) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        InfoList("Matériel") {
            InfoRow("Câble de programmation", cable?.name ?: "—")
            InfoRow("Talkie", channels.ident?.let { channels.spec.label } ?: "—")
            InfoRow("Entrée audio", audio.selected?.label ?: "—")
            InfoRow("Émission", "Jamais : lecture et écriture de la mémoire, écoute seule")
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Pour programmer : un câble USB à puce FTDI, CH340, CP210x ou PL2303 sur la prise casque-micro du talkie. " +
                        "Pour l'audio : l'entrée micro du téléphone placée contre le haut-parleur, ou une interface USB (Digirig, carte son) sur la sortie casque. " +
                        "Rien n'est enregistré ni conservé.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- audio ------------------------------------------------------------------------------------

/** Where the audio comes from, and the start / stop button. Shared by the two audio tools. */
@Composable
private fun AudioSourceCard(vm: MainViewModel, hint: String) {
    val a = vm.tools.talkieAudio
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    LaunchedEffect(Unit) { a.refreshInputs() }
    SectionCard {
        Text("Entrée audio", style = rf(14, 20, 600), color = AntTheme.accent.accent)
        if (a.inputs.isEmpty()) {
            Text("Aucune entrée audio trouvée.", Modifier.padding(top = 8.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
        } else {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.inputs.forEach { input -> AntFilterChip(input.label, a.selected?.id == input.id, { if (!a.running) a.selected = input }) }
            }
        }
        Text(hint, Modifier.padding(top = 10.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
        a.error?.let { Text(it, Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.error) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            when {
                !perms.mic -> PillButton("Autoriser le micro", { actions.request(com.allnetworktools.data.PermGroup.Microphone) }, icon = Sym.Mic, height = 48.dp)
                a.running -> PillButton("Arrêter", { a.stop() }, icon = Sym.Close, height = 48.dp, bg = cs.surface, fg = AntTheme.accent.accent)
                else -> PillButton("Écouter", { a.start() }, icon = Sym.Hearing, height = 48.dp)
            }
        }
    }
}

private fun dbText(db: Float) = if (db <= -89f) "−∞" else "%d".format(db.roundToInt()).replace('-', '−')

@Composable
fun ListenTool(vm: MainViewModel) {
    val a = vm.tools.talkieAudio
    val acc = AntTheme.accent
    TopBarAction(Sym.RestartAlt) { a.clear() }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(dbText(a.rmsDb), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text("dBFS", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            LevelBar(((a.rmsDb + 90f) / 80f).coerceIn(0f, 1f), if (a.peakDb > -2f) cs.error else acc.accent, Modifier.padding(top = 10.dp), height = 10.dp, track = cs.surface)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !a.running -> HeroChip("À l'arrêt", cs.outline)
                    a.busy -> HeroChip("Signal reçu", AntTheme.net.good, blink = true)
                    else -> HeroChip("Silence", cs.outline)
                }
                if (a.running) HeroChip("Occupé ${(a.occupancy * 100).roundToInt()} % de la dernière minute", cs.outline)
            }
            if (a.peakDb > -2f) {
                Text("Le son sature : baissez le volume du talkie ou le gain de l'interface.", Modifier.padding(top = 10.dp), style = rf(13, 18, 600), color = cs.error)
            }
        }
        AudioSourceCard(vm, "Sans interface, placez le micro du téléphone contre le haut-parleur du talkie. Avec une interface USB, branchez-la sur la sortie casque.")
        SectionCard {
            Text("Niveau, dernière minute", style = rf(14, 20, 600), color = acc.accent)
            Sparkline(
                a.levelHistory.ifEmpty { listOf(-90f, -90f) }, -90f, -10f, acc.accent,
                Modifier.fillMaxWidth().padding(top = 10.dp).height(64.dp),
            )
        }
        SectionCard {
            Text("Spectre audio, 0 à 4 kHz", style = rf(14, 20, 600), color = acc.accent)
            AudioSpectrum(a)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "1 kHz", "2 kHz", "3 kHz", "4 kHz").forEach { Text(it, style = rf(11, 14), color = cs.onSurfaceVariant) }
            }
            Waterfall(a)
        }
        SectionCard {
            Text("Touches DTMF entendues", style = rf(14, 20, 600), color = acc.accent)
            if (a.digits.isEmpty()) {
                Text("Les chiffres envoyés par un autre talkie (PTT-ID, appel sélectif) s'affichent ici.", Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            } else {
                Text(a.digits.toList().joinToString(" "), Modifier.padding(top = 8.dp), style = mono(26, 34, 600))
            }
        }
    }
}

@Composable
private fun AudioSpectrum(a: TalkieAudioController) {
    val line = AntTheme.accent.accent
    val grid = cs.outlineVariant
    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(110.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp)) {
            val minDb = -100f
            val maxDb = -20f
            fun y(v: Float) = size.height * (1 - ((v - minDb) / (maxDb - minDb)).coerceIn(0f, 1f))
            for (g in -80..-40 step 20) drawLine(grid, Offset(0f, y(g.toFloat())), Offset(size.width, y(g.toFloat())), 1f)
            for (k in 1..3) drawLine(grid, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height), 1f)
            val s = a.spectrum
            if (s != null && s.size > 1) {
                val path = Path()
                s.forEachIndexed { i, v ->
                    val x = size.width * i / (s.size - 1)
                    if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                }
                drawPath(path, line, style = Stroke(2f))
            }
        }
        if (a.spectrum == null) Text("Le spectre s'affiche ici pendant l'écoute", Modifier.align(Alignment.Center), style = rf(13, 18), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Waterfall(a: TalkieAudioController) {
    val rows = a.rows // recompose on each new row
    val image = remember { a.waterfall.asImageBitmap() }
    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(150.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0B1026))) {
        Canvas(Modifier.fillMaxSize()) {
            if (rows >= 0) {
                drawImage(
                    image, IntOffset.Zero, IntSize(TalkieAudioController.WIDTH, TalkieAudioController.HEIGHT),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low,
                )
            }
        }
        Text("Chute d'eau · plus récent en haut", Modifier.align(Alignment.BottomStart).padding(10.dp), style = rf(11, 14), color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun AudioAprsTool(vm: MainViewModel) {
    val a = vm.tools.talkieAudio
    val acc = AntTheme.accent
    TopBarAction(Sym.RestartAlt) { a.clear() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.stations.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(plural(a.stations.size, "station", "stations"), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text("${a.packets} ${plural(a.packets, "balise décodée", "balises décodées")} · APRS 1200 bauds, 144,800 MHz en Europe", style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                if (a.running) HeroChip("Écoute en cours", AntTheme.net.good, blink = true) else HeroChip("À l'arrêt", cs.outline)
            }
        }
        AudioSourceCard(vm, "Réglez le talkie sur 144,800 MHz (préréglage « APRS ») et envoyez sa sortie casque vers l'entrée audio. Un volume moyen suffit.")
        if (a.stations.isEmpty()) {
            SectionCard {
                Text(
                    "Les balises des radioamateurs (position, météo, relais) s'affichent ici dès qu'elles sont entendues. Elles reviennent toutes les quelques minutes. " +
                        "Les messages entre opérateurs sont comptés, jamais affichés.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                a.stations.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.call, Modifier.weight(1f), style = rf(16, 22, 600), maxLines = 1)
                            Text("il y a ${((now - s.lastSeenMs) / 1000).coerceAtLeast(0)} s", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                        }
                        val place = if (s.lat != null && s.lon != null) "%.4f, %.4f".format(Locale.FRANCE, s.lat, s.lon) else null
                        val line = listOfNotNull(
                            Aprs.symbolName(s.symbol), place, s.speedKn?.takeIf { it > 2 }?.let { "${(it * 1.852).roundToInt()} km/h" },
                            s.via?.let { "via $it" }, "${s.packets} ${plural(s.packets, "balise")}",
                        ).joinToString(" · ")
                        Text(line, Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
                        s.comment?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 2.dp), style = rf(13, 18), maxLines = 2) }
                    }
                }
            }
        }
    }
}
