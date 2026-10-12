package com.allnetworktools.data

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

/** What a Find My accessory tells once connected: its name (Generic Access 0x2A00) and the services it offers. */
data class FindMyProbe(val name: String?, val airTagService: Boolean)

/**
 * Connects to a Find My accessory, reads its device name and disconnects, as AirGuard does to tell an AirTag from
 * other accessories: recent AirTag firmware announces itself as a plain Find My accessory in its advertisements.
 * Nothing is written to the tag.
 */
suspend fun probeFindMy(context: Context, address: String, timeoutMs: Long = 15_000): FindMyProbe? {
    val client = GattClient(context, address)
    return try {
        withTimeoutOrNull(timeoutMs) {
            client.connect(8_000)
            val services = client.discover()
            val name = services.firstOrNull { GattNames.uuid16(it.uuid) == 0x1800 }?.getCharacteristic(GattNames.uuid(0x2A00))
                ?.let { c -> runCatching { client.read(c) }.getOrNull() }?.let(BleIdentifier::text)
            FindMyProbe(name, services.any { it.uuid == SoundProtocol.AirTag.service })
        }
    } catch (_: GattException) {
        null
    } finally {
        client.close()
    }
}
