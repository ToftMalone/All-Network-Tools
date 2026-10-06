package com.allnetworktools.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.BuildConfig
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.SignalDisplay
import com.allnetworktools.data.ThemeMode
import com.allnetworktools.data.Units
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf

private data class PermRow(val group: PermGroup, val icon: String, val title: String, val subtitle: String)

private val PermRows = listOf(
    PermRow(PermGroup.Location, Sym.LocationOn, "Position précise", "Wi-Fi (SSID, scans), cellules et GNSS"),
    PermRow(PermGroup.Nearby, Sym.BluetoothSearching, "Appareils à proximité", "Scan et connexion Bluetooth"),
    PermRow(PermGroup.Phone, Sym.SimCard, "Téléphone", "Opérateur, cellules, double SIM"),
    PermRow(PermGroup.UsageAccess, Sym.DataUsage, "Accès aux données d'utilisation", "Données mobiles consommées par application"),
)

private val Licenses = listOf(
    "AndroidX, Jetpack Compose, Material 3" to "Apache 2.0",
    "Material Components for Android" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines" to "Apache 2.0",
    "Roboto Flex, Google Sans Flex" to "SIL Open Font License 1.1",
    "Material Symbols" to "Apache 2.0",
)

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = AntTheme.settings
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    var dialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    TopBarAction(Sym.Help) { dialog = "help" }
    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Apparence") {
                Item(0, 1, Sym.Contrast, "Thème", "Suit le réglage Android par défaut") {
                    SegmentedRow(
                        listOf(ThemeMode.System to "Système", ThemeMode.Light to "Clair", ThemeMode.Dark to "Sombre"), s.theme,
                        { vm.updateSettings { setTheme(it) } }, Modifier.fillMaxWidth(),
                        selectedColor = cs.primary, onSelectedColor = cs.onPrimary,
                        icons = mapOf(ThemeMode.System to Sym.BrightnessAuto, ThemeMode.Light to Sym.LightMode, ThemeMode.Dark to Sym.DarkMode),
                    )
                }
            }
            Section("Mesures") {
                Item(0, 4, Sym.Straighten, "Unités", "Distances, vitesses et altitudes") {
                    SegmentedRow(listOf(Units.Metric to "Métrique", Units.Imperial to "Impérial"), s.units, { vm.updateSettings { setUnits(it) } }, Modifier.fillMaxWidth(), selectedColor = cs.primary, onSelectedColor = cs.onPrimary)
                }
                Item(1, 4, Sym.CellBars3, "Affichage du signal", "Valeur brute ou pourcentage") {
                    SegmentedRow(listOf(SignalDisplay.Dbm to "dBm", SignalDisplay.Percent to "%"), s.signal, { vm.updateSettings { setSignal(it) } }, Modifier.fillMaxWidth(), selectedColor = cs.primary, onSelectedColor = cs.onPrimary)
                }
                Item(2, 4, Sym.Vibration, "Retour haptique", "Vibrations sur les actions importantes", trailing = { AntSwitch(s.haptics) }, onClick = { vm.updateSettings { setHaptics(!s.haptics) } })
                Item(3, 4, Sym.ScreenLock, "Écran toujours allumé", "Pendant une mesure ou un enregistrement", trailing = { AntSwitch(s.keepAwake) }, onClick = { vm.updateSettings { setKeepAwake(!s.keepAwake) } })
            }
            Section("Autorisations") {
                PermRows.forEachIndexed { i, p ->
                    val granted = p.group in perms
                    Item(
                        i, PermRows.size, p.icon, p.title, p.subtitle,
                        trailing = { PermPill(granted) },
                        onClick = {
                            if (granted) {
                                actions.toast("Géré dans Paramètres Android › Applications")
                                actions.openAppSettings()
                            } else {
                                actions.request(p.group)
                            }
                        },
                    )
                }
            }
            Section("Mises à jour") {
                val us by vm.updater.state.collectAsStateWithLifecycle()
                Item(
                    0, 3, Sym.Update, "Rechercher au lancement", "Vérifie GitHub et propose la nouvelle version avec ses nouveautés",
                    trailing = { AntSwitch(s.autoUpdate) }, onClick = { vm.updateSettings { setAutoUpdate(!s.autoUpdate) } },
                )
                Item(
                    1, 3, Sym.Refresh, "Rechercher maintenant",
                    when (val u = us) {
                        is com.allnetworktools.update.UpdateState.Checking -> "Vérification…"
                        is com.allnetworktools.update.UpdateState.UpToDate -> "Vous avez la dernière version (${BuildConfig.VERSION_NAME})"
                        is com.allnetworktools.update.UpdateState.Available -> "Version ${u.info.version} disponible"
                        is com.allnetworktools.update.UpdateState.Downloading -> "Téléchargement de la version ${u.info.version}…"
                        is com.allnetworktools.update.UpdateState.Failed -> u.message
                        else -> "Version installée : ${BuildConfig.VERSION_NAME}"
                    },
                    trailing = { LinkIcon(Sym.ChevronRight) },
                    onClick = { scope.launch { vm.updater.check(autoInstall = false, force = true) } },
                )
                Item(
                    2, 3, Sym.NewspaperNotes, "Journal des nouveautés", "Les notes de chaque version, publiées sur GitHub",
                    trailing = { LinkIcon(Sym.ChevronRight) },
                    onClick = { vm.navigate { it.copy(network = null, page = com.allnetworktools.Page.Changelog) } },
                )
            }
            Section("À propos") {
                Item(0, 3, Sym.Info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Item(1, 3, Sym.Description, "Licences open source", "Bibliothèques utilisées", trailing = { LinkIcon(Sym.ChevronRight) }, onClick = { dialog = "licenses" })
                Item(2, 3, Sym.Shield, "Confidentialité", "Rien n'est conservé ni envoyé", trailing = { LinkIcon(Sym.OpenInNew) }, onClick = { dialog = "privacy" })
            }
            Text(
                "All Radio Tools ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Aucune mesure n'est conservée.",
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp), style = rf(12, 18), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
    when (dialog) {
        "licenses" -> InfoDialog("Licences open source", Licenses.joinToString("\n\n") { (lib, lic) -> "$lib\n$lic" }) { dialog = null }
        "privacy" -> InfoDialog(
            "Confidentialité",
            "Les mesures (signaux, positions, appareils détectés) sont traitées en mémoire, sur cet appareil, le temps de la session : " +
                "rien n'est enregistré ni exportable, et tout disparaît à la fermeture de l'application. " +
                "L'application n'envoie aucune donnée à un serveur et ne contient aucun traceur. Seuls vos réglages sont mémorisés.",
        ) { dialog = null }
        "help" -> InfoDialog(
            "Aide",
            "Touchez une carte de l'accueil pour la sélectionner, puis touchez-la à nouveau ou utilisez le dock pour ouvrir le dashboard, " +
                "les outils ou l'outil phare du réseau. Le bouton de gauche du dock change de réseau.",
        ) { dialog = null }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp), style = rf(14, 20, 600), color = cs.primary)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
