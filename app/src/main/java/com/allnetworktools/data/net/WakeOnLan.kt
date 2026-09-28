package com.allnetworktools.data.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WakeOnLan {
    fun parseMac(text: String): ByteArray? {
        val hex = text.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun format(mac: ByteArray) = mac.joinToString(":") { "%02x".format(it) }

    private fun broadcast(ip: String, prefix: Int): InetAddress {
        val p = ip.split('.').map { it.toInt() }
        val v = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
        val b = v or (-1 ushr prefix.coerceIn(0, 32))
        return InetAddress.getByName("${(b ushr 24) and 255}.${(b ushr 16) and 255}.${(b ushr 8) and 255}.${b and 255}")
    }

    /** Sends the magic packet (6 × FF then 16 × MAC) to the subnet broadcast on UDP 9 and 7. */
    suspend fun send(macText: String, ip: String, prefix: Int): Boolean = withContext(Dispatchers.IO) {
        val mac = parseMac(macText) ?: return@withContext false
        val payload = ByteArray(6) { 0xFF.toByte() } + (0 until 16).flatMap { mac.toList() }.toByteArray()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                val to = broadcast(ip, prefix)
                listOf(9, 7).forEach { port -> s.send(DatagramPacket(payload, payload.size, to, port)) }
            }
        }.isSuccess
    }
}
