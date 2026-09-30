package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.NfcWriteResult
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolBtn
import com.allnetworktools.ui.tools.ToolButton

@Composable
fun NfcEraseTool(c: NfcMaintController) {
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { c.disarm(context) } }
    var confirmLock by androidx.compose.runtime.remember { mutableStateOf(false) }
    PageColumn {
        HeroCard {
            val armed = c.pending
            val res = c.result
            Text(
                when {
                    armed == NfcMaintAction.Erase -> "Approchez le tag à effacer"
                    armed == NfcMaintAction.Lock -> "Approchez le tag à verrouiller"
                    res is NfcWriteResult.Success && c.action == NfcMaintAction.Erase -> "Tag effacé"
                    res is NfcWriteResult.Success && c.action == NfcMaintAction.Lock -> "Tag verrouillé"
                    res != null -> "Échec"
                    else -> "Effacer ou verrouiller un tag"
                },
                style = gs(24, 30, 500),
            )
            Text(
                when {
                    armed == NfcMaintAction.Erase -> "Posez le tag contre le dos du téléphone. Son contenu sera remplacé par un message vide."
                    armed == NfcMaintAction.Lock -> "Posez le tag contre le dos du téléphone. Le verrouillage est définitif."
                    res is NfcWriteResult.Success && c.action == NfcMaintAction.Lock -> "Il ne peut plus être modifié."
                    res is NfcWriteResult.Success -> "Il est de nouveau vierge."
                    res is NfcWriteResult.NotWritable -> res.reason
                    res is NfcWriteResult.Failed -> res.message
                    else -> "Effacer remet le tag à vide. Verrouiller le bloque en lecture seule pour toujours : à utiliser seulement une fois le contenu définitif."
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            if (armed != null) Row(Modifier.padding(top = 12.dp)) { HeroChip("En attente d'un tag", AntTheme.net.fair, blink = true) }
        }
        if (c.pending != null) {
            Box(Modifier.padding(top = 4.dp)) { StartButton("Annuler", Sym.Stop) { c.disarm(context) } }
        } else {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolBtn(
                    ToolButton("Effacer", Sym.Delete, BtnKind.Fill) { c.arm(context, NfcMaintAction.Erase) },
                )
                ToolBtn(
                    ToolButton("Verrouiller", Sym.Lock, BtnKind.OutlineOnSurface) { confirmLock = true },
                )
            }
        }
        SectionCard(color = cs.errorContainer) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Warning, size = 22.dp, filled = true, tint = cs.onErrorContainer)
                Text(
                    "Le verrouillage est irréversible : le tag ne pourra plus jamais être réécrit, par cette application ni aucune autre. " +
                        "Tous les tags ne le permettent pas.",
                    style = rf(13, 18), color = cs.onErrorContainer,
                )
            }
        }
    }
    if (confirmLock) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            icon = { Symbol(Sym.Lock, size = 24.dp, tint = cs.error) },
            title = { Text("Verrouiller définitivement ?") },
            text = { Text("Cette action ne peut pas être annulée : le tag restera en lecture seule pour toujours.") },
            confirmButton = {
                TextButton({ confirmLock = false; c.arm(context, NfcMaintAction.Lock) }) { Text("Verrouiller", color = cs.error) }
            },
            dismissButton = { TextButton({ confirmLock = false }) { Text("Annuler") } },
        )
    }
}
