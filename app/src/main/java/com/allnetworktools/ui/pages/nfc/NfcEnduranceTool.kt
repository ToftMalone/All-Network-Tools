package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.EnduranceReport
import com.allnetworktools.data.PageFault
import com.allnetworktools.ui.components.AntFilterChip
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
import com.allnetworktools.ui.tools.StartButton
import java.util.Locale

@Composable
fun NfcEnduranceTool(c: NfcEnduranceController) {
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { c.disarm(context) } }
    val r = c.report
    val p = c.progress
    PageColumn {
        HeroCard {
            Text(
                when {
                    p != null -> "Test en cours…"
                    c.armed -> "Approchez le tag à tester"
                    r == null -> "Test d'endurance"
                    r.aborted != null -> "Test interrompu"
                    r.ok -> "Tag en bon état"
                    else -> "${r.faults.size} ${if (r.pageLevel) "page(s) défectueuse(s)" else "cycle(s) en erreur"}"
                },
                style = gs(24, 30, 500),
            )
            Text(
                when {
                    p != null -> "Gardez le tag immobile contre le téléphone. Cycle ${p.cycle.coerceAtLeast(1)} sur ${p.cycles}."
                    c.armed -> "Posez le tag et ne le bougez plus jusqu'à la fin du test."
                    r == null -> "Écrit puis relit un motif de test dans toute la mémoire, plusieurs fois, pour repérer une puce usée ou contrefaite. Le contenu d'origine est remis à la fin."
                    r.aborted != null -> r.aborted
                    r.ok -> "Toutes les écritures ont été relues à l'identique sur ${r.cycles} ${if (r.cycles > 1) "cycles" else "cycle"}."
                    else -> "Certaines données relues ne correspondent pas à ce qui a été écrit : la mémoire de ce tag n'est pas fiable."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            if (p != null) {
                val f = if (p.total == 0) 0f else (p.done.toFloat() / p.total).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surface)) {
                    Box(Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(AntTheme.accent.accent))
                }
            } else if (c.armed) {
                Row(Modifier.padding(top = 12.dp)) { HeroChip("En attente d'un tag", AntTheme.net.fair, blink = true) }
            } else if (r != null) {
                Row(Modifier.padding(top = 12.dp)) {
                    HeroChip(
                        if (r.restored) "Contenu d'origine remis" else "Contenu d'origine non remis",
                        if (r.restored) AntTheme.net.good else AntTheme.net.poor,
                    )
                }
            }
        }
        if (p == null) {
            Text("Nombre de cycles", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 10, 25).forEach { n -> AntFilterChip("$n", c.cycles == n, { c.cycles = n }) }
            }
            Box(Modifier.padding(top = 4.dp)) {
                if (c.armed) StartButton("Annuler", Sym.Stop) { c.disarm(context) }
                else StartButton("Lancer le test", Sym.Science) { c.arm(context) }
            }
        }
        if (r != null && p == null) ResultCards(r)
        SectionCard(color = cs.surfaceContainerHigh) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Warning, size = 22.dp, tint = AntTheme.net.fair)
                Text(
                    "Chaque cycle use un peu la mémoire (environ 100 000 écritures garanties par page sur un NTAG). " +
                        "Si le tag est retiré pendant le test, son contenu d'origine peut être perdu. " +
                        "Les tags verrouillés ou protégés par mot de passe ne sont pas modifiés.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultCards(r: EnduranceReport) {
    InfoList("Résultat") {
        InfoRow("Puce", r.chip)
        InfoRow("Méthode", if (r.pageLevel) "Page par page (${r.pagesTested} pages)" else "Message NDEF complet")
        InfoRow("Cycles", "${r.cycles}")
        if (r.avgWriteMs > 0) InfoRow(if (r.pageLevel) "Écriture d'une page" else "Écriture du message", "%.1f ms".format(Locale.FRANCE, r.avgWriteMs))
        if (r.avgReadMs > 0) InfoRow(if (r.pageLevel) "Lecture de 4 pages" else "Lecture du message", "%.1f ms".format(Locale.FRANCE, r.avgReadMs))
    }
    if (r.pageLevel && r.pagesTested > 0) {
        SectionCard {
            Text("Carte de la mémoire", style = rf(16, 22, 600))
            Text("Une case par page de 4 octets.", Modifier.padding(top = 2.dp, bottom = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            val first = r.faults.keys.minOrNull()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(r.pagesTested) { i ->
                    val fault = r.faults[i + 4]
                    val color = when (fault) {
                        null -> AntTheme.net.good
                        PageFault.Mismatch -> AntTheme.net.poor
                        PageFault.WriteRefused -> AntTheme.net.fair
                    }
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
            if (first != null) {
                Text(
                    "Première page en erreur : $first. " + "Rouge : donnée relue différente · Orange : écriture refusée (page verrouillée ou défaillante).",
                    Modifier.padding(top = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
