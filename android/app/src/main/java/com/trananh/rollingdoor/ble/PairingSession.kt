package com.trananh.rollingdoor.ble

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.crypto.PairingCrypto
import com.trananh.rollingdoor.crypto.PairingException
import com.trananh.rollingdoor.data.DeviceRepository
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.CommandFrame
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.DoorProtocol
import com.trananh.rollingdoor.protocol.PhoneList
import com.trananh.rollingdoor.protocol.SetupCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface PairingProgress {
    data object Searching : PairingProgress

    // pairingAdvertised = false: the scan saw no "pairing open" flag; the device will most
    // likely answer PairingClosed, so the UI can already hint at the BOOT button.
    data class Connecting(val pairingAdvertised: Boolean) : PairingProgress
    data object ExchangingKey : PairingProgress
    data object Confirming : PairingProgress
}

enum class PairingError {
    BluetoothOff,
    NoPermission,
    NotFound,
    ConnectionFailed,
    PairingClosed,   // 06: hold BOOT for 3 s and try again
    WrongCode,       // 02: the QR code belongs to another device
    LockedOut,       // 05: too many failures, wait 60 s
    TableFull,       // 07: 8 phones already paired
    InvalidResponse, // GCM failure or an unexpected answer
}

sealed interface PairingResult {
    data class Success(val device: SavedDevice) : PairingResult
    data class Failure(val error: PairingError) : PairingResult
}

// Runs the whole pairing on one connection: the device keeps the new key in a pending slot
// that only this connection can confirm, with a Ping signed by that key.
class PairingSession(
    private val context: Context,
    private val scanner: DeviceScanner,
    private val repository: DeviceRepository,
) {
    // The caller keeps code.secret for "Try again" and wipes it when the pairing screen closes.
    suspend fun run(code: SetupCode, onProgress: (PairingProgress) -> Unit): PairingResult {
        onProgress(PairingProgress.Searching)
        val found = try {
            scanner.find(code.mac)
        } catch (e: BleException) {
            return PairingResult.Failure(e.toPairingError())
        } ?: return PairingResult.Failure(PairingError.NotFound)

        onProgress(PairingProgress.Connecting(found.pairingOpen))
        val mac = found.device.address
        val link = DoorLink(context, found.device)
        var saved = false
        var confirmed = false
        try {
            link.open(largeMtu = true)

            onProgress(PairingProgress.ExchangingKey)
            val crypto = withContext(Dispatchers.Default) { PairingCrypto() }
            val nonce = link.takeNonce()
            val request = crypto.request(code.secret, nonce)
            val result = link.exchange(DoorProtocol.PAIRING_UUID, request, DoorProtocol.STATUS_PAIRING)
            if (result != CommandResult.Ok) return PairingResult.Failure(result.toPairingError())

            val response = link.read(DoorProtocol.PAIRING_UUID)
            val key = try {
                withContext(Dispatchers.Default) { crypto.open(response, code.secret, nonce) }
            } catch (e: PairingException) {
                return PairingResult.Failure(PairingError.InvalidResponse)
            }
            val device = SavedDevice(mac, key.keyId, key.role)
            repository.savePending(mac, key)
            saved = true

            onProgress(PairingProgress.Confirming)
            val signer = repository.signer() ?: return PairingResult.Failure(PairingError.InvalidResponse)
            val pingNonce = link.takeNonce()
            val ping = withContext(Dispatchers.Default) {
                CommandFrame.build(device.keyId, Command.Ping, nonce = pingNonce, signer = signer)
            }
            if (link.exchange(DoorProtocol.COMMAND_UUID, ping, Command.Ping.code) != CommandResult.Ok) {
                return PairingResult.Failure(PairingError.InvalidResponse)
            }
            repository.confirm()
            confirmed = true
            nameOnBoard(link, device.keyId, signer)
            return PairingResult.Success(device)
        } catch (e: BleException) {
            return PairingResult.Failure(e.toPairingError())
        } finally {
            link.close()
            if (saved && !confirmed) withContext(NonCancellable) { repository.forget() }
        }
    }

    // Names the new slot after this phone (the name in Android's About phone), so the admin's
    // phone list tells phones apart. Best effort: the pairing is already confirmed, and the
    // admin can name the phone later.
    private suspend fun nameOnBoard(link: DoorLink, keyId: Int, signer: CommandSigner) {
        val name = ButtonList.fitName(deviceName()).trim()
        if (name.isEmpty()) return
        try {
            val nonce = link.takeNonce()
            val frame = withContext(Dispatchers.Default) {
                CommandFrame.build(keyId, Command.RenamePhone, PhoneList.renameArgs(keyId, name), nonce, signer)
            }
            link.exchange(DoorProtocol.COMMAND_UUID, frame, Command.RenamePhone.code)
        } catch (e: BleException) {
            // Stays unnamed: the app shows "Phone <n>".
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun BleException.toPairingError() = when (error) {
        LinkError.BluetoothOff -> PairingError.BluetoothOff
        LinkError.NoPermission -> PairingError.NoPermission
        else -> PairingError.ConnectionFailed
    }

    private fun CommandResult.toPairingError() = when (this) {
        CommandResult.PairingClosed -> PairingError.PairingClosed
        CommandResult.AuthFailed -> PairingError.WrongCode
        CommandResult.LockedOut -> PairingError.LockedOut
        CommandResult.TableFull -> PairingError.TableFull
        else -> PairingError.InvalidResponse
    }
}
