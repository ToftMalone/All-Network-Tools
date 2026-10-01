package com.allnetworktools.ui.pages.ir

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.IrEncoder
import com.allnetworktools.data.IrKey
import com.allnetworktools.data.IrProtocol
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
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
import com.allnetworktools.ui.tools.ToolError

private fun ranges(list: List<IntRange>) =
    if (list.isEmpty()) "Non indiquées par le téléphone"
    else list.joinToString(", ") { r -> if (r.first == r.last) "${r.first / 1000} kHz" else "${r.first / 1000}–${r.last / 1000} kHz" }

@Composable
private fun SentLine(c: IrController) {
    val last = c.last
    val (text, color) = when {
        c.sending -> "Émission…" to AntTheme.net.fair
        last == null -> "Prêt à émettre" to AntTheme.net.good
        last.error != null -> "${last.label} : ${last.error}" to AntTheme.net.poor
        else -> "Envoyé : ${last.label}" to AntTheme.net.good
    }
    HeroChip(text, color, blink = c.sending)
}

private fun keyIcon(k: IrKey) = when (k) {
    IrKey.Power -> Sym.PowerSettings
    IrKey.VolUp -> Sym.VolumeUp
    IrKey.VolDown -> Sym.VolumeDown
    IrKey.Mute -> Sym.VolumeOff
    IrKey.ChUp -> Sym.ExpandLess
    IrKey.ChDown -> Sym.ExpandMore
    IrKey.Source -> Sym.Input
}

