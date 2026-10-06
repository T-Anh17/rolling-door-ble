package com.trananh.rollingdoor.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.DoorProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

// One GATT connection to the door, set up the same way for pairing and for daily use.
// Tracks the CHALLENGE nonce: every frame the firmware receives consumes it, and the firmware
// notifies STATUS first and the next CHALLENGE right after.
class DoorLink(context: Context, device: BluetoothDevice) {
    private sealed interface NonceState {
        data object None : NonceState
        class Fresh(val value: ByteArray) : NonceState
        data object Used : NonceState
        data object Lost : NonceState
    }

    private val nonce = MutableStateFlow<NonceState>(NonceState.None)
    private val statuses = Channel<ByteArray>(Channel.UNLIMITED)
    private val down = CompletableDeferred<Unit>()

    // How long open() spent in connect and in the steps after it, for timing logs.
    var connectMs = 0L
        private set
    var setupMs = 0L
        private set

    private val gatt = GattClient(context, device, object : GattClient.Listener {
        override fun onNotification(uuid: UUID, value: ByteArray) {
            when (uuid) {
                DoorProtocol.CHALLENGE_UUID ->
                    if (value.size == DoorProtocol.NONCE_LENGTH) nonce.value = NonceState.Fresh(value)
                DoorProtocol.STATUS_UUID -> statuses.trySend(value)
            }
        }

        override fun onDisconnected() {
            markLost()
        }
    })

    // connect -> high priority -> [MTU] -> discover -> notify CHALLENGE + STATUS -> read CHALLENGE.
    // connectTimeoutMs = null with autoConnect waits until the device comes into range.
    // largeMtu is only needed for pairing: daily frames fit the default MTU, and the exchange
    // costs ~0.65 s on every connect.
    suspend fun open(
        autoConnect: Boolean = false,
        connectTimeoutMs: Long? = DIRECT_CONNECT_TIMEOUT_MS,
        largeMtu: Boolean = false,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        gatt.connect(autoConnect, connectTimeoutMs)
        val connectedAt = SystemClock.elapsedRealtime()
        connectMs = connectedAt - startedAt
        // Android shortens the interval for discovery by itself, then drops back to ~49 ms; this
        // keeps it short while the link is up (only while the app is on screen), so a command's
        // write and STATUS notify take a few ms instead of a couple of ~49 ms events.
        gatt.requestHighPriority()
        if (largeMtu) gatt.requestMtu(MTU)
        gatt.discoverServices()
        gatt.enableNotifications(DoorProtocol.SERVICE_UUID, DoorProtocol.CHALLENGE_UUID)
        gatt.enableNotifications(DoorProtocol.SERVICE_UUID, DoorProtocol.STATUS_UUID)
        val value = gatt.read(DoorProtocol.SERVICE_UUID, DoorProtocol.CHALLENGE_UUID)
        if (value.size != DoorProtocol.NONCE_LENGTH) {
            throw BleException(LinkError.Unsupported, "CHALLENGE is ${value.size} bytes")
        }
        // A notify that arrived meanwhile is newer than the read; keep it.
        nonce.compareAndSet(NonceState.None, NonceState.Fresh(value))
        setupMs = SystemClock.elapsedRealtime() - connectedAt
    }

    // Waits for a nonce no frame has used yet and marks it used.
    suspend fun takeNonce(): ByteArray {
        while (true) {
            val state = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
                nonce.first { it is NonceState.Fresh || it is NonceState.Lost }
            } ?: throw BleException(LinkError.Timeout, "no fresh CHALLENGE")
            if (state !is NonceState.Fresh) throw BleException(LinkError.Lost)
            if (nonce.compareAndSet(state, NonceState.Used)) return state.value
        }
    }

    // Writes a COMMAND or PAIRING value and waits for STATUS [command][result].
    suspend fun exchange(characteristic: UUID, value: ByteArray, command: Byte): CommandResult {
        while (statuses.tryReceive().isSuccess) Unit // drop anything left from an earlier frame
        gatt.write(DoorProtocol.SERVICE_UUID, characteristic, value)
        val status = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            var status: ByteArray
            do {
                status = statuses.receiveCatching().getOrNull() ?: throw BleException(LinkError.Lost)
            } while (status.size != 2 || status[0] != command)
            status
        } ?: throw BleException(LinkError.Timeout, "no STATUS for command $command")
        return CommandResult.fromCode(status[1])
    }

    suspend fun read(characteristic: UUID): ByteArray =
        gatt.read(DoorProtocol.SERVICE_UUID, characteristic)

    // Returns once the link is down: dropped by the device or closed here.
    suspend fun awaitDown() = down.await()

    fun close() {
        markLost()
        gatt.close()
    }

    private fun markLost() {
        nonce.value = NonceState.Lost
        statuses.close()
        down.complete(Unit)
    }

    private companion object {
        // For the 82-byte PAIRING request and its longer response; NimBLE accepts up to ~255.
        // COMMAND (18 bytes without args), CHALLENGE and STATUS fit the default 23.
        const val MTU = 247
        const val STATUS_TIMEOUT_MS = 3_000L
        const val DIRECT_CONNECT_TIMEOUT_MS = 10_000L
    }
}
