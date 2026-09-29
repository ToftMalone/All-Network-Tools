package com.allnetworktools.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AdvPreset(val label: String) { IBeacon("iBeacon"), Eddystone("Eddystone"), Manufacturer("Fabricant"), Service("Service") }

enum class AdvPower(val label: String, val level: Int) {
    UltraLow("Très faible", AdvertisingSetParameters.TX_POWER_ULTRA_LOW),
    Low("Faible", AdvertisingSetParameters.TX_POWER_LOW),
    Medium("Moyenne", AdvertisingSetParameters.TX_POWER_MEDIUM),
    High("Forte", AdvertisingSetParameters.TX_POWER_HIGH),
}

enum class AdvInterval(val label: String, val units: Int) {
    Fast("100 ms", AdvertisingSetParameters.INTERVAL_LOW),
    Balanced("250 ms", AdvertisingSetParameters.INTERVAL_MEDIUM),
    Slow("1 s", AdvertisingSetParameters.INTERVAL_HIGH),
}

data class AdvConfig(
    val preset: AdvPreset = AdvPreset.IBeacon,
    val beaconUuid: String = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
    val major: Int = 1,
    val minor: Int = 1,
    val measuredPower: Int = -59,
    val url: String = "https://example.com",
    val companyId: Int = 0xFFFF,
    val payloadHex: String = "0102030405",
    val serviceUuid: String = "FFF0",
    val serviceDataHex: String = "",
    val includeName: Boolean = false,
    val connectable: Boolean = false,
    val power: AdvPower = AdvPower.Medium,
    val interval: AdvInterval = AdvInterval.Balanced,
)

/** Builds the advertisement of a configuration; errors are reported in French for the UI. */
object AdvBuilder {
    private val BaseUuid = "-0000-1000-8000-00805F9B34FB"

