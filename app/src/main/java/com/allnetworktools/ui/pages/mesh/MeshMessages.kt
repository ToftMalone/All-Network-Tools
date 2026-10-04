package com.allnetworktools.ui.pages.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.mesh.ChatMessage
import com.allnetworktools.data.mesh.MeshConn
import com.allnetworktools.data.mesh.MeshNode
import com.allnetworktools.data.mesh.MeshProto
import com.allnetworktools.data.mesh.MsgStatus
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayHm = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE)

private fun stamp(ms: Long): String = if (System.currentTimeMillis() - ms < 20 * 3_600_000L) hm.format(Date(ms)) else dayHm.format(Date(ms))

/** A conversation of the Messages page: a channel or a direct exchange with one node. */
internal data class Conversation(val key: String, val title: String, val node: MeshNode?, val channel: Int?, val last: ChatMessage?, val unread: Int)

internal fun conversations(vm: MainViewModel): List<Conversation> {
    val mesh = vm.mesh
    val nodes = mesh.nodes.value
    val preset = mesh.config.value.lora?.int(2) ?: 0
    val msgs = mesh.messages.value
    val lastByKey = msgs.groupBy { it.key }.mapValues { (_, l) -> l.maxByOrNull { it.timeMs } }
    val chans = mesh.channels.value.filter { it.role != 0 }.map { c ->
        Conversation("c${c.index}", channelName(c, preset), null, c.index, lastByKey["c${c.index}"], mesh.unread("c${c.index}"))
    }
    val known = chans.map { it.key }.toSet()
    val orphanChannels = lastByKey.keys.filter { it.startsWith("c") && it !in known }.map { k ->
        Conversation(k, "Canal ${k.drop(1)}", null, k.drop(1).toIntOrNull(), lastByKey[k], mesh.unread(k))
    }
    val dms = lastByKey.keys.filter { it.startsWith("d") }.map { k ->
        val num = k.drop(1).toLong()
        val n = nodes[num] ?: MeshNode(num)
        Conversation(k, n.longName, n, null, lastByKey[k], mesh.unread(k))
    }.sortedByDescending { it.last?.timeMs ?: 0 }
    return chans + orphanChannels + dms
}

