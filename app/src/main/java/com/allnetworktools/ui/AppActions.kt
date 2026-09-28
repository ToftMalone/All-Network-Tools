package com.allnetworktools.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.allnetworktools.Blocker
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup

/** Snackbar message holder (inverse surface, bottom, 2.4 s). */
class Toaster {
    var message by mutableStateOf<String?>(null)
        private set
    var serial by mutableStateOf(0)
        private set

    fun show(text: String) {
        message = text
        serial++
    }

    fun dismiss(forSerial: Int) {
        if (serial == forSerial) message = null
    }
}

class AppActions(
    private val context: Context,
    private val vm: MainViewModel,
    val toaster: Toaster,
    private val requestPermissions: (Array<String>) -> Unit,
) {
    fun toast(text: String) = toaster.show(text)

    private fun activity(): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun launch(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Écran indisponible sur cet appareil") }
    }

    fun openAppSettings() = launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    /** True if Android will no longer show the dialog for this group (denied twice or "don't ask again"). */
    fun isPermanentlyDenied(group: PermGroup): Boolean {
        val act = activity() ?: return false
        val asked = group.name in (vm.settings.value?.askedPermissions ?: emptySet())
        val missing = group.permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        return asked && missing.isNotEmpty() && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
    }

    fun request(group: PermGroup) {
        when {
            group == PermGroup.UsageAccess -> launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            group.permissions.isEmpty() -> vm.refreshPermissions()
            isPermanentlyDenied(group) -> {
                openAppSettings()
                toast("Autorisez l'accès dans Paramètres Android › Autorisations")
            }
            group == PermGroup.BackgroundLocation && !vm.permissions.value.location -> {
                toast("Autorisez d'abord la position précise")
                request(PermGroup.Location)
            }
            else -> {
                vm.markAsked(group)
                requestPermissions(group.permissions.toTypedArray())
            }
        }
    }

    fun openWifiPanel() = launch(Intent(Settings.Panel.ACTION_WIFI))
    fun openWifiSettings() = launch(Intent(Settings.ACTION_WIFI_SETTINGS))
    fun openBluetoothSettings() = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openAirplaneSettings() = launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    fun openLocationSettings() = launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    fun openNetworkSettings() = launch(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))

    fun enableBluetooth() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            request(PermGroup.Nearby)
        } else {
            launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun share(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, title))
    }

    fun openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, url.toUri()))

    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("$label copié")
    }

    /** Primary action for a blocked network. */
    fun resolve(blocker: Blocker) = when (blocker) {
        Blocker.WifiOff -> openWifiPanel()
        Blocker.BluetoothOff -> enableBluetooth()
        Blocker.NearbyPermission -> request(PermGroup.Nearby)
        Blocker.Airplane -> openAirplaneSettings()
        Blocker.PhonePermission -> request(PermGroup.Phone)
        Blocker.NoSim -> openNetworkSettings()
        Blocker.LocationPermission -> request(PermGroup.Location)
        Blocker.LocationOff -> openLocationSettings()
        Blocker.NoHardware -> Unit
    }
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

@Composable
fun rememberAppActions(vm: MainViewModel, toaster: Toaster): AppActions {
    val context = LocalContext.current
    val vmState by rememberUpdatedState(vm)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vmState.refreshPermissions()
    }
    return remember(context, vm, toaster) { AppActions(context, vm, toaster) { launcher.launch(it) } }
}
