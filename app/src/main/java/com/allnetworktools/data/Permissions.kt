package com.allnetworktools.data

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PermGroup(val permissions: List<String>) {
    Location(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Nearby(
        buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        },
    ),
    Phone(listOf(Manifest.permission.READ_PHONE_STATE)),

    /** Only for the BLE advertiser tool. */
    Advertise(listOf(Manifest.permission.BLUETOOTH_ADVERTISE)),

    /** Special app-op, granted from the system "Usage access" screen. */
    UsageAccess(emptyList()),
}

data class PermissionSnapshot(val granted: Set<PermGroup>) {
    operator fun contains(g: PermGroup) = g in granted
    val location get() = PermGroup.Location in granted
    val nearby get() = PermGroup.Nearby in granted
    val phone get() = PermGroup.Phone in granted
}

open class PermissionsRepository(private val context: Context) {
    private val _state = MutableStateFlow(read())
    open val state: StateFlow<PermissionSnapshot> = _state.asStateFlow()

    open fun refresh() {
        _state.value = read()
    }

    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun read(): PermissionSnapshot {
        val granted = PermGroup.entries.filter { g ->
            when (g) {
                PermGroup.UsageAccess -> usageAccessGranted()
                PermGroup.Location -> has(Manifest.permission.ACCESS_FINE_LOCATION)
                else -> g.permissions.all(::has)
            }
        }.toSet()
        return PermissionSnapshot(granted)
    }

    private fun usageAccessGranted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
