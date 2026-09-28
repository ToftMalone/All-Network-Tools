package com.allnetworktools.ui.tools

import com.allnetworktools.AntApplication
import com.allnetworktools.data.net.LanScanner
import com.allnetworktools.ui.pages.bt.GattController
import com.allnetworktools.ui.pages.bt.TrackerController
import com.allnetworktools.ui.pages.wifi.ChannelsController
import com.allnetworktools.ui.pages.wifi.DnsController
import com.allnetworktools.ui.pages.wifi.LanController
import com.allnetworktools.ui.pages.wifi.LanDeviceController
import com.allnetworktools.ui.pages.wifi.PingController
import com.allnetworktools.ui.pages.wifi.PortsController
import com.allnetworktools.ui.pages.wifi.SpeedController
import com.allnetworktools.ui.pages.wifi.TraceController
import kotlinx.coroutines.CoroutineScope

/** One controller per tool, owned by the ViewModel so a scan survives navigating away and back. */
class ToolsHub(private val app: AntApplication, private val scope: CoroutineScope) {
    private val lanScanner by lazy { LanScanner(app) }

    val channels by lazy { ChannelsController(scope) }
    val lan by lazy { LanController(scope, lanScanner) }
    val lanDevice by lazy { LanDeviceController(scope, lanScanner) }
    val ping by lazy { PingController(scope) }
    val trace by lazy { TraceController(scope) }
    val ports by lazy { PortsController(scope) }
    val dns by lazy { DnsController(scope) }
    val speed by lazy { SpeedController(scope, app.history) }
    val gatt by lazy { GattController(app, scope) }
    val tracker by lazy { TrackerController(app, scope) }
}
