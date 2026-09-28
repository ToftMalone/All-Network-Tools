package com.allnetworktools

import android.content.Context
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.os.SystemClock
import com.allnetworktools.data.AdapterInfo
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.BluetoothRepository
import com.allnetworktools.data.BluetoothSnapshot
import com.allnetworktools.data.BondedDevice
import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellRepository
import com.allnetworktools.data.CellState
import com.allnetworktools.data.CompassReading
import com.allnetworktools.data.CompassRepository
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.FixType
import com.allnetworktools.data.GnssRepository
import com.allnetworktools.data.GnssState
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.PermissionSnapshot
import com.allnetworktools.data.PermissionsRepository
import com.allnetworktools.data.RadioTech
import com.allnetworktools.data.RadiosRepository
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.SimInfo
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.WifiRepository
import com.allnetworktools.ui.theme.Sym
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Scenario switches read by the fakes; set before the ViewModel is created. */
object Scenario {
    var airplane = false
    var gnssDenied = false
    var throttled = false
    var bleEmpty = false
}

private fun Context.fakePermissions() = object : PermissionsRepository(this@fakePermissions) {
    override val state: StateFlow<PermissionSnapshot> = MutableStateFlow(
        PermissionSnapshot(
            PermGroup.entries.toSet() - PermGroup.BackgroundLocation - PermGroup.UsageAccess -
                (if (Scenario.gnssDenied) setOf(PermGroup.Location) else emptySet()),
        ),
    )

    override fun refresh() = Unit
}

private fun Context.fakeRadios() = object : RadiosRepository(this@fakeRadios) {
    override val hasWifi = true
    override val hasBluetooth = true
    override val wifiEnabled: Flow<Boolean> = flowOf(!Scenario.airplane)
    override val bluetoothEnabled: Flow<Boolean> = flowOf(!Scenario.airplane)
    override val airplaneMode: Flow<Boolean> = flowOf(Scenario.airplane)
    override val locationEnabled: Flow<Boolean> = flowOf(true)
}

private val scan = listOf(
    WifiAp("Freebox-7A2C", "a4:3e:51:7c:2a:9f", -54, 5180, 80, "WPA3", ScanResult.WIFI_STANDARD_11AX, 5210),
    WifiAp("FreeWifi_secure", "a4:3e:51:7c:2a:a0", -58, 5180, 80, "WPA2-EAP", ScanResult.WIFI_STANDARD_11AX, 5210),
    WifiAp("Livebox-91F0", "70:fc:8f:11:22:33", -67, 5220, 80, "WPA2", ScanResult.WIFI_STANDARD_11AC, 5210),
    WifiAp("SFR_5G_B2E1", "00:1f:9f:44:55:66", -73, 5500, 80, "WPA2", ScanResult.WIFI_STANDARD_11AC, 5530),
    WifiAp("Bbox-5G-4410", "e8:ad:a6:77:88:99", -79, 5260, 80, "WPA2/3", ScanResult.WIFI_STANDARD_11AX, 5290),
    WifiAp("DIRECT-HP-Print", "fa:da:0c:01:02:03", -82, 5745, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 5745),
    WifiAp("Café Lumière", "b0:be:76:04:05:06", -86, 5580, 40, "Ouvert", ScanResult.WIFI_STANDARD_11AC, 5590),
    WifiAp("Freebox-7A2C", "a4:3e:51:7c:2a:9e", -51, 2437, 20, "WPA3", ScanResult.WIFI_STANDARD_11AX, 2437),
    WifiAp("Livebox-91F0", "70:fc:8f:11:22:34", -63, 2412, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 2412),
    WifiAp("SFR_B2E1", "00:1f:9f:44:55:67", -70, 2462, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 2462),
)

