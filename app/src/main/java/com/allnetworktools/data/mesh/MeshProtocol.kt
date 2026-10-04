package com.allnetworktools.data.mesh

/**
 * The Meshtastic client API (meshtastic/protobufs): what a phone app exchanges with its node over Bluetooth, TCP or
 * serial. The node does all the radio work; the app sends ToRadio messages and reads FromRadio messages.
 */
object MeshProto {
    const val BROADCAST = 0xFFFFFFFFL

    // PortNum
    const val PORT_TEXT = 1
    const val PORT_POSITION = 3
    const val PORT_NODEINFO = 4
    const val PORT_ROUTING = 5
    const val PORT_ADMIN = 6
    const val PORT_TELEMETRY = 67
    const val PORT_TRACEROUTE = 70

    /** Asks the node for its whole configuration and node database (any nonce other than the two-stage ones). */
    fun wantConfig(nonce: Int): ByteArray = ProtoWriter().varint(3, nonce.toLong() and 0xFFFFFFFFL).toByteArray()

    fun heartbeat(nonce: Int): ByteArray = ProtoWriter().message(7) { varint(1, nonce.toLong() and 0xFFFFFFFFL) }.toByteArray()

    fun disconnect(): ByteArray = ProtoWriter().bool(4, true).toByteArray()

    /** ToRadio.packet: a MeshPacket the node fills in (from, encryption, hop limit) and sends. */
    fun packet(to: Long, channel: Int, id: Long, port: Int, payload: ByteArray, wantAck: Boolean, wantResponse: Boolean = false, replyId: Long = 0, emoji: Boolean = false): ByteArray =
        ProtoWriter().message(1) {
            fixed32(2, to)
            if (channel != 0) int(3, channel)
            message(4) {
                int(1, port)
                bytes(2, payload)
                if (wantResponse) bool(3, true)
                if (replyId != 0L) fixed32(7, replyId)
                if (emoji) fixed32(8, 1)
            }
            fixed32(6, id)
            if (wantAck) bool(10, true)
        }.toByteArray()

    /** An AdminMessage for our own node (the phone link is trusted: no session key). */
    fun admin(myNode: Long, id: Long, build: ProtoWriter.() -> Unit): ByteArray =
        packet(myNode, 0, id, PORT_ADMIN, ProtoWriter().apply(build).toByteArray(), wantAck = false, wantResponse = true)

    // ---- decoding -------------------------------------------------------------------------------------------------

    sealed interface FromRadio {
        data class Packet(val p: MeshPacket) : FromRadio
        data class MyInfo(val myNodeNum: Long, val rebootCount: Int, val firmwareEnv: String?) : FromRadio
        data class Node(val n: NodeRecord) : FromRadio
        data class Config(val section: Int, val raw: ProtoMsg) : FromRadio
        data class ModuleConfig(val raw: ProtoMsg) : FromRadio
        data class Channel(val c: ChannelRecord) : FromRadio
        data class ConfigComplete(val id: Long) : FromRadio
        data object Rebooted : FromRadio
        data class Queue(val res: Int, val free: Int, val maxLen: Int, val packetId: Long) : FromRadio
        data class Metadata(val firmware: String?, val hwModel: Int, val hasWifi: Boolean, val hasBluetooth: Boolean, val role: Int) : FromRadio
        data class Notification(val text: String?, val replyId: Long) : FromRadio
        data object Other : FromRadio
    }

