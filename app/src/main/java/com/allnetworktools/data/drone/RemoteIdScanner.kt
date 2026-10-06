package com.allnetworktools.data.drone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** Which of the phone's radios are listening for Remote ID. */
data class RidRadios(val bluetooth: Boolean, val wifi: Boolean)

/**
 * Listens for Remote ID with the phone's own radios: Bluetooth advertisements (legacy and long range) and the vendor
 * element of Wi-Fi beacons, which is how DJI drones send it. Wi-Fi is limited by Android to 4 scans every 2 minutes, so
 * Bluetooth gives the faster updates. Nothing is stored.
 */
@SuppressLint("MissingPermission")
open class RemoteIdScanner(private val context: Context) {
    private val bt get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val wifi get() = context.getSystemService(WifiManager::class.java)

    open fun radios(): RidRadios = RidRadios(
        bluetooth = runCatching { bt?.isEnabled == true && bt?.bluetoothLeScanner != null }.getOrDefault(false),
        wifi = runCatching { wifi?.isWifiEnabled == true || wifi?.isScanAlwaysAvailable == true }.getOrDefault(false),
    )

    open fun frames(): Flow<RidFrame> = callbackFlow {
        val uuid = ParcelUuid.fromString(RemoteId.BLE_UUID)
        val scanner = runCatching { bt?.takeIf { it.isEnabled }?.bluetoothLeScanner }.getOrNull()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(uuid) ?: return
                val msgs = RemoteId.fromBleServiceData(data)
                if (msgs.isNotEmpty()) trySend(RidFrame(result.device.address, RidTransport.Bluetooth, result.rssi, msgs))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }
        }
        if (scanner != null) {
            val filter = ScanFilter.Builder().setServiceData(uuid, byteArrayOf(RemoteId.APP_CODE.toByte()), byteArrayOf(0xFF.toByte())).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                .build()
            runCatching { scanner.startScan(listOf(filter), settings, callback) }
        }

        val mgr = wifi
        fun readWifi() {
            val results = runCatching { mgr?.scanResults }.getOrNull() ?: return
            for (r in results) {
                val msgs = r.informationElements.filter { it.id == VENDOR_ELEMENT }.flatMap { ie ->
                    val buf = ie.bytes.duplicate()
                    RemoteId.fromWifiVendorElement(ByteArray(buf.remaining()).also { buf.get(it) })
                }
                if (msgs.isNotEmpty()) trySend(RidFrame(r.BSSID ?: continue, RidTransport.Wifi, r.level, msgs))
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = readWifi()
        }
        if (mgr != null) {
            context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
            readWifi()
        }
        // One scan every 30 s stays inside Android's allowance of 4 per 2 minutes.
        val pacer = launch {
            while (true) {
                @Suppress("DEPRECATION") runCatching { mgr?.startScan() }
                delay(30_000)
            }
        }
        awaitClose {
            pacer.cancel()
            if (mgr != null) runCatching { context.unregisterReceiver(receiver) }
            if (scanner != null) runCatching { scanner.stopScan(callback) }
        }
    }

    private companion object {
        const val VENDOR_ELEMENT = 221
    }
}