    fun hexBytes(hex: String): ByteArray? {
        val h = hex.filter { !it.isWhitespace() && it != ':' && it != '-' }
        if (h.length % 2 != 0 || h.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** "FFF0", "0xFFF0" or a full 128-bit UUID. */
    fun serviceUuid(s: String): UUID? {
        val t = s.trim().removePrefix("0x").removePrefix("0X")
        return runCatching {
            when {
                t.length == 4 && t.all { it.isLetterOrDigit() } -> UUID.fromString("0000${t.uppercase()}$BaseUuid")
                t.length == 8 && t.all { it.isLetterOrDigit() } -> UUID.fromString("${t.uppercase()}$BaseUuid")
                else -> UUID.fromString(t)
            }
        }.getOrNull()
    }

    private val Schemes = listOf("http://www.", "https://www.", "http://", "https://")
    private val Expansions = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    /** Eddystone-URL encoding: scheme byte then the URL with the standard expansion codes. */
    fun eddystoneUrl(url: String): ByteArray? {
        val scheme = Schemes.indexOfFirst { url.startsWith(it, ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        var rest = url.substring(Schemes[scheme].length)
        val out = mutableListOf(scheme.toByte())
        while (rest.isNotEmpty()) {
            val e = Expansions.indexOfFirst { rest.startsWith(it, ignoreCase = true) }
            if (e >= 0) {
                out += e.toByte()
                rest = rest.substring(Expansions[e].length)
            } else {
                val ch = rest[0]
                if (ch.code !in 0x21..0x7E) return null
                out += ch.code.toByte()
                rest = rest.substring(1)
            }
        }
        return out.toByteArray().takeIf { it.size <= 18 }
    }

    /** The AD structures that will be broadcast (flags excluded: the stack adds them when connectable). */
    fun structures(c: AdvConfig, deviceName: String?): Result<List<AdStructure>> = runCatching {
        buildList {
            when (c.preset) {
                AdvPreset.IBeacon -> {
                    val u = runCatching { UUID.fromString(c.beaconUuid.trim()) }.getOrNull() ?: error("UUID iBeacon invalide")
                    require(c.major in 0..65535 && c.minor in 0..65535) { "Major et minor vont de 0 à 65535" }
                    val d = java.nio.ByteBuffer.allocate(25).order(java.nio.ByteOrder.BIG_ENDIAN)
                    d.put(0x4C).put(0x00).put(0x02).put(0x15)
                    d.putLong(u.mostSignificantBits).putLong(u.leastSignificantBits)
                    d.putShort(c.major.toShort()).putShort(c.minor.toShort()).put(c.measuredPower.toByte())
                    add(AdStructure(Ad.MANUFACTURER, d.array()))
                }
                AdvPreset.Eddystone -> {
                    val enc = eddystoneUrl(c.url.trim()) ?: error("URL trop longue ou invalide (http:// ou https://, 17 caractères encodés au plus)")
                    add(AdStructure(Ad.UUID16, byteArrayOf(0xAA.toByte(), 0xFE.toByte())))
                    add(AdStructure(Ad.SERVICE_DATA16, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x10, (-20).toByte()) + enc))
                }
                AdvPreset.Manufacturer -> {
                    require(c.companyId in 0..0xFFFF) { "Identifiant fabricant sur 16 bits" }
                    val p = hexBytes(c.payloadHex) ?: error("Données en hexadécimal (ex. 01 02 A0)")
                    add(AdStructure(Ad.MANUFACTURER, byteArrayOf((c.companyId and 0xFF).toByte(), (c.companyId shr 8).toByte()) + p))
                }
                AdvPreset.Service -> {
                    val u = serviceUuid(c.serviceUuid) ?: error("UUID de service invalide")
                    val short = u.toString().uppercase().endsWith(BaseUuid) && u.toString().startsWith("0000")
                    val data = if (c.serviceDataHex.isBlank()) null else hexBytes(c.serviceDataHex) ?: error("Données de service en hexadécimal")
                    if (short) {
                        val v = u.toString().substring(4, 8).toInt(16)
                        val le = byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte())
                        add(AdStructure(Ad.UUID16, le))
                        if (data != null) add(AdStructure(Ad.SERVICE_DATA16, le + data))
                    } else {
                        val b = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        b.putLong(u.leastSignificantBits).putLong(u.mostSignificantBits)
                        add(AdStructure(Ad.UUID128, b.array()))
                        if (data != null) add(AdStructure(Ad.SERVICE_DATA128, b.array() + data))
                    }
                }
            }
            if (c.includeName && !deviceName.isNullOrEmpty()) add(AdStructure(Ad.NAME, deviceName.toByteArray()))
        }
    }

    /** Legacy advertising carries 31 bytes, 3 of which go to the flags of a connectable advertisement. */
    fun size(structs: List<AdStructure>, connectable: Boolean) = structs.sumOf { it.data.size + 2 } + if (connectable) 3 else 0
}

sealed interface AdvState {
    data object Idle : AdvState
    data object Starting : AdvState
    data class On(val startedAtMs: Long, val txPowerDbm: Int) : AdvState
    data class Failed(val message: String) : AdvState
}

@SuppressLint("MissingPermission")
open class BleAdvertiser(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _state = MutableStateFlow<AdvState>(AdvState.Idle)
    open val state: StateFlow<AdvState> = _state.asStateFlow()
    private var callback: AdvertisingSetCallback? = null

    open val supported: Boolean get() = adapter?.bluetoothLeAdvertiser != null || adapter?.isMultipleAdvertisementSupported == true

    open fun deviceName(): String? = runCatching { adapter?.name }.getOrNull()

    open fun start(c: AdvConfig) {
        stop()
        val adv = adapter?.bluetoothLeAdvertiser ?: run { _state.value = AdvState.Failed("L'émission BLE n'est pas disponible (Bluetooth éteint ou non pris en charge)"); return }
        val structs = AdvBuilder.structures(c, deviceName()).getOrElse { _state.value = AdvState.Failed(it.message ?: "Configuration invalide"); return }
        if (AdvBuilder.size(structs, c.connectable) > 31) {
            _state.value = AdvState.Failed("La trame dépasse 31 octets : raccourcissez les données ou retirez le nom")
            return
        }
        val data = AdvertiseData.Builder().setIncludeDeviceName(c.includeName).setIncludeTxPowerLevel(false)
        structs.forEach { s ->
            when (s.type) {
                Ad.MANUFACTURER -> data.addManufacturerData(s.data.u16le(0), s.data.copyOfRange(2, s.data.size))
                Ad.UUID16 -> data.addServiceUuid(ParcelUuid.fromString("0000%04X$BaseSuffix".format(s.data.u16le(0))))
                Ad.UUID128 -> data.addServiceUuid(ParcelUuid(Ad.uuid128(s.data, 0).let(UUID::fromString)))
                Ad.SERVICE_DATA16 -> data.addServiceData(ParcelUuid.fromString("0000%04X$BaseSuffix".format(s.data.u16le(0))), s.data.copyOfRange(2, s.data.size))
                Ad.SERVICE_DATA128 -> data.addServiceData(ParcelUuid(UUID.fromString(Ad.uuid128(s.data, 0))), s.data.copyOfRange(16, s.data.size))
                else -> Unit
            }
        }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(c.connectable)
            .setScannable(c.connectable)
            .setInterval(c.interval.units)
            .setTxPowerLevel(c.power.level)
            .build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                _state.value = if (status == ADVERTISE_SUCCESS) AdvState.On(System.currentTimeMillis(), txPower) else AdvState.Failed(error(status))
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                if (_state.value is AdvState.On) _state.value = AdvState.Idle
            }
        }
        callback = cb
        _state.value = AdvState.Starting
        try {
            adv.startAdvertisingSet(params, data.build(), null, null, null, cb)
        } catch (e: Exception) {
            callback = null
            _state.value = AdvState.Failed(e.message ?: "Démarrage refusé")
        }
    }

    open fun stop() {
        val cb = callback ?: return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
        callback = null
        _state.value = AdvState.Idle
    }

    private fun error(status: Int) = when (status) {
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Trame trop grande pour ce téléphone"
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Trop d'émissions BLE en cours sur le téléphone"
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Émission BLE non prise en charge par ce téléphone"
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Émission déjà en cours"
        else -> "Erreur interne du Bluetooth ($status)"
    }

    private companion object {
        const val BaseSuffix = "-0000-1000-8000-00805F9B34FB"
    }
}
