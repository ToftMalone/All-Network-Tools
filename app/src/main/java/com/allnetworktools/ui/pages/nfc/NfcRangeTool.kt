package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.LinkSample
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import java.util.Locale

private fun zoneName(z: Int): String {
    val row = z / NfcAntennaController.COLS
    val col = z % NfcAntennaController.COLS
    val v = listOf("en haut", "au milieu haut", "au milieu bas", "en bas")[row]
    val h = listOf("à gauche", "au centre", "à droite")[col]
    return "$v $h"
}

@Composable
private fun scoreColor(score: Int): Color {
    val n = AntTheme.net
    return if (score >= 50) lerp(n.fair, n.good, (score - 50) / 50f) else lerp(n.poor, n.fair, score / 50f)
}

/** Dos du téléphone découpé en zones : chaque zone reçoit la qualité de lecture mesurée quand le tag y est posé. */
@Composable
fun NfcRangeTool(c: NfcAntennaController) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        c.begin(context)
        onDispose { c.end(context) }
    }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    val active = c.active
    val best = c.best
    PageColumn {
        HeroCard {
            Text(
                when {
                    active != null && c.countdown > 0 -> "Posez le tag ${zoneName(active)} · ${c.countdown}"
                    active != null && c.waitingForTag -> "Approchez le tag ${zoneName(active)}"
                    active != null -> "Mesure ${zoneName(active)}…"
                    best != null -> "Meilleure zone : ${zoneName(best)}"
                    else -> "Où est l'antenne ?"
                },
                style = gs(24, 30, 500),
            )
            Text(
                when {
                    active != null -> "Gardez le tag immobile, à plat contre le dos du téléphone."
                    c.scores.isEmpty() -> "Touchez une zone ci-dessous, posez le tag à cet endroit du dos du téléphone : l'app le lit en boucle pendant 2,5 s et note la qualité de la liaison."
                    else -> "${c.scores.size} zone(s) mesurée(s) sur ${NfcAntennaController.COLS * NfcAntennaController.ROWS}. Touchez une autre zone pour la mesurer."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                if (active != null) HeroChip(if (c.measuring) "Lecture en boucle" else "Préparation", AntTheme.net.fair, blink = true)
                else HeroChip("Écoute NFC active", AntTheme.net.good, blink = true)
            }
        }
        PhoneBack(c, active, best)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Legend(AntTheme.net.good, "Bonne")
            Legend(AntTheme.net.fair, "Moyenne")
            Legend(AntTheme.net.poor, "Aucune lecture")
        }
        val sel = best?.let { c.scores[it] }
        if (sel != null) {
            SectionCard {
                Text("Zone ${zoneName(best)}", style = rf(16, 22, 600))
                Text(
                    "${sel.successes} lectures réussies sur ${sel.attempts} (${(sel.rate * 100).toInt()} %)" +
                        (sel.avgLatencyMs?.let { " · %.1f ms par lecture".format(Locale.FRANCE, it) } ?: ""),
                    Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Android ne donne pas la puissance du signal NFC : le score combine le taux de lectures réussies et leur rapidité. " +
                        "C'est à l'endroit le mieux noté qu'il faut présenter les cartes, badges et terminaux de paiement.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun PhoneBack(c: NfcAntennaController, active: Int?, best: Int?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(220.dp).aspectRatio(0.48f).clip(RoundedCornerShape(36.dp)).background(cs.surfaceContainerHigh)
                .border(2.dp, cs.outlineVariant, RoundedCornerShape(36.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(NfcAntennaController.ROWS) { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(NfcAntennaController.COLS) { col ->
                        val zone = row * NfcAntennaController.COLS + col
                        Zone(Modifier.weight(1f), zone, c.scores[zone], zone == active, zone == best, camera = row == 0 && col == 0) {
                            if (zone == active) c.cancel() else c.measure(zone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Zone(modifier: Modifier, zone: Int, sample: LinkSample?, active: Boolean, best: Boolean, camera: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (zone == 0) 28.dp else 12.dp)
    val bg = when {
        sample != null -> scoreColor(sample.score).copy(alpha = 0.85f)
        else -> cs.surfaceContainerHighest
    }
    Box(
        modifier.fillMaxSize().clip(shape).background(bg)
            .border(if (active || best) 3.dp else 0.dp, if (active) AntTheme.accent.accent else if (best) cs.onSurface else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            active -> Symbol(Sym.Nfc, size = 26.dp, tint = cs.onSurface)
            sample != null -> Text("${sample.score}", style = gs(20, 24, 500, tnum = true), color = Color.Black.copy(alpha = 0.8f))
            camera -> Box(Modifier.size(28.dp).clip(CircleShape).background(cs.outlineVariant))
            else -> Unit
        }
    }
}
