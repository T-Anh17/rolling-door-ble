package com.trananh.rollingdoor.ui.control

import android.bluetooth.BluetoothAdapter
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.ble.ConnectionState
import com.trananh.rollingdoor.ble.LinkError
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.PowerInfo
import com.trananh.rollingdoor.protocol.PowerSource
import com.trananh.rollingdoor.ui.components.BannerHost
import com.trananh.rollingdoor.ui.components.BannerKind
import com.trananh.rollingdoor.ui.components.BannerMessage
import com.trananh.rollingdoor.ui.components.DoorButton
import com.trananh.rollingdoor.ui.components.DoorPreviews
import com.trananh.rollingdoor.ui.components.NavBarIconButton
import com.trananh.rollingdoor.ui.components.NavBarScaffold
import com.trananh.rollingdoor.ui.components.StatusPill
import com.trananh.rollingdoor.ui.components.StatusTone
import com.trananh.rollingdoor.ui.components.TextLink
import com.trananh.rollingdoor.ui.gate.BluetoothGate
import com.trananh.rollingdoor.ui.gate.GateContent
import com.trananh.rollingdoor.ui.gate.GateStatus
import com.trananh.rollingdoor.ui.gate.rememberBluetoothGate
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme

// The remote: connection status and the four door buttons, on one screen that never scrolls.
// Everything else (device info, admin actions, Forget device) lives in Settings.
@Composable
fun ControlScreen(device: SavedDevice, adapter: BluetoothAdapter?, onForget: () -> Unit) {
    val viewModel: ControlViewModel = viewModel(
        key = ControlViewModel.key(device),
        factory = ControlViewModel.factory(device),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBluetoothGate(adapter, forPairing = false)

    // Connected only while the screen is visible and Bluetooth is usable; losing either stops it.
    // MainActivity usually starts it before this first composes; start() then does nothing, and
    // this still starts it once Bluetooth becomes usable on screen.
    if (gate.status == GateStatus.Ready) {
        LifecycleStartEffect(viewModel) {
            viewModel.start()
            onStopOrDispose { viewModel.stop() }
        }
    }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // The banner sits under the sheet: close the sheet when Add phone answers.
    LaunchedEffect(state.message) {
        if (state.message != null) settingsOpen = false
    }

    ControlContent(
        state = state,
        gate = gate.takeIf { it.status != GateStatus.Ready },
        onSend = viewModel::send,
        onRetry = viewModel::retryNow,
        onDismissMessage = viewModel::dismissMessage,
        onOpenSettings = { settingsOpen = true },
    )
    if (settingsOpen) {
        SettingsSheet(
            device = device,
            canAddPhone = state.connection == ConnectionState.Ready && state.sending == null,
            addingPhone = state.sending == Command.OpenPairing,
            onAddPhone = { viewModel.send(Command.OpenPairing) },
            onForget = onForget,
            onDismiss = { settingsOpen = false },
        )
    }
}

// gate: what still blocks Bluetooth, shown in place of the buttons; null when nothing does.
@Composable
private fun ControlContent(
    state: ControlUiState,
    gate: BluetoothGate?,
    onSend: (Command) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val banner = rememberBanner(state.message, onOpenSettings)
    NavBarScaffold(
        title = stringResource(R.string.control_title),
        scrollable = false,
        actions = {
            NavBarIconButton(Icons.Rounded.Settings, stringResource(R.string.settings_title), onOpenSettings)
        },
        topOverlay = { BannerHost(banner, onDismissMessage) },
    ) {
        if (gate != null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                GateContent(gate)
            }
        } else {
            ConnectionStatus(state.connection, state.power, onRetry)
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
            ) {
                // Up and Down are taller, in the same ratio as DoorButton's minimum heights.
                Row(Modifier.weight(1.35f), horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s)) {
                    RemoteButton(Command.Up, state, onSend, Modifier.weight(1f))
                    RemoteButton(Command.Down, state, onSend, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s)) {
                    RemoteButton(Command.Lock, state, onSend, Modifier.weight(1f))
                    RemoteButton(Command.Unlock, state, onSend, Modifier.weight(1f))
                }
            }
        }
    }
}