    fun decode(bytes: ByteArray): FromRadio? {
        val m = ProtoMsg.parse(bytes) ?: return null
        return when {
            m.has(2) -> m.msg(2)?.let(::meshPacket)?.let { FromRadio.Packet(it) }
            m.has(3) -> m.msg(3)?.let { FromRadio.MyInfo(it.uint(1), it.int(8), it.str(13)) }
            m.has(4) -> m.msg(4)?.let(::nodeRecord)?.let { FromRadio.Node(it) }
            m.has(5) -> m.msg(5)?.let { c ->
                val section = (1..10).firstOrNull { c.has(it) } ?: return@let null
                FromRadio.Config(section, c.msg(section) ?: return@let null)
            }
            m.has(7) -> FromRadio.ConfigComplete(m.uint(7))
            m.has(8) -> FromRadio.Rebooted
            m.has(9) -> m.msg(9)?.let { FromRadio.ModuleConfig(it) }
            m.has(10) -> m.msg(10)?.let(::channelRecord)?.let { FromRadio.Channel(it) }
            m.has(11) -> m.msg(11)?.let { FromRadio.Queue(it.int32(1), it.int(2), it.int(3), it.uint(4)) }
            m.has(13) -> m.msg(13)?.let { FromRadio.Metadata(it.str(1), it.int(9), it.bool(4), it.bool(5), it.int(7)) }
            m.has(16) -> m.msg(16)?.let { FromRadio.Notification(it.str(4), it.uint(1)) }
            else -> FromRadio.Other
        }
    }

    class Data(val port: Int, val payload: ByteArray, val wantResponse: Boolean, val requestId: Long, val replyId: Long, val emoji: Boolean)

    class MeshPacket(
        val from: Long, val to: Long, val channel: Int, val id: Long, val data: Data?, val encrypted: Boolean,
        val rxTime: Long, val rxSnr: Float, val rxRssi: Int, val hopLimit: Int, val hopStart: Int, val wantAck: Boolean, val viaMqtt: Boolean,
        val pkiEncrypted: Boolean,
    ) {
        /** Hops the packet made to reach us, when the sender's firmware tells (hop_start). */
        val hops: Int? get() = if (hopStart > 0 && hopStart >= hopLimit) hopStart - hopLimit else null
    }

    fun meshPacket(m: ProtoMsg): MeshPacket = MeshPacket(
        from = m.uint(1), to = m.uint(2), channel = m.int(3), id = m.uint(6),
        data = m.msg(4)?.let { d -> Data(d.int(1), d.bytes(2) ?: ByteArray(0), d.bool(3), d.uint(6), d.uint(7), d.uint(8) != 0L) },
        encrypted = m.has(5), rxTime = m.uint(7), rxSnr = m.float(8), rxRssi = m.int32(12), hopLimit = m.int(9), hopStart = m.int(15),
        wantAck = m.bool(10), viaMqtt = m.bool(14), pkiEncrypted = m.bool(17),
    )

    class User(val id: String?, val longName: String?, val shortName: String?, val hwModel: Int, val role: Int, val isLicensed: Boolean, val hasPublicKey: Boolean, val raw: ByteArray?)

    fun user(m: ProtoMsg) = User(m.str(1), m.str(2), m.str(3), m.int(5), m.int(7), m.bool(6), (m.bytes(8)?.size ?: 0) > 0, m.raw)

    data class Position(val lat: Double, val lon: Double, val altitude: Int?, val timeS: Long, val sats: Int, val precisionBits: Int, val groundSpeed: Int?)

    fun position(m: ProtoMsg): Position? {
        val lat = m.int32(1)
        val lon = m.int32(2)
        if (lat == 0 && lon == 0) return null
        return Position(lat * 1e-7, lon * 1e-7, if (m.has(3)) m.int32(3) else null, m.uint(4).takeIf { it != 0L } ?: m.uint(7), m.int(19), m.int(23), if (m.has(15)) m.int(15) else null)
    }

    data class Metrics(val battery: Int?, val voltage: Float?, val channelUtil: Float?, val airUtilTx: Float?, val uptimeS: Long?)

    fun metrics(m: ProtoMsg) = Metrics(
        if (m.has(1)) m.int(1) else null, if (m.has(2)) m.float(2) else null, if (m.has(3)) m.float(3) else null,
        if (m.has(4)) m.float(4) else null, if (m.has(5)) m.uint(5) else null,
    )

    data class Environment(val temperature: Float?, val humidity: Float?, val pressure: Float?)

