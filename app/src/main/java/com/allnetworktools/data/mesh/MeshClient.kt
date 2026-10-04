package com.allnetworktools.data.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.File
import java.util.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** How the phone reaches its node. [address] is a Bluetooth MAC address or a host name. */
data class MeshLink(val kind: Kind, val address: String, val name: String) {
    enum class Kind { Bluetooth, Tcp }
}

sealed interface MeshConn {
    data object Disconnected : MeshConn
    data class Connecting(val link: MeshLink, val step: String) : MeshConn
    data class Connected(val link: MeshLink) : MeshConn
    data class Failed(val link: MeshLink?, val message: String) : MeshConn
}

/** A node of the mesh as our node knows it. */
data class MeshNode(
    val num: Long,
    val user: MeshProto.User? = null,
    val position: MeshProto.Position? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val lastHeardS: Long = 0,
    val metrics: MeshProto.Metrics? = null,
    val environment: MeshProto.Environment? = null,
    val hopsAway: Int? = null,
    val viaMqtt: Boolean = false,
    val isFavorite: Boolean = false,
    val isIgnored: Boolean = false,
    val channel: Int = 0,
) {
    val id: String get() = MeshProto.nodeId(num)
    val longName: String get() = user?.longName?.takeIf { it.isNotBlank() } ?: "Meshtastic ${id.takeLast(4)}"
    val shortName: String get() = user?.shortName?.takeIf { it.isNotBlank() } ?: id.takeLast(4)
}

enum class MsgStatus { Queued, Sent, Relayed, Delivered, Failed }

data class ChatMessage(
    val id: Long,
    /** "c<index>" for a channel, "d<node number>" for a direct conversation. */
    val key: String,
    val from: Long,
    val to: Long,
    val channel: Int,
    val text: String,
    val timeMs: Long,
    val mine: Boolean,
    val status: MsgStatus = MsgStatus.Delivered,
    val error: String? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hops: Int? = null,
    val viaMqtt: Boolean = false,
)

/** The node's configuration sections as received (Config.device = 1 … security = 8). */
data class RadioConfig(val sections: Map<Int, ProtoMsg> = emptyMap()) {
    val device get() = sections[1]
    val position get() = sections[2]
    val power get() = sections[3]
    val network get() = sections[4]
    val display get() = sections[5]
    val lora get() = sections[6]
    val bluetooth get() = sections[7]
    val security get() = sections[8]
}

/** A Meshtastic node seen while scanning for Bluetooth devices. */
data class FoundNode(val address: String, val name: String, val rssi: Int?, val bonded: Boolean)

/**
 * The app side of a Meshtastic node, like the official app's service: connects over Bluetooth or TCP, asks for the
 * configuration and node database, keeps the conversations, and sends messages and settings through the node.
 */
@SuppressLint("MissingPermission")
open class MeshClient(private val context: Context) {
    // A Bluetooth call refused by Android (permission withdrawn, adapter off) must never take the app down.
    private val guard = CoroutineExceptionHandler { _, e ->
        _conn.value = MeshConn.Failed(link, if (e is SecurityException) "Autorisation Bluetooth manquante" else e.message ?: "Erreur de connexion")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + guard)
    private val prefs = context.getSharedPreferences("mesh_client", Context.MODE_PRIVATE)
    private val store = File(context.filesDir, "meshtastic/messages.json")
    private val rnd = Random()

    private val _conn = MutableStateFlow<MeshConn>(MeshConn.Disconnected)
    open val conn: StateFlow<MeshConn> = _conn.asStateFlow()
    private val _myNum = MutableStateFlow<Long?>(null)
    val myNum: StateFlow<Long?> = _myNum.asStateFlow()
    private val _nodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Long, MeshNode>> = _nodes.asStateFlow()
    private val _channels = MutableStateFlow<List<MeshProto.ChannelRecord>>(emptyList())
    val channels: StateFlow<List<MeshProto.ChannelRecord>> = _channels.asStateFlow()
    private val _config = MutableStateFlow(RadioConfig())
    val config: StateFlow<RadioConfig> = _config.asStateFlow()
    private val _metadata = MutableStateFlow<MeshProto.FromRadio.Metadata?>(null)
    val metadata: StateFlow<MeshProto.FromRadio.Metadata?> = _metadata.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastRead: StateFlow<Map<String, Long>> = _lastRead.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** The last message from the node meant for the user (refusals, errors). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var transport: MeshTransport? = null
    private var link: MeshLink? = null
    private var session: Job? = null
    private var heartbeat: Job? = null
    private var configNonce = 0
    private var userClosed = false
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    val lastLink: MeshLink?
        get() {
            val kind = prefs.getString("kind", null) ?: return null
            return MeshLink(MeshLink.Kind.valueOf(kind), prefs.getString("address", "")!!, prefs.getString("name", "")!!)
        }

