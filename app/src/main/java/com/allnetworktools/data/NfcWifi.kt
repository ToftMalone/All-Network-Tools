package com.allnetworktools.data

import java.io.ByteArrayOutputStream

/** Security a Wi-Fi tag can announce; WSC has no code for WPA3-only (SAE) networks. */
enum class WifiTagSecurity(val label: String, val auth: Int, val encryption: Int) {
    Wpa2("WPA2 / WPA3 (transition)", 0x0020, 0x0008),
    WpaWpa2("WPA / WPA2", 0x0022, 0x000C),
    Open("Ouvert (sans mot de passe)", 0x0001, 0x0001),
    ;

    val needsKey: Boolean get() = this != Open
}

data class WifiTagContent(val ssid: String, val security: String, val hasKey: Boolean)

/**
 * Wi-Fi Simple Configuration "credential" token (MIME application/vnd.wfa.wsc), the format Android's
 * NFC service turns into a "Se connecter à ce réseau ?" prompt. Attributes are big-endian
 * type (2 bytes), length (2 bytes), value — Wi-Fi Simple Configuration Technical Specification v2, §12.
 */
object WscToken {
    const val MIME = "application/vnd.wfa.wsc"
    private const val VERSION = 0x104A
    private const val CREDENTIAL = 0x100E
    private const val NETWORK_INDEX = 0x1026
    private const val SSID = 0x1045
    private const val AUTH_TYPE = 0x1003
    private const val ENCRYPTION_TYPE = 0x100F
    private const val NETWORK_KEY = 0x1027
    private const val MAC_ADDRESS = 0x1020
    private const val VENDOR_EXTENSION = 0x1049
    private val WFA_VENDOR_ID = byteArrayOf(0x00, 0x37, 0x2A)

    private fun ByteArrayOutputStream.attr(type: Int, value: ByteArray) {
        write(type shr 8); write(type and 0xFF)
        write(value.size shr 8); write(value.size and 0xFF)
        write(value)
    }

    private fun short(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun encode(ssid: String, key: String, security: WifiTagSecurity): ByteArray {
        val credential = ByteArrayOutputStream().apply {
            attr(NETWORK_INDEX, byteArrayOf(1))
            attr(SSID, ssid.toByteArray(Charsets.UTF_8))
            attr(AUTH_TYPE, short(security.auth))
            attr(ENCRYPTION_TYPE, short(security.encryption))
            attr(NETWORK_KEY, if (security.needsKey) key.toByteArray(Charsets.UTF_8) else ByteArray(0))
            // Broadcast address: the credential is not bound to one enrollee.
            attr(MAC_ADDRESS, ByteArray(6) { 0xFF.toByte() })
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            attr(VERSION, byteArrayOf(0x10))
            attr(CREDENTIAL, credential)
            // WFA vendor extension carrying Version2 = 2.0.
            attr(VENDOR_EXTENSION, WFA_VENDOR_ID + byteArrayOf(0x00, 0x01, 0x20))
        }.toByteArray()
    }

    private fun attrs(bytes: ByteArray): Map<Int, ByteArray>? {
        val out = HashMap<Int, ByteArray>()
        var i = 0
        while (i + 4 <= bytes.size) {
            val type = (bytes[i].toInt() and 0xFF shl 8) or (bytes[i + 1].toInt() and 0xFF)
            val len = (bytes[i + 2].toInt() and 0xFF shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (i + 4 + len > bytes.size) return null
            out.putIfAbsent(type, bytes.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return out
    }

    /** Network name and security of a token; the key itself is never returned. */
    fun decode(payload: ByteArray): WifiTagContent? {
        val cred = attrs(payload)?.get(CREDENTIAL)?.let(::attrs) ?: return null
        val ssid = cred[SSID]?.toString(Charsets.UTF_8) ?: return null
        val auth = cred[AUTH_TYPE]?.takeIf { it.size == 2 }?.let { (it[0].toInt() and 0xFF shl 8) or (it[1].toInt() and 0xFF) }
        val security = when (auth) {
            0x0001 -> "Ouvert"
            0x0002 -> "WPA-Personnel"
            0x0020 -> "WPA2-Personnel"
            0x0022 -> "WPA/WPA2-Personnel"
            0x0008, 0x0010 -> "Entreprise (EAP)"
            0x0004 -> "WEP partagé"
            else -> "Inconnue"
        }
        return WifiTagContent(ssid, security, (cred[NETWORK_KEY]?.size ?: 0) > 0)
    }

    /** Null when the fields can be written; otherwise what to fix. */
    fun validate(ssid: String, key: String, security: WifiTagSecurity): String? = when {
        ssid.isEmpty() -> "Entrez le nom du réseau (SSID)"
        ssid.toByteArray(Charsets.UTF_8).size > 32 -> "Le SSID ne peut pas dépasser 32 octets"
        !security.needsKey -> null
        key.length == 64 && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> null
        key.length !in 8..63 -> "Le mot de passe WPA doit faire 8 à 63 caractères"
        else -> null
    }

    /** Best tag security for what Android reports about the current connection. */
    fun securityFor(connectionSecurity: String?): WifiTagSecurity = when {
        connectionSecurity == null -> WifiTagSecurity.Wpa2
        connectionSecurity.startsWith("Ouvert") || "OWE" in connectionSecurity -> WifiTagSecurity.Open
        else -> WifiTagSecurity.Wpa2
    }
}
