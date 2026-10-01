package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.NdefBuild
import com.allnetworktools.data.NfcWriteResult
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.WifiTagSecurity
import com.allnetworktools.data.WscToken
import com.allnetworktools.ui.components.AntFilterChip
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
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton

/** SSID, password and security fields of a Wi-Fi tag, shared by the write tool and the share tool. */
@Composable
fun WifiTagFields(
    ssid: String,
    onSsid: (String) -> Unit,
    key: String,
    onKey: (String) -> Unit,
    security: WifiTagSecurity,
    onSecurity: (WifiTagSecurity) -> Unit,
) {
    HostInputField(ssid, onSsid, "Nom du réseau (SSID)", Sym.Wifi, keyboardType = KeyboardType.Text)
    if (security.needsKey) HostInputField(key, onKey, "Mot de passe du Wi-Fi", Sym.Key, keyboardType = KeyboardType.Password)
    Column {
        Text("Sécurité", Modifier.padding(bottom = 8.dp), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WifiTagSecurity.entries.forEach { s -> AntFilterChip(s.label, s == security, { onSecurity(s) }) }
        }
    }
}

/**
 * Writes a Wi-Fi Simple Configuration token for the network the phone is on: a guest touches the tag
 * and Android offers to join, without the password being read out loud.
 */
@Composable
fun NfcWifiShareTool(c: NfcWriteController, connection: WifiConnection?) {
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { c.disarm(context) } }
    LaunchedEffect(connection?.ssid) {
        c.preset = NfcWritePreset.Wifi
        val ssid = connection?.ssid
        if (ssid != null && c.wifiSsid.isEmpty()) {
            c.wifiSsid = ssid
            c.wifiSecurity = WscToken.securityFor(connection.security)
        }
    }
    val error = NdefBuild.validate(c.request())
    PageColumn {
        HeroCard {
            val res = c.result
            Text(
                when {
                    c.armed -> "Approchez le tag à écrire"
                    res is NfcWriteResult.Success -> "Tag Wi-Fi prêt"
                    res != null -> "Échec de l'écriture"
                    else -> "Rejoindre mon Wi-Fi"
                },
                style = gs(24, 30, 500),
            )
            Text(
                when {
                    c.armed -> "Posez le tag contre le dos du téléphone. Son contenu actuel sera remplacé."
                    res is NfcWriteResult.Success -> "Un invité n'a plus qu'à toucher le tag avec son téléphone pour se voir proposer « ${c.wifiSsid} »."
                    res is NfcWriteResult.NotWritable -> res.reason
                    res is NfcWriteResult.Failed -> res.message
                    connection?.ssid != null -> "Connecté à « ${connection.ssid} ». Saisissez son mot de passe, puis écrivez le tag à poser près de la box."
                    else -> "Pas de Wi-Fi connecté : saisissez le nom et le mot de passe du réseau à partager."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            if (c.armed) Row(Modifier.padding(top = 12.dp)) { HeroChip("En attente d'un tag", AntTheme.net.fair, blink = true) }
        }
        WifiTagFields(
            c.wifiSsid, { c.wifiSsid = it }, c.wifiKey, { c.wifiKey = it }, c.wifiSecurity, { c.wifiSecurity = it },
        )
        if (error != null && !c.armed) {
            SectionCard(color = cs.errorContainer) { Text(error, style = rf(13, 18), color = cs.onErrorContainer) }
        }
        Box(Modifier.padding(top = 4.dp)) {
            if (c.armed) StartButton("Annuler", Sym.Stop) { c.disarm(context) }
            else StartButton("Écrire le tag", Sym.WifiPassword, enabled = error == null) { c.arm(context) }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Android ne donne pas aux applications le mot de passe des réseaux enregistrés : il faut le saisir. " +
                        "Le tag utilise le format standard Wi-Fi Simple Configuration, reconnu par Android sans application. " +
                        "Le mot de passe est écrit en clair sur le tag : quiconque le lit peut le récupérer, gardez-le à l'intérieur et verrouillez-le si besoin. " +
                        "Les réseaux en WPA3 seul ne peuvent pas être décrits dans ce format.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
