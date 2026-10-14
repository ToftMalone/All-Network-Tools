package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.Whois
import com.allnetworktools.data.net.WhoisResult
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
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
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.plural
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---- Whois ----------------------------------------------------------------------------------------

class WhoisController(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var phase by mutableStateOf(Phase.Idle)
    var result by mutableStateOf<WhoisResult?>(null)
    var error by mutableStateOf<String?>(null)
    var showRaw by mutableStateOf(false)
    val recent = Recents("google.com", "wikipedia.org", "1.1.1.1")
    private var job: Job? = null

    fun start(q: String = query) {
        query = q.trim()
        if (query.isEmpty()) return
        job?.cancel()
        phase = Phase.Running
        result = null; error = null; showRaw = false
        job = scope.launch {
            try {
                val r = Whois.lookup(query)
                recent.push(query)
                result = r
                phase = if (r.text.isBlank() || r.text.contains("No match", true) || r.text.contains("NOT FOUND", true)) Phase.Empty else Phase.Results
            } catch (e: UnknownHostException) {
                error = "Serveur whois injoignable : vérifiez la connexion Internet."; phase = Phase.Error
            } catch (e: IOException) {
                error = e.message ?: "Le serveur whois n'a pas répondu."; phase = Phase.Error
            }
        }
    }
}

@Composable
fun WhoisTool(c: WhoisController) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    val r = c.result
    if (r != null && c.phase == Phase.Results) TopBarAction(Sym.IosShare) { actions.share("Whois ${r.query}", r.hops.joinToString("\n\n") { "# ${it.server}\n${it.text}" }) }
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        if (c.phase == Phase.Error) ToolError(Sym.CloudOff, "Requête impossible", c.error ?: "", "Réessayer") { c.start() }
        HostInputField(c.query, { c.query = it }, "Domaine ou adresse IP", Sym.TravelExplore, c.recent.items, onDone = start)
        if (c.phase != Phase.Running) StartButton("Interroger", Sym.Search, enabled = c.query.isNotBlank(), onClick = start)
        if (c.phase == Phase.Running) {
            SectionCard { IndeterminateBar(); Text("Interrogation de whois.iana.org puis du registre…", Modifier.padding(top = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant) }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.SearchOff, "Aucun enregistrement", "Le registre ne connaît pas « ${c.query} ». Le domaine est peut-être libre.", null)
        }
        if (r != null && c.phase == Phase.Results) {
            HeroCard {
                Text(r.query, style = gs(26, 32, 500), maxLines = 2)
                Text(
                    if (r.isIp) listOfNotNull(r.netName, r.network).joinToString(" · ").ifEmpty { "Adresse IP" }
                    else r.registrar ?: "Registrar non communiqué",
                    Modifier.padding(top = 2.dp), style = rf(14, 20),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeroChip(r.hops.last().server, acc.accent)
                    r.country?.let { HeroChip(it.uppercase(), cs.outline) }
                }
            }
            InfoList(if (r.isIp) "Réseau" else "Domaine") {
                val rows = if (r.isIp) listOf(
                    "Plage" to r.network, "Nom du réseau" to r.netName, "Organisation" to (r.registrant ?: r.description),
                    "Pays" to r.country, "AS d'origine" to r.asn, "Mis à jour" to r.updated?.let(::shortDate),
                ) else listOf(
                    "Registrar" to r.registrar, "Titulaire" to r.registrant, "Création" to r.created?.let(::shortDate),
                    "Expiration" to r.expires?.let(::shortDate), "Mise à jour" to r.updated?.let(::shortDate),
                    "Serveurs DNS" to r.nameServers.joinToString("\n").ifEmpty { null }, "Statut" to r.status.joinToString("\n").ifEmpty { null },
                    "DNSSEC" to r.dnssec,
                )
                rows.filter { it.second != null }.forEach { (k, v) -> InfoRow(k, v!!) { actions.copy(k, v) } }
                if (rows.all { it.second == null }) Text("Réponse sans champ standard : voir la réponse brute.", Modifier.padding(vertical = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
            }
            SectionCard(padding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.showRaw = !c.showRaw }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Réponse brute · ${r.hops.size} ${plural(r.hops.size, "serveur")}", Modifier.weight(1f), style = rf(15, 20, 600))
                    Symbol(if (c.showRaw) Sym.UnfoldLess else Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
                }
                AnimatedVisibility(c.showRaw) {
                    Column(Modifier.padding(top = 10.dp)) {
                        r.hops.forEach { h ->
                            Text("# ${h.server}", Modifier.padding(top = 8.dp), style = mono(11, 16, 700), color = acc.accent)
                            Text(
                                h.text.lines().filter { it.isNotBlank() && !it.startsWith("%") && !it.startsWith(">>>") && !it.startsWith("#") }.take(120).joinToString("\n"),
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(10.dp),
                                style = mono(10.5f, 15),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "2027-08-13T04:00:00Z" → "13/08/2027"; other formats are returned unchanged. */
fun shortDate(s: String): String {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(s) ?: Regex("""^(\d{4})(\d{2})(\d{2})$""").find(s) ?: return s
    val (y, mo, d) = m.destructured
    return "$d/$mo/$y"
}
