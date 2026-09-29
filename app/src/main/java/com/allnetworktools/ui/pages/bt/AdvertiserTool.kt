package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.data.Ad
import com.allnetworktools.data.AdvBuilder
import com.allnetworktools.data.AdvConfig
import com.allnetworktools.data.AdvInterval
import com.allnetworktools.data.AdvPower
import com.allnetworktools.data.AdvPreset
import com.allnetworktools.data.AdvState
import com.allnetworktools.data.BleAdvertiser
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
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
import com.allnetworktools.ui.tools.ParamCard
import com.allnetworktools.ui.tools.ParamRow
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import kotlinx.coroutines.delay

/** The form survives leaving the tool; the broadcast itself stops with it. */
class AdvertiserController {
    var config by mutableStateOf(AdvConfig())
}

@Composable
fun AdvertiserTool(c: AdvertiserController, adv: BleAdvertiser, canAdvertise: Boolean, onGrant: () -> Unit) {
    val state by adv.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { adv.stop() } }
    val cfg = c.config
    fun set(block: AdvConfig.() -> AdvConfig) {
        c.config = cfg.block()
        if (state is AdvState.On) adv.stop()
    }
    val built = AdvBuilder.structures(cfg, adv.deviceName())
    val size = built.getOrNull()?.let { AdvBuilder.size(it, cfg.connectable) }
    PageColumn {
        if (!canAdvertise) {
            ToolError(Sym.Podcasts, "Autorisation requise", "Android demande l'autorisation « Appareils à proximité » pour émettre en Bluetooth.", "Autoriser", onGrant)
            return@PageColumn
        }
        if (!adv.supported) {
            ToolError(Sym.BluetoothDisabled, "Émission indisponible", "Ce téléphone ne permet pas d'émettre des annonces BLE, ou le Bluetooth est éteint.", "Réessayer") { }
            return@PageColumn
        }
        HeroCard {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state) { while (state is AdvState.On) { now = System.currentTimeMillis(); delay(1000) } }
            val s = state
            Text(
                when (s) {
                    is AdvState.On -> "Émission en cours"
                    AdvState.Starting -> "Démarrage…"
                    else -> "Émettre une trame de test"
                },
                style = gs(24, 30, 500),
            )
            Text(
                when (s) {
                    is AdvState.On -> "${cfg.preset.label} · depuis ${(now - s.startedAtMs) / 1000} s · puissance réelle ${s.txPowerDbm} dBm"
                    is AdvState.Failed -> s.message
                    else -> "Votre téléphone diffuse l'annonce choisie. Vérifiez-la avec un autre appareil : un téléphone ne capte pas ses propres annonces."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    is AdvState.On -> HeroChip("À l'antenne", AntTheme.net.good, blink = true)
                    is AdvState.Failed -> HeroChip("Échec", cs.error)
                    else -> HeroChip("Arrêté", cs.outline)
                }
                HeroChip("${cfg.interval.label} · ${cfg.power.label.lowercase()}", AntTheme.accent.accent)
            }
            Box(Modifier.padding(top = 16.dp)) {
                if (s is AdvState.On || s == AdvState.Starting) {
                    StartButton("Arrêter", Sym.Stop) { adv.stop() }
                } else {
                    StartButton("Émettre", Sym.Podcasts, enabled = built.isSuccess && (size ?: 99) <= 31) { adv.start(cfg) }
                }
            }
        }
        SegmentedRow(AdvPreset.entries.map { it to it.label }, cfg.preset, { set { copy(preset = it) } }, Modifier.fillMaxWidth(), height = 36.dp)
        when (cfg.preset) {
            AdvPreset.IBeacon -> {
                HostInputField(cfg.beaconUuid, { v -> set { copy(beaconUuid = v) } }, "UUID de proximité", Sym.Tag, keyboardType = KeyboardType.Ascii)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { HostInputField(cfg.major.toString(), { v -> set { copy(major = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } }, "Major", Sym.Tag, keyboardType = KeyboardType.Number) }
                    Box(Modifier.weight(1f)) { HostInputField(cfg.minor.toString(), { v -> set { copy(minor = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } }, "Minor", Sym.Tag, keyboardType = KeyboardType.Number) }
                }
            }
            AdvPreset.Eddystone -> HostInputField(cfg.url, { v -> set { copy(url = v) } }, "URL diffusée", Sym.Link)
            AdvPreset.Manufacturer -> {
                HostInputField("%04X".format(cfg.companyId), { v -> set { copy(companyId = v.filter { it.isLetterOrDigit() }.take(4).toIntOrNull(16) ?: 0) } }, "Identifiant fabricant (hex, FFFF = test)", Sym.Badge, keyboardType = KeyboardType.Ascii)
                HostInputField(cfg.payloadHex, { v -> set { copy(payloadHex = v) } }, "Données (hexadécimal)", Sym.DataObject, keyboardType = KeyboardType.Ascii)
            }
            AdvPreset.Service -> {
                HostInputField(cfg.serviceUuid, { v -> set { copy(serviceUuid = v) } }, "UUID du service (16 bits ou complet)", Sym.AccountTree, keyboardType = KeyboardType.Ascii)
                HostInputField(cfg.serviceDataHex, { v -> set { copy(serviceDataHex = v) } }, "Données de service (hex, facultatif)", Sym.DataObject, keyboardType = KeyboardType.Ascii)
            }
        }
        ParamCard {
            ParamRow("Puissance d'émission", first = true) {}
            SegmentedRow(AdvPower.entries.map { it to it.label }, cfg.power, { set { copy(power = it) } }, Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 36.dp)
            ParamRow("Intervalle") {}
            SegmentedRow(AdvInterval.entries.map { it to it.label }, cfg.interval, { set { copy(interval = it) } }, Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 36.dp)
            ParamRow("Connectable", "Les autres appareils peuvent tenter de s'y connecter") { Switch(cfg.connectable, { v -> set { copy(connectable = v) } }) }
            ParamRow("Inclure le nom du téléphone", adv.deviceName()?.let { "« $it »" }) { Switch(cfg.includeName, { v -> set { copy(includeName = v) } }) }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trame", Modifier.weight(1f), style = rf(16, 22, 600))
                size?.let { Text("$it / 31 octets", style = rf(13, 18, 600, tnum = true), color = if (it > 31) cs.error else cs.onSurfaceVariant) }
            }
            size?.let { LevelBar(it / 31f, if (it > 31) cs.error else AntTheme.accent.accent, Modifier.padding(top = 8.dp), height = 6.dp) }
            built.fold(
                onSuccess = { list ->
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cfg.connectable) AdLine("Flags", "02 01 06")
                        list.forEach { s -> AdLine(Ad.typeName(s.type), s.encode().joinToString(" ") { "%02X".format(it) }) }
                    }
                },
                onFailure = { e -> Text(e.message ?: "Configuration invalide", Modifier.padding(top = 10.dp), style = rf(13, 18), color = cs.error) },
            )
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Annonce « legacy » BLE 4 (31 octets), lisible par tous les scanners. Android remplace l'adresse Bluetooth par une adresse aléatoire à chaque émission. " +
                        "L'émission s'arrête quand vous quittez l'outil.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdLine(type: String, hex: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(type, style = rf(12, 16, 600), color = AntTheme.accent.accent)
        Text(hex, style = mono(12, 18))
    }
}
