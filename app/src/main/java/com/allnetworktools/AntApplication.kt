package com.allnetworktools

import android.app.Application
import com.allnetworktools.data.BluetoothRepository
import com.allnetworktools.data.CellRepository
import com.allnetworktools.data.CompassRepository
import com.allnetworktools.data.GnssRepository
import com.allnetworktools.data.HistoryStore
import com.allnetworktools.data.PermissionsRepository
import com.allnetworktools.data.RadiosRepository
import com.allnetworktools.data.SettingsRepository
import com.allnetworktools.data.WifiRepository

open class AntApplication : Application() {
    open val settings by lazy { SettingsRepository(this) }
    open val permissions by lazy { PermissionsRepository(this) }
    open val radios by lazy { RadiosRepository(this) }
    open val wifi by lazy { WifiRepository(this) }
    open val bluetooth by lazy { BluetoothRepository(this) }
    open val cell by lazy { CellRepository(this) }
    open val gnss by lazy { GnssRepository(this) }
    open val compass by lazy { CompassRepository(this) }
    open val history by lazy { HistoryStore(this) }
}