/** Messages: the channels and direct conversations, like the official app's first tab. */
@Composable
fun MeshMessagesPage(vm: MainViewModel) {
    val mesh = vm.mesh
    val conn by mesh.conn.collectAsStateWithLifecycle()
    mesh.messages.collectAsStateWithLifecycle().value
    mesh.channels.collectAsStateWithLifecycle().value
    val nodes by mesh.nodes.collectAsStateWithLifecycle()
    mesh.lastRead.collectAsStateWithLifecycle().value
    val list = conversations(vm)
    val me = mesh.myNum.collectAsStateWithLifecycle().value
    PageColumn {
        if (conn !is MeshConn.Connected) NotConnectedCard(vm, conn)
        if (list.isEmpty()) {
            SectionCard { Text("Aucune conversation pour l'instant. Les canaux de votre nœud apparaissent ici dès qu'il est connecté.", style = rf(13, 18), color = cs.onSurfaceVariant) }
            return@PageColumn
        }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            list.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                ConversationRow(c, me, nodes) { vm.navigate { it.copy(page = Page.ToolPage(Tool.MeshChat, c.key)) } }
            }
        }
        Text(
            "Touchez un nœud dans la page Nœuds pour lui écrire en privé.",
            Modifier.padding(horizontal = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConversationRow(c: Conversation, me: Long?, nodes: Map<Long, MeshNode>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (c.node != null) NodeBadge(c.node) else ChannelBadge(c.channel ?: 0)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                c.last?.let { Text(stamp(it.timeMs), style = rf(12, 16), color = cs.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val l = c.last
                val preview = when {
                    l == null -> if (c.channel != null) "Canal ${c.channel} · aucun message" else "Aucun message"
                    l.mine -> "Vous : ${l.text}"
                    l.from == me -> l.text
                    c.channel != null -> "${nodes[l.from]?.shortName ?: MeshProto.nodeId(l.from).takeLast(4)} : ${l.text}"
                    else -> l.text
                }
                Text(preview, Modifier.weight(1f), style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.unread > 0) {
                    Box(Modifier.padding(start = 8.dp).heightIn(min = 20.dp).widthIn(min = 20.dp).clip(CircleShape).background(AntTheme.accent.accent).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text("${c.unread}", style = rf(11, 16, 700), color = AntTheme.accent.onAccent)
                    }
                }
            }
        }
    }
}

/** One conversation: bubbles, delivery status, and the field to write, as in the official app. */
@Composable
fun MeshChatPage(vm: MainViewModel, key: String?) {
    val mesh = vm.mesh
    val k = key ?: "c0"
    val conn by mesh.conn.collectAsStateWithLifecycle()
    val all by mesh.messages.collectAsStateWithLifecycle()
    val nodes by mesh.nodes.collectAsStateWithLifecycle()
    val channels by mesh.channels.collectAsStateWithLifecycle()
    val config by mesh.config.collectAsStateWithLifecycle()
    val msgs = remember(all, k) { all.filter { it.key == k }.sortedBy { it.timeMs } }
    LaunchedEffect(k, msgs.size) { mesh.markRead(k) }
    val dmNode = if (k.startsWith("d")) k.drop(1).toLongOrNull()?.let { nodes[it] ?: MeshNode(it) } else null
    val channel = if (k.startsWith("c")) k.drop(1).toIntOrNull() else null
    val title = dmNode?.longName ?: channels.firstOrNull { it.index == channel }?.let { channelName(it, config.lora?.int(2) ?: 0) } ?: "Canal ${channel ?: 0}"
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) }
    val nav = WindowInsets.navigationBars.asPaddingValues()
    Column(Modifier.fillMaxSize().padding(bottom = 100.dp + nav.calculateBottomPadding())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .then(if (dmNode != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable { vm.navigate { it.copy(page = Page.ToolPage(Tool.MeshNode, dmNode.num.toString())) } } else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (dmNode != null) NodeBadge(dmNode, 36.dp) else ChannelBadge(channel ?: 0, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = rf(16, 22, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (dmNode != null) "Message direct · ${dmNode.id}" else "Canal ${channel ?: 0} · tout le maillage", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (msgs.isEmpty()) item {
                Text("Aucun message. Écrivez le premier !", Modifier.fillMaxWidth().padding(24.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
            items(msgs, key = { "${it.from}-${it.id}" }) { m -> Bubble(vm, m, nodes[m.from], showSender = dmNode == null) }
        }
        if (conn !is MeshConn.Connected) Box(Modifier.padding(horizontal = 16.dp)) { NotConnectedCard(vm, conn) }
        else Composer(enabled = true) { text -> mesh.sendText(k, text) }
    }
}

@Composable
private fun Bubble(vm: MainViewModel, m: ChatMessage, sender: MeshNode?, showSender: Boolean) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!m.mine && showSender) {
            NodeBadge(sender ?: MeshNode(m.from), 32.dp)
            Box(Modifier.size(8.dp))
        }
        Column(
            Modifier.widthIn(max = 290.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (m.mine) 18.dp else 4.dp, bottomEnd = if (m.mine) 4.dp else 18.dp))
                .background(if (m.mine) acc.accent else cs.surfaceContainerHigh)
                .then(if (m.mine && m.status == MsgStatus.Failed) Modifier.clickable { vm.mesh.retry(m) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val fg = if (m.mine) acc.onAccent else cs.onSurface
            if (!m.mine && showSender) Text(sender?.longName ?: MeshProto.nodeId(m.from), style = rf(12, 16, 600), color = nodeColor(m.from))
            Text(m.text, style = rf(15, 21), color = fg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.End)) {
                val meta = buildList {
                    add(stamp(m.timeMs))
                    if (!m.mine) {
                        m.hops?.let { add(if (it == 0) "direct" else "$it ${if (it > 1) "sauts" else "saut"}") }
                        m.snr?.let { add("SNR %.1f".format(Locale.FRANCE, it)) }
                        if (m.viaMqtt) add("MQTT")
                    }
                }.joinToString(" · ")
                Text(meta, style = rf(11, 14), color = fg.copy(alpha = 0.75f))
                if (m.mine) {
                    val (icon, label) = when (m.status) {
                        MsgStatus.Queued -> Sym.Schedule to "en attente"
                        MsgStatus.Sent -> Sym.Check to "envoyé"
                        MsgStatus.Relayed -> Sym.CloudDone to "relayé"
                        MsgStatus.Delivered -> Sym.DoneAll to "reçu"
                        MsgStatus.Failed -> Sym.Error to "échec"
                    }
                    Symbol(icon, size = 14.dp, tint = fg.copy(alpha = 0.85f))
                    if (m.status == MsgStatus.Failed) Text("$label · ${m.error ?: ""} · toucher pour réessayer", style = rf(11, 14), color = fg)
                }
            }
        }
    }
}

/** The text field: Meshtastic payloads are limited to about 200 bytes of text. */
@Composable
private fun Composer(enabled: Boolean, onSend: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val bytes = text.toByteArray(Charsets.UTF_8).size
    val acc = AntTheme.accent
    val canSend = enabled && text.isNotBlank() && bytes <= MAX_TEXT_BYTES
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (text.isEmpty()) Text("Message", style = rf(15, 20), color = cs.onSurfaceVariant)
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth(),
                textStyle = rf(15, 20).copy(color = cs.onSurface), cursorBrush = SolidColor(acc.accent), maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend && onSend(text)) text = "" }),
            )
            if (bytes > MAX_TEXT_BYTES * 3 / 4) Text("$bytes/$MAX_TEXT_BYTES", Modifier.align(Alignment.BottomEnd), style = rf(10, 12), color = if (bytes > MAX_TEXT_BYTES) cs.error else cs.onSurfaceVariant)
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (canSend) acc.accent else cs.surfaceContainerHighest)
                .clickable(enabled = canSend) { if (onSend(text)) text = "" },
            contentAlignment = Alignment.Center,
        ) { Symbol(Sym.Send, size = 22.dp, filled = true, tint = if (canSend) acc.onAccent else cs.onSurfaceVariant) }
    }
}

private const val MAX_TEXT_BYTES = 200
