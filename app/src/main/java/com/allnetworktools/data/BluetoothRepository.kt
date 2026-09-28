package com.allnetworktools.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.util.isNotEmpty
import com.allnetworktools.ui.theme.Sym
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class BleKind(val label: String, val filter: String) {
    Audio("Audio", "Audio"),
    Watch("Montre", "Montres"),
    Beacon("Balise", "Balises"),
    Unknown("Inconnu", "Inconnu"),
}

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val kind: BleKind,
    val maker: String?,
    val connectable: Boolean,
    val lastSeen: Long,
    /** 16-bit (or full) service UUIDs from the advertisement. */
    val services: List<String> = emptyList(),
    val manufacturerHex: String? = null,
    val flags: Int? = null,
    /** Mean gap between received advertisements, a lower bound of the real interval. */
    val intervalMs: Int? = null,
) {
    val displayName: String get() = name ?: "Inconnu"

    /** Stable pseudo-angle so a device keeps its place on the radar. */
    val angle: Float get() = (abs(address.hashCode()) % 360).toFloat()

    val icon: String
        get() = when (kind) {
            BleKind.Audio -> if (name?.contains("speaker", true) == true || name?.contains("flip", true) == true) Sym.Speaker else Sym.Headphones
            BleKind.Watch -> Sym.Watch
            BleKind.Beacon -> Sym.Sell
            BleKind.Unknown -> Sym.Bluetooth
        }
}

data class AdapterInfo(
    val name: String?,
    val features: List<String>,
)

data class BondedDevice(
    val address: String,
    val name: String,
    val icon: String,
    val kind: String,
    val connected: Boolean,
    val profiles: List<String>,
    /** Classique, LE or Double mode. */
    val transport: String = "Classique",
    /** Battery reported over HFP/BAS, when the system exposes it. */
    val battery: Int? = null,
)

data class BluetoothSnapshot(
    val adapter: AdapterInfo,
    val bonded: List<BondedDevice>,
) {
    val connected get() = bonded.filter { it.connected }
}

private val Companies = mapOf(
    0x004C to "Apple, Inc.", 0x00E0 to "Google", 0x0006 to "Microsoft", 0x0075 to "Samsung",
    0x038F to "Xiaomi", 0x0157 to "Huami", 0x0087 to "Garmin", 0x009E to "Bose", 0x0057 to "Harman",
    0x012D to "Sony", 0x0171 to "Amazon", 0x02E5 to "Espressif", 0x0059 to "Nordic Semiconductor",
    0x000D to "Texas Instruments", 0x010F to "Huawei", 0x027D to "Huawei", 0x0822 to "Tile",
    0x067C to "Tile", 0x0499 to "Ruuvi", 0x0110 to "Nippon Seiki", 0x00D2 to "Realtek",
    0x0310 to "SGL Italia", 0x0590 to "Oppo", 0x072F to "OnePlus", 0x058E to "Meta", 0x0131 to "Cypress",
    0x0078 to "Nike", 0x00C4 to "LG Electronics", 0x0002 to "Intel", 0x001D to "Qualcomm",
    0x000A to "Qualcomm", 0x0046 to "MediaTek", 0x0030 to "ST Microelectronics", 0x02FF to "Fitbit",
)

private fun uuid16(u: ParcelUuid): Int? {
    val s = u.uuid.toString()
    return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).toIntOrNull(16) else null
}

private val AudioUuids = setOf(0x110B, 0x110A, 0x110D, 0x111E, 0x1108, 0x184E, 0x1850, 0x1853, 0x184F, 0x1844, 0xFE2C, 0xFD82)
private val WatchUuids = setOf(0x180D, 0x1814, 0x1816, 0x183E, 0xFEE0, 0xFE07)
private val BeaconUuids = setOf(0xFEAA, 0xFEED, 0xFEEC, 0xFD5A, 0xFD59, 0xFD44, 0xFE33)

