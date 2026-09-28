package com.allnetworktools

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.allnetworktools.data.AppSettings
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BluetoothSnapshot
import com.allnetworktools.data.AdapterInfo
import com.allnetworktools.data.CellState
import com.allnetworktools.data.CompassReading
import com.allnetworktools.data.GnssState
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.PermissionSnapshot
import com.allnetworktools.data.SimInfo
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why a network card or page is unavailable. */
enum class Blocker { NoHardware, WifiOff, BluetoothOff, NearbyPermission, Airplane, PhonePermission, NoSim, LocationPermission, LocationOff }

sealed interface Page {
    data object Home : Page
    data object Dashboard : Page
    data object Tools : Page
    data class ToolPage(val tool: Tool, val arg: String? = null) : Page
    data object Settings : Page
}

data class NavState(
    val network: Network? = null,
    val page: Page = Page.Home,
    val switcherOpen: Boolean = false,
)

data class WifiUi(val connection: WifiConnection?, val history: List<Float>)
data class CellUi(val state: CellState?, val history: List<Float>)

private fun tickerFlow(periodMs: Long): Flow<Long> = flow {
    while (true) {
        emit(SystemClock.elapsedRealtime())
        delay(periodMs)
    }
}

/** Samples [value] of the latest upstream item every [rate] ms into a sliding window. */
private fun <T> Flow<T>.sampledHistory(rate: Flow<Long>, capacity: Int, value: (T) -> Float?): Flow<List<Float>> = channelFlow {
    var latest: T? = null
    val buffer = ArrayDeque<Float>()
    launch { this@sampledHistory.collect { latest = it } }
    rate.collectLatest { period ->
        while (true) {
            latest?.let(value)?.let {
                buffer.addLast(it)
                while (buffer.size > capacity) buffer.removeFirst()
                send(buffer.toList())
            }
            delay(period)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app as AntApplication
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val settings: StateFlow<AppSettings?> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val rate: Flow<Long> = g.settings.settings.map { it.refreshMillis }.distinctUntilChanged()

    val permissions: StateFlow<PermissionSnapshot> = g.permissions.state
    fun refreshPermissions() = g.permissions.refresh()

    val wifiEnabled = g.radios.wifiEnabled.stateIn(viewModelScope, sharing, true)
    val bluetoothEnabled = g.radios.bluetoothEnabled.stateIn(viewModelScope, sharing, true)
    val airplane = g.radios.airplaneMode.stateIn(viewModelScope, sharing, false)
    val locationEnabled = g.radios.locationEnabled.stateIn(viewModelScope, sharing, true)

    private val _nav = MutableStateFlow(NavState())
    val nav: StateFlow<NavState> = _nav.asStateFlow()
    fun navigate(block: (NavState) -> NavState) = _nav.value.let { _nav.value = block(it) }

    // ---- availability ---------------------------------------------------------------------

    val blockers: StateFlow<Map<Network, Blocker?>> = combine(
        permissions, wifiEnabled, bluetoothEnabled, airplane, locationEnabled,
    ) { p, wifi, bt, plane, loc ->
        mapOf(
            Network.Wifi to when {
                !g.radios.hasWifi -> Blocker.NoHardware
                !wifi -> Blocker.WifiOff
                else -> null
            },
            Network.Bluetooth to when {
                !g.radios.hasBluetooth -> Blocker.NoHardware
                !bt -> Blocker.BluetoothOff
                !p.nearby -> Blocker.NearbyPermission
                else -> null
            },
            Network.Cellular to when {
                !g.cell.hasTelephony -> Blocker.NoHardware
                plane -> Blocker.Airplane
                !p.phone -> Blocker.PhonePermission
                !g.cell.simReady() -> Blocker.NoSim
                else -> null
            },
            Network.Gnss to when {
                !p.location -> Blocker.LocationPermission
                !loc -> Blocker.LocationOff
                else -> null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun <T> whenAvailable(network: Network, off: T, source: () -> Flow<T>): Flow<T> =
        blockers.map { it[network] == null && it.isNotEmpty() }.distinctUntilChanged()
            .flatMapLatest { ok -> if (ok) source().catch { emit(off) } else flowOf(off) }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    private val wifiLive: Flow<WifiConnection?> = whenAvailable(Network.Wifi, null) {
        combine(g.wifi.connection, rate) { c, r -> c to r }.flatMapLatest { (c, r) ->
            if (c == null) flowOf(null) else tickerFlow(r).map {
                g.wifi.pollRssi()?.let { (rssi, tx, rx) ->
                    c.copy(rssi = rssi, linkTx = tx.takeIf { it > 0 } ?: c.linkTx, linkRx = rx.takeIf { it > 0 } ?: c.linkRx)
                } ?: c
            }
        }
    }

    val wifi: StateFlow<WifiUi> = channelFlow {
        var conn: WifiConnection? = null
        var hist = emptyList<Float>()
        launch {
            wifiLive.sampledHistory(rate, 60) { it?.rssi?.toFloat() }.collect { hist = it; send(WifiUi(conn, hist)) }
        }
        wifiLive.collect { conn = it; send(WifiUi(conn, if (it == null) emptyList() else hist)) }
    }.stateIn(viewModelScope, sharing, WifiUi(null, emptyList()))

    val wifiScan: StateFlow<List<WifiAp>?> = whenAvailable<List<WifiAp>?>(Network.Wifi, emptyList()) { g.wifi.scanResults }
        .stateIn(viewModelScope, sharing, null)
    val wifiThrottledUntil: StateFlow<Long> = g.wifi.throttledUntil
    fun startWifiScan(): Boolean = g.wifi.startScan()

    // ---- Bluetooth ---------------------------------------------------------------------------

    val bluetooth: StateFlow<BluetoothSnapshot> = whenAvailable(Network.Bluetooth, BluetoothSnapshot(AdapterInfo(null, emptyList()), emptyList())) {
        g.bluetooth.snapshot
    }.stateIn(viewModelScope, sharing, BluetoothSnapshot(AdapterInfo(null, emptyList()), emptyList()))

    private val bleLowLatency = MutableStateFlow(false)
    private val bleRestarts = MutableStateFlow(0)
    fun setBleLowLatency(v: Boolean) {
        bleLowLatency.value = v
    }

    fun forgetBluetooth(address: String): Boolean = g.bluetooth.forget(address)

    fun restartBleScan() {
        bleRestarts.value++
    }

    val ble: StateFlow<List<BleDevice>> = whenAvailable(Network.Bluetooth, emptyList()) {
        combine(bleLowLatency, bleRestarts) { fast, _ -> fast }.flatMapLatest { fast -> g.bluetooth.bleScan(lowPower = !fast) }
    }.stateIn(viewModelScope, sharing, emptyList())

    // ---- Cellular ------------------------------------------------------------------------------

    private val cellLive: Flow<CellState?> = whenAvailable<CellState?>(Network.Cellular, null) {
        rate.flatMapLatest { g.cell.cells(it) }
    }

    val cell: StateFlow<CellUi> = channelFlow {
        var state: CellState? = null
        var hist = emptyList<Float>()
        launch {
            cellLive.sampledHistory(rate, 300) { it?.serving?.level?.toFloat() }.collect { hist = it; send(CellUi(state, hist)) }
        }
        cellLive.collect { state = it; send(CellUi(state, hist)) }
    }.stateIn(viewModelScope, sharing, CellUi(null, emptyList()))

    val sims: StateFlow<List<SimInfo>> = whenAvailable(Network.Cellular, emptyList()) {
        cellLive.filterNotNull().map { g.cell.sims() }.distinctUntilChanged()
    }.stateIn(viewModelScope, sharing, emptyList())

    // ---- GNSS -----------------------------------------------------------------------------------

    val gnss: StateFlow<GnssState> = whenAvailable(Network.Gnss, GnssState()) {
        rate.flatMapLatest { g.gnss.status(it) }
    }.stateIn(viewModelScope, sharing, GnssState())

    val compass: StateFlow<CompassReading?> = (if (g.compass.available) g.compass.readings else emptyFlow())
        .stateIn(viewModelScope, sharing, null)
    val compassAvailable get() = g.compass.available
    fun lastKnownLocation() = g.gnss.lastKnownLocation()
    fun declination(): Float? = g.compass.declination(gnss.value.location ?: g.gnss.lastKnownLocation())

    // ---- settings -------------------------------------------------------------------------------

    fun updateSettings(block: suspend com.allnetworktools.data.SettingsRepository.() -> Unit) {
        viewModelScope.launch { g.settings.block() }
    }

    fun markAsked(group: PermGroup) = updateSettings { markAsked(group.name) }

    val tools = com.allnetworktools.ui.tools.ToolsHub(g, viewModelScope)
    val history get() = g.history

    // ---- Recording ------------------------------------------------------------------------------

    val recording get() = g.recording

    fun nmea() = g.gnss.nmea()

    init {
        viewModelScope.launch { g.history.load(g.settings.settings.first().historyDays) }
    }
}
