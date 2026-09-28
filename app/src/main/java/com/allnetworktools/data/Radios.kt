package com.allnetworktools.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits [read] now and again every time one of [actions] is broadcast. */
fun <T> broadcastFlow(context: Context, vararg actions: String, read: () -> T): Flow<T> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            trySend(read())
        }
    }
    val filter = IntentFilter().apply { actions.forEach(::addAction) }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(read())
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/** On/off state of the radios and of location services. */
open class RadiosRepository(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val location = context.getSystemService(LocationManager::class.java)

    open val hasWifi = wifi != null
    open val hasBluetooth = bt != null

    open val wifiEnabled: Flow<Boolean> = broadcastFlow(context, WifiManager.WIFI_STATE_CHANGED_ACTION) { wifi?.isWifiEnabled == true }

    open val bluetoothEnabled: Flow<Boolean> = broadcastFlow(context, BluetoothAdapter.ACTION_STATE_CHANGED) { bt?.isEnabled == true }

    open val airplaneMode: Flow<Boolean> = broadcastFlow(context, Intent.ACTION_AIRPLANE_MODE_CHANGED) {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }

    open val locationEnabled: Flow<Boolean> = broadcastFlow(context, LocationManager.MODE_CHANGED_ACTION, LocationManager.PROVIDERS_CHANGED_ACTION) {
        location?.isLocationEnabled == true
    }
}
