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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.ble.ConnectionState
import com.trananh.rollingdoor.ble.LinkError
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.ButtonIcon
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.PowerInfo
import com.trananh.rollingdoor.protocol.PowerSource
import com.trananh.rollingdoor.protocol.RemoteButton
import com.trananh.rollingdoor.protocol.Role
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

// The remote: connection status and the board's buttons (four on a new board, up to eight), on
// one screen that never scrolls. Everything else (device info, admin actions, editing buttons,
// Forget device) lives in Settings.
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
        isAdmin = device.role == Role.Admin,
        gate = gate.takeIf { it.status != GateStatus.Ready },
        onPress = viewModel::press,
        onRetry = viewModel::retryNow,
        onDismissMessage = viewModel::dismissMessage,
        onOpenSettings = { settingsOpen = true },
    )
    if (settingsOpen) {
        SettingsSheet(
            device = device,
            canAddPhone = state.connection == ConnectionState.Ready && state.sending == null,
            addingPhone = state.sending == Command.OpenPairing,
            onAddPhone = viewModel::openPairing,
            buttons = ButtonActions(
                state.buttons,
                state.learned,
                state.learning,
                state.editing,
                onSave = viewModel::saveButton,
                onDelete = viewModel::deleteButton,
                onLearn = viewModel::learn,
                onClear = viewModel::clearCode,
                onCancelLearn = viewModel::cancelLearn,
                onLeave = viewModel::clearResults,
            ),
            onForget = onForget,
            onDismiss = {
                // Closing the sheet mid-learning turns the board's receiver off.
                viewModel.cancelLearn()
                viewModel.clearResults()
                settingsOpen = false
            },
        )
    }
}

// gate: what still blocks Bluetooth, shown in place of the buttons; null when nothing does.
@Composable
private fun ControlContent(
    state: ControlUiState,
    isAdmin: Boolean,
    gate: BluetoothGate?,
    onPress: (Int) -> Unit,
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
            ButtonGrid(state, isAdmin, onPress, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

// Two buttons per row, rows sharing the height; an odd last button takes the whole row.
// With one or two rows the buttons use DoorButton's tall size.
@Composable
private fun ButtonGrid(state: ControlUiState, isAdmin: Boolean, onPress: (Int) -> Unit, modifier: Modifier) {
    val buttons = state.buttons.buttons
    if (buttons.isEmpty()) {
        Column(modifier, Arrangement.Center, Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.control_empty),
                style = DoorTheme.type.headline,
                color = DoorTheme.colors.label,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(if (isAdmin) R.string.control_empty_admin else R.string.control_empty_normal),
                style = DoorTheme.type.footnote,
                color = DoorTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val rows = buttons.chunked(2)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s)) {
        for (row in rows) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s)) {
                for (button in row) {
                    RemoteButton(button, state, tall = rows.size <= 2, onPress, Modifier.weight(1f))
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

// Skeleton while connecting, dimmed while not connected, during a power cut, or while the board
// has no code for this button. While a command is in flight its button shows a spinner and the
// others are dimmed. Before INFO is read (learned == null) all buttons stay enabled.
@Composable
private fun RemoteButton(
    button: RemoteButton,
    state: ControlUiState,
    tall: Boolean,
    onPress: (Int) -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val connected = state.connection == ConnectionState.Ready || state.connection == ConnectionState.Busy
    val powerOut = state.power?.onBattery == true
    val hasCode = state.learned?.contains(button.id) ?: true
    val pressing = state.pressing == button.id
    DoorButton(
        label = button.displayName(),
        icon = button.icon.vector,
        color = button.icon.circleColor(),
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onPress(button.id)
        },
        modifier = modifier.fillMaxHeight(),
        tall = tall,
        enabled = connected && !powerOut && hasCode && (state.sending == null || pressing),
        sending = pressing,
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
        ControlMessage.NotLearned -> R.string.control_message_not_learned
        ControlMessage.RfBusy -> R.string.control_message_rf_busy
        ControlMessage.NotPermitted -> R.string.control_message_not_permitted
        ControlMessage.Unexpected -> R.string.control_message_unexpected
    }

@Composable
private fun ControlPreview(state: ControlUiState) = RollingDoorTheme {
    Box(Modifier.height(720.dp)) {
        ControlContent(
            state,
            isAdmin = true,
            gate = null,
            onPress = {},
            onRetry = {},
            onDismissMessage = {},
            onOpenSettings = {},
        )
    }
}

@DoorPreviews
@Composable
private fun ControlReadyPreview() = ControlPreview(ControlUiState(ConnectionState.Ready))

@DoorPreviews
@Composable
private fun ControlPartlyLearnedPreview() =
    ControlPreview(ControlUiState(ConnectionState.Ready, learned = setOf(1, 2)))

@DoorPreviews
@Composable
private fun ControlEightButtonsPreview() = ControlPreview(
    ControlUiState(
        ConnectionState.Ready,
        buttons = ButtonList(
            revision = 0,
            buttons = ButtonList.DEFAULT.buttons + listOf(
                RemoteButton(5, ButtonIcon.Stop),
                RemoteButton(6, ButtonIcon.Gate, "Cổng sau"),
                RemoteButton(7, ButtonIcon.Light, "Đèn sân"),
                RemoteButton(8, ButtonIcon.Bell),
            ),
        ),
    ),
)

@DoorPreviews
@Composable
private fun ControlThreeButtonsPreview() = ControlPreview(
    ControlUiState(
        ConnectionState.Ready,
        buttons = ButtonList(0, ButtonList.DEFAULT.buttons.take(2) + RemoteButton(5, ButtonIcon.Garage)),
    ),
)

@DoorPreviews
@Composable
private fun ControlNoButtonsPreview() =
    ControlPreview(ControlUiState(ConnectionState.Ready, buttons = ButtonList(0, emptyList())))

@DoorPreviews
@Composable
private fun ControlSendingPreview() =
    ControlPreview(ControlUiState(ConnectionState.Busy, sending = Command.Press, pressing = 1))

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
