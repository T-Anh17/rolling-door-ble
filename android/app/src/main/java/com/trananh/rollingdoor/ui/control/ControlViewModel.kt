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
import com.trananh.rollingdoor.data.DeviceRepository
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.DoorProtocol
import com.trananh.rollingdoor.protocol.InviteCode
import com.trananh.rollingdoor.protocol.PhoneList
import com.trananh.rollingdoor.protocol.PowerInfo
import com.trananh.rollingdoor.protocol.RemoteButton
import com.trananh.rollingdoor.protocol.Role
import com.trananh.rollingdoor.protocol.SetupCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Shown in the banner after a command. Door commands that succeed show nothing: the door moving
// is the answer, as with the remote.
enum class ControlMessage {
    NoReply,       // the link failed mid-command; the door may or may not have moved
    KeyRejected,   // the device no longer knows this phone's key, or the key is gone from Keystore
    LockedOut,     // too many failed signatures: the device ignores this phone for 60 seconds
    NotLearned,    // RfError for a button the board has no code for
    RfBusy,        // RfError for a learned button: the receiver is in use (learning from the console)
    NotPermitted,  // an admin command from a normal phone
    Unexpected,
}

enum class LearnOutcome {
    Learned,
    NotHeard, // no code received twice in 15 s
    Busy,     // the board's receiver is in use (learning from the serial console)
    Failed,   // link lost or an unexpected answer
    Cleared,
    ClearFailed,
}

data class LearnResult(val button: Int, val outcome: LearnOutcome)

// Learning remote codes from the admin's Settings. Buttons are given by id.
data class RfLearning(
    val listening: Int? = null, // the board's receiver is on, waiting for this button's code
    val clearing: Int? = null, // ClearRf in flight for this button
    val result: LearnResult? = null, // how the last attempt ended, until the page is left
)

enum class EditOutcome { Saved, SaveFailed, Deleted, DeleteFailed, Busy }

data class EditResult(val button: Int, val outcome: EditOutcome)

// Adding, changing and deleting buttons from the admin's Settings.
data class ButtonEditing(
    val saving: Int? = null, // SetButton in flight for this button id
    val deleting: Int? = null, // DeleteButton in flight
    val result: EditResult? = null, // until the page is left
)

enum class PhoneOutcome { Saved, SaveFailed, Revoked, RevokeFailed, MadeAdmin, MakeAdminFailed }

data class PhoneResult(val keyId: Int, val outcome: PhoneOutcome)

// The board's phones, read when Settings opens. Every phone reads them to learn its own role
// (an admin may have handed it over); only the admin's Settings shows the list.
data class PhonesState(
    val list: PhoneList? = null, // the last list read while Settings is open
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Int? = null, // RenamePhone in flight for this key id
    val revoking: Int? = null,
    val promoting: Int? = null, // MakeAdmin in flight
    val result: PhoneResult? = null, // until the page is left
)

// Add phone: the board's 8 digits for a new phone, and the same as a QR code. The board takes
// several phones at once, so this one stays connected meanwhile.
data class InviteState(
    val creating: Boolean = false,
    val digits: String? = null,
    val qr: String? = null, // RDOOR1 text with the secret derived from the digits
    val failed: Boolean = false,
    val expired: Boolean = false,
)

// Forget device: the board is asked to free this phone's slot first.
enum class LeaveState {
    Leaving,
    MustHandOver, // the admin, with other phones paired: make one of them admin first
    Offline,      // the board cannot be reached; a normal phone may still forget
    OfflineAdmin, // the same for the admin, which may not: the board would be left without one
    Failed,
}

data class ControlUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val power: PowerInfo? = null, // known only while connected
    // The board's list, cached or read; a new board's four buttons until either is known.
    val buttons: ButtonList = ButtonList.DEFAULT,
    val learned: Set<Int>? = null, // ids of buttons the board has a code for, while connected
    val sending: Command? = null, // in flight; the buttons wait for it (LearnRf while learning)
    val pressing: Int? = null, // the button whose Press is in flight
    val message: ControlMessage? = null,
    val learning: RfLearning = RfLearning(),
    val editing: ButtonEditing = ButtonEditing(),
    val phones: PhonesState = PhonesState(),
    val leave: LeaveState? = null,
    val invite: InviteState = InviteState(),
)