    /** Telemetry: device metrics (2) or environment (3). */
    fun telemetry(b: ByteArray): Pair<Metrics?, Environment?>? {
        val m = ProtoMsg.parse(b) ?: return null
        val dev = m.msg(2)?.let(::metrics)
        val env = m.msg(3)?.let { Environment(if (it.has(1)) it.float(1) else null, if (it.has(2)) it.float(2) else null, if (it.has(3)) it.float(3) else null) }
        return dev to env
    }

    class NodeRecord(
        val num: Long, val user: User?, val position: Position?, val snr: Float, val lastHeardS: Long, val metrics: Metrics?,
        val channel: Int, val viaMqtt: Boolean, val hopsAway: Int?, val isFavorite: Boolean, val isIgnored: Boolean,
    )

    fun nodeRecord(m: ProtoMsg) = NodeRecord(
        m.uint(1), m.msg(2)?.let(::user), m.msg(3)?.let(::position), m.float(4), m.uint(5), m.msg(6)?.let(::metrics),
        m.int(7), m.bool(8), if (m.has(9)) m.int(9) else null, m.bool(10), m.bool(11),
    )

    /** Channel role: 0 disabled, 1 primary, 2 secondary. */
    class ChannelRecord(val index: Int, val role: Int, val name: String, val psk: ByteArray, val positionPrecision: Int, val uplink: Boolean, val downlink: Boolean, val settingsRaw: ByteArray?)

    fun channelRecord(m: ProtoMsg): ChannelRecord {
        val s = m.msg(2)
        return ChannelRecord(
            m.int(1), m.int(3), s?.str(3).orEmpty(), s?.bytes(2) ?: ByteArray(0), s?.msg(7)?.int(1) ?: 0,
            s?.bool(5) ?: false, s?.bool(6) ?: false, s?.raw,
        )
    }

    /** Routing.error_reason, 0 when the packet was delivered. */
    fun routingError(payload: ByteArray): Int? = ProtoMsg.parse(payload)?.let { if (it.has(1) || it.has(2)) 0 else it.int(3) }

    fun routingErrorText(e: Int): String = when (e) {
        0 -> "Remis"
        1 -> "Pas de route"
        2 -> "Refusé par un nœud"
        3 -> "Pas de réponse (délai dépassé)"
        4 -> "Pas d'interface radio"
        5 -> "Nombre maximal de tentatives atteint"
        6 -> "Canal inconnu du destinataire"
        7 -> "Message trop long"
        8 -> "Pas de réponse"
        9 -> "Limite de temps d'émission atteinte"
        32 -> "Requête invalide"
        33 -> "Non autorisé"
        34 -> "Échec du chiffrement de bout en bout"
        35 -> "Clé publique du destinataire inconnue"
        38 -> "Trop de messages, réessayez plus tard"
        else -> "Erreur $e"
    }

    // ---- names ----------------------------------------------------------------------------------------------------

    fun nodeId(num: Long) = "!%08x".format(num)

