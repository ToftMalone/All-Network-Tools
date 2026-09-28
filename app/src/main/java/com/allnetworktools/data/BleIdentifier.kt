package com.allnetworktools.data

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active identification: connects, reads the Generic Access and Device Information
 * characteristics (name, appearance, manufacturer, model, firmware) and disconnects.
 * Nothing is written to the device.
 */
class BleIdentifier(private val context: Context, private val store: BleIdentityStore) {
    suspend fun identify(address: String, timeoutMs: Long = 20_000): GattIdentity? {
        val client = GattClient(context, address)
        return try {
            withTimeoutOrNull(timeoutMs) {
                client.connect(7_000)
                val services = client.discover()
                fun ch(service: Int, char: Int) = services.firstOrNull { GattNames.uuid16(it.uuid) == service }?.getCharacteristic(GattNames.uuid(char))
                suspend fun read(service: Int, char: Int): ByteArray? = ch(service, char)?.let { c ->
                    runCatching { client.read(c) }.getOrNull()
                }
                val id = GattIdentity(
                    name = read(0x1800, 0x2A00)?.let(::text),
                    appearance = read(0x1800, 0x2A01)?.takeIf { it.size >= 2 }?.u16le(0),
                    manufacturer = read(0x180A, 0x2A29)?.let(::text),
                    model = read(0x180A, 0x2A24)?.let(::text),
                    firmware = read(0x180A, 0x2A26)?.let(::text),
                    services = services.mapNotNull { GattNames.uuid16(it.uuid) }.toSet(),
                )
                store.put(address, id)
                id
            }
        } catch (_: GattException) {
            null
        } finally {
            client.close()
        }
    }

    companion object {
        fun text(b: ByteArray): String? {
            val s = String(b, Charsets.UTF_8).trim { it.isWhitespace() || it == '\u0000' }
            return s.takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() } }
        }
    }
}
