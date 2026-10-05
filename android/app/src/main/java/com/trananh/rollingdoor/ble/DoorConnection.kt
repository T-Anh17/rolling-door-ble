package com.trananh.rollingdoor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.CommandFrame
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.DoorProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Ready : ConnectionState
    data object Busy : ConnectionState
    data class Error(val error: LinkError) : ConnectionState
}

// Connection to the paired device for the control screen. Connects straight to the saved MAC,
// without scanning. Follows the Activity: connect() in onStart, disconnect() in onStop, because
// the ESP32 stops advertising while one phone holds the connection.
//
// scope must run on the main thread: connect(), disconnect() and the lost-link callback
// all touch the same fields.
@SuppressLint("MissingPermission")
class DoorConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: SavedDevice,
    private val signer: CommandSigner,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val sendMutex = Mutex()
    private var link: DoorLink? = null
    private var connectJob: Job? = null
    private var generation = 0 // ignores callbacks from links that were already replaced

    fun connect() {
        if (connectJob?.isActive == true || link != null) return
        if (!adapter.isEnabled) {
            _state.value = ConnectionState.Error(LinkError.BluetoothOff)
            return
        }
        val current = ++generation
        val newLink = DoorLink(context, adapter.getRemoteDevice(device.mac)) {
            scope.launch { onLost(current) }
        }
        _state.value = ConnectionState.Connecting
        connectJob = scope.launch {
            try {
                newLink.open()
                if (current == generation) {
                    link = newLink
                    _state.value = ConnectionState.Ready
                }
            } catch (e: BleException) {
                newLink.close()
                if (current == generation) _state.value = ConnectionState.Error(e.error)
            } catch (e: Throwable) {
                newLink.close()
                throw e
            }
        }
    }

    fun disconnect() {
        generation++
        connectJob?.cancel()
        connectJob = null
        link?.close()
        link = null
        _state.value = ConnectionState.Disconnected
    }

    // Signs with the current nonce, writes COMMAND and waits for STATUS. One command at a time.
    // Throws BleException if the link fails; the state then shows the error.
    suspend fun send(command: Command, args: ByteArray = ByteArray(0)): CommandResult =
        sendMutex.withLock {
            val current = link ?: throw BleException(LinkError.Lost, "not connected")
            val linkGeneration = generation
            _state.value = ConnectionState.Busy
            try {
                val nonce = current.takeNonce()
                val frame = withContext(Dispatchers.Default) {
                    CommandFrame.build(device.keyId, command, args, nonce, signer)
                }
                current.exchange(DoorProtocol.COMMAND_UUID, frame, command.code)
            } catch (e: BleException) {
                // A missing STATUS or CHALLENGE leaves the nonce state unknown: start over.
                if (linkGeneration == generation) {
                    current.close()
                    link = null
                    _state.value = ConnectionState.Error(e.error)
                }
                throw e
            } finally {
                if (linkGeneration == generation && _state.value == ConnectionState.Busy) {
                    _state.value = ConnectionState.Ready
                }
            }
        }

    private fun onLost(lostGeneration: Int) {
        if (lostGeneration != generation) return
        link?.close()
        link = null
        connectJob?.cancel()
        _state.value = ConnectionState.Error(LinkError.Lost)
    }
}