@SuppressLint("MissingPermission")
private fun classify(r: ScanResult): Pair<BleKind, String?> {
    val record = r.scanRecord
    val name = (record?.deviceName ?: runCatching { r.device.name }.getOrNull())?.lowercase().orEmpty()
    val uuids = record?.serviceUuids.orEmpty().mapNotNull(::uuid16).toSet()
    val mfg = record?.manufacturerSpecificData
    val companyId = if (mfg != null && mfg.isNotEmpty()) mfg.keyAt(0) else null
    val maker = companyId?.let { Companies[it] }
    val mfgData = companyId?.let { mfg?.get(it) }
    val iBeacon = companyId == 0x004C && mfgData != null && mfgData.size >= 2 && mfgData[0] == 0x02.toByte() && mfgData[1] == 0x15.toByte()
    val cls = runCatching { r.device.bluetoothClass?.majorDeviceClass }.getOrNull()
    val kind = when {
        iBeacon || uuids.any { it in BeaconUuids } || listOf("tile", "tag", "beacon", "airtag", "chipolo", "nut").any { it in name } -> BleKind.Beacon
        cls == BluetoothClass.Device.Major.AUDIO_VIDEO || uuids.any { it in AudioUuids } ||
            listOf("buds", "airpods", "headphone", "jbl", "bose", "wh-", "wf-", "speaker", "sony", "beats", "qc", "earbuds", "soundcore").any { it in name } -> BleKind.Audio
        cls == BluetoothClass.Device.Major.WEARABLE || cls == BluetoothClass.Device.Major.HEALTH || uuids.any { it in WatchUuids } ||
            listOf("watch", "band", "fitbit", "garmin", "amazfit", "forerunner", "fenix", "whoop").any { it in name } -> BleKind.Watch
        else -> BleKind.Unknown
    }
    return kind to maker
}

