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
    }

    private fun shot(name: String, dark: Boolean = false, onboarding: Boolean = false, nav: NavState = NavState()) {
        runBlocking {
            app.settings.setTheme(if (dark) ThemeMode.Dark else ThemeMode.Light)
            app.settings.setOnboardingDone(!onboarding)
        }
        val vm = MainViewModel(app)
        vm.navigate { nav }
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
}
