package com.trananh.rollingdoor.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trananh.rollingdoor.RollingDoorApp
import com.trananh.rollingdoor.ble.BleException
import com.trananh.rollingdoor.ble.ConnectionState
import com.trananh.rollingdoor.ble.DoorConnection
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.PowerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Shown in the banner after a command. Door commands that succeed show nothing: the door moving
// is the answer, as with the remote.
enum class ControlMessage {
    PairingOpened, // Add phone: the device takes a new phone for 60 seconds
    NoReply,       // the link failed mid-command; the door may or may not have moved
    KeyRejected,   // the device no longer knows this phone's key, or the key is gone from Keystore
    LockedOut,     // too many failed signatures: the device ignores this phone for 60 seconds
    RfFailed,
    NotPermitted,  // an admin command from a normal phone
    Unexpected,
}

data class ControlUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val power: PowerInfo? = null, // known only while connected
    val sending: Command? = null, // in flight; the other buttons wait for it
    val message: ControlMessage? = null,
)

// newConnection gets viewModelScope, which runs on the main thread as DoorConnection requires.
class ControlViewModel(newConnection: (CoroutineScope) -> DoorConnection) : ViewModel() {
    private val connection = newConnection(viewModelScope)
    private val sending = MutableStateFlow<Command?>(null)
    private val message = MutableStateFlow<ControlMessage?>(null)

    val state: StateFlow<ControlUiState> =
        combine(connection.state, connection.power, sending, message, ::ControlUiState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ControlUiState())

    // Called while the screen is visible and Bluetooth is usable.
    fun start() = connection.start()

    fun stop() = connection.stop()

    fun retryNow() = connection.retryNow()

    // Ignored unless connected and idle: one command at a time, as with the remote.
    fun send(command: Command) {
        if (sending.value != null || connection.state.value != ConnectionState.Ready) return
        sending.value = command
        viewModelScope.launch {
            val result = try {
                messageFor(command, connection.send(command))
            } catch (e: BleException) {
                ControlMessage.NoReply
            } catch (e: IllegalStateException) {
                ControlMessage.KeyRejected
            } finally {
                sending.value = null
            }
            if (result != null) message.value = result
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    override fun onCleared() = connection.stop()

    private fun messageFor(command: Command, result: CommandResult): ControlMessage? = when (result) {
        CommandResult.Ok -> if (command == Command.OpenPairing) ControlMessage.PairingOpened else null
        CommandResult.AuthFailed -> ControlMessage.KeyRejected
        CommandResult.LockedOut -> ControlMessage.LockedOut
        CommandResult.RfError -> ControlMessage.RfFailed
        CommandResult.NotPermitted -> ControlMessage.NotPermitted
        CommandResult.BadCommand,
        CommandResult.PairingClosed,
        CommandResult.TableFull,
        CommandResult.Unknown -> ControlMessage.Unexpected
    }

    companion object {
        // Keyed by slot too: pairing the same device again gives a new key. MainActivity and
        // ControlScreen both get the ViewModel by this key, so they share one connection.
        fun key(device: SavedDevice) = "control-${device.mac}-${device.keyId}"

        fun factory(device: SavedDevice) = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as RollingDoorApp).container
                ControlViewModel { scope -> container.doorConnection(device, scope) }
            }
        }
    }
}
