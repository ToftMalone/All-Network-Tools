package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.NfcChipReport
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
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

@Composable
fun NfcAnalyzeTool(c: NfcAnalyzeController) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        c.begin(context)
        onDispose { c.end(context) }
    }
    val r = c.report
    PageColumn {
        HeroCard {
            Text(r?.chip ?: "Approchez un tag", style = gs(24, 30, 500))
            Text(
                when {
                    r == null -> "L'app identifie la puce, son fabricant et sa mémoire. Le contenu du tag n'est pas modifié."
                    r.exact -> "Modèle exact, donné par la puce elle-même."
                    else -> "Famille déduite des identifiants radio : le modèle exact n'a pas pu être demandé à la puce."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.busy -> HeroChip("Analyse…", AntTheme.net.fair, blink = true)
                    r != null && r.anomalies.isNotEmpty() -> HeroChip("${r.anomalies.size} incohérence(s)", AntTheme.net.poor)
                    r != null -> HeroChip("Aucune incohérence", AntTheme.net.good)
                    else -> HeroChip("Écoute NFC active", AntTheme.net.good, blink = true)
                }
            }
        }
        if (r != null) {
            r.anomalies.forEach { a ->
                SectionCard(color = cs.errorContainer) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Warning, size = 22.dp, tint = cs.onErrorContainer)
                        Text(a, style = rf(13, 18), color = cs.onErrorContainer)
                    }
                }
            }
            MemoryCard(r)
            InfoList("Puce") {
                InfoRow("Modèle", r.chip)
                r.manufacturer?.let { InfoRow("Fabricant", it) }
                InfoRow("UID", r.uidHex)
                InfoRow("Longueur de l'UID", "${r.uidBytes} octets" + if (r.uidBytes == 4) " (souvent aléatoire)" else "")
                r.model?.let { InfoRow("Pages utilisateur", "${it.firstUserPage}–${it.lastUserPage} (${it.userPages} pages)") }
                r.versionHex?.let { InfoRow("Réponse GET_VERSION", it) }
            }
            InfoList("Radio") {
                r.atqa?.let { InfoRow("ATQA", it) }
                r.sak?.let { InfoRow("SAK", it) }
                r.maxTransceive?.let { InfoRow("Trame max.", "$it octets") }
                r.techLabels.forEach { InfoRow("Technologie", it) }
            }
            if (r.capability != null || r.ndefType != null) {
                InfoList("NDEF") {
                    r.ndefType?.let { InfoRow("Type NFC Forum", it.substringAfterLast('.').replace("type", "Type ")) }
                    r.capability?.let { cc ->
                        InfoRow("Conteneur de capacités", if (cc.magicOk) "Valide (E1, v${cc.version shr 4}.${cc.version and 0xF})" else "Absent")
                        if (cc.magicOk) InfoRow("Taille annoncée", "${cc.announcedBytes} octets")
                    }
                    r.ndefWritable?.let { InfoRow("Modifiable", if (it) "Oui" else "Non (lecture seule)") }
                    r.ndefCanLock?.let { InfoRow("Verrouillable", if (it) "Oui" else "Non") }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Les NTAG et Ultralight EV1 donnent leur modèle exact (commande GET_VERSION). Pour les autres, la famille est déduite " +
                        "des identifiants radio (ATQA/SAK). Les cartes à puce (bancaires, transport, badges sécurisés) ne sont pas interrogées.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemoryCard(r: NfcChipReport) {
    val total = r.ndefCapacity ?: r.model?.userBytes ?: return
    val used = r.ndefUsed ?: 0
    val fraction = if (total == 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)
    SectionCard {
        Text("Mémoire", style = rf(16, 22, 600))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("$used", style = gs(32, 40, 500, tnum = true))
            Text(" / $total octets utilisés", Modifier.padding(bottom = 6.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction).height(12.dp).clip(RoundedCornerShape(6.dp)).background(AntTheme.accent.accent))
        }
        Column(Modifier.padding(top = 8.dp)) {
            Text(
                "${r.ndefFree ?: (total - used)} octets libres pour un message NDEF" +
                    (r.model?.let { " · ${it.userBytes} octets de mémoire utilisateur sur la puce" } ?: ""),
                style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
    }
}