// newConnection gets viewModelScope, which runs on the main thread as DoorConnection requires.
class ControlViewModel(
    private val device: SavedDevice,
    private val repository: DeviceRepository,
    newConnection: (CoroutineScope) -> DoorConnection,
) : ViewModel() {
    // What this screen does, as opposed to what the connection reports.
    private data class Local(
        val sending: Command? = null,
        val pressing: Int? = null,
        val message: ControlMessage? = null,
        val learning: RfLearning = RfLearning(),
        val editing: ButtonEditing = ButtonEditing(),
        val phones: PhonesState = PhonesState(),
        val leave: LeaveState? = null,
        val invite: InviteState = InviteState(),
    )

    private val connection = newConnection(viewModelScope)
    private val local = MutableStateFlow(Local())
    private var learnJob: Job? = null
    private var inviteJob: Job? = null

    val state: StateFlow<ControlUiState> =
        combine(connection.state, connection.power, connection.buttons, connection.learned, local) {
            connection, power, buttons, learned, local ->
            ControlUiState(
                connection,
                power,
                buttons ?: ButtonList.DEFAULT,
                learned,
                local.sending,
                local.pressing,
                local.message,
                local.learning,
                local.editing,
                local.phones,
                local.leave,
                local.invite,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ControlUiState())

    // Called while the screen is visible and Bluetooth is usable.
    fun start() = connection.start()

    fun stop() {
        // The board stops learning by itself when the phone disconnects.
        learnJob?.cancel()
        local.update { it.copy(learning = RfLearning()) }
        connection.stop()
    }

    fun retryNow() = connection.retryNow()

    // Ignored unless connected and idle: one command at a time, as with the remote.
    fun press(button: Int) {
        if (!begin(Command.Press)) return
        local.update { it.copy(pressing = button) }
        viewModelScope.launch {
            val result = try {
                pressMessage(button, connection.send(Command.Press, byteArrayOf(button.toByte())))
            } catch (e: BleException) {
                ControlMessage.NoReply
            } catch (e: IllegalStateException) {
                ControlMessage.KeyRejected
            }
            local.update { it.copy(sending = null, pressing = null, message = result ?: it.message) }
        }
    }

    // Admin only. Gets 8 digits for a new phone from the board; they expire after 5 minutes.
    fun invite() {
        if (!begin(Command.Invite)) return
        inviteJob?.cancel()
        local.update { it.copy(invite = InviteState(creating = true)) }
        inviteJob = viewModelScope.launch {
            val digits = try {
                if (connection.send(Command.Invite) == CommandResult.Ok) connection.readInvite() else null
            } catch (e: BleException) {
                null
            } catch (e: IllegalStateException) {
                null
            }
            if (digits == null) {
                local.update { it.copy(sending = null, invite = InviteState(failed = true)) }
                return@launch
            }
            val qr = SetupCode(device.mac, InviteCode.secret(digits)).format()
            local.update { it.copy(sending = null, invite = InviteState(digits = digits, qr = qr)) }
            delay(InviteCode.VALID_MS)
            local.update { it.copy(invite = it.invite.copy(expired = true)) }
        }
    }

    // Leaves the invite page: forgets the digits. reloadPhones: read the list again, which then
    // shows a phone that has just paired.
    fun closeInvite(reloadPhones: Boolean = false) {
        inviteJob?.cancel()
        inviteJob = null
        local.update { it.copy(sending = if (it.sending == Command.Invite) null else it.sending, invite = InviteState()) }
        if (reloadPhones) loadPhones()
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // Admin only. Turns on the board's receiver for this button; the result arrives when the
    // board decodes the code twice or gives up after 15 s. Ignored unless connected and idle.
    fun learn(button: Int) {
        if (!begin(Command.LearnRf)) return
        local.update { it.copy(learning = RfLearning(listening = button)) }
        learnJob = viewModelScope.launch {
            val outcome = try {
                when (connection.send(Command.LearnRf, byteArrayOf(button.toByte()))) {
                    CommandResult.Ok -> when (connection.awaitLearned()) {
                        CommandResult.Ok -> LearnOutcome.Learned
                        CommandResult.RfError -> LearnOutcome.NotHeard
                        else -> LearnOutcome.Failed
                    }
                    CommandResult.RfError -> LearnOutcome.Busy
                    else -> LearnOutcome.Failed
                }
            } catch (e: CancellationException) {
                local.update { it.copy(sending = null) }
                learnJob = null
                throw e // cancelLearn() or stop(); also an IllegalStateException, so caught first
            } catch (e: BleException) {
                LearnOutcome.Failed
            } catch (e: IllegalStateException) {
                LearnOutcome.Failed
            }
            learnJob = null
            local.update {
                it.copy(sending = null, learning = RfLearning(result = LearnResult(button, outcome)))
            }
        }
    }

    // Stops waiting here and tells the board to turn its receiver off.
    fun cancelLearn() {
        val job = learnJob ?: return
        job.cancel()
        local.update { it.copy(learning = RfLearning()) }
        viewModelScope.launch {
            job.join()
            try {
                connection.send(Command.LearnRf, byteArrayOf(DoorProtocol.LEARN_CANCEL))
            } catch (e: BleException) {
                // The link is gone, and the board stops learning when it notices.
            } catch (e: IllegalStateException) {
                // Key gone from Keystore; the board gives up after 15 s.
            }
        }
    }

    // Admin only. Removes the board's code for this button: pressing it then fails with
    // RfError until it is learned again. Ignored unless connected and idle.
    fun clearCode(button: Int) {
        if (!begin(Command.ClearRf)) return
        local.update { it.copy(learning = RfLearning(clearing = button)) }
        viewModelScope.launch {
            val outcome = try {
                when (connection.send(Command.ClearRf, byteArrayOf(button.toByte()))) {
                    CommandResult.Ok -> LearnOutcome.Cleared
                    CommandResult.RfError -> LearnOutcome.Busy
                    else -> LearnOutcome.ClearFailed
                }
            } catch (e: BleException) {
                LearnOutcome.ClearFailed
            } catch (e: IllegalStateException) {
                LearnOutcome.ClearFailed
            }
            local.update {
                it.copy(sending = null, learning = RfLearning(result = LearnResult(button, outcome)))
            }
        }
    }

    // Admin only. Adds the button (a free id) or changes its icon and name. The board then
    // reports a new list revision and the list is read again.
    fun saveButton(button: RemoteButton) {
        if (!begin(Command.SetButton)) return
        local.update { it.copy(editing = ButtonEditing(saving = button.id)) }
        viewModelScope.launch {
            val outcome = try {
                when (connection.send(Command.SetButton, ButtonList.setArgs(button))) {
                    CommandResult.Ok -> EditOutcome.Saved
                    else -> EditOutcome.SaveFailed
                }
            } catch (e: BleException) {
                EditOutcome.SaveFailed
            } catch (e: IllegalStateException) {
                EditOutcome.SaveFailed
            }
            local.update {
                it.copy(sending = null, editing = ButtonEditing(result = EditResult(button.id, outcome)))
            }
        }
    }

    // Admin only. Deletes the button and its learned code.
    fun deleteButton(button: Int) {
        if (!begin(Command.DeleteButton)) return
        local.update { it.copy(editing = ButtonEditing(deleting = button)) }
        viewModelScope.launch {
            val outcome = try {
                when (connection.send(Command.DeleteButton, byteArrayOf(button.toByte()))) {
                    CommandResult.Ok -> EditOutcome.Deleted
                    CommandResult.RfError -> EditOutcome.Busy
                    else -> EditOutcome.DeleteFailed
                }
            } catch (e: BleException) {
                EditOutcome.DeleteFailed
            } catch (e: IllegalStateException) {
                EditOutcome.DeleteFailed
            }
            local.update {
                it.copy(sending = null, editing = ButtonEditing(result = EditResult(button, outcome)))
            }
        }
    }

    // Reads the board's phones; also updates this phone's role if the admin handed it over.
    fun loadPhones() {
        if (!begin(Command.ListPhones)) return
        local.update { it.copy(phones = it.phones.copy(loading = true, loadFailed = false)) }
        viewModelScope.launch {
            val list = fetchPhones()
            local.update {
                it.copy(
                    sending = null,
                    phones = it.phones.copy(list = list ?: it.phones.list, loading = false, loadFailed = list == null),
                )
            }
        }
    }

    // Admin only for other phones. The name must already fit (ButtonList.fitName).
    fun renamePhone(keyId: Int, name: String) =
        changePhone(Command.RenamePhone, PhoneList.renameArgs(keyId, name), keyId, PhoneOutcome.Saved) {
            it.copy(saving = keyId)
        }

    // Admin only, never this phone: its key stops working at once.
    fun revokePhone(keyId: Int) =
        changePhone(Command.RevokePhone, byteArrayOf(keyId.toByte()), keyId, PhoneOutcome.Revoked) {
            it.copy(revoking = keyId)
        }

    // Admin only: keyId becomes the admin and this phone a normal one. The role saved here is
    // updated from the list read right after.
    fun makeAdmin(keyId: Int) =
        changePhone(Command.MakeAdmin, byteArrayOf(keyId.toByte()), keyId, PhoneOutcome.MadeAdmin) {
            it.copy(promoting = keyId)
        }

    // Forget device: asks the board to free this phone's slot, then forgets the device here.
    // A board that no longer knows the key has nothing to free.
    fun leave() {
        viewModelScope.launch {
            val isAdmin = repository.device.first()?.role == Role.Admin
            val offline = if (isAdmin) LeaveState.OfflineAdmin else LeaveState.Offline
            val connected = connection.state.value.let { it == ConnectionState.Ready || it == ConnectionState.Busy }
            if (!connected) {
                local.update { it.copy(leave = offline) }
                return@launch
            }
            if (!begin(Command.Leave)) return@launch
            local.update { it.copy(leave = LeaveState.Leaving) }
            val result = try {
                connection.send(Command.Leave)
            } catch (e: BleException) {
                null
            } catch (e: IllegalStateException) {
                CommandResult.AuthFailed // the key is gone from Keystore: nothing left to sign with
            }
            val next = when (result) {
                CommandResult.Ok, CommandResult.AuthFailed -> null
                CommandResult.NotPermitted -> LeaveState.MustHandOver
                null -> offline
                else -> LeaveState.Failed
            }
            local.update { it.copy(sending = null, leave = next) }
            if (next == null) repository.forget()
        }
    }

    // Only after LeaveState.Offline: the slot stays on the board until the admin revokes it.
    fun forgetAnyway() {
        viewModelScope.launch { repository.forget() }
    }

    // Leaving a page forgets the last learning, editing and phone results.
    fun clearResults() {
        local.update {
            it.copy(
                learning = if (learnJob == null) RfLearning() else it.learning,
                editing = it.editing.copy(result = null),
                phones = it.phones.copy(result = null, loadFailed = false),
                leave = if (it.leave == LeaveState.Leaving) it.leave else null,
            )
        }
    }

    // Closing Settings drops the list too; it is read again next time.
    fun closeSettings() {
        closeInvite()
        clearResults()
        local.update { it.copy(phones = PhonesState(loading = it.phones.loading)) }
    }

    override fun onCleared() = connection.stop()

    // Marks command as in flight; false (and nothing sent) unless connected and idle.
    private fun begin(command: Command): Boolean {
        if (connection.state.value != ConnectionState.Ready) return false
        var started = false
        local.update {
            if (it.sending != null) return@update it
            started = true
            it.copy(sending = command)
        }
        return started
    }

    // Sends a change to the phone table, then reads the list again so the pages show the board's.
    private fun changePhone(
        command: Command,
        args: ByteArray,
        keyId: Int,
        ok: PhoneOutcome,
        inFlight: (PhonesState) -> PhonesState,
    ) {
        if (!begin(command)) return
        local.update { it.copy(phones = inFlight(it.phones.copy(result = null))) }
        viewModelScope.launch {
            val done = try {
                connection.send(command, args) == CommandResult.Ok
            } catch (e: BleException) {
                false
            } catch (e: IllegalStateException) {
                false
            }
            val list = if (done) fetchPhones() else null
            val outcome = if (done) ok else ok.failed()
            local.update {
                it.copy(
                    sending = null,
                    phones = PhonesState(list = list ?: it.phones.list, result = PhoneResult(keyId, outcome)),
                )
            }
        }
    }

    // LIST_PHONES then PHONES. Saves this phone's role when the board's differs. null on failure.
    private suspend fun fetchPhones(): PhoneList? {
        val list = try {
            if (connection.send(Command.ListPhones) != CommandResult.Ok) return null
            connection.readPhones()
        } catch (e: BleException) {
            null
        } catch (e: IllegalStateException) {
            null
        } ?: return null
        val role = list[device.keyId]?.role
        if (role != null && role != repository.device.first()?.role) repository.setRole(role)
        return list
    }

    private fun PhoneOutcome.failed() = when (this) {
        PhoneOutcome.Saved -> PhoneOutcome.SaveFailed
        PhoneOutcome.Revoked -> PhoneOutcome.RevokeFailed
        PhoneOutcome.MadeAdmin -> PhoneOutcome.MakeAdminFailed
        else -> this
    }

    private fun pressMessage(button: Int, result: CommandResult): ControlMessage? = when (result) {
        CommandResult.Ok -> null
        // The board refuses to transmit only without a code or while its receiver is in use.
        CommandResult.RfError ->
            if (connection.learned.value?.contains(button) == true) ControlMessage.RfBusy else ControlMessage.NotLearned
        else -> adminMessage(result)
    }

    private fun adminMessage(result: CommandResult): ControlMessage = when (result) {
        CommandResult.AuthFailed -> ControlMessage.KeyRejected
        CommandResult.LockedOut -> ControlMessage.LockedOut
        CommandResult.NotPermitted -> ControlMessage.NotPermitted
        else -> ControlMessage.Unexpected
    }

    companion object {
        // Keyed by slot too: pairing the same device again gives a new key. MainActivity and
        // ControlScreen both get the ViewModel by this key, so they share one connection.
        fun key(device: SavedDevice) = "control-${device.mac}-${device.keyId}"

        fun factory(device: SavedDevice) = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as RollingDoorApp).container
                ControlViewModel(device, container.repository) { scope ->
                    container.doorConnection(device, scope)
                }
            }
        }
    }
}
