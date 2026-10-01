package com.allnetworktools.data.sdr

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Meshtastic over-the-air format (firmware src/mesh/RadioInterface.h, CryptoEngine.cpp, Channels.cpp and the
 * meshtastic/protobufs definitions): a 16-byte clear header, then the channel-encrypted `Data` protobuf.
 */
object Meshtastic {
    const val BROADCAST = 0xFFFFFFFFL

    /** Default channel key, the one the 1-byte PSK "AQ==" (index 1) expands to. */
    private val DEFAULT_PSK = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
    )

    /** Expands a channel PSK as the firmware does: 1 byte = default key variant, 0 = no encryption, short keys zero-padded. */
    fun expandKey(psk: ByteArray): ByteArray? = when {
        psk.isEmpty() -> null
        psk.size == 1 -> {
            val idx = psk[0].toInt() and 0xFF
            if (idx == 0) null else DEFAULT_PSK.copyOf().also { it[15] = (it[15] + idx - 1).toByte() }
        }
        psk.size < 16 -> psk.copyOf(16)
        psk.size in 17..31 -> psk.copyOf(32)
        else -> psk.copyOf()
    }

    /** Channel hash sent in clear in each packet: XOR of the name bytes XOR the key bytes. */
    fun channelHash(name: String, key: ByteArray?): Int {
        var h = 0
        name.toByteArray(Charsets.UTF_8).forEach { h = h xor (it.toInt() and 0xFF) }
        key?.forEach { h = h xor (it.toInt() and 0xFF) }
        return h
    }

    class Header(
        val to: Long,
        val from: Long,
        val id: Long,
        val hopLimit: Int,
        val wantAck: Boolean,
        val viaMqtt: Boolean,
        val hopStart: Int,
        val channel: Int,
        val nextHop: Int,
        val relayNode: Int,
    ) {
        val hops: Int? get() = if (hopStart >= hopLimit && hopStart > 0) hopStart - hopLimit else null
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    fun header(b: ByteArray): Header? {
        if (b.size < 16) return null
        val flags = b[12].toInt() and 0xFF
        return Header(
            to = u32(b, 0), from = u32(b, 4), id = u32(b, 8),
            hopLimit = flags and 0x07, wantAck = flags and 0x08 != 0, viaMqtt = flags and 0x10 != 0, hopStart = (flags and 0xE0) shr 5,
            channel = b[13].toInt() and 0xFF, nextHop = b[14].toInt() and 0xFF, relayNode = b[15].toInt() and 0xFF,
        )
    }

    /** AES-CTR with nonce = packet id (8 bytes LE) ‖ sender (4 bytes LE) ‖ 0; encryption and decryption are the same. */
    fun crypt(key: ByteArray, from: Long, id: Long, data: ByteArray): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 4) iv[i] = (id shr (8 * i)).toByte()
        for (i in 0 until 4) iv[8 + i] = (from shr (8 * i)).toByte()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    // ---- minimal protobuf reader --------------------------------------------------------------------

    class Field(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?)

    /** Parses a protobuf message into its fields; null when the bytes are not a well-formed message. */
    fun fields(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long? {
            var r = 0L
            var shift = 0
            while (i < b.size && shift < 64) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
            return null
        }
        while (i < b.size) {
            val key = varint() ?: return null
            val num = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (num == 0) return null
            when (wire) {
                0 -> out += Field(num, 0, varint() ?: return null, null)
                1 -> { if (i + 8 > b.size) return null; out += Field(num, 1, (u32(b, i) or (u32(b, i + 4) shl 32)), b.copyOfRange(i, i + 8)); i += 8 }
                2 -> {
                    val len = varint()?.toInt() ?: return null
                    if (len < 0 || i + len > b.size) return null
                    out += Field(num, 2, len.toLong(), b.copyOfRange(i, i + len)); i += len
                }
                5 -> { if (i + 4 > b.size) return null; out += Field(num, 5, u32(b, i), b.copyOfRange(i, i + 4)); i += 4 }
                else -> return null
            }
        }
        return out
    }

    private fun List<Field>.f(n: Int) = firstOrNull { it.number == n }
    private fun Field.str() = bytes?.toString(Charsets.UTF_8)
    private fun Field.float() = java.lang.Float.intBitsToFloat(varint.toInt())
    private fun Field.int32() = varint.toInt()

    // ---- decoded content -----------------------------------------------------------------------------

    sealed interface Content {
        data class Text(val text: String) : Content
        data class Position(val lat: Double?, val lon: Double?, val altitude: Int?, val sats: Int?) : Content
        data class NodeInfo(val id: String?, val longName: String?, val shortName: String?, val hwModel: Int?, val role: Int?) : Content
        data class Telemetry(val battery: Int?, val voltage: Float?, val chUtil: Float?, val airUtil: Float?, val uptime: Long?, val temperature: Float?, val humidity: Float?, val pressure: Float?) : Content
        data class Other(val port: Int, val size: Int) : Content
    }

    class Data(val port: Int, val content: Content, val requestId: Long?, val wantResponse: Boolean)

    /** Decodes a decrypted `Data` message; null when it does not parse, i.e. wrong key or not for this channel. */
    fun data(plain: ByteArray): Data? {
        val f = fields(plain) ?: return null
        val portField = f.f(1) ?: return null
        if (portField.wire != 0) return null
        val port = portField.varint.toInt()
        if (port !in 0..511) return null
        val payload = f.f(2)?.bytes ?: ByteArray(0)
        val content: Content = when (port) {
            1 -> Content.Text(payload.toString(Charsets.UTF_8))
            3 -> fields(payload)?.let { p ->
                Content.Position(
                    lat = p.f(1)?.let { it.int32() / 1e7 }, lon = p.f(2)?.let { it.int32() / 1e7 },
                    altitude = p.f(3)?.int32(), sats = p.f(19)?.int32(),
                )
            } ?: Content.Other(port, payload.size)
            4 -> fields(payload)?.let { u ->
                Content.NodeInfo(u.f(1)?.str(), u.f(2)?.str(), u.f(3)?.str(), u.f(5)?.int32(), u.f(7)?.int32())
            } ?: Content.Other(port, payload.size)
            67 -> fields(payload)?.let { t ->
                val dev = t.f(2)?.bytes?.let(::fields)
                val env = t.f(3)?.bytes?.let(::fields)
                Content.Telemetry(
                    battery = dev?.f(1)?.int32(), voltage = dev?.f(2)?.float(), chUtil = dev?.f(3)?.float(), airUtil = dev?.f(4)?.float(),
                    uptime = dev?.f(5)?.varint, temperature = env?.f(1)?.float(), humidity = env?.f(2)?.float(), pressure = env?.f(3)?.float(),
                )
            } ?: Content.Other(port, payload.size)
            else -> Content.Other(port, payload.size)
        }
        return Data(port, content, f.f(6)?.varint, f.f(3)?.varint == 1L)
    }

    fun portName(port: Int): String = when (port) {
        0 -> "Inconnu"
        1 -> "Message"
        2 -> "Matériel distant"
        3 -> "Position"
        4 -> "Infos du nœud"
        5 -> "Routage / accusé"
        6 -> "Administration"
        7 -> "Message compressé"
        8 -> "Point de passage"
        9 -> "Audio"
        10 -> "Détecteur"
        11 -> "Alerte"
        12 -> "Vérification de clé"
        32 -> "Réponse"
        33 -> "Tunnel IP"
        34 -> "Paxcounter"
        64 -> "Série"
        65 -> "Store & Forward"
        66 -> "Test de portée"
        67 -> "Télémétrie"
        68 -> "ZPS"
        69 -> "Simulateur"
        70 -> "Traceroute"
        71 -> "Voisins"
        72 -> "ATAK"
        73 -> "Carte (map report)"
        74 -> "Power stress"
        76 -> "Reticulum"
        256 -> "Privé"
        257 -> "ATAK forwarder"
        else -> "Port $port"
    }

    fun nodeId(num: Long) = "!%08x".format(num)

    /** Common hardware models (HardwareModel enum); others are shown by number. */
    fun hwModel(v: Int): String = when (v) {
        4 -> "T-Beam"; 7 -> "T-Echo"; 9 -> "RAK4631"; 10 -> "Heltec V2.1"; 25 -> "Station G1"; 39 -> "Diy V1"; 43 -> "Heltec V3"
        44 -> "Heltec WSL V3"; 47 -> "RP2040 LoRa"; 48 -> "Heltec Wireless Tracker"; 50 -> "T-Deck"; 51 -> "T-Watch S3"
        58 -> "Heltec Wireless Paper"; 64 -> "Heltec HT62"; 66 -> "SenseCAP Indicator"; 71 -> "Tracker T1000-E"; 1 -> "TLoRa V2"
        else -> "Modèle $v"
    }

    /** A received packet as far as it could be decoded with the configured channel. */
    class Packet(
        val atMs: Long,
        val header: Header,
        val data: Data?,
        /** True when the hash matches the configured channel but the content did not decode. */
        val undecodable: Boolean,
        val snrDb: Double,
        val sizeBytes: Int,
    ) {
        val otherChannel: Boolean get() = data == null && !undecodable
    }

    fun decode(frame: ByteArray, key: ByteArray?, hash: Int, atMs: Long, snrDb: Double): Packet? {
        val h = header(frame) ?: return null
        val body = frame.copyOfRange(16, frame.size)
        if (h.channel != hash) return Packet(atMs, h, null, false, snrDb, frame.size)
        val plain = if (key == null) body else crypt(key, h.from, h.id, body)
        val d = data(plain)
        return Packet(atMs, h, d, d == null, snrDb, frame.size)
    }
}