    private val hwModels: Map<Int, String> = mapOf(0 to "UNSET", 1 to "TLORA_V2", 2 to "TLORA_V1", 3 to "TLORA_V2_1_1P6", 4 to "TBEAM", 5 to "HELTEC_V2_0", 6 to "TBEAM_V0P7", 7 to "T_ECHO", 8 to "TLORA_V1_1P3", 9 to "RAK4631", 10 to "HELTEC_V2_1", 11 to "HELTEC_V1", 12 to "LILYGO_TBEAM_S3_CORE", 13 to "RAK11200", 14 to "NANO_G1", 15 to "TLORA_V2_1_1P8", 16 to "TLORA_T3_S3", 17 to "NANO_G1_EXPLORER", 18 to "NANO_G2_ULTRA", 19 to "LORA_TYPE", 20 to "WIPHONE", 21 to "WIO_WM1110", 22 to "RAK2560", 23 to "HELTEC_HRU_3601", 24 to "HELTEC_WIRELESS_BRIDGE", 25 to "STATION_G1", 26 to "RAK11310", 27 to "MAKERFABS_TRACKER", 28 to "MAKERFABS_RESERVED", 29 to "CANARYONE", 30 to "RP2040_LORA", 31 to "STATION_G2", 32 to "LORA_RELAY_V1", 33 to "T_ECHO_PLUS", 34 to "PPR", 35 to "GENIEBLOCKS", 36 to "NRF52_UNKNOWN", 37 to "PORTDUINO", 38 to "ANDROID_SIM", 39 to "DIY_V1", 40 to "NRF52840_PCA10059", 41 to "DR_DEV", 42 to "M5STACK", 43 to "HELTEC_V3", 44 to "HELTEC_WSL_V3", 45 to "BETAFPV_2400_TX", 46 to "BETAFPV_900_NANO_TX", 47 to "RPI_PICO", 48 to "HELTEC_WIRELESS_TRACKER", 49 to "HELTEC_WIRELESS_PAPER", 50 to "T_DECK", 51 to "T_WATCH_S3", 52 to "PICOMPUTER_S3", 53 to "HELTEC_HT62", 54 to "EBYTE_ESP32_S3", 55 to "ESP32_S3_PICO", 56 to "CHATTER_2", 57 to "HELTEC_WIRELESS_PAPER_V1_0", 58 to "HELTEC_WIRELESS_TRACKER_V1_0", 59 to "UNPHONE", 60 to "TD_LORAC", 61 to "CDEBYTE_EORA_S3", 62 to "TWC_MESH_V4", 63 to "NRF52_PROMICRO_DIY", 64 to "RADIOMASTER_900_BANDIT_NANO", 65 to "HELTEC_CAPSULE_SENSOR_V3", 66 to "HELTEC_VISION_MASTER_T190", 67 to "HELTEC_VISION_MASTER_E213", 68 to "HELTEC_VISION_MASTER_E290", 69 to "HELTEC_MESH_NODE_T114", 70 to "SENSECAP_INDICATOR", 71 to "TRACKER_T1000_E", 72 to "RAK3172", 73 to "WIO_E5", 74 to "RADIOMASTER_900_BANDIT", 75 to "ME25LS01_4Y10TD", 76 to "RP2040_FEATHER_RFM95", 77 to "M5STACK_COREBASIC", 78 to "M5STACK_CORE2", 79 to "RPI_PICO2", 80 to "M5STACK_CORES3", 81 to "SEEED_XIAO_S3", 82 to "MS24SF1", 83 to "TLORA_C6", 84 to "WISMESH_TAP", 85 to "ROUTASTIC", 86 to "MESH_TAB", 87 to "MESHLINK", 88 to "XIAO_NRF52_KIT", 89 to "THINKNODE_M1", 90 to "THINKNODE_M2", 91 to "T_ETH_ELITE", 92 to "HELTEC_SENSOR_HUB", 93 to "MUZI_BASE", 94 to "HELTEC_MESH_POCKET", 95 to "SEEED_SOLAR_NODE", 96 to "NOMADSTAR_METEOR_PRO", 97 to "CROWPANEL", 98 to "LINK_32", 99 to "SEEED_WIO_TRACKER_L1", 100 to "SEEED_WIO_TRACKER_L1_EINK", 101 to "MUZI_R1_NEO", 102 to "T_DECK_PRO", 103 to "T_LORA_PAGER", 104 to "M5STACK_RESERVED", 105 to "WISMESH_TAG", 106 to "RAK3312", 107 to "THINKNODE_M5", 108 to "HELTEC_MESH_SOLAR", 109 to "T_ECHO_LITE", 110 to "HELTEC_V4", 111 to "M5STACK_C6L", 112 to "M5STACK_CARDPUTER_ADV", 113 to "HELTEC_WIRELESS_TRACKER_V2", 114 to "T_WATCH_ULTRA", 115 to "THINKNODE_M3", 116 to "WISMESH_TAP_V2", 117 to "RAK3401", 118 to "RAK6421", 119 to "THINKNODE_M4", 120 to "THINKNODE_M6", 121 to "MESHSTICK_1262", 122 to "TBEAM_1_WATT", 123 to "T5_S3_EPAPER_PRO", 124 to "TBEAM_BPF", 125 to "MINI_EPAPER_S3", 126 to "TDISPLAY_S3_PRO", 127 to "HELTEC_MESH_NODE_T096", 128 to "MESH_TRACKER_X1", 129 to "THINKNODE_M7", 130 to "THINKNODE_M8", 131 to "THINKNODE_M9", 132 to "HELTEC_V4_R8", 133 to "HELTEC_MESH_NODE_T1", 134 to "STATION_G3", 135 to "T_IMPULSE_PLUS", 136 to "T_ECHO_CARD", 137 to "SEEED_WIO_TRACKER_L2", 138 to "CROWPANEL_P4", 139 to "HELTEC_MESH_TOWER_V2", 140 to "MESHNOLOGY_W10", 141 to "HELTEC_RC32", 142 to "HELTEC_RC52", 143 to "HELTEC_RCC6", 144 to "SEEED_WIO_TRACKER_L1_PRO_1W", 145 to "MESHNOLOGY_W12", 146 to "MESHPAGER_X2", 147 to "T_CONNECT_PRO", 148 to "AXIOMETA_GENESIS_MINI", 149 to "MAKERFABS_NOMAD_TERMINAL", 150 to "THINKNODE_MX", 255 to "PRIVATE_HW")