private fun Context.fakeWifi() = object : WifiRepository(this@fakeWifi) {
    private var rssi = -54
    override val connection: Flow<WifiConnection?> = flowOf(
        WifiConnection(
            ssid = "Freebox-7A2C", bssid = "a4:3e:51:7c:2a:9f", rssi = -54, frequency = 5180, linkTx = 1201, linkRx = 960,
            standard = ScanResult.WIFI_STANDARD_11AX, security = "WPA3-Personnel (SAE)", ipv4 = "192.168.1.42", prefix = 24,
            gateway = "192.168.1.254", dns = listOf("192.168.1.254", "1.1.1.1"), ipv6 = "2a01:e0a:3c1:5e70::7f2e",
            connectedAtElapsed = SystemClock.elapsedRealtime() - 8_040_000,
        ),
    )

    override fun pollRssi(): Triple<Int, Int, Int> {
        rssi = (rssi + listOf(-3, -1, 0, 2, 3).random()).coerceIn(-64, -46)
        return Triple(rssi, 1201, 960)
    }

    override val throttledUntil: StateFlow<Long> =
        MutableStateFlow(if (Scenario.throttled) SystemClock.elapsedRealtime() + 72_000 else 0L)

    override fun startScan() = !Scenario.throttled
    override val scanResults: Flow<List<WifiAp>> = flowOf(scan)
}

private val bleDevices = listOf(
    BleDevice("F4:0E:11:A2:3C:9B", "Pixel Buds Pro 2", -48, null, BleKind.Audio, "Google", true, 0),
    BleDevice("C8:2A:DD:14:07:E1", "Pixel Watch 3", -55, null, BleKind.Watch, "Google", true, 0),
    BleDevice("70:99:1C:5B:E2:40", "JBL Flip 6", -71, null, BleKind.Audio, "Harman", true, 0),
    BleDevice("E6:43:9A:0C:71:D8", "Tile Mate", -74, null, BleKind.Beacon, "Tile", false, 0),
    BleDevice("D2:5F:88:31:AA:06", "Mi Smart Band 8", -79, null, BleKind.Watch, "Xiaomi", true, 0),
    BleDevice("5A:1B:C7:9E:22:F3", null, -84, null, BleKind.Unknown, "Apple, Inc.", false, 0),
    BleDevice("2C:41:A1:6D:90:3B", "LE-Bose QC45", -88, null, BleKind.Audio, "Bose", true, 0),
)

private fun Context.fakeBluetooth() = object : BluetoothRepository(this@fakeBluetooth) {
    override val snapshot: Flow<BluetoothSnapshot> = flowOf(
        BluetoothSnapshot(
            AdapterInfo("Pixel 9 Pro", listOf("Bluetooth 5", "LE Audio", "Auracast")),
            listOf(
                BondedDevice("F4:0E:11:A2:3C:9B", "Pixel Buds Pro 2", Sym.Headphones, "Audio", true, listOf("LE Audio", "A2DP", "HFP")),
                BondedDevice("C8:2A:DD:14:07:E1", "Pixel Watch 3", Sym.Watch, "Montre", true, listOf("GATT")),
                BondedDevice("aa", "MX Keys", Sym.Keyboard, "Clavier", false, emptyList()),
                BondedDevice("bb", "JBL Flip 6", Sym.Speaker, "Enceinte", false, emptyList()),
                BondedDevice("cc", "Peugeot 208", Sym.DirectionsCar, "Voiture", false, emptyList()),
                BondedDevice("dd", "MX Master 3S", Sym.Mouse, "Souris", false, emptyList()),
            ),
        ),
    )

    override fun bleScan(lowPower: Boolean, staleMs: Long): Flow<List<BleDevice>> = flowOf(if (Scenario.bleEmpty) emptyList() else bleDevices)
}

private fun cell(tech: RadioTech, band: String, pci: Int, label: String, arfcn: Int, level: Int, registered: Boolean = false) = CellMeasure(
    tech = tech, registered = registered, band = band, arfcnLabel = label, arfcn = arfcn, pci = pci, tac = 36104,
    cellId = if (registered) 2351872017L else null, nodeId = if (registered) 574187L else null, sector = 17,
    mcc = "208", mnc = "01", rsrp = level, rsrq = -11, sinr = 14, rssi = if (tech == RadioTech.LTE || registered) -67 else null,
    cqi = 12, timingAdvance = 3, bandwidthKhz = if (registered) 90_000 else null, downlinkMhz = if (tech == RadioTech.NR) 3549.99 else null,
)

