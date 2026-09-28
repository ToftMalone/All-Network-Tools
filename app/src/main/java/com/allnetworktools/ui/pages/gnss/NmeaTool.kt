package com.allnetworktools.ui.pages.gnss

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val NmeaTypes = listOf("GGA", "RMC", "GSA", "GSV", "VTG", "GLL", "Autres")

class NmeaLine(val atMs: Long, val type: String, val header: String, val body: String, val checksum: String, val valid: Boolean) {
    val raw get() = header + body + checksum

    companion object {
        /** Splits "$GPGGA,…*47" into header, body and checksum and verifies the XOR checksum. */
        fun parse(atMs: Long, sentence: String): NmeaLine? {
            val s = sentence.trim()
            if (s.length < 7 || (s[0] != '$' && s[0] != '!')) return null
            val star = s.lastIndexOf('*')
            val core = if (star > 0) s.substring(1, star) else s.substring(1)
            val comma = core.indexOf(',').takeIf { it > 0 } ?: core.length
            val address = core.substring(0, comma)
            val type = address.takeLast(3).let { if (it in NmeaTypes) it else "Autres" }
            val cs = if (star > 0) s.substring(star) else ""
            val expected = core.fold(0) { acc, ch -> acc xor ch.code }
            val valid = star > 0 && s.substring(star + 1).take(2).toIntOrNull(16) == expected
            return NmeaLine(atMs, type, s.substring(0, comma + 1), core.substring(comma), cs, valid)
        }
    }
}

class NmeaController {
    val lines = mutableStateListOf<NmeaLine>()
    val counts = mutableStateMapOf<String, Int>()
    val filters = mutableStateMapOf<String, Boolean>().apply { NmeaTypes.forEach { put(it, true) } }
    var total by mutableIntStateOf(0)
    var live by mutableStateOf(true)
    var rate by mutableStateOf(0f)
    var talker by mutableStateOf<String?>(null)
    private val recent = ArrayDeque<Long>()
    private val all = ArrayDeque<String>()

    fun add(atMs: Long, sentence: String) {
        val l = NmeaLine.parse(atMs, sentence) ?: return
        lines.add(l)
        if (lines.size > 400) lines.removeRange(0, lines.size - 400)
        counts[l.type] = (counts[l.type] ?: 0) + 1
        total++
        all.addLast(l.raw)
        while (all.size > 5000) all.removeFirst()
        val now = System.currentTimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 5000) recent.removeFirst()
        rate = recent.size / 5f
        if (l.header.length >= 3) talker = l.header.substring(1, 3)
    }

    fun visible(): List<NmeaLine> = lines.filter { filters[it.type] == true }

    fun export(): String = all.joinToString("\r\n")
}

