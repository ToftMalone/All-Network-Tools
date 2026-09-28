package com.allnetworktools

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.ThemeMode
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.AntApp
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.data.WifiBand
import com.allnetworktools.data.net.DnsAnswer
import com.allnetworktools.data.net.DnsRecord
import com.allnetworktools.data.net.DnsType
import com.allnetworktools.data.net.SpeedServer
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.GattNames
import com.allnetworktools.data.CellEvent
import com.allnetworktools.data.SignalSample
import com.allnetworktools.ui.pages.bt.GattCharUi
import com.allnetworktools.ui.pages.bt.GattConn
import com.allnetworktools.ui.pages.bt.GattServiceUi
import com.allnetworktools.ui.pages.bt.NotifyLine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with fake data into build/screenshots for visual review.
 * Run with: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = FakeApp::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<FakeApp>()

    @After
    fun reset() {
        Scenario.airplane = false
        Scenario.gnssDenied = false
        Scenario.throttled = false
        Scenario.bleEmpty = false
        Scenario.usageGranted = false
    }

    private fun shot(name: String, dark: Boolean = false, onboarding: Boolean = false, nav: NavState = NavState(), setup: (MainViewModel) -> Unit = {}) {
        runBlocking {
            app.settings.setTheme(if (dark) ThemeMode.Dark else ThemeMode.Light)
            app.settings.setOnboardingDone(!onboarding)
        }
        val vm = MainViewModel(app)
        vm.navigate { nav }
        setup(vm)
        compose.mainClock.autoAdvance = false
        compose.setContent { AntApp(vm) }
        repeat(30) {
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun onboarding() = shot("01_onboarding", onboarding = true)
    @Test fun home() = shot("02_home")
    @Test fun homeSelected() = shot("03_home_wifi_selected", nav = NavState(Network.Wifi))
    @Test fun homeDark() = shot("04_home_gnss_dark", dark = true, nav = NavState(Network.Gnss))
    @Test fun homeAirplane() {
        Scenario.airplane = true
        shot("05_home_airplane")
    }
    @Test fun wifiDashboard() = shot("10_wifi_dashboard", nav = NavState(Network.Wifi, Page.Dashboard))
    @Test fun wifiTools() = shot("11_wifi_tools", nav = NavState(Network.Wifi, Page.Tools))
    @Test fun wifiScanner() = shot("12_wifi_scanner", nav = NavState(Network.Wifi, Page.ToolPage(Tool.WifiScan)))
    @Test fun wifiThrottled() {
        Scenario.throttled = true
        shot("13_wifi_scanner_throttled", nav = NavState(Network.Wifi, Page.ToolPage(Tool.WifiScan)))
    }
    @Test fun btDashboard() = shot("20_bt_dashboard", nav = NavState(Network.Bluetooth, Page.Dashboard))
    @Test fun bleScanner() = shot("21_ble_scanner", nav = NavState(Network.Bluetooth, Page.ToolPage(Tool.BleScan)))
    @Test fun bleEmpty() {
        Scenario.bleEmpty = true
        shot("22_ble_empty", nav = NavState(Network.Bluetooth, Page.ToolPage(Tool.BleScan)))
    }
    @Test fun cellDashboard() = shot("30_cell_dashboard", nav = NavState(Network.Cellular, Page.Dashboard))
    @Test fun neighbors() = shot("31_cell_neighbors", nav = NavState(Network.Cellular, Page.ToolPage(Tool.Neighbors)))
    @Test fun gnssDashboard() = shot("40_gnss_dashboard", nav = NavState(Network.Gnss, Page.Dashboard))
    @Test fun gnssTools() = shot("43_gnss_tools", nav = NavState(Network.Gnss, Page.Tools))
    @Test fun compass() = shot("41_compass", nav = NavState(Network.Gnss, Page.ToolPage(Tool.Compass)))
    @Test fun gnssDenied() {
        Scenario.gnssDenied = true
        shot("42_gnss_denied", nav = NavState(Network.Gnss, Page.Dashboard))
    }
    @Test fun settings() = shot("50_settings", nav = NavState(page = Page.Settings))
    @Test fun settingsDark() = shot("51_settings_dark", dark = true, nav = NavState(page = Page.Settings))
    @Test fun cellDashboardDark() = shot("32_cell_dashboard_dark", dark = true, nav = NavState(Network.Cellular, Page.Dashboard))

    private fun tool(t: Tool, arg: String? = null) = NavState(Network.Wifi, Page.ToolPage(t, arg))

    @Test fun channelsIdle() = shot("60_channels_idle", nav = tool(Tool.Channels))
    @Test fun channelsResults() = shot("61_channels_results", nav = tool(Tool.Channels)) { vm ->
        vm.tools.channels.band = WifiBand.B5
        FakeData.scan.forEach { vm.tools.channels.seen[it.bssid] = it }
        vm.tools.channels.phase = Phase.Results
    }
    @Test fun lanIdle() = shot("62_lan_idle", nav = tool(Tool.Lan))
    @Test fun lanResults() = shot("63_lan_results", nav = tool(Tool.Lan)) { vm ->
        vm.tools.lan.devices.addAll(FakeData.lan)
        vm.tools.lan.subnet = "192.168.1.0/24"
        vm.tools.lan.durationMs = 4800
        vm.tools.lan.phase = Phase.Results
    }
    @Test fun lanDevice() = shot("64_lan_device", nav = tool(Tool.LanDevice, "192.168.1.23")) { vm ->
        vm.tools.lan.devices.addAll(FakeData.lan)
        vm.tools.lanDevice.ip = "192.168.1.23"
        vm.tools.lanDevice.latency = 5f
        vm.tools.lanDevice.services.addAll(listOf(8008 to "HTTP 200 OK", 8009 to null, 8443 to "HTTP 404 Not Found"))
        vm.tools.lanDevice.phase = Phase.Results
    }
    @Test fun pingIdle() = shot("65_ping_idle", nav = tool(Tool.Ping))
    @Test fun pingResults() = shot("66_ping_results", nav = tool(Tool.Ping)) { vm ->
        val c = vm.tools.ping
        c.resolved = "one.one.one.one"
        c.results.addAll(listOf(13.2f, 12.8f, 15.1f, 14.0f, null, 13.6f, 29.4f, 12.9f, 13.3f, 14.2f))
        c.phase = Phase.Results
    }
    @Test fun traceResults() = shot("67_trace_results", nav = tool(Tool.Trace)) { vm ->
        val c = vm.tools.trace
        c.target = "1.1.1.1"
        c.hops.addAll(FakeData.hops)
        c.phase = Phase.Results
    }
    @Test fun portsIdle() = shot("68_ports_idle", nav = tool(Tool.Ports))
    @Test fun portsResults() = shot("69_ports_results", nav = tool(Tool.Ports)) { vm ->
        val c = vm.tools.ports
        c.host = "192.168.1.254"; c.total = 100; c.scanned = 100; c.closed = 93; c.filtered = 2
        c.startedAt = 1000; c.endedAt = 1300
        c.open.addAll(FakeData.ports)
        c.phase = Phase.Results
    }
    @Test fun dnsResults() = shot("70_dns_results", nav = tool(Tool.Dns)) { vm ->
        val c = vm.tools.dns
        c.type = DnsType.NS; c.host = "google.com"
        c.answer = DnsAnswer(0, (1..4).map { DnsRecord("NS", "ns$it.google.com", 21600) }, 9f)
        listOf("Système" to 18f, "Cloudflare" to 9f, "Google" to 14f, "Quad9" to 21f).forEach { (k, v) -> c.compare[k] = v }
        c.phase = Phase.Results
    }
    @Test fun speedEmpty() = shot("71_speed_empty", nav = tool(Tool.Speed)) { vm ->
        vm.tools.speed.server = SpeedServer("Paris", "CDG", "Free SAS")
    }
    @Test fun speedResults() = shot("72_speed_results", nav = tool(Tool.Speed)) { vm ->
        val c = vm.tools.speed
        c.server = SpeedServer("Paris", "CDG", "Free SAS")
        c.ping = 6f; c.jitter = 1.2f; c.down = 842f; c.up = 612f; c.current = 842f
        c.phase = Phase.Results
    }
    @Test fun pingDark() = shot("73_ping_dark", dark = true, nav = tool(Tool.Ping)) { vm ->
        vm.tools.ping.resolved = "one.one.one.one"
        vm.tools.ping.results.addAll(listOf(13.2f, 12.8f, 15.1f, null, 13.6f))
        vm.tools.ping.phase = Phase.Running
    }

    private fun bt(t: Tool, arg: String? = null) = NavState(Network.Bluetooth, Page.ToolPage(t, arg))

    private fun fakeGatt(vm: MainViewModel) {
        val c = vm.tools.gatt
        c.address = "F4:0E:11:A2:3C:9B"
        fun ch(svc: Int, n: Int, props: Int) = GattCharUi("$svc/$n", GattNames.uuid(n), props)
        c.services.addAll(
            listOf(
                GattServiceUi(GattNames.uuid(0x1800), listOf(ch(0x1800, 0x2A00, 2), ch(0x1800, 0x2A01, 2))),
                GattServiceUi(GattNames.uuid(0x180F), listOf(ch(0x180F, 0x2A19, 2 or 16))),
                GattServiceUi(GattNames.uuid(0x180D), listOf(ch(0x180D, 0x2A37, 16), ch(0x180D, 0x2A38, 2))),
                GattServiceUi(GattNames.uuid(0x180A), listOf(ch(0x180A, 0x2A29, 2), ch(0x180A, 0x2A26, 2))),
            ),
        )
        c.values["${0x180F}/${0x2A19}"] = byteArrayOf(80)
        c.values["${0x180D}/${0x2A37}"] = byteArrayOf(0, 72)
        c.values["${0x180D}/${0x2A38}"] = byteArrayOf(2)
        c.notifying["${0x180D}/${0x2A37}"] = true
        c.expanded[GattNames.uuid(0x180F)] = true
        c.expanded[GattNames.uuid(0x180D)] = true
        c.log.add(NotifyLine(1_700_000_000_000, GattNames.uuid(0x2A37), byteArrayOf(0, 72)))
        c.log.add(NotifyLine(1_700_000_000_000, GattNames.uuid(0x2A37), byteArrayOf(0, 71)))
        c.mtu = 247; c.phy = 2; c.elapsedS = 1.8f
        c.conn = GattConn.Connected
        c.phase = Phase.Results
    }

    @Test fun gattIdle() = shot("80_gatt_idle", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B")) { it.tools.gatt.address = "F4:0E:11:A2:3C:9B" }
    @Test fun gattRunning() = shot("81_gatt_running", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B")) { vm ->
        vm.tools.gatt.address = "F4:0E:11:A2:3C:9B"
        vm.tools.gatt.mtu = 247; vm.tools.gatt.step = 2
        vm.tools.gatt.conn = GattConn.Connecting; vm.tools.gatt.phase = Phase.Running
    }
    @Test fun gattResults() = shot("82_gatt_results", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B"), setup = ::fakeGatt)
    @Test fun gattError() = shot("83_gatt_error", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B")) { vm ->
        vm.tools.gatt.address = "F4:0E:11:A2:3C:9B"
        vm.tools.gatt.error = GattNames.status(133)
        vm.tools.gatt.conn = GattConn.Lost; vm.tools.gatt.phase = Phase.Error
    }
    @Test fun paired() = shot("84_paired", nav = bt(Tool.Paired, "F4:0E:11:A2:3C:9B"))
    @Test fun pairedOff() = shot("85_paired_off", nav = bt(Tool.Paired, "aa"))
    @Test fun trackerIdle() = shot("86_tracker_idle", nav = bt(Tool.Tracker))
    @Test fun trackerRunning() = shot("87_tracker_running", nav = bt(Tool.Tracker)) { vm ->
        val c = vm.tools.tracker
        c.follow(BleDevice("70:99:1C:5B:E2:40", "JBL Flip 6", -61, null, BleKind.Audio, "Harman", true, 0))
        c.history.addAll(listOf(-82f, -80f, -79f, -77f, -76f, -74f, -73f, -72f, -70f, -68f, -66f, -65f, -63f, -62f, -61f))
        c.rssi = -61f; c.trend = 1
    }
    @Test fun trackerHotDark() = shot("88_tracker_found_dark", dark = true, nav = bt(Tool.Tracker)) { vm ->
        val c = vm.tools.tracker
        c.follow(BleDevice("E6:43:9A:0C:71:D8", "Tile Mate", -48, null, BleKind.Beacon, "Tile", false, 0))
        c.history.addAll(listOf(-70f, -66f, -62f, -58f, -55f, -52f, -49f, -48f))
        c.rssi = -48f
    }

    private fun cellTool(t: Tool, arg: String? = null) = NavState(Network.Cellular, Page.ToolPage(t, arg))
    private fun gnssTool(t: Tool) = NavState(Network.Gnss, Page.ToolPage(t))

    private fun seedHistory() = runBlocking {
        val now = System.currentTimeMillis()
        val h = app.history
        h.clear()
        listOf(
            CellEvent(now - 30 * 86_400_000L / 30 - 3_600_000, "down", "5G → 4G", "PCI 187 → 445 · n78 → B1", -115, "Perte de couverture NR", "LTE"),
            CellEvent(now - 7_200_000, "handover", "Changement de cellule LTE", "PCI 445 → 322 · B1 → B3", -97, "eNB 81342", "LTE"),
            CellEvent(now - 3_000_000, "down", "5G → 4G", "PCI 412 → 97 · n78 → B20", -108, "Perte de couverture NR", "LTE"),
            CellEvent(now - 2_400_000, "up", "4G → 5G", "PCI 97 → 412 · B20 → n78", -95, "Retour de la couverture NR", "NR"),
            CellEvent(now - 600_000, "handover", "Changement de cellule NR", "PCI 412 → 187 · n78", -92, "Même gNB 574187", "NR"),
        ).forEach { h.addCellEvent(it) }
        for (i in 0 until 24 * 60 step 4) {
            val at = now - (24 * 60 - i) * 60_000L
            val lte = i in 300..420 || i in 1000..1080
            val base = if (lte) -101.0 else -92.0
            h.addSignal(SignalSample(at, if (lte) "LTE" else "NR", (base + 5 * Math.sin(i / 37.0)).toInt(), -11 + (i / 50) % 4, 14 - (i / 70) % 6))
        }
    }

    @Test fun cellLog() {
        seedHistory(); app.recording.start(com.allnetworktools.service.RecKind.CellLog)
        shot("90_cell_log", nav = cellTool(Tool.CellLog))
    }
    @Test fun cellLogStopped() {
        seedHistory(); app.recording.stopAll()
        shot("91_cell_log_stopped", dark = true, nav = cellTool(Tool.CellLog))
    }
    @Test fun signalHistory() {
        seedHistory(); shot("92_signal_history", nav = cellTool(Tool.SignalHistory))
    }
    @Test fun signalHistoryEmpty() {
        runBlocking { app.history.clear() }; shot("93_signal_history_empty", nav = cellTool(Tool.SignalHistory))
    }
    @Test fun dataUsageDenied() = shot("94_data_usage_denied", nav = cellTool(Tool.DataUsage))
    @Test fun dataUsage() {
        Scenario.usageGranted = true
        runBlocking { app.settings.setDataPlanGb(20) }
        shot("95_data_usage", nav = cellTool(Tool.DataUsage))
    }
    @Test fun cellDetail() = shot("96_cell_detail", nav = cellTool(Tool.CellDetail, "serving"))
    @Test fun nmea() = shot("97_nmea", nav = gnssTool(Tool.Nmea)) { vm ->
        val c = vm.tools.nmea
        val t = System.currentTimeMillis()
        listOf(
            "\$GPGGA,123519.00,4851.2152,N,00221.1234,E,1,12,0.8,42.1,M,47.0,M,,*5C",
            "\$GNGSA,A,3,05,13,15,18,20,23,24,,,,,,1.3,0.8,1.0,1*07",
            "\$GPGSV,3,1,12,05,41,295,43,13,62,050,45,15,34,103,40,18,17,168,33,1*6B",
            "\$GAGSV,2,1,07,02,27,248,38,07,53,137,44,08,61,292,46,26,12,040,29,7*7E",
            "\$GNRMC,123519.00,A,4851.2152,N,00221.1234,E,0.02,,280926,,,A,V*1F",
            "\$GNVTG,,T,,M,0.02,N,0.04,K,A*3D",
            "\$GNGLL,4851.2152,N,00221.1234,E,123519.00,A,A*7A",
        ).let { batch -> repeat(3) { r -> batch.forEach { c.add(t + r * 1000, it) } } }
        c.live = false
    }

    @Test fun sky() = shot("A0_gnss_sky", nav = gnssTool(Tool.Sky))
    @Test fun skyMap() = shot("A1_gnss_sky_map", nav = gnssTool(Tool.Sky)) { it.tools.skyView.value = com.allnetworktools.ui.pages.gnss.SkyView.Map }
    @Test fun skyMapDark() = shot("A2_gnss_sky_map_dark", dark = true, nav = gnssTool(Tool.Sky)) { it.tools.skyView.value = com.allnetworktools.ui.pages.gnss.SkyView.Map }

    @Test fun bleFiltered() = shot("B0_ble_filtered", nav = bt(Tool.BleScan)) { vm ->
        vm.tools.bleFilter.exclude = setOf(com.allnetworktools.data.BleVendor.Apple)
        vm.tools.bleFilter.minRssi = -80
        vm.tools.bleFilter.nameMode = com.allnetworktools.ui.pages.bt.NameMode.Named
    }
    @Test fun bleFilterSheet() = shot("B1_ble_filter_sheet", nav = bt(Tool.BleScan)) { vm ->
        vm.tools.bleFilter.raw = "0xFF4C00"
        vm.tools.bleFilter.include = setOf(com.allnetworktools.data.BleVendor.Google)
        vm.tools.bleFilter.sheetOpen = true
    }
    @Test fun upnp() = shot("B2_upnp", nav = tool(Tool.Upnp)) { vm ->
        val c = vm.tools.upnp
        c.devices.addAll(
            listOf(
                com.allnetworktools.data.net.UpnpDevice("192.168.1.254", "http://192.168.1.254:5678/desc.xml", "Linux/4.19 UPnP/1.0", "Freebox Server", "Free", "Freebox v7", "urn:schemas-upnp-org:device:InternetGatewayDevice:2", listOf("Layer3Forwarding", "WANIPConnection"), "http://192.168.1.254/"),
                com.allnetworktools.data.net.UpnpDevice("192.168.1.20", "http://192.168.1.20:1500/", "WebOS/4.0 UPnP/1.0", "[LG] webOS TV OLED55C1", "LG Electronics", "OLED55C1", "urn:schemas-upnp-org:device:MediaRenderer:1", listOf("AVTransport", "RenderingControl", "ConnectionManager")),
                com.allnetworktools.data.net.UpnpDevice("192.168.1.30", "http://192.168.1.30:5000/ssdp/desc-DSM-eth0.xml", "Synology/DSM UPnP/1.0", "NAS-Maison (DS220+)", "Synology", "DS220+", "urn:schemas-upnp-org:device:Basic:1"),
            ),
        )
        c.expanded["http://192.168.1.254:5678/desc.xml"] = true
        c.phase = com.allnetworktools.ui.tools.Phase.Results
    }
    @Test fun bonjour() = shot("B3_bonjour", nav = tool(Tool.Bonjour)) { vm ->
        val c = vm.tools.bonjour
        c.services.addAll(
            listOf(
                com.allnetworktools.data.net.BonjourService("_googlecast._tcp", "Chromecast Salon", "192.168.1.23", 8009, mapOf("md" to "Chromecast", "fn" to "Salon")),
                com.allnetworktools.data.net.BonjourService("_googlecast._tcp", "Nest Mini Cuisine", "192.168.1.41", 8009, mapOf("md" to "Google Nest Mini")),
                com.allnetworktools.data.net.BonjourService("_ipp._tcp", "HP LaserJet M110w", "192.168.1.60", 631, mapOf("ty" to "HP LaserJet M110w")),
                com.allnetworktools.data.net.BonjourService("_airplay._tcp", "MacBook Air de Léa", "192.168.1.12", 7000, mapOf("model" to "Mac14,2")),
                com.allnetworktools.data.net.BonjourService("_hap._tcp", "Hue Bridge", "192.168.1.70", 8080, emptyMap()),
            ),
        )
        c.phase = com.allnetworktools.ui.tools.Phase.Results
    }
    @Test fun whois() = shot("B4_whois", nav = tool(Tool.Whois)) { vm ->
        val c = vm.tools.whois
        c.query = "google.com"
        c.result = com.allnetworktools.data.net.WhoisResult(
            "google.com", false,
            listOf(
                com.allnetworktools.data.net.WhoisHop("whois.iana.org", "refer: whois.verisign-grs.com"),
                com.allnetworktools.data.net.WhoisHop(
                    "whois.markmonitor.com",
                    "Domain Name: google.com\nRegistrar: MarkMonitor, Inc.\nCreation Date: 1997-09-15T07:00:00+0000\nRegistrar Registration Expiration Date: 2028-09-13T07:00:00+0000\nUpdated Date: 2024-08-02T02:17:33+0000\nRegistrant Organization: Google LLC\nRegistrant Country: US\nName Server: ns1.google.com\nName Server: ns2.google.com\nDomain Status: clientUpdateProhibited (https://www.icann.org/epp#clientUpdateProhibited)\nDNSSEC: unsigned",
                ),
            ),
        )
        c.phase = com.allnetworktools.ui.tools.Phase.Results
    }
}
