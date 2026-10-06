package com.trananh.rollingdoor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.Command
import com.trananh.rollingdoor.protocol.CommandFrame
import com.trananh.rollingdoor.protocol.CommandResult
import com.trananh.rollingdoor.protocol.DoorProtocol
import com.trananh.rollingdoor.protocol.InviteCode
import com.trananh.rollingdoor.protocol.PhoneList
import com.trananh.rollingdoor.protocol.PowerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
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
// Connects by the saved MAC without scanning. The board takes a few phones at once and keeps
// advertising while it has room; disconnecting in stop() still frees a place for another phone.
//
// scope must run on the main thread: start(), stop(), retryNow(), the Bluetooth receiver and the
// connection loop all touch the same fields. Only opening a link (connect to the first CHALLENGE)
// runs on a background thread, so it goes on while the main thread draws the first frame.
//
// The button list is kept in buttonCache, so the screen shows it before connecting. The board's
// list is read again only when the revision in INFO differs from the cached one.
@SuppressLint("MissingPermission")
class DoorConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: SavedDevice,
    private val signer: CommandSigner,
    private val buttonCache: ButtonCache,
    private val scope: CoroutineScope,
) {
    // Raw BUTTONS values, saved per paired device.
    interface ButtonCache {
        suspend fun load(): ByteArray?
        suspend fun save(value: ByteArray)
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Board power while Ready or Busy; null while not connected or not read yet.
    private val _power = MutableStateFlow<PowerInfo?>(null)
    val power: StateFlow<PowerInfo?> = _power.asStateFlow()

    // Ids of the buttons the board has a code for, while Ready or Busy; null otherwise.
    private val _learned = MutableStateFlow<Set<Int>?>(null)
    val learned: StateFlow<Set<Int>?> = _learned.asStateFlow()

    // The board's button list: cached, or read from the board. Kept while disconnected.
    // null until the cache has loaded, and on a phone that has never read the list.
    private val _buttons = MutableStateFlow<ButtonList?>(null)
    val buttons: StateFlow<ButtonList?> = _buttons.asStateFlow()

    private val cacheLoaded = scope.async {
        val cached = try {
            buttonCache.load()?.let(ButtonList::parse)
        } catch (e: java.io.IOException) {
            null
        }
        if (cached != null) _buttons.compareAndSet(null, cached)
    }

    private val sendMutex = Mutex()
    private var started = false
    private var loopJob: Job? = null
    private var link: DoorLink? = null // set while Ready or Busy

    // Timing logs for the launch-to-ready target, debug builds only.
    private val logTiming = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var startedAt = 0L // first attempt after start() measures from here
    private var startedAfterProcess = 0L // start() time since the process was created

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
        startedAt = SystemClock.elapsedRealtime()
        startedAfterProcess = startedAt - Process.getStartElapsedRealtime()
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
                if (frame.size > DoorProtocol.DEFAULT_MTU_PAYLOAD) current.ensureLargeMtu()
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

    // After LearnRf answered Ok: waits for the board to report how learning ended, without
    // holding the command lock, so LearnRf with LEARN_CANCEL can still be sent meanwhile.
    // Throws BleException if the link drops or nothing arrives in time.
    suspend fun awaitLearned(): CommandResult {
        val current = link ?: throw BleException(LinkError.Lost, "not connected")
        return current.awaitStatus(DoorProtocol.STATUS_RF_LEARNED, LEARN_TIMEOUT_MS)
    }

    // Reads PHONES, which the board fills after LIST_PHONES on this connection. null if the value
    // is malformed. Throws BleException if the link fails.
    suspend fun readPhones(): PhoneList? {
        val current = link ?: throw BleException(LinkError.Lost, "not connected")
        return PhoneList.parse(current.read(DoorProtocol.PHONES_UUID))
    }

    // Reads PAIRING after INVITE and unmasks the digits with this phone's key. null if the value
    // is not an invite. Throws BleException if the link fails.
    suspend fun readInvite(): String? {
        val current = link ?: throw BleException(LinkError.Lost, "not connected")
        val value = current.read(DoorProtocol.PAIRING_UUID)
        val salt = InviteCode.salt(value) ?: return null
        val mask = withContext(Dispatchers.Default) { signer.hmacSha256(InviteCode.maskMessage(salt)) }
        return InviteCode.unmask(value, mask)
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
        _power.value = null
        _learned.value = null
    }

    // Ends only with an error the waiting connection cannot fix (no permission, wrong device
    // firmware, or the waiting connection itself failing), or when cancelled.
    private suspend fun runLoop() {
        try {
            while (true) {
                _state.value = ConnectionState.Connecting
                val attemptAt = SystemClock.elapsedRealtime()
                var waited = false
                // Direct first, also right after a drop: the ESP32 may just have restarted.
                val opened = try {
                    open(autoConnect = false, timeoutMs = DIRECT_TIMEOUT_MS)
                } catch (e: BleException) {
                    if (e.error !in OUT_OF_RANGE_ERRORS) throw e
                    _state.value = ConnectionState.WaitingInRange
                    waited = true
                    open(autoConnect = true, timeoutMs = null)
                }
                try {
                    link = opened
                    _state.value = ConnectionState.Ready
                    if (logTiming) logReady(opened, attemptAt, waited)
                    watchPower(opened)
                } finally {
                    if (link === opened) {
                        link = null
                        _power.value = null
                        _learned.value = null
                    }
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

    // Reads INFO once Ready, then mirrors its notifications until the link is down. Reads
    // BUTTONS whenever INFO reports a revision other than the list's.
    private suspend fun watchPower(opened: DoorLink) = coroutineScope {
        val mirror = launch { opened.power.collect { _power.value = it } }
        val mirrorLearned = launch { opened.learned.collect { _learned.value = it } }
        val syncButtons = launch {
            opened.buttonsRevision.filterNotNull().collectLatest { revision ->
                cacheLoaded.await()
                if (_buttons.value?.revision != revision) readButtons(opened)
            }
        }
        try {
            opened.loadPower()
        } catch (e: BleException) {
            // Power stays unknown and the screen shows no power status. If the link was lost,
            // awaitDown() returns right away.
        }
        opened.awaitDown()
        mirror.cancel()
        mirrorLearned.cancel()
        syncButtons.cancel()
    }

    // A failed read leaves the old list; the next connect tries again.
    private suspend fun readButtons(opened: DoorLink) {
        val value = try {
            opened.read(DoorProtocol.BUTTONS_UUID)
        } catch (e: BleException) {
            return
        }
        val list = ButtonList.parse(value) ?: return
        _buttons.value = list
        try {
            buttonCache.save(value)
        } catch (e: java.io.IOException) {
            // Read again on the next connect.
        }
    }

    // Filter logcat by tag DoorTiming. "since start" counts from start(), so it includes the
    // time from start() to the first connect() call; later reconnects count from their attempt.
    // "process" is when start() ran after the process was created, which only means something
    // on a cold launch.
    private fun logReady(opened: DoorLink, attemptAt: Long, waited: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val since = if (startedAt != 0L) "since start (process +$startedAfterProcess ms)" else "since attempt"
        val from = if (startedAt != 0L) startedAt else attemptAt
        startedAt = 0L
        Log.d(
            TIMING_TAG,
            "ready ${now - from} ms $since: connect ${opened.connectMs} ms, " +
                "setup ${opened.setupMs} ms" + (if (waited) ", after waiting in range" else "") +
                " (steps: ${opened.steps})",
        )
    }

    private suspend fun open(autoConnect: Boolean, timeoutMs: Long?): DoorLink {
        val opening = DoorLink(context, adapter.getRemoteDevice(device.mac))
        try {
            withContext(Dispatchers.Default) { opening.open(autoConnect, timeoutMs) }
            return opening
        } catch (e: Throwable) {
            opening.close()
            throw e
        }
    }

    private companion object {
        const val DIRECT_TIMEOUT_MS = 4_000L

        // The board listens for 15 s; the margin covers the notify on a slow link.
        const val LEARN_TIMEOUT_MS = 20_000L
        const val TIMING_TAG = "DoorTiming"

        // What a direct attempt reports when the device is out of range or restarting
        // (including status 133, which arrives as ConnectFailed).
        val OUT_OF_RANGE_ERRORS = setOf(LinkError.Timeout, LinkError.ConnectFailed, LinkError.Lost)
    }
}