@Composable
fun NmeaTool(c: NmeaController, vm: com.allnetworktools.MainViewModel, source: () -> Flow<Pair<Long, String>> = vm::nmea) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    val net = AntTheme.net
    val typeColor = mapOf("GGA" to net.gps, "RMC" to net.beidou, "GSA" to net.galileo, "GSV" to acc.accent, "VTG" to net.glonass, "GLL" to net.qzss, "Autres" to cs.outline)
    LaunchedEffect(c.live) { if (c.live) source().collect { (ts, s) -> c.add(ts, s) } }
    val vis = c.visible().takeLast(40)
    val recFile by vm.recording.nmeaFile.collectAsStateWithLifecycle()
    val recCount by vm.recording.nmeaCount.collectAsStateWithLifecycle()
    val recActive by vm.recording.active.collectAsStateWithLifecycle()
    val recording = com.allnetworktools.service.RecKind.Nmea in recActive
    PageColumn {
        com.allnetworktools.ui.pages.RecordingCard(
            vm, com.allnetworktools.service.RecKind.Nmea,
            idleText = "Enregistrez les phrases dans un fichier .nmea, même application fermée.",
            activeText = "${fmt(recCount)} phrases · ${recFile?.name ?: "fichier .nmea"}",
        )
        val last = recFile
        if (last != null && last.exists() && !recording) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Symbol(Sym.Description, size = 22.dp, tint = acc.accent)
                Column(Modifier.weight(1f)) {
                    Text(last.name, style = rf(14, 20, 600), maxLines = 1)
                    Text("${fmt(last.length() / 1024f, 0)} Ko · dernier enregistrement", style = rf(12, 16), color = cs.onSurfaceVariant)
                }
                com.allnetworktools.ui.components.TextAction("Partager", { actions.shareFile(last, "text/plain", last.name) }, trailingIcon = Sym.IosShare)
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerLow).padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.live && c.total > 0) BlinkDot(net.good, 10.dp, 1200) else Box(Modifier.size(10.dp).clip(CircleShape).background(cs.outline))
                    Text(
                        when {
                            !c.live -> "En pause"
                            c.total == 0 -> "En attente…"
                            else -> "En direct · ${fmt(c.rate, 0)} Hz"
                        },
                        style = rf(16, 22, 600),
                    )
                }
                Text(
                    "${fmt(c.total)} phrases" + (if (c.live && c.total > 0) " · ${fmt(c.rate, 1)} /s" else "") + " · NMEA 0183",
                    Modifier.padding(top = 2.dp), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                )
            }
            IconCircleButton(Sym.ContentCopy, {
                actions.copy("NMEA", vis.joinToString("\n") { it.raw }); actions.toast("${vis.size} phrases copiées")
            }, tint = cs.onSurfaceVariant)
            IconCircleButton(Sym.IosShare, {
                if (c.total == 0) actions.toast("Aucune phrase à exporter")
                else actions.share("gnss_${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.FRANCE).format(Date())}.nmea", c.export())
            }, tint = cs.onSurfaceVariant)
            val radius by animateDpAsState(if (c.live) 18.dp else 28.dp, Motion.bouncy(), label = "play")
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(radius)).background(acc.accent).clickable { haptics.segment(); c.live = !c.live },
                contentAlignment = Alignment.Center,
            ) { Symbol(if (c.live) Sym.Pause else Sym.PlayArrow, size = 28.dp, filled = true, tint = acc.onAccent) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NmeaTypes.forEach { t ->
                val on = c.filters[t] == true
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) acc.container else Color.Transparent)
                        .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, RoundedCornerShape(8.dp))
                        .clickable { haptics.tick(); c.filters[t] = !on }.padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) typeColor.getValue(t) else cs.outline))
                    Text(t, style = rf(13, 18, 700), color = if (on) acc.onContainer else cs.onSurfaceVariant)
                    Text(fmt(c.counts[t] ?: 0), style = rf(12, 16, 500, tnum = true), color = if (on) acc.onContainer else cs.onSurfaceVariant)
                }
            }
        }
        when {
            c.total == 0 && c.live -> ToolEmpty(
                Sym.HourglassEmpty, "En attente du récepteur",
                "Aucune phrase reçue pour l'instant. Le récepteur démarre : placez-vous près d'une fenêtre ou à l'extérieur.", null,
            )
            vis.isEmpty() && c.total > 0 -> ToolEmpty(
                Sym.HourglassEmpty, "Aucune phrase affichée",
                "Aucune phrase reçue pour les filtres choisis. Activez d'autres types.", "Afficher tous les types",
            ) { NmeaTypes.forEach { c.filters[it] = true } }
            vis.isNotEmpty() -> {
                val time = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLowest)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    vis.forEach { l ->
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(time.format(Date(l.atMs)) + " ") }
                                withStyle(SpanStyle(color = typeColor.getValue(l.type), fontWeight = FontWeight.Bold)) { append(l.header) }
                                append(l.body)
                                withStyle(SpanStyle(color = if (l.valid) cs.onSurfaceVariant else cs.error)) { append(l.checksum) }
                            },
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            style = mono(11, 16), color = cs.onSurface,
                        )
                    }
                    if (!c.live) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Symbol(Sym.Pause, size = 16.dp, tint = cs.onSurfaceVariant)
                            Text("Flux en pause · défilement libre", style = rf(12, 16, 500), color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
