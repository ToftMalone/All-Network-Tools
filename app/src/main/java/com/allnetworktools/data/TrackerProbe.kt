package com.allnetworktools.data

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a Find My accessory tells once connected: its name (Generic Access 0x2A00), model (Device Information
 * 0x2A24) and whether it offers the first-generation AirTag service. [error] says why nothing could be read.
 */
data class FindMyProbe(val name: String?, val model: String?, val airTagService: Boolean, val services: Int, val error: String? = null)

/**
 * Connects to a Find My accessory, reads what identifies it and disconnects, as AirGuard does to tell an AirTag from
 * other accessories: AirTags often announce themselves as a plain Find My accessory in their advertisements.
 * Nothing is written to the tag.
 */
suspend fun probeFindMy(context: Context, address: String, timeoutMs: Long = 20_000): FindMyProbe {
    val client = GattClient(context, address)
    return try {
        withTimeoutOrNull(timeoutMs) {
            client.connect(10_000)
            val services = client.discover()
            suspend fun read(service: Int, char: Int): String? =
                services.firstOrNull { GattNames.uuid16(it.uuid) == service }?.getCharacteristic(GattNames.uuid(char))
                    ?.let { c -> runCatching { client.read(c) }.getOrNull() }?.let(BleIdentifier::text)
            FindMyProbe(read(0x1800, 0x2A00), read(0x180A, 0x2A24), services.any { it.uuid == SoundProtocol.AirTag.service }, services.size)
        } ?: FindMyProbe(null, null, false, 0, "pas de réponse en ${timeoutMs / 1000} s")
    } catch (e: GattException) {
        FindMyProbe(null, null, false, 0, e.message ?: "erreur GATT ${e.status}")
    } catch (e: SecurityException) {
        FindMyProbe(null, null, false, 0, "autorisation Bluetooth manquante")
    } finally {
        client.close()
    }
}