private fun Item(
    index: Int,
    count: Int,
    icon: String,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().clip(groupShape(index, count, 24.dp, 6.dp)).background(cs.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Symbol(icon, size = 24.dp, tint = cs.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = rf(16, 22, 500), color = if (danger) cs.error else cs.onSurface)
                Text(subtitle, style = rf(13, 18), color = cs.onSurfaceVariant)
            }
            trailing?.invoke()
        }
        if (below != null) Box(Modifier.padding(start = 40.dp, top = 12.dp)) { below() }
    }
}

@Composable
private fun AntSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        thumbContent = if (checked) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
        colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
    )
}

@Composable
private fun LinkIcon(icon: String) = Symbol(icon, size = 22.dp, tint = cs.onSurfaceVariant)

@Composable
private fun PermPill(granted: Boolean) {
    val good = AntTheme.net.good
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (granted) Color.Transparent else cs.primary).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (granted) Symbol(Sym.CheckCircle, size = 16.dp, filled = true, tint = good)
        Text(if (granted) "Accordée" else "Autoriser", style = rf(12, 16, 700), color = if (granted) good else cs.onPrimary)
        if (!granted) Symbol(Sym.ArrowForward, size = 16.dp, tint = cs.onPrimary)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(v) }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.RadioButton(selected = v == selected, onClick = null)
                        Text(label, style = rf(16, 22))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = rf(14, 20)) },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}
