package com.allnetworktools.ui.pages.wifi

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.Bonjour
import com.allnetworktools.data.net.BonjourService
import com.allnetworktools.data.net.UpnpDevice
import com.allnetworktools.data.net.Upnp
import com.allnetworktools.data.net.Whois
import com.allnetworktools.data.net.WhoisResult
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.OutlineChip
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.rise
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
import kotlinx.coroutines.withTimeoutOrNull

// ---- UPnP ----------------------------------------------------------------------------------------

class UpnpController(private val context: Context, private val scope: CoroutineScope) {
    var phase by mutableStateOf(Phase.Idle)
    val devices = mutableStateListOf<UpnpDevice>()
    val expanded = mutableStateMapOf<String, Boolean>()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        devices.clear()
        phase = Phase.Running
        job = scope.launch {
            Upnp.discover(context, 5000).collect { d ->
                val i = devices.indexOfFirst { it.location == d.location }
                if (i >= 0) devices[i] = d else devices += d
            }
            devices.sortWith(compareBy({ it.friendlyName == null }, { it.name.lowercase() }))
            phase = if (devices.isEmpty()) Phase.Empty else Phase.Results
        }
    }

    fun report() = devices.joinToString("\n\n") { d ->
        listOfNotNull(d.name, d.ip, d.manufacturer, d.model, d.deviceType, d.server, d.location, d.services.joinToString(", ").ifEmpty { null }).joinToString("\n")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpnpTool(c: UpnpController) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    if (c.phase == Phase.Results) TopBarAction(Sym.Refresh) { haptics.confirm(); c.start() }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LeadingIcon(Sym.Router, acc.accent, acc.onAccent, 52.dp, RoundedCornerShape(18.dp), 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (c.phase) {
                            Phase.Idle -> "Appareils UPnP"
                            Phase.Running -> "Recherche SSDP…"
                            else -> "${c.devices.size} ${plural(c.devices.size, "appareil")}"
                        },
                        style = gs(24, 30, 500),
                    )
                    Text("Box, TV, NAS, enceintes et imprimantes qui s'annoncent en SSDP (UDP 1900)", style = rf(13, 18))
                }
            }
            if (c.phase == Phase.Running) IndeterminateBar(Modifier.padding(top = 16.dp))
        }
        when (c.phase) {
            Phase.Idle, Phase.Error -> StartButton("Lancer la découverte", Sym.Search) { haptics.confirm(); c.start() }
            Phase.Empty -> ToolEmpty(
                Sym.SearchOff, "Aucun appareil UPnP",
                "Aucun appareil n'a répondu. L'UPnP est parfois désactivé sur la box, ou le réseau isole les clients (Wi-Fi invité).",
                "Relancer",
            ) { c.start() }
            else -> Unit
        }
        c.devices.forEachIndexed { i, d ->
            val open = c.expanded[d.location] == true
            val rot by animateFloatAsState(if (open) 90f else 0f, tween(250), label = "chev")
            SectionCard(Modifier.rise(i), padding = PaddingValues(0.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { c.expanded[d.location] = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LeadingIcon(d.icon, acc.container, acc.onContainer, 44.dp, RoundedCornerShape(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = rf(15, 20, 600), maxLines = 1)
                        Text(listOfNotNull(d.ip, d.manufacturer, d.typeShort).joinToString(" · "), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Symbol(Sym.ChevronRight, Modifier.graphicsLayer { rotationZ = rot }, size = 22.dp, tint = cs.onSurfaceVariant)
                }
                AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                        listOfNotNull(
                            "Modèle" to d.model, "Type" to d.deviceType, "Serveur" to d.server, "Description" to d.location,
                        ).forEach { (k, v) -> if (v != null) InfoRow(k, v) { actions.copy(k, v) } }
                        if (d.services.isNotEmpty()) {
                            Hairline()
                            Text("Services", Modifier.padding(top = 10.dp, bottom = 6.dp), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                d.services.forEach { OutlineChip(it) }
                            }
                        }
                        d.presentationUrl?.let { url ->
                            PillButton("Ouvrir l'interface web", { actions.openUrl(url) }, Modifier.padding(top = 12.dp), icon = Sym.OpenInNew, height = 40.dp, outlined = true, bg = acc.accent)
                        }
                    }
                }
            }
        }
        if (c.phase == Phase.Results) {
            PillButton("Copier la liste", { actions.copy("UPnP", c.report()); actions.toast("Liste copiée") }, Modifier.fillMaxWidth(), icon = Sym.ContentCopy, height = 44.dp, outlined = true, bg = acc.accent)
        }
    }
}

