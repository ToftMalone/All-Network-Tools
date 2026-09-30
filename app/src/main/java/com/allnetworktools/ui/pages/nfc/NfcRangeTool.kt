package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.components.Hairline
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
import com.allnetworktools.util.plural
import kotlinx.coroutines.delay

@Composable
fun NfcRangeTool(c: NfcRangeController) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        c.begin(context)
        onDispose { c.end(context) }
    }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { delay(200); now = System.currentTimeMillis() } }
    val hits = c.hits
    val intervals = hits.zipWithNext { a, b -> a - b }
    PageColumn {
        HeroCard {
            Text(hits.size.toString(), style = gs(48, 52, 500, tnum = true))
            Text(plural(hits.size, "lecture", "lectures") + " cette session", style = rf(16, 22, 500))
            Text(
                if (hits.isEmpty()) "Faites glisser lentement le tag sur le dos du téléphone : l'app signale chaque fois qu'elle le lit, ce qui aide à repérer l'antenne."
                else "Dernière lecture il y a ${((now - hits.first()) / 1000.0).let { "%.1f".format(java.util.Locale.FRANCE, it) }} s",
                Modifier.padding(top = 6.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) { HeroChip("Écoute NFC active", AntTheme.net.good, blink = true) }
        }
        if (intervals.isNotEmpty()) {
            SectionCard {
                Text("Régularité des lectures", style = rf(16, 22, 600))
                Text(
                    "Intervalle moyen entre deux lectures : ${"%.2f".format(java.util.Locale.FRANCE, intervals.average() / 1000.0)} s sur les ${intervals.size} derniers. " +
                        "Plus il est court et régulier, plus le tag reste bien placé sur l'antenne.",
                    Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
        if (hits.isNotEmpty()) {
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Journal", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                hits.take(20).forEachIndexed { i, t ->
                    Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Symbol(Sym.Nfc, size = 18.dp, tint = cs.onSurfaceVariant)
                        Text("Lecture ${hits.size - i}", Modifier.weight(1f), style = rf(13, 18))
                        Text("il y a ${((now - t) / 1000.0).let { "%.1f".format(java.util.Locale.FRANCE, it) }} s", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Android ne donne pas la puissance du signal NFC aux applications : cet outil compte seulement les lectures réussies pour repérer, par tâtonnement, " +
                        "où se trouve l'antenne et jusqu'à quelle distance elle capte.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
