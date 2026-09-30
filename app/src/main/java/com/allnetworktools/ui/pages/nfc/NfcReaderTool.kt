package com.allnetworktools.ui.pages.nfc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.NfcRecordInfo
import com.allnetworktools.data.NfcRecordKind
import com.allnetworktools.data.NfcTagInfo
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.util.plural
import kotlinx.coroutines.delay

private fun recordIcon(k: NfcRecordKind) = when (k) {
    NfcRecordKind.Link -> Sym.Link
    NfcRecordKind.Text -> Sym.Description
    NfcRecordKind.Phone -> Sym.Call
    NfcRecordKind.Contact -> Sym.Contacts
    NfcRecordKind.WifiHandover -> Sym.Wifi
    NfcRecordKind.App -> Sym.Android
    NfcRecordKind.SmartPoster -> Sym.Link
    NfcRecordKind.Mime -> Sym.DataObject
    NfcRecordKind.Unknown -> Sym.Help
}

@Composable
fun NfcReaderTool(c: NfcReaderController) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        c.begin(context)
        onDispose { c.end(context) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    PageColumn {
        HeroCard {
            val tag = c.current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeBadge(Sym.Nfc, cookieShape(), 64.dp, AntTheme.accent.accent, AntTheme.accent.onAccent, 30.dp, spinMs = 20_000)
                Column(Modifier.weight(1f)) {
                    Text(if (tag == null) "Approchez un tag" else "Tag lu", style = gs(22, 28, 500))
                    Text(
                        if (tag == null) "Posez un badge, une carte ou un tag contre le dos du téléphone, à l'endroit de l'antenne NFC."
                        else "Il y a ${((now - c.lastReadAtMs) / 1000).coerceAtLeast(0)} s · ${c.readCount} ${plural(c.readCount, "lecture")} cette session",
                        Modifier.padding(top = 4.dp), style = rf(13, 18),
                    )
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip("Écoute NFC active", AntTheme.net.good, blink = true)
            }
        }
        val tag = c.current
        if (tag != null) TagCard(tag)
        if (c.history.size > 1) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Lus pendant cette session", Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                c.history.drop(1).take(10).forEach { t ->
                    Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Symbol(Sym.Nfc, size = 20.dp, tint = cs.onSurfaceVariant)
                        Text(t.techLabels.firstOrNull() ?: "Tag", Modifier.weight(1f), style = rf(13, 18), maxLines = 1)
                        Text(t.uidHex, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    "Tout reste dans l'application : rien n'est enregistré sur le téléphone ni envoyé. L'historique de cette session disparaît en quittant l'outil. " +
                        "Une carte de paiement ou de transport n'apparaît qu'en technologie détectée (ISO 14443…) : l'app n'en lit pas le contenu.",
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TagCard(tag: NfcTagInfo) {
    val actions = LocalActions.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(Sym.Nfc, size = 22.dp, filled = true, tint = AntTheme.accent.accent)
            Text(tag.techLabels.firstOrNull() ?: "Tag NFC", Modifier.weight(1f), style = rf(16, 22, 600))
        }
        Column(Modifier.padding(top = 4.dp)) {
            InfoRow("Identifiant (UID)", tag.uidHex) { actions.copy("UID", tag.uidHex) }
            if (tag.techLabels.size > 1) InfoRow("Technologies", tag.techLabels.drop(1).joinToString(" · "))
            tag.memoryBytes?.let { InfoRow("Mémoire", "$it octets") }
            InfoRow("NDEF", if (!tag.hasNdef) "Absent" else if (tag.ndefCanLock) "Modifiable, verrouillable" else if (tag.ndefWritable) "Modifiable" else "Lecture seule")
        }
        if (tag.hasNdef && tag.isEmpty) {
            Text("Tag NDEF vide.", Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
        if (!tag.hasNdef) {
            Text(
                "Pas de contenu NDEF : ce tag n'annonce ni lien, ni texte, ni carte de visite. C'est normal pour une carte de paiement, un badge de transport ou une carte d'accès.",
                Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
    }
    tag.ndefRecords.forEachIndexed { i, r -> RecordCard(i + 1, r) }
}

@Composable
private fun RecordCard(index: Int, r: NfcRecordInfo) {
    val actions = LocalActions.current
    SectionCard(onClick = { actions.copy(r.kind.label, r.title) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
                Symbol(recordIcon(r.kind), size = 20.dp, filled = true, tint = AntTheme.accent.accent)
            }
            Column(Modifier.weight(1f)) {
                Text("${r.kind.label} · enregistrement $index", style = rf(12, 16, 600), color = AntTheme.accent.accent)
                Text(r.title, Modifier.padding(top = 2.dp), style = rf(15, 20, 600), maxLines = 3)
                r.detail?.let { Text(it, Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2) }
            }
        }
    }
}