// ---- Bonjour --------------------------------------------------------------------------------------

class BonjourController(private val context: Context, private val scope: CoroutineScope) {
    var phase by mutableStateOf(Phase.Idle)
    val services = mutableStateListOf<BonjourService>()
    private var job: Job? = null

    fun start(seconds: Int = 8) {
        job?.cancel()
        services.clear()
        phase = Phase.Running
        job = scope.launch {
            withTimeoutOrNull(seconds * 1000L) {
                Bonjour.browse(context).collect { s ->
                    if (services.none { it.type == s.type && it.name == s.name }) services += s
                }
            }
            phase = if (services.isEmpty()) Phase.Empty else Phase.Results
        }
    }

    fun stop() {
        job?.cancel()
        phase = if (services.isEmpty()) Phase.Idle else Phase.Results
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BonjourTool(c: BonjourController) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    if (c.phase == Phase.Results) TopBarAction(Sym.Refresh) { haptics.confirm(); c.start() }
    val byType = c.services.groupBy { it.type }.toSortedMap(compareBy { Bonjour.label(it).lowercase() })
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LeadingIcon(Sym.Cast, acc.accent, acc.onAccent, 52.dp, RoundedCornerShape(18.dp), 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (c.phase == Phase.Idle) "Services Bonjour" else "${c.services.size} ${plural(c.services.size, "service")} · ${byType.size} ${plural(byType.size, "type")}",
                        style = gs(24, 30, 500),
                    )
                    Text("Services annoncés en mDNS / DNS-SD : Cast, AirPlay, imprimantes, HomeKit, Matter…", style = rf(13, 18))
                }
            }
            if (c.phase == Phase.Running) {
                IndeterminateBar(Modifier.padding(top = 16.dp))
                Row(Modifier.padding(top = 12.dp)) {
                    HeroChip("Écoute mDNS…", acc.accent, blink = true)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    com.allnetworktools.ui.components.TextAction("Arrêter", { c.stop() }, color = acc.onContainer, trailingIcon = Sym.Stop)
                }
            }
        }
        when (c.phase) {
            Phase.Idle, Phase.Error -> StartButton("Lancer l'écoute", Sym.Search) { haptics.confirm(); c.start() }
            Phase.Empty -> ToolEmpty(Sym.SearchOff, "Aucun service trouvé", "Rien ne s'est annoncé en mDNS sur ce réseau pendant 8 secondes.", "Relancer") { c.start() }
            else -> Unit
        }
        var index = 0
        byType.forEach { (type, list) ->
            SectionTitle("${Bonjour.label(type)} · ${list.size}")
            SectionCard(Modifier.rise(index++), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                list.sortedBy { it.name.lowercase() }.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            actions.copy(s.name, listOfNotNull(s.name, type, s.host?.let { "$it:${s.port}" }).joinToString("\n") + s.txt.entries.joinToString("") { "\n${it.key}=${it.value}" })
                            actions.toast("Détails copiés")
                        }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Symbol(Bonjour.icon(type), size = 24.dp, tint = acc.accent)
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = rf(15, 20, 600))
                            Text(
                                listOfNotNull(s.host?.let { h -> s.port?.let { "$h:$it" } ?: h } ?: "Non résolu", type).joinToString(" · "),
                                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                            )
                            val txt = s.txt.filterKeys { it in setOf("md", "fn", "model", "ty", "am", "vendor", "manufacturer", "product", "rs") }.values.filter { it.isNotBlank() }.distinct()
                            if (txt.isNotEmpty()) {
                                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    txt.take(4).forEach { OutlineChip(it.take(28)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
