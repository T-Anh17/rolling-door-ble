package com.trananh.rollingdoor.ui.gate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.ui.components.DoorPreviews
import com.trananh.rollingdoor.ui.components.PrimaryButton
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme

enum class GateStatus { NeedsPermission, BluetoothOff, LocationOff, Ready }

// What has to be true before the app may use Bluetooth, re-checked whenever the app resumes and
// when Bluetooth or location is switched while it is open.
//   Android 12+: "Nearby devices" (BLUETOOTH_SCAN + BLUETOOTH_CONNECT), always.
//   Android 11 and below: location permission and location turned on, only to scan while
//   pairing; connecting to the saved device needs neither.
@Stable
class BluetoothGate internal constructor(
    val status: GateStatus,
    // Android 11 and below asks for location instead of "Nearby devices".
    val asksForLocation: Boolean,
    // The user refused for good: the system dialog no longer shows, only app settings can fix it.
    val permissionBlocked: Boolean,
    val fix: () -> Unit,
)

@Composable
fun rememberBluetoothGate(adapter: BluetoothAdapter?, forPairing: Boolean): BluetoothGate {
    val context = LocalContext.current
    val permissions = remember(forPairing) { bluetoothPermissions(forPairing) }
    val needsLocationOn = forPairing && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    var granted by remember { mutableStateOf(context.hasAll(permissions)) }
    var bluetoothOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    var locationOn by remember { mutableStateOf(context.isLocationOn()) }
    var blocked by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        granted = context.hasAll(permissions)
        bluetoothOn = adapter?.isEnabled == true
        locationOn = context.isLocationOn()
        if (granted) blocked = false
    }

    // Back from system settings, or from the Bluetooth / permission dialogs.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
        // Refused, and Android will not ask again: send the user to app settings next time.
        val activity = context.findActivity()
        blocked = !granted && activity != null &&
            permissions.none { activity.shouldShowRequestPermissionRationale(it) }
    }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { bluetoothOn = adapter?.isEnabled == true }

    val status = when {
        !granted -> GateStatus.NeedsPermission
        !bluetoothOn -> GateStatus.BluetoothOff
        needsLocationOn && !locationOn -> GateStatus.LocationOff
        else -> GateStatus.Ready
    }
    return BluetoothGate(
        status = status,
        asksForLocation = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R,
        permissionBlocked = blocked,
        fix = {
            when (status) {
                GateStatus.NeedsPermission ->
                    if (blocked) context.openAppSettings() else permissionLauncher.launch(permissions)
                GateStatus.BluetoothOff -> enableLauncher.launch(enableBluetoothIntent())
                GateStatus.LocationOff ->
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                GateStatus.Ready -> Unit
            }
        },
    )
}

// Full-screen blocking state: icon, explanation and the one action that fixes it.
@Composable
fun GateScreen(gate: BluetoothGate, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(DoorTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = DoorTheme.spacing.screenMargin),
        contentAlignment = Alignment.Center,
    ) {
        GateContent(gate)
    }
}

// The same message without the page around it, for use inside another screen.
@Composable
fun GateContent(gate: BluetoothGate, modifier: Modifier = Modifier) {
    val content = gate.content()
    val colors = DoorTheme.colors
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(content.tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(content.icon, contentDescription = null, tint = content.tint, modifier = Modifier.size(36.dp))
        }
        Text(
            stringResource(content.title),
            Modifier.semantics { heading() },
            style = DoorTheme.type.title2,
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(content.body),
            style = DoorTheme.type.body,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(
            stringResource(content.action),
            onClick = gate.fix,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DoorTheme.spacing.xs),
        )
    }
}

private class GateText(
    val icon: ImageVector,
    val tint: Color,
    val title: Int,
    val body: Int,
    val action: Int,
)

@Composable
private fun BluetoothGate.content(): GateText {
    val colors = DoorTheme.colors
    return when (status) {
        GateStatus.NeedsPermission -> when {
            permissionBlocked -> GateText(
                if (asksForLocation) Icons.Rounded.LocationOn else Icons.Rounded.Bluetooth,
                colors.accent,
                if (asksForLocation) R.string.gate_location_permission_title else R.string.gate_permission_title,
                R.string.gate_permission_blocked_body,
                R.string.gate_open_settings,
            )
            asksForLocation -> GateText(
                Icons.Rounded.LocationOn, colors.accent,
                R.string.gate_location_permission_title, R.string.gate_location_permission_body, R.string.gate_allow,
            )
            else -> GateText(
                Icons.Rounded.Bluetooth, colors.accent,
                R.string.gate_permission_title, R.string.gate_permission_body, R.string.gate_allow,
            )
        }
        GateStatus.BluetoothOff -> GateText(
            Icons.Rounded.BluetoothDisabled, colors.red,
            R.string.gate_bluetooth_off_title, R.string.gate_bluetooth_off_body, R.string.gate_turn_on_bluetooth,
        )
        GateStatus.LocationOff, GateStatus.Ready -> GateText(
            Icons.Rounded.LocationOff, colors.red,
            R.string.gate_location_off_title, R.string.gate_location_off_body, R.string.gate_open_location_settings,
        )
    }
}

// Outside Compose: whether the saved device may be connected to without asking first.
fun Context.canConnectToSavedDevice(): Boolean = hasAll(bluetoothPermissions(forPairing = false))

private fun bluetoothPermissions(forPairing: Boolean): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    forPairing -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> emptyArray() // BLUETOOTH and BLUETOOTH_ADMIN are granted at install
}

// BLUETOOTH_CONNECT is granted before this runs: the gate asks for permissions first.
@SuppressLint("MissingPermission")
private fun enableBluetoothIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

private fun Context.hasAll(permissions: Array<String>) = permissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private fun Context.isLocationOn(): Boolean {
    val manager = getSystemService(LocationManager::class.java) ?: return false
    return LocationManagerCompat.isLocationEnabled(manager)
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@DoorPreviews
@Composable
private fun GatePermissionPreview() = RollingDoorTheme {
    GateScreen(BluetoothGate(GateStatus.NeedsPermission, asksForLocation = false, permissionBlocked = false) {})
}

@DoorPreviews
@Composable
private fun GateBluetoothOffPreview() = RollingDoorTheme {
    GateScreen(BluetoothGate(GateStatus.BluetoothOff, asksForLocation = false, permissionBlocked = false) {})
}

@DoorPreviews
@Composable
private fun GateLocationOffPreview() = RollingDoorTheme {
    GateScreen(BluetoothGate(GateStatus.LocationOff, asksForLocation = true, permissionBlocked = false) {})
}