@Composable
private fun RemoteKey(key: IrKey, enabled: Boolean, modifier: Modifier = Modifier, size: Dp = 64.dp, primary: Boolean = false, onPress: () -> Unit) {
    val acc = AntTheme.accent
    val haptics = AntTheme.haptics
    Column(modifier.alpha(if (enabled) 1f else 0.35f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(if (primary) CircleShape else RoundedCornerShape(22.dp))
                .background(if (primary) acc.accent else acc.container)
                .clickable(enabled = enabled) { haptics.confirm(); onPress() },
            contentAlignment = Alignment.Center,
        ) {
            Symbol(keyIcon(key), size = if (primary) 34.dp else 28.dp, filled = true, tint = if (primary) acc.onAccent else acc.onContainer)
        }
        Text(key.label, Modifier.padding(top = 6.dp), style = rf(12, 16, 500), color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun IrRemoteTool(c: IrController, onDetector: () -> Unit) {
    PageColumn {
        if (!c.hasEmitter) { NoEmitterCard(onDetector); return@PageColumn }
        HeroCard {
            Text("Télécommande ${c.brand.label}", style = gs(24, 30, 500))
            Text(
                "Visez l'appareil avec le haut du téléphone, à quelques mètres au plus.",
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) { SentLine(c) }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.allnetworktools.data.IrBrand.entries.forEach { b -> AntFilterChip(b.label, c.brand == b, { c.brand = b }) }
        }
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                RemoteKey(IrKey.Power, c.brand.code(IrKey.Power) != null, size = 84.dp, primary = true) { c.press(IrKey.Power) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(IrKey.VolUp, IrKey.Mute, IrKey.ChUp).forEach { k -> RemoteKey(k, c.brand.code(k) != null) { c.press(k) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(IrKey.VolDown, IrKey.Source, IrKey.ChDown).forEach { k -> RemoteKey(k, c.brand.code(k) != null) { c.press(k) } }
                }
            }
        }
        InfoNote(
            "Codes ${c.brand.protocol.label} documentés pour les téléviseurs ${c.brand.label} (bases LIRC et Flipper Zero) : " +
                "ils fonctionnent sur la plupart des modèles de la marque, mais pas forcément sur tous. " +
                "Android ne permet pas de recevoir l'infrarouge : l'app ne peut pas apprendre les codes d'une autre télécommande.",
        )
    }
}

@Composable
fun IrCustomTool(c: IrController, onDetector: () -> Unit) {
    val (code, error) = c.customCode()
    PageColumn {
        if (!c.hasEmitter) { NoEmitterCard(onDetector); return@PageColumn }
        HeroCard {
            Text("Code personnalisé", style = gs(24, 30, 500))
            Text(
                "Envoyez n'importe quelle commande d'un protocole courant, en décimal ou en hexadécimal (0x…).",
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) { SentLine(c) }
        }
        Text("Protocole", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IrProtocol.entries.forEach { p -> AntFilterChip(p.label, c.protocol == p, { c.protocol = p }) }
        }
        HostInputField(c.address, { c.address = it }, "Adresse (0 à ${c.protocol.maxAddress})", Sym.Tune, keyboardType = KeyboardType.Ascii)
        HostInputField(c.command, { c.command = it }, "Commande (0 à ${c.protocol.maxCommand})", Sym.Tune, keyboardType = KeyboardType.Ascii)
        if (error != null) {
            SectionCard(color = cs.errorContainer) { Text(error, style = rf(13, 18), color = cs.onErrorContainer) }
        }
        Box(Modifier.padding(top = 4.dp)) {
            StartButton("Envoyer", Sym.Send, enabled = code != null && !c.sending) {
                code?.let { c.send(it, "${it.protocol.label} ${hex(it.address)}/${hex(it.command)}") }
            }
        }
        if (code != null) {
            val signal = IrEncoder.encode(code)
            InfoList("Trame") {
                InfoRow("Porteuse", "${signal.carrier / 1000} kHz")
                InfoRow("Impulsions", "${signal.pattern.size}")
                InfoRow("Durée", "%.1f ms".format(java.util.Locale.FRANCE, signal.durationUs / 1000.0))
            }
        }
        InfoNote(
            "NEC : LG, Hisense, TCL, nombreux appareils asiatiques · Samsung : téléviseurs et barres de son Samsung · " +
                "Sony SIRC : appareils Sony · RC5 : Philips et matériel européen ancien.",
        )
    }
}

private fun hex(v: Int) = "0x" + v.toString(16).uppercase().padStart(2, '0')

@Composable
fun IrTestTool(c: IrController, onDetector: () -> Unit) {
    PageColumn {
        if (!c.hasEmitter) { NoEmitterCard(onDetector); return@PageColumn }
        HeroCard {
            Text("Test de l'émetteur", style = gs(24, 30, 500))
            Text(
                "Ouvrez l'appareil photo (souvent la caméra avant), visez la LED infrarouge en haut du téléphone et lancez la salve : " +
                    "elle doit clignoter en violet à l'écran.",
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) { SentLine(c) }
        }
        StartButton("Émettre une salve de 1,5 s", Sym.PhotoCamera, enabled = !c.sending) { c.testBurst() }
        InfoList("Émetteur") {
            InfoRow("Émetteur infrarouge", "Présent")
            InfoRow("Fréquences porteuses", ranges(c.ranges))
            InfoRow("Émissions cette session", "${c.sentCount}")
        }
        InfoNote(
            "Les capteurs photo voient l'infrarouge que l'œil ne voit pas. Si rien ne clignote, essayez l'autre caméra : " +
                "certaines filtrent l'infrarouge. Si la salve est refusée, le pilote infrarouge du téléphone a renvoyé une erreur.",
        )
    }
}

@Composable
private fun InfoNote(text: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Text(text, style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

/** Shown instead of an emitter tool on phones that have no infrared emitter. */
@Composable
fun NoEmitterCard(onDetector: () -> Unit) {
    ToolError(
        Sym.SettingsRemote, "Pas d'émetteur infrarouge",
        "Ce téléphone n'a pas l'émetteur infrarouge de télécommande qu'Android met à disposition des applications. " +
            "Les capteurs infrarouges de l'appareil photo (profondeur, autofocus, proximité) ne peuvent que recevoir : " +
            "ils ne savent pas envoyer de codes à un téléviseur. Le détecteur d'infrarouge, lui, fonctionne.",
        "Détecteur", onDetector,
    )
}

@Composable
fun IrDashboard(vm: MainViewModel) {
    val roles = AntTheme.net.ir
    val c = vm.tools.ir
    val emitter = c.hasEmitter
    val open = { t: Tool -> vm.navigate { it.copy(page = Page.ToolPage(t)) } }
    val sensors = androidx.compose.runtime.remember { vm.irSensors() }
    PageColumn {
        val hero = if (emitter) Tool.IrRemote else Tool.IrDetect
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container)
                .clickable { open(hero) }.padding(20.dp),
        ) {
            ShapeBadge(if (emitter) Sym.SettingsRemote else Sym.Videocam, cookieShape(), 64.dp, roles.accent, roles.onAccent, 30.dp, spinMs = 20_000)
            Text(if (emitter) "Télécommande" else "Détecteur d'infrarouge", Modifier.padding(top = 16.dp), style = gs(24, 30, 500), color = roles.onContainer)
            Text(
                if (emitter) "Allumez, éteignez et réglez le son d'un téléviseur Samsung, LG, Sony ou Philips."
                else "Vérifiez qu'une télécommande émet bien : la caméra voit sa LED infrarouge, invisible à l'œil.",
                Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = roles.onContainer,
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (emitter) {
                    Box(
                        Modifier.padding(end = 12.dp).size(48.dp).clip(CircleShape).background(roles.accent)
                            .clickable { c.press(IrKey.Power) },
                        contentAlignment = Alignment.Center,
                    ) { Symbol(Sym.PowerSettings, size = 26.dp, filled = true, tint = roles.onAccent) }
                }
                PillButton("Ouvrir", { open(hero) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        InfoList("Émetteur infrarouge") {
            InfoRow("État", if (emitter) "Disponible" else "Absent")
            if (emitter) {
                InfoRow("Fréquences porteuses", ranges(c.ranges))
                InfoRow("Protocoles", IrProtocol.entries.joinToString(", ") { it.label })
                c.last?.let { InfoRow("Dernière émission", if (it.error == null) it.label else "Échec") }
            }
        }
        InfoList("Capteurs infrarouges") {
            if (sensors.isEmpty()) InfoRow("Déclarés au système", "Aucun")
            sensors.forEach { InfoRow(it.label, it.detail) }
        }
        InfoNote(
            if (emitter) "L'infrarouge ne fonctionne qu'en ligne droite et à quelques mètres. Rien n'est émis sans que vous appuyiez sur un bouton."
            else "Les capteurs infrarouges de l'appareil photo (profondeur, autofocus laser) et de proximité ne font que recevoir, et beaucoup de " +
                "fabricants ne les déclarent même pas aux applications. Seul un émetteur infrarouge de télécommande, absent de ce téléphone, permet de piloter un téléviseur.",
        )
    }
}