    fun hwModel(v: Int): String = hwModels[v]?.replace('_', ' ') ?: "Modèle $v"

    val roles: List<Pair<Int, String>> = listOf(
        0 to "Client", 1 to "Client muet", 2 to "Routeur", 4 to "Répéteur", 5 to "Traceur", 6 to "Capteur", 7 to "TAK",
        8 to "Client caché", 9 to "Objets perdus", 10 to "Traceur TAK", 11 to "Routeur tardif", 12 to "Client base",
    )

    fun roleName(v: Int): String = roles.firstOrNull { it.first == v }?.second ?: if (v == 3) "Routeur client" else "Rôle $v"

    val presets: List<Pair<Int, String>> = listOf(
        0 to "Long Fast", 9 to "Long Turbo", 7 to "Long Moderate", 1 to "Long Slow", 4 to "Medium Fast", 3 to "Medium Slow",
        8 to "Short Turbo", 6 to "Short Fast", 5 to "Short Slow",
    )

    fun presetName(v: Int): String = presets.firstOrNull { it.first == v }?.second ?: if (v == 2) "Very Long Slow" else "Préréglage $v"

    /** The default channel name of a preset, used when the primary channel's name is empty. */
    fun presetChannelName(v: Int): String = presetName(v).replace(" ", "")

    val regions: List<Pair<Int, String>> = listOf(
        0 to "Non défini", 3 to "EU_868", 2 to "EU_433", 1 to "US", 4 to "CN", 5 to "JP", 6 to "ANZ", 22 to "ANZ_433", 7 to "KR", 8 to "TW", 9 to "RU",
        10 to "IN", 11 to "NZ_865", 12 to "TH", 13 to "LORA_24", 14 to "UA_433", 15 to "UA_868", 16 to "MY_433", 17 to "MY_919", 18 to "SG_923",
        19 to "PH_433", 20 to "PH_868", 21 to "PH_915", 23 to "KZ_433", 24 to "KZ_863", 25 to "NP_865", 26 to "BR_902",
    )

    fun regionName(v: Int): String = regions.firstOrNull { it.first == v }?.second ?: "Région $v"

    /** The default PSK that "AQ==" (1) stands for. */
    private val DEFAULT_PSK = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59, 0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(),
        0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
    )

    /** A short description of a channel's key, the way the official app shows it. */
    fun keyKind(psk: ByteArray): String = when {
        psk.isEmpty() -> "Sans chiffrement"
        psk.size == 1 && psk[0].toInt() == 0 -> "Sans chiffrement"
        psk.size == 1 -> "Clé par défaut"
        psk.contentEquals(DEFAULT_PSK) -> "Clé par défaut"
        psk.size == 16 -> "AES-128"
        psk.size == 32 -> "AES-256"
        else -> "Clé de ${psk.size} octets"
    }
}
