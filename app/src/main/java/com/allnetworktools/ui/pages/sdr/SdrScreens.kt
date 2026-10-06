package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import java.util.Locale

internal fun snr(db: Double) = "%+.1f dB".format(Locale.FRANCE, db)

@Composable
fun SdrDashboard(vm: MainViewModel) {
    val roles = AntTheme.net.sdr
    val c = vm.tools.spectrum
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val open = { t: Tool -> vm.navigate { it.copy(page = Page.ToolPage(t)) } }
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container)
                .clickable { open(Tool.Spectrum) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(Sym.BarChart, cookieShape(), 64.dp, roles.accent, roles.onAccent, 30.dp, spinMs = 20_000)
                if (c.running) TechChip("En écoute", roles.accent, roles.onAccent)
            }
            Text("Analyseur de spectre", Modifier.padding(top = 16.dp), style = gs(24, 30, 500), color = roles.onContainer)
            Text(
                "Spectre et chute d'eau, de 1 MHz à 6 GHz, avec préréglages de bandes.",
                Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = roles.onContainer,
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Ouvrir", { open(Tool.Spectrum) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        InfoList("Récepteur") {
            InfoRow("Matériel", device?.name ?: "—")
            InfoRow("Mode", "Réception uniquement")
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Le HackRF ne reçoit que lorsqu'un outil est lancé, et s'arrête quand vous quittez l'onglet SDR. " +
                        "L'application n'émet jamais. Le décodage est fait par l'application, sur le téléphone.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun Empty(text: String) {
    SectionCard { Text(text, style = rf(13, 18), color = cs.onSurfaceVariant) }
}

@Composable
internal fun ListCard(content: @Composable () -> Unit) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) { content() }
}

/**
 * The HackRF's two gain stages, each on its own line so the values never mix, then the +14 dB amplifier when the tool
 * offers it. Calls [onChange] after every tap so the controller can push the new gains to the radio.
 */
@Composable
internal fun GainSettings(
    lna: Int, lnaSteps: List<Int>, onLna: (Int) -> Unit,
    vga: Int, vgaSteps: List<Int>, onVga: (Int) -> Unit,
    amp: Boolean? = null, onAmp: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GainLine("Gain LNA (dB)", lna, lnaSteps, onLna)
        GainLine("Gain VGA (dB)", vga, vgaSteps, onVga)
        if (amp != null) {
            Text("Préamplificateur", style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            AntFilterChip("Ampli +14 dB", amp, onAmp)
        }
    }
}

@Composable
private fun GainLine(label: String, value: Int, steps: List<Int>, onPick: (Int) -> Unit) {
    Text(label, style = rf(13, 18, 600), color = cs.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { g -> AntFilterChip("$g", value == g, { onPick(g) }) }
    }
}
