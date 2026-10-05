package com.trananh.rollingdoor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
    data object Idle : ConnectionState           // stopped: the app is not on screen
    data object Connecting : ConnectionState     // direct attempt
    data object WaitingInRange : ConnectionState // out of range, the controller connects when it shows up
    data object Ready : ConnectionState
    data object Busy : ConnectionState           // a command is in flight
    data object BluetoothOff : ConnectionState
    data class Error(val error: LinkError) : ConnectionState // stopped until retryNow()
}

// Connection to the paired device for the control screen, connected only while the app is on
// screen: start() in onStart, stop() in onStop. No service, scan, timer or wake lock.
//
//   start / link dropped -> Connecting (direct, 4 s) -> Ready
//                                  | fails
//                                  v
//                           WaitingInRange (autoConnect = true, no timeout) -> Ready
//
// Connects by the saved MAC without scanning. Disconnecting in stop() also matters for other
// phones: the ESP32 stops advertising while one phone holds the connection.
//
// scope must run on the main thread: start(), stop(), retryNow(), the Bluetooth receiver and the
// connection loop all touch the same fields.
@SuppressLint("MissingPermission")
class DoorConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: SavedDevice,
    private val signer: CommandSigner,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val sendMutex = Mutex()
    private var started = false
    private var loopJob: Job? = null
    private var link: DoorLink? = null // set while Ready or Busy

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopLoop()
                    _state.value = ConnectionState.BluetoothOff
                }
                BluetoothAdapter.STATE_ON -> if (started) startLoop()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        // Registered in code and only while started: nothing wakes the app when it is closed.
        ContextCompat.registerReceiver(
            context,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (adapter.isEnabled) startLoop() else _state.value = ConnectionState.BluetoothOff
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(bluetoothReceiver)
        stopLoop()
        _state.value = ConnectionState.Idle
    }

    // "Try now" while out of range or after an error: drops the pending wait, connects directly.
    fun retryNow() {
        if (!started || !adapter.isEnabled) return
        val current = _state.value
        if (current == ConnectionState.WaitingInRange || current is ConnectionState.Error) startLoop()
    }

    // Signs with the current nonce, writes COMMAND and waits for STATUS. One command at a time.
    // Throws BleException if the link fails; the link is then dropped and the loop reconnects.
    suspend fun send(command: Command, args: ByteArray = ByteArray(0)): CommandResult =
        sendMutex.withLock {
            val current = link ?: throw BleException(LinkError.Lost, "not connected")
            _state.value = ConnectionState.Busy
            try {
                val nonce = current.takeNonce()
                val frame = withContext(Dispatchers.Default) {
                    CommandFrame.build(device.keyId, command, args, nonce, signer)
                }
                current.exchange(DoorProtocol.COMMAND_UUID, frame, command.code)
            } catch (e: BleException) {
                // A missing STATUS or CHALLENGE leaves the nonce state unknown: start over.
                current.close()
                throw e
            } finally {
                if (link === current && _state.value == ConnectionState.Busy) {
                    _state.value = ConnectionState.Ready
                }
            }
        }

    private fun startLoop() {
        stopLoop()
        loopJob = scope.launch { runLoop() }
    }

    // Cancelling the loop closes whatever link or attempt it holds.
    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        link = null
    }

    // Ends only with an error the waiting connection cannot fix (no permission, wrong device
    // firmware, or the waiting connection itself failing), or when cancelled.
    private suspend fun runLoop() {
        try {
            while (true) {
                _state.value = ConnectionState.Connecting
                // Direct first, also right after a drop: the ESP32 may just have restarted.
                val opened = try {
                    open(autoConnect = false, timeoutMs = DIRECT_TIMEOUT_MS)
                } catch (e: BleException) {
                    if (e.error !in OUT_OF_RANGE_ERRORS) throw e
                    _state.value = ConnectionState.WaitingInRange
                    open(autoConnect = true, timeoutMs = null)
                }
                try {
                    link = opened
                    _state.value = ConnectionState.Ready
                    opened.awaitDown()
                } finally {
                    if (link === opened) link = null
                    opened.close()
                }
            }
        } catch (e: BleException) {
            _state.value = if (e.error == LinkError.BluetoothOff) {
                ConnectionState.BluetoothOff
            } else {
                ConnectionState.Error(e.error)
            }
        }
    }

    private suspend fun open(autoConnect: Boolean, timeoutMs: Long?): DoorLink {
        val opening = DoorLink(context, adapter.getRemoteDevice(device.mac))
        try {
            opening.open(autoConnect, timeoutMs)
            return opening
        } catch (e: Throwable) {
            opening.close()
            throw e
        }
    }

    private companion object {
        const val DIRECT_TIMEOUT_MS = 4_000L

        // What a direct attempt reports when the device is out of range or restarting
        // (including status 133, which arrives as ConnectFailed).
        val OUT_OF_RANGE_ERRORS = setOf(LinkError.Timeout, LinkError.ConnectFailed, LinkError.Lost)
    }
}
