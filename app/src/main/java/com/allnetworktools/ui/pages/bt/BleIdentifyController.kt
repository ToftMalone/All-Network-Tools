package com.allnetworktools.ui.pages.bt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BluetoothRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Connects one after the other to the unidentified devices nearby to read their name, appearance
 * and Device Information. Read-only, short, and started by the user only.
 */
class BleIdentifyController(private val scope: CoroutineScope, private val repo: BluetoothRepository) {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var found by mutableIntStateOf(0)
    var current by mutableStateOf<String?>(null)
    var finished by mutableStateOf(false)
    private var job: Job? = null

    /** Devices worth a connection: unidentified or guessed, connectable, close enough to answer. */
    fun candidates(devices: List<BleDevice>): List<BleDevice> = devices
        .filter { (it.isUnknown || it.guessed) && it.connectable && it.rssi >= -90 && !it.fromGatt && repo.identities.get(it.address) == null }
        .sortedByDescending { it.rssi }
        .take(MaxPerRun)

    fun start(devices: List<BleDevice>) {
        if (running) return
        val list = candidates(devices)
        if (list.isEmpty()) return
        running = true; finished = false; done = 0; found = 0; total = list.size
        job = scope.launch {
            try {
                for (d in list) {
                    current = d.title
                    val id = repo.identify(d.address)
                    if (id != null && (id.name != null || id.manufacturer != null || id.model != null || id.appearance != null || id.services.isNotEmpty())) found++
                    done++
                }
            } finally {
                running = false; current = null; finished = true
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    companion object {
        const val MaxPerRun = 10
    }
}