@SuppressLint("MissingPermission")
open class BluetoothRepository(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val profileIds = buildList {
        add(BluetoothProfile.A2DP to "A2DP")
        add(BluetoothProfile.HEADSET to "HFP")
        add(BluetoothProfile.HEARING_AID to "ASHA")
        if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO to "LE Audio")
        add(BluetoothProfile.HID_DEVICE to "HID")
    }

    private fun adapterInfo(): AdapterInfo {
        val a = adapter ?: return AdapterInfo(null, emptyList())
        val features = buildList {
            add(if (a.isLe2MPhySupported || a.isLeExtendedAdvertisingSupported) "Bluetooth 5" else "Bluetooth 4")
            if (Build.VERSION.SDK_INT >= 33) {
                if (a.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED) add("LE Audio")
                if (a.isLeAudioBroadcastSourceSupported == BluetoothStatusCodes.FEATURE_SUPPORTED) add("Auracast")
            }
            if (a.isLeCodedPhySupported) add("Longue portée")
        }
        return AdapterInfo(runCatching { a.name }.getOrNull(), features)
    }

    /** Adapter details, bonded devices and which of them are connected, refreshed on ACL and profile changes. */
    open val snapshot: Flow<BluetoothSnapshot> = callbackFlow {
        val a = adapter
        if (a == null) {
            trySend(BluetoothSnapshot(AdapterInfo(null, emptyList()), emptyList())); awaitClose { }; return@callbackFlow
        }
        val proxies = mutableMapOf<Int, BluetoothProfile>()
        fun emit() {
            val bonded = runCatching { a.bondedDevices }.getOrNull().orEmpty()
            val gatt = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList()).map { it.address }.toSet()
            val profilesByDevice = mutableMapOf<String, MutableList<String>>()
            profileIds.forEach { (id, label) ->
                runCatching { proxies[id]?.connectedDevices }.getOrNull()?.forEach { d ->
                    profilesByDevice.getOrPut(d.address) { mutableListOf() }.add(label)
                }
            }
            val list = bonded.map { d ->
                val profiles = profilesByDevice[d.address].orEmpty()
                val isConnected = profiles.isNotEmpty() || d.address in gatt || isAclConnected(d)
                val (icon, kind) = describeClass(d)
                BondedDevice(
                    address = d.address,
                    name = runCatching { d.alias ?: d.name }.getOrNull() ?: d.address,
                    icon = icon,
                    kind = kind,
                    connected = isConnected,
                    profiles = profiles + if (d.address in gatt) listOf("GATT") else emptyList(),
                    transport = when (runCatching { d.type }.getOrDefault(0)) {
                        BluetoothDevice.DEVICE_TYPE_LE -> "Bluetooth LE"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "Double mode"
                        else -> "Classique (BR/EDR)"
                    },
                    battery = if (isConnected) batteryLevel(d) else null,
                )
            }.sortedWith(compareByDescending<BondedDevice> { it.connected }.thenBy { it.name.lowercase() })
            trySend(BluetoothSnapshot(adapterInfo(), list))
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy; emit()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile); emit()
            }
        }
        profileIds.forEach { (id, _) -> runCatching { a.getProfileProxy(context, listener, id) } }
        val refresh = launch {
            broadcastFlow(
                context,
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED, BluetoothDevice.ACTION_NAME_CHANGED,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED",
            ) { SystemClock.elapsedRealtimeNanos() }.collect { emit() }
        }
        emit()
        awaitClose {
            refresh.cancel()
            proxies.forEach { (id, p) -> runCatching { a.closeProfileProxy(id, p) } }
        }
    }

    /** Hidden but greylisted getter fed by HFP and the LE battery service; null when unknown or blocked. */
    private fun batteryLevel(d: BluetoothDevice): Int? =
        runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrNull()?.takeIf { it in 0..100 }

    /** Asks the stack to drop the bond; hidden API, so callers fall back to system settings when it returns false. */
    open fun forget(address: String): Boolean = runCatching {
        val d = adapter?.getRemoteDevice(address) ?: return false
        d.javaClass.getMethod("removeBond").invoke(d) as Boolean
    }.getOrDefault(false)

    private fun isAclConnected(d: BluetoothDevice): Boolean =
        runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)

    private fun describeClass(d: BluetoothDevice): Pair<String, String> {
        val cls = runCatching { d.bluetoothClass }.getOrNull() ?: return Sym.Bluetooth to "Appareil"
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Sym.Speaker to "Enceinte"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Sym.DirectionsCar to "Voiture"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> Sym.Tv to "Écran"
                else -> Sym.Headphones to "Audio"
            }
            BluetoothClass.Device.Major.WEARABLE, BluetoothClass.Device.Major.HEALTH -> Sym.Watch to "Montre"
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                cls.deviceClass and 0x40 != 0 -> Sym.Keyboard to "Clavier"
                cls.deviceClass and 0x80 != 0 -> Sym.Mouse to "Souris"
                else -> Sym.Gamepad to "Périphérique"
            }
            BluetoothClass.Device.Major.COMPUTER -> Sym.Laptop to "Ordinateur"
            BluetoothClass.Device.Major.PHONE -> Sym.Smartphone to "Téléphone"
            BluetoothClass.Device.Major.IMAGING -> Sym.Print to "Imprimante"
            else -> Sym.Bluetooth to "Appareil"
        }
    }

    /**
     * Live BLE advertisements, merged per address and dropped after [staleMs] without a packet.
     * Emits a snapshot at least every second so RSSI and staleness stay current.
     */
    open fun bleScan(lowPower: Boolean, staleMs: Long = 15_000): Flow<List<BleDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            trySend(emptyList()); awaitClose { }; return@callbackFlow
        }
        val devices = mutableMapOf<String, BleDevice>()
        fun snapshot(): List<BleDevice> {
            val now = SystemClock.elapsedRealtime()
            devices.values.removeAll { now - it.lastSeen > staleMs }
            return devices.values.sortedByDescending { it.rssi }
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val (kind, maker) = classify(result)
                val prev = devices[result.device.address]
                val now = SystemClock.elapsedRealtime()
                val record = result.scanRecord
                val gap = prev?.let { (now - it.lastSeen).toInt() }?.takeIf { it in 5..10_000 }
                val mfg = record?.manufacturerSpecificData
                val smoothed = if (prev == null) result.rssi else ((prev.rssi * 0.6f) + result.rssi * 0.4f).toInt()
                devices[result.device.address] = BleDevice(
                    address = result.device.address,
                    name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: prev?.name,
                    rssi = smoothed,
                    txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT } ?: result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
                    kind = if (kind == BleKind.Unknown) prev?.kind ?: kind else kind,
                    maker = maker ?: prev?.maker,
                    connectable = result.isConnectable,
                    lastSeen = now,
                    services = record?.serviceUuids.orEmpty().map { u -> uuid16(u)?.let { "0x%04X".format(it) } ?: u.uuid.toString() }
                        .ifEmpty { prev?.services.orEmpty() },
                    manufacturerHex = if (mfg != null && mfg.isNotEmpty()) {
                        "%04X ".format(mfg.keyAt(0)) + mfg.valueAt(0).joinToString(" ") { "%02X".format(it) }
                    } else prev?.manufacturerHex,
                    flags = record?.advertiseFlags?.takeIf { it >= 0 } ?: prev?.flags,
                    intervalMs = when {
                        gap == null -> prev?.intervalMs
                        prev?.intervalMs == null -> gap
                        else -> (prev.intervalMs * 0.8f + gap * 0.2f).toInt()
                    },
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }.onFailure { close(it) }
        val ticker = launch {
            while (true) {
                trySend(snapshot())
                delay(1000)
            }
        }
        awaitClose {
            ticker.cancel()
            runCatching { scanner.stopScan(callback) }
        }
    }
}
