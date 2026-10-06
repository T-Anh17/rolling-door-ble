package com.trananh.rollingdoor.ui

import android.bluetooth.BluetoothAdapter
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trananh.rollingdoor.RollingDoorApp
import com.trananh.rollingdoor.data.DeviceRepository
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.ui.control.ControlScreen
import com.trananh.rollingdoor.ui.gate.GateScreen
import com.trananh.rollingdoor.ui.gate.GateStatus
import com.trananh.rollingdoor.ui.gate.rememberBluetoothGate
import com.trananh.rollingdoor.ui.pairing.PairingScreen
import com.trananh.rollingdoor.ui.theme.DoorMotion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

sealed interface RootState {
    data object NotPaired : RootState
    data class Paired(val device: SavedDevice) : RootState
}

// logTiming: debug builds log how long the startup read takes (tag DoorTiming), since the
// connection only starts after it.
class RootViewModel(
    private val repository: DeviceRepository,
    logTiming: Boolean,
) : ViewModel() {
    // Read once while blocking the main thread (~50 ms, under the system splash), so the saved
    // device is known before setContent and the connection starts before the first frame. Read
    // asynchronously, the result waits ~170 ms for the main thread to finish the first frame.
    private val initial: RootState = runBlocking {
        val readAt = SystemClock.elapsedRealtime()
        // A pairing that crashed before its confirming Ping leaves nothing usable: drop it.
        runCatching { repository.discardIncomplete() }
        repository.device.first().toState().also {
            if (logTiming) Log.d("DoorTiming", "saved device read in ${SystemClock.elapsedRealtime() - readAt} ms")
        }
    }

    val state: StateFlow<RootState> =
        repository.device.map { it.toState() }.stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    private fun SavedDevice?.toState(): RootState =
        if (this == null) RootState.NotPaired else RootState.Paired(this)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RollingDoorApp
                val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                RootViewModel(app.container.repository, logTiming = debuggable)
            }
        }
    }
}

// Paired phones go straight to the control screen; the first run goes through the
// permission / Bluetooth gate, then pairing. Forgetting the device (in Settings) is the only
// way back.
@Composable
fun RootScreen(state: RootState, adapter: BluetoothAdapter?) {
    Crossfade(targetState = state, animationSpec = DoorMotion.spring(), label = "root") { current ->
        when (current) {
            RootState.NotPaired -> {
                val gate = rememberBluetoothGate(adapter, forPairing = true)
                if (gate.status == GateStatus.Ready) PairingScreen() else GateScreen(gate)
            }
            is RootState.Paired -> ControlScreen(current.device, adapter)
        }
    }
}
