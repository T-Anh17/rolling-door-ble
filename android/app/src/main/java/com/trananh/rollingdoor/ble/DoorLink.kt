package com.trananh.rollingdoor.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.DoorProtocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

// One GATT connection to the door, set up the same way for pairing and for daily use.
// Tracks the CHALLENGE nonce: every frame the firmware receives consumes it, and the firmware
// notifies STATUS first and the next CHALLENGE right after.
class DoorLink(context: Context, device: BluetoothDevice, onLost: () -> Unit = {}) {
    private sealed interface NonceState {
        data object None : NonceState
        class Fresh(val value: ByteArray) : NonceState
        data object Used : NonceState
        data object Lost : NonceState
    }

    private val nonce = MutableStateFlow<NonceState>(NonceState.None)
    private val statuses = Channel<ByteArray>(Channel.UNLIMITED)

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
            onLost()
        }
    })

    // connect -> MTU -> discover -> notify CHALLENGE + STATUS -> read CHALLENGE.
    suspend fun open() {
        gatt.connect()
        gatt.requestMtu(MTU)
        gatt.discoverServices()
        gatt.enableNotifications(DoorProtocol.SERVICE_UUID, DoorProtocol.CHALLENGE_UUID)
        gatt.enableNotifications(DoorProtocol.SERVICE_UUID, DoorProtocol.STATUS_UUID)
        val value = gatt.read(DoorProtocol.SERVICE_UUID, DoorProtocol.CHALLENGE_UUID)
        if (value.size != DoorProtocol.NONCE_LENGTH) {
            throw BleException(LinkError.Unsupported, "CHALLENGE is ${value.size} bytes")
        }
        // A notify that arrived meanwhile is newer than the read; keep it.
        nonce.compareAndSet(NonceState.None, NonceState.Fresh(value))
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

    fun close() {
        markLost()
        gatt.close()
    }

    private fun markLost() {
        nonce.value = NonceState.Lost
        statuses.close()
    }

    private companion object {
        // Largest frame is the 82-byte PAIRING request; NimBLE accepts up to ~255.
        const val MTU = 247
        const val STATUS_TIMEOUT_MS = 3_000L
    }
}