private fun Context.fakeCell() = object : CellRepository(this@fakeCell) {
    override val hasTelephony = true
    override fun simReady() = true
    override fun cells(refreshMs: Long): Flow<CellState> = flowOf(Unit).map {
        CellState(
            operator = "Orange F", simSlot = 1, techLabel = "5G SA", techLong = "STANDALONE", techBig = "5G",
            roaming = false, voiceAndData = true,
            serving = cell(RadioTech.NR, "n78", 412, "NR-ARFCN", 636666, -92, registered = true),
            neighbors = listOf(
                cell(RadioTech.LTE, "B20", 97, "EARFCN", 6300, -89),
                cell(RadioTech.LTE, "B3", 322, "EARFCN", 1850, -95),
                cell(RadioTech.NR, "n78", 187, "NR-ARFCN", 636666, -99),
                cell(RadioTech.LTE, "B7", 322, "EARFCN", 3100, -101),
                cell(RadioTech.NR, "n78", 58, "NR-ARFCN", 636666, -106),
                cell(RadioTech.LTE, "B1", 445, "EARFCN", 300, -112),
            ),
            servingBandwidthsKhz = listOf(90_000), signalLevel = 3, hasService = true,
        )
    }

    override fun sims() = listOf(
        SimInfo(1, 1, "Orange F", listOf("Données mobiles", "Appels", "SMS"), false, "5G", 3, true),
        SimInfo(2, 2, "Free Mobile", emptyList(), true, "4G+", 2, false),
    )
}

private val satRandom = java.util.Random(7)

private val sats = listOf(
    Constellation.GPS to 5, Constellation.GPS to 12, Constellation.GPS to 13, Constellation.GPS to 15, Constellation.GPS to 18,
    Constellation.GPS to 20, Constellation.GPS to 25, Constellation.GPS to 29, Constellation.Galileo to 3, Constellation.Galileo to 7,
    Constellation.Galileo to 11, Constellation.Galileo to 24, Constellation.Galileo to 26, Constellation.Galileo to 33,
    Constellation.Glonass to 2, Constellation.Glonass to 8, Constellation.Glonass to 17, Constellation.Glonass to 23,
    Constellation.BeiDou to 6, Constellation.BeiDou to 14, Constellation.BeiDou to 21, Constellation.BeiDou to 26,
    Constellation.BeiDou to 33, Constellation.BeiDou to 42, Constellation.QZSS to 194,
).mapIndexed { i, (c, svid) ->
    val r = satRandom
    val el = 8 + r.nextFloat() * 78
    val cn = 16 + r.nextFloat() * 30
    Satellite(c, svid, cn, el, (i * 137.5f + r.nextFloat() * 40) % 360, cn >= 26 && el >= 12, null)
}

private fun Context.fakeGnss() = object : GnssRepository(this@fakeGnss) {
    val loc = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 48.85661; longitude = 2.35222; altitude = 42.0; accuracy = 3.2f; speed = 0f; bearing = 184f
    }

    override fun lastKnownLocation() = loc
    override fun status(refreshMs: Long): Flow<GnssState> = flowOf(GnssState(sats, loc, FixType.Fix3D, 0.8f, 4100))
}

private fun Context.fakeCompass() = object : CompassRepository(this@fakeCompass) {
    override val available = true
    override fun declination(location: Location?) = 1.8f
    override val readings: Flow<CompassReading> = flowOf(CompassReading(182f, 2f, 1f, 47f, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
}

class FakeApp : AntApplication() {
    override val permissions by lazy { fakePermissions() }
    override val radios by lazy { fakeRadios() }
    override val wifi by lazy { fakeWifi() }
    override val bluetooth by lazy { fakeBluetooth() }
    override val cell by lazy { fakeCell() }
    override val gnss by lazy { fakeGnss() }
    override val compass by lazy { fakeCompass() }
}