    init {
        load()
        scope.launch { for (b in inbox) runCatching { handle(b) } }
    }

    // ---- connection -----------------------------------------------------------------------------------------------

    fun connect(target: MeshLink) {
        disconnectQuietly()
        userClosed = false
        link = target
        session = scope.launch { runSession(target, attempt = 0) }
    }

    /** Reconnects to the last node, if there is one and nothing is connected. */
    fun resume() {
        val l = lastLink ?: return
        if (_conn.value is MeshConn.Disconnected || _conn.value is MeshConn.Failed) connect(l)
    }

    private suspend fun runSession(target: MeshLink, attempt: Int) {
        _conn.value = MeshConn.Connecting(target, "Préparation…")
        val t: MeshTransport = when (target.kind) {
            MeshLink.Kind.Bluetooth -> {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: run { _conn.value = MeshConn.Failed(target, "Bluetooth indisponible"); return }
                if (!adapter.isEnabled) { _conn.value = MeshConn.Failed(target, "Le Bluetooth est désactivé"); return }
                BleMeshTransport(context, adapter.getRemoteDevice(target.address), scope, { inbox.trySend(it) }, ::lost)
            }
            MeshLink.Kind.Tcp -> TcpMeshTransport(target.address, scope = scope, onFromRadio = { inbox.trySend(it) }, onLost = ::lost)
        }
        transport = t
        val err = t.open { step -> _conn.value = MeshConn.Connecting(target, step) }
        if (err != null) {
            t.close(); transport = null
            _conn.value = MeshConn.Failed(target, err)
            return
        }
        _conn.value = MeshConn.Connecting(target, "Lecture de la configuration du nœud…")
        configNonce = rnd.nextInt(1_000_000) + 100_000 // not the two-stage nonces: everything at once
        if (!t.send(MeshProto.wantConfig(configNonce))) {
            t.close(); transport = null
            _conn.value = MeshConn.Failed(target, "Le nœud n'accepte pas la demande de configuration")
            return
        }
        val ok = withTimeoutOrNull(60_000) { while (_conn.value !is MeshConn.Connected) delay(100); true }
        if (ok == null) {
            t.close(); transport = null
            _conn.value = MeshConn.Failed(target, "Le nœud n'a pas envoyé sa configuration")
            return
        }
        prefs.edit().putString("kind", target.kind.name).putString("address", target.address).putString("name", target.name).apply()
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(60_000)
                if (transport?.send(MeshProto.heartbeat(rnd.nextInt())) != true) break
            }
        }
    }

    private fun lost(reason: String) {
        if (userClosed) return
        val l = link
        transport?.close(); transport = null
        heartbeat?.cancel()
        _conn.value = MeshConn.Failed(l, reason)
        // Like the official app: try again a few times when the link drops by itself.
        if (l != null) session = scope.launch {
            for (wait in longArrayOf(4_000, 10_000, 30_000)) {
                delay(wait)
                if (userClosed || _conn.value is MeshConn.Connected || _conn.value is MeshConn.Connecting) return@launch
                runSession(l, 1)
                if (_conn.value is MeshConn.Connected) return@launch
            }
        }
    }

    private fun disconnectQuietly() {
        userClosed = true
        session?.cancel()
        heartbeat?.cancel()
        val t = transport
        transport = null
        if (t != null) scope.launch { runCatching { t.send(MeshProto.disconnect()) }; t.close() }
    }

    fun disconnect() {
        disconnectQuietly()
        _conn.value = MeshConn.Disconnected
    }

    /** Forgets the last node (no automatic reconnection). */
    fun forget() {
        disconnect()
        prefs.edit().clear().apply()
    }

    // ---- incoming -------------------------------------------------------------------------------------------------

    private fun handle(bytes: ByteArray) {
        when (val f = MeshProto.decode(bytes) ?: return) {
            is MeshProto.FromRadio.MyInfo -> {
                if (_myNum.value != f.myNodeNum) { _nodes.value = emptyMap(); _channels.value = emptyList(); _config.value = RadioConfig() }
                _myNum.value = f.myNodeNum
            }
            is MeshProto.FromRadio.Node -> upsert(f.n.num) {
                it.copy(
                    user = f.n.user ?: it.user, position = f.n.position ?: it.position, snr = f.n.snr.takeIf { s -> s != 0f } ?: it.snr,
                    lastHeardS = maxOf(f.n.lastHeardS, it.lastHeardS), metrics = f.n.metrics ?: it.metrics, hopsAway = f.n.hopsAway ?: it.hopsAway,
                    viaMqtt = f.n.viaMqtt, isFavorite = f.n.isFavorite, isIgnored = f.n.isIgnored, channel = f.n.channel,
                )
            }
            is MeshProto.FromRadio.Channel -> _channels.update { list -> (list.filter { it.index != f.c.index } + f.c).sortedBy { it.index } }
            is MeshProto.FromRadio.Config -> _config.update { it.copy(sections = it.sections + (f.section to f.raw)) }
            is MeshProto.FromRadio.Metadata -> _metadata.value = f
            is MeshProto.FromRadio.ConfigComplete -> if (f.id == configNonce.toLong()) link?.let { _conn.value = MeshConn.Connected(it) }
            is MeshProto.FromRadio.Queue -> if (f.packetId != 0L) updateMessage(f.packetId) {
                if (f.res == 0) (if (it.status == MsgStatus.Queued) it.copy(status = MsgStatus.Sent) else it) else it.copy(status = MsgStatus.Failed, error = "File d'émission du nœud pleine")
            }
            is MeshProto.FromRadio.Notification -> _notice.value = f.text
            is MeshProto.FromRadio.Rebooted -> _notice.value = "Le nœud a redémarré"
            is MeshProto.FromRadio.Packet -> packet(f.p)
            else -> Unit
        }
    }

    private fun packet(p: MeshProto.MeshPacket) {
        val nowS = System.currentTimeMillis() / 1000
        val heardS = if (p.rxTime > 0) p.rxTime else nowS
        if (p.from != _myNum.value) upsert(p.from) {
            it.copy(
                lastHeardS = maxOf(it.lastHeardS, heardS),
                snr = if (p.rxSnr != 0f) p.rxSnr else it.snr, rssi = if (p.rxRssi != 0) p.rxRssi else it.rssi,
                hopsAway = p.hops ?: it.hopsAway, viaMqtt = p.viaMqtt,
            )
        }
        val d = p.data ?: return
        when (d.port) {
            MeshProto.PORT_TEXT -> {
                val text = d.payload.toString(Charsets.UTF_8)
                val me = _myNum.value
                val key = if (p.to == MeshProto.BROADCAST) "c${p.channel}" else "d${if (p.from == me) p.to else p.from}"
                val msg = ChatMessage(
                    p.id, key, p.from, p.to, p.channel, text, heardS * 1000, mine = p.from == me,
                    snr = p.rxSnr.takeIf { it != 0f }, rssi = p.rxRssi.takeIf { it != 0 }, hops = p.hops, viaMqtt = p.viaMqtt,
                )
                _messages.update { list -> if (list.any { it.id == p.id && it.from == p.from }) list else (list + msg).takeLast(MAX_MESSAGES) }
                save()
            }
            MeshProto.PORT_POSITION -> ProtoMsg.parse(d.payload)?.let(MeshProto::position)?.let { pos -> upsert(p.from) { it.copy(position = pos) } }
            MeshProto.PORT_NODEINFO -> ProtoMsg.parse(d.payload)?.let(MeshProto::user)?.let { u -> upsert(p.from) { it.copy(user = u) } }
            MeshProto.PORT_TELEMETRY -> MeshProto.telemetry(d.payload)?.let { (m, e) -> upsert(p.from) { it.copy(metrics = m ?: it.metrics, environment = e ?: it.environment) } }
            MeshProto.PORT_ROUTING -> {
                val err = MeshProto.routingError(d.payload) ?: return
                if (d.requestId == 0L) return
                updateMessage(d.requestId) { m ->
                    when {
                        err != 0 -> m.copy(status = MsgStatus.Failed, error = MeshProto.routingErrorText(err))
                        m.to != MeshProto.BROADCAST && p.from == m.to -> m.copy(status = MsgStatus.Delivered, error = null)
                        m.status == MsgStatus.Delivered -> m
                        else -> m.copy(status = MsgStatus.Relayed, error = null) // heard a neighbour repeat it
                    }
                }
            }
        }
    }

    private fun upsert(num: Long, change: (MeshNode) -> MeshNode) {
        if (num == 0L || num == MeshProto.BROADCAST) return
        _nodes.update { it + (num to change(it[num] ?: MeshNode(num))) }
    }

    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        var hit = false
        _messages.update { list -> list.map { if (it.mine && it.id == id) { hit = true; change(it) } else it } }
        if (hit) save()
    }

    // ---- outgoing -------------------------------------------------------------------------------------------------

    private fun newId(): Long = (rnd.nextInt().toLong() and 0x7FFFFFFFL).coerceAtLeast(1L)

    private suspend fun sendRaw(b: ByteArray): Boolean = transport?.send(b) ?: false

    /** Sends a text to a channel ("c<index>") or a node ("d<num>"). Returns false when no node is connected. */
    fun sendText(key: String, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || transport == null || _conn.value !is MeshConn.Connected) return false
        val me = _myNum.value ?: return false
        val (to, channel) = target(key)
        val id = newId()
        val msg = ChatMessage(id, key, me, to, channel, t, System.currentTimeMillis(), mine = true, status = MsgStatus.Queued)
        _messages.update { (it + msg).takeLast(MAX_MESSAGES) }
        markRead(key)
        save()
        scope.launch {
            if (!sendRaw(MeshProto.packet(to, channel, id, MeshProto.PORT_TEXT, t.toByteArray(Charsets.UTF_8), wantAck = true))) {
                updateMessage(id) { it.copy(status = MsgStatus.Failed, error = "Le nœud n'a pas reçu le message") }
            }
        }
        return true
    }

    fun retry(m: ChatMessage) {
        _messages.update { list -> list.filterNot { it.mine && it.id == m.id } }
        sendText(m.key, m.text)
    }

    fun deleteConversation(key: String) {
        _messages.update { list -> list.filterNot { it.key == key } }
        save()
    }

    /** "d<num>" → (num, channel the node was heard on); "c<i>" → (broadcast, i). */
    fun target(key: String): Pair<Long, Int> = when {
        key.startsWith("d") -> key.drop(1).toLong().let { it to (_nodes.value[it]?.channel ?: 0) }
        else -> MeshProto.BROADCAST to key.drop(1).toInt()
    }

    fun markRead(key: String) {
        _lastRead.update { it + (key to System.currentTimeMillis()) }
        save()
    }

    fun unread(key: String): Int {
        val since = _lastRead.value[key] ?: 0L
        return _messages.value.count { it.key == key && !it.mine && it.timeMs > since }
    }

    fun dismissNotice() { _notice.value = null }

    // ---- settings, through AdminMessage like the official app -------------------------------------------------------

    private fun admin(build: ProtoWriter.() -> Unit): Boolean {
        val me = _myNum.value ?: return false
        if (_conn.value !is MeshConn.Connected) return false
        scope.launch { sendRaw(MeshProto.admin(me, newId(), build)) }
        return true
    }

    /** Several settings in one transaction: the node saves once, at commit (begin_edit_settings / commit_edit_settings). */
    private fun edit(vararg changes: ProtoWriter.() -> Unit): Boolean {
        val me = _myNum.value ?: return false
        if (_conn.value !is MeshConn.Connected) return false
        scope.launch {
            sendRaw(MeshProto.admin(me, newId()) { bool(64, true) })
            for (c in changes) sendRaw(MeshProto.admin(me, newId(), c))
            sendRaw(MeshProto.admin(me, newId()) { bool(65, true) })
        }
        return true
    }

    fun setOwner(longName: String, shortName: String): Boolean {
        val me = _myNum.value ?: return false
        val u = _nodes.value[me]?.user
        val ok = edit({
            message(32) {
                u?.raw?.let { rawBytes(it) }
                string(1, MeshProto.nodeId(me))
                string(2, longName.trim().take(39))
                string(3, shortName.trim().take(4))
            }
        })
        if (ok) upsert(me) { n ->
            n.copy(user = MeshProto.User(MeshProto.nodeId(me), longName.trim(), shortName.trim(), u?.hwModel ?: 0, u?.role ?: 0, u?.isLicensed ?: false, u?.hasPublicKey ?: false, u?.raw))
        }
        return ok
    }

    /** Changes the LoRa section; the fields given override those the node sent (the others are kept as they are). */
    fun setLora(region: Int? = null, preset: Int? = null, hopLimit: Int? = null, txEnabled: Boolean? = null, ignoreMqtt: Boolean? = null, okToMqtt: Boolean? = null): Boolean {
        val raw = _config.value.lora?.raw ?: ByteArray(0)
        val body = ProtoWriter().rawBytes(raw).apply {
            if (preset != null) { bool(1, true); int(2, preset) }
            region?.let { int(7, it) }
            hopLimit?.let { int(8, it) }
            txEnabled?.let { bool(9, it) }
            ignoreMqtt?.let { bool(104, it) }
            okToMqtt?.let { bool(105, it) }
        }.toByteArray()
        val ok = edit({ message(34) { bytes(6, body) } })
        if (ok) ProtoMsg.parse(body)?.let { m -> _config.update { it.copy(sections = it.sections + (6 to m)) } }
        return ok
    }

    fun setRole(role: Int): Boolean {
        val raw = _config.value.device?.raw ?: ByteArray(0)
        val body = ProtoWriter().rawBytes(raw).int(1, role).toByteArray()
        val ok = edit({ message(34) { bytes(1, body) } })
        if (ok) ProtoMsg.parse(body)?.let { m -> _config.update { it.copy(sections = it.sections + (1 to m)) } }
        return ok
    }

    /** Position: broadcast interval (s) and smart broadcast. */
    fun setPositionBroadcast(seconds: Int, smart: Boolean): Boolean {
        val raw = _config.value.position?.raw ?: ByteArray(0)
        val body = ProtoWriter().rawBytes(raw).int(1, seconds).bool(2, smart).toByteArray()
        val ok = edit({ message(34) { bytes(2, body) } })
        if (ok) ProtoMsg.parse(body)?.let { m -> _config.update { it.copy(sections = it.sections + (2 to m)) } }
        return ok
    }

    fun setFavorite(num: Long, favorite: Boolean): Boolean {
        val ok = admin { int(if (favorite) 39 else 40, num.toInt()) }
        if (ok) upsert(num) { it.copy(isFavorite = favorite) }
        return ok
    }

    fun setIgnored(num: Long, ignored: Boolean): Boolean {
        val ok = admin { int(if (ignored) 47 else 48, num.toInt()) }
        if (ok) upsert(num) { it.copy(isIgnored = ignored) }
        return ok
    }

    fun removeNode(num: Long): Boolean {
        val ok = admin { int(38, num.toInt()) }
        if (ok) _nodes.update { it - num }
        return ok
    }

    /** Asks a node for its user info (NodeInfo with want_response), as "Échanger les infos" does. */
    fun requestUserInfo(num: Long): Boolean {
        val me = _myNum.value ?: return false
        val u = _nodes.value[me]?.user?.raw ?: return false
        val ch = _nodes.value[num]?.channel ?: 0
        scope.launch { sendRaw(MeshProto.packet(num, ch, newId(), MeshProto.PORT_NODEINFO, u, wantAck = false, wantResponse = true)) }
        return true
    }

    fun reboot(): Boolean = admin { int(97, 5) }

    fun shutdown(): Boolean = admin { int(98, 5) }

    // ---- scanning ------------------------------------------------------------------------------------------------

    /** Nearby Meshtastic nodes advertising their Bluetooth service, plus those already paired with the phone. */
    open fun scan(): Flow<List<FoundNode>> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        val found = LinkedHashMap<String, FoundNode>()
        adapter?.bondedDevices?.filter { d -> d.name?.let { looksLikeMeshtastic(it) } == true }?.forEach { d ->
            found[d.address] = FoundNode(d.address, d.name ?: d.address, null, true)
        }
        trySend(found.values.toList())
        if (scanner == null) { awaitClose { }; return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val d = r.device
                found[d.address] = FoundNode(d.address, r.scanRecord?.deviceName ?: d.name ?: d.address, r.rssi, d.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED)
                trySend(found.values.sortedByDescending { it.rssi ?: -200 })
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleMeshTransport.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(filters, settings, cb) }
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    private fun looksLikeMeshtastic(name: String) = name.contains("meshtastic", true) || Regex("_[0-9a-fA-F]{4}$").containsMatchIn(name)

    // ---- storage --------------------------------------------------------------------------------------------------

    private var saveJob: Job? = null

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            withContext(Dispatchers.IO) {
                val arr = JSONArray()
                _messages.value.forEach { m ->
                    arr.put(
                        JSONObject().put("id", m.id).put("key", m.key).put("from", m.from).put("to", m.to).put("ch", m.channel).put("text", m.text)
                            .put("t", m.timeMs).put("mine", m.mine).put("st", m.status.name).put("err", m.error ?: JSONObject.NULL)
                            .put("snr", m.snr?.toDouble() ?: JSONObject.NULL).put("rssi", m.rssi ?: JSONObject.NULL).put("hops", m.hops ?: JSONObject.NULL),
                    )
                }
                val read = JSONObject().apply { _lastRead.value.forEach { (k, v) -> put(k, v) } }
                store.parentFile?.mkdirs()
                store.writeText(JSONObject().put("messages", arr).put("read", read).toString())
            }
        }
    }

    private fun load() {
        runCatching {
            if (!store.exists()) return
            val o = JSONObject(store.readText())
            val arr = o.optJSONArray("messages") ?: JSONArray()
            _messages.value = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(
                    m.getLong("id"), m.getString("key"), m.getLong("from"), m.getLong("to"), m.getInt("ch"), m.getString("text"), m.getLong("t"),
                    m.getBoolean("mine"),
                    runCatching { MsgStatus.valueOf(m.getString("st")) }.getOrDefault(MsgStatus.Delivered).let { if (it == MsgStatus.Queued) MsgStatus.Failed else it },
                    m.optString("err").takeIf { it.isNotEmpty() && it != "null" },
                    if (m.isNull("snr")) null else m.getDouble("snr").toFloat(),
                    if (m.isNull("rssi")) null else m.getInt("rssi"),
                    if (m.isNull("hops")) null else m.getInt("hops"),
                )
            }
            val r = o.optJSONObject("read")
            if (r != null) _lastRead.value = r.keys().asSequence().associateWith { r.getLong(it) }
        }
    }

    /** Test hook: fills the client as if a node had been read. */
    internal fun setForTest(me: Long, nodes: List<MeshNode>, channels: List<MeshProto.ChannelRecord>, config: RadioConfig, messages: List<ChatMessage>, conn: MeshConn) {
        _myNum.value = me
        _nodes.value = nodes.associateBy { it.num }
        _channels.value = channels
        _config.value = config
        _messages.value = messages
        _conn.value = conn
    }

    /** Test hook: feeds one FromRadio message as if the node had sent it. */
    internal fun feedForTest(fromRadio: ByteArray) = handle(fromRadio)

    internal fun setLinkForTest(l: MeshLink, nonce: Int) { link = l; configNonce = nonce }

    companion object {
        const val MAX_MESSAGES = 3000
    }
}