// The pill, "Try now" while out of range or failed, and a line saying what happens next.
// With large text "Try now" moves below the pill instead of squeezing it.
// Connected on battery means a power cut: the board is up but the door cannot move.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionStatus(connection: ConnectionState, power: PowerInfo?, onRetry: () -> Unit) {
    val connected = connection == ConnectionState.Ready || connection == ConnectionState.Busy
    val powerOut = connected && power?.onBattery == true
    val (text, tone) = if (powerOut) {
        R.string.control_status_power_out to StatusTone.Problem
    } else {
        when (connection) {
            ConnectionState.Idle, ConnectionState.Connecting -> R.string.control_status_connecting to StatusTone.Pending
            ConnectionState.WaitingInRange -> R.string.control_status_out_of_range to StatusTone.Pending
            ConnectionState.Ready, ConnectionState.Busy -> R.string.control_status_ready to StatusTone.Ready
            ConnectionState.BluetoothOff -> R.string.control_status_bluetooth_off to StatusTone.Problem
            is ConnectionState.Error -> R.string.control_status_failed to StatusTone.Problem
        }
    }
    val canRetry = connection == ConnectionState.WaitingInRange || connection is ConnectionState.Error
    val hint = when {
        powerOut -> power?.batteryPercent
            ?.let { stringResource(R.string.control_hint_power_out_battery, it) }
            ?: stringResource(R.string.control_hint_power_out)
        connection == ConnectionState.WaitingInRange -> stringResource(R.string.control_hint_out_of_range)
        connection is ConnectionState.Error && connection.error == LinkError.Unsupported ->
            stringResource(R.string.control_hint_unsupported)
        connection is ConnectionState.Error -> stringResource(R.string.control_hint_failed)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xxs)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusPill(stringResource(text), tone, Modifier.align(Alignment.CenterVertically))
            if (canRetry) {
                TextLink(stringResource(R.string.control_retry_now), onRetry, Modifier.align(Alignment.CenterVertically))
            }
        }
        if (hint != null) {
            Text(hint, style = DoorTheme.type.footnote, color = DoorTheme.colors.secondaryLabel)
        }
    }
}

// Skeleton while connecting, dimmed while not connected or during a power cut. While a command is
// in flight its button shows a spinner and the others are dimmed.
@Composable
private fun RemoteButton(command: Command, state: ControlUiState, onSend: (Command) -> Unit, modifier: Modifier) {
    val colors = DoorTheme.colors
    val view = LocalView.current
    val (label, icon, color) = when (command) {
        Command.Up -> Triple(R.string.control_up, Icons.Rounded.KeyboardArrowUp, colors.up)
        Command.Down -> Triple(R.string.control_down, Icons.Rounded.KeyboardArrowDown, colors.down)
        Command.Lock -> Triple(R.string.control_lock, Icons.Rounded.Lock, colors.lock)
        Command.Unlock -> Triple(R.string.control_unlock, Icons.Rounded.LockOpen, colors.unlock)
        else -> error("not a remote button: $command")
    }
    val connected = state.connection == ConnectionState.Ready || state.connection == ConnectionState.Busy
    val powerOut = state.power?.onBattery == true
    DoorButton(
        label = stringResource(label),
        icon = icon,
        color = color,
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onSend(command)
        },
        modifier = modifier.fillMaxHeight(),
        tall = command == Command.Up || command == Command.Down,
        enabled = connected && !powerOut && (state.sending == null || state.sending == command),
        sending = state.sending == command,
        placeholder = state.connection == ConnectionState.Idle || state.connection == ConnectionState.Connecting,
    )
}

// Remembered per message, so the banner's timer does not restart on every recomposition.
@Composable
private fun rememberBanner(message: ControlMessage?, onOpenSettings: () -> Unit): BannerMessage? {
    if (message == null) return null
    val text = stringResource(message.text)
    val settings = stringResource(R.string.settings_title)
    return remember(message, text) {
        when (message) {
            ControlMessage.PairingOpened -> BannerMessage(text, BannerKind.Success)
            // Stays up: the way out is Settings, then Forget device.
            ControlMessage.KeyRejected -> BannerMessage(
                text,
                BannerKind.Error,
                actionLabel = settings,
                onAction = onOpenSettings,
                durationMs = null,
            )
            else -> BannerMessage(text, BannerKind.Error, durationMs = 5_000)
        }
    }
}

private val ControlMessage.text: Int
    get() = when (this) {
        ControlMessage.PairingOpened -> R.string.control_message_pairing_opened
        ControlMessage.NoReply -> R.string.control_message_no_reply
        ControlMessage.KeyRejected -> R.string.control_message_key_rejected
        ControlMessage.LockedOut -> R.string.control_message_locked_out
        ControlMessage.RfFailed -> R.string.control_message_rf_failed
        ControlMessage.NotPermitted -> R.string.control_message_not_permitted
        ControlMessage.Unexpected -> R.string.control_message_unexpected
    }

@Composable
private fun ControlPreview(state: ControlUiState) = RollingDoorTheme {
    Box(Modifier.height(720.dp)) {
        ControlContent(state, gate = null, onSend = {}, onRetry = {}, onDismissMessage = {}, onOpenSettings = {})
    }
}

@DoorPreviews
@Composable
private fun ControlReadyPreview() = ControlPreview(ControlUiState(ConnectionState.Ready))

@DoorPreviews
@Composable
private fun ControlSendingPreview() = ControlPreview(ControlUiState(ConnectionState.Busy, sending = Command.Up))

@DoorPreviews
@Composable
private fun ControlConnectingPreview() = ControlPreview(ControlUiState(ConnectionState.Connecting))

@DoorPreviews
@Composable
private fun ControlOutOfRangePreview() = ControlPreview(ControlUiState(ConnectionState.WaitingInRange))

@DoorPreviews
@Composable
private fun ControlPowerOutPreview() =
    ControlPreview(ControlUiState(ConnectionState.Ready, power = PowerInfo(PowerSource.Battery, 80)))

@DoorPreviews
@Composable
private fun ControlKeyRejectedPreview() =
    ControlPreview(ControlUiState(ConnectionState.Ready, message = ControlMessage.KeyRejected))
