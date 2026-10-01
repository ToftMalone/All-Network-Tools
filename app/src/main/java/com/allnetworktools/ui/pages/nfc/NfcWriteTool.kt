package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.NdefBuild
import com.allnetworktools.data.NfcWriteResult
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.AntFilterChip
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
import com.allnetworktools.ui.tools.ToolError

@Composable
fun NfcWriteTool(c: NfcWriteController) {
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { c.disarm(context) } }
    val req = c.request()
    val error = NdefBuild.validate(req)
    PageColumn {
        HeroCard {
            val res = c.result
            Text(
                when {
                    c.armed -> "Approchez le tag à écrire"
                    res is NfcWriteResult.Success -> "Écriture réussie"
                    res != null -> "Échec de l'écriture"
                    else -> "Préparer un tag"
                },
                style = gs(24, 30, 500),
            )
            Text(
                when {
                    c.armed -> "Posez le tag contre le dos du téléphone. Son contenu actuel sera remplacé."
                    res is NfcWriteResult.Success -> "Le tag contient maintenant ce message."
                    res is NfcWriteResult.NotWritable -> res.reason
                    res is NfcWriteResult.Failed -> res.message
                    else -> "Choisissez ce que le tag doit contenir, puis appuyez sur Écrire."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            if (c.armed) {
                Row(Modifier.padding(top = 12.dp)) { HeroChip("En attente d'un tag", AntTheme.net.fair, blink = true) }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NfcWritePreset.entries.forEach { p -> AntFilterChip(p.label, c.preset == p, { c.preset = p }) }
        }
        when (c.preset) {
            NfcWritePreset.Link -> HostInputField(c.url, { c.url = it }, "Lien (https://…)", Sym.Link, keyboardType = KeyboardType.Uri)
            NfcWritePreset.Text -> HostInputField(c.text, { c.text = it }, "Texte", Sym.Description)
            NfcWritePreset.Contact -> {
                HostInputField(c.contactName, { c.contactName = it }, "Nom", Sym.Contacts)
                HostInputField(c.contactPhone, { c.contactPhone = it }, "Téléphone (facultatif)", Sym.Call, keyboardType = KeyboardType.Phone)
                HostInputField(c.contactEmail, { c.contactEmail = it }, "E-mail (facultatif)", Sym.Description, keyboardType = KeyboardType.Email)
            }
            NfcWritePreset.Phone -> HostInputField(c.phone, { c.phone = it }, "Numéro de téléphone", Sym.Call, keyboardType = KeyboardType.Phone)
            NfcWritePreset.App -> HostInputField(c.packageName, { c.packageName = it }, "Package (ex. com.exemple.app)", Sym.Android, keyboardType = KeyboardType.Ascii)
            NfcWritePreset.Wifi -> WifiTagFields(
                c.wifiSsid, { c.wifiSsid = it }, c.wifiKey, { c.wifiKey = it }, c.wifiSecurity, { c.wifiSecurity = it },
            )
            NfcWritePreset.Sms -> {
                HostInputField(c.smsNumber, { c.smsNumber = it }, "Destinataire", Sym.Call, keyboardType = KeyboardType.Phone)
                HostInputField(c.smsBody, { c.smsBody = it }, "Message (facultatif)", Sym.Sms, keyboardType = KeyboardType.Text)
            }
            NfcWritePreset.Email -> {
                HostInputField(c.emailTo, { c.emailTo = it }, "Destinataire", Sym.Mail, keyboardType = KeyboardType.Email)
                HostInputField(c.emailSubject, { c.emailSubject = it }, "Objet (facultatif)", Sym.Description, keyboardType = KeyboardType.Text)
                HostInputField(c.emailBody, { c.emailBody = it }, "Message (facultatif)", Sym.Description, keyboardType = KeyboardType.Text)
            }
            NfcWritePreset.Geo -> {
                HostInputField(c.lat, { c.lat = it }, "Latitude (ex. 48.8584)", Sym.LocationOn, keyboardType = KeyboardType.Decimal)
                HostInputField(c.lon, { c.lon = it }, "Longitude (ex. 2.2945)", Sym.LocationOn, keyboardType = KeyboardType.Decimal)
            }
        }
        if (error != null && !c.armed) {
            SectionCard(color = cs.errorContainer) { Text(error, style = rf(13, 18), color = cs.onErrorContainer) }
        }
        Box(Modifier.padding(top = 4.dp)) {
            if (c.armed) {
                StartButton("Annuler", Sym.Stop) { c.disarm(context) }
            } else {
                StartButton("Écrire", Sym.Edit, enabled = error == null) { c.arm(context) }
            }
        }
        SectionCard {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Écrit un enregistrement NDEF standard, lisible par n'importe quel téléphone. Le contenu actuel du tag est remplacé. " +
                        "Rien n'est envoyé hors de l'application.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
