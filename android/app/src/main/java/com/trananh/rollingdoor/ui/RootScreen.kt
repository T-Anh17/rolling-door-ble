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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RootState {
    data object Loading : RootState // the splash screen stays up
    data object NotPaired : RootState
    data class Paired(val device: SavedDevice) : RootState
}

// logTiming: debug builds log how long the startup check takes (tag DoorTiming), since the
// connection only starts after it.
class RootViewModel(
    private val repository: DeviceRepository,
    private val logTiming: Boolean,
) : ViewModel() {
    val state: StateFlow<RootState> = flow {
        // A pairing that crashed before its confirming Ping leaves nothing usable: drop it.
        val checkAt = SystemClock.elapsedRealtime()
        runCatching { repository.discardIncomplete() }
        if (logTiming) Log.d("DoorTiming", "saved device checked in ${SystemClock.elapsedRealtime() - checkAt} ms")
        emitAll(repository.device.map { if (it == null) RootState.NotPaired else RootState.Paired(it) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    fun forget() {
        viewModelScope.launch { repository.forget() }
    }

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
// permission / Bluetooth gate, then pairing. Forgetting the device is the only way back.
@Composable
fun RootScreen(state: RootState, adapter: BluetoothAdapter?, onForget: () -> Unit) {
    Crossfade(targetState = state, animationSpec = DoorMotion.spring(), label = "root") { current ->
        when (current) {
            RootState.Loading -> Unit
            RootState.NotPaired -> {
                val gate = rememberBluetoothGate(adapter, forPairing = true)
                if (gate.status == GateStatus.Ready) PairingScreen() else GateScreen(gate)
            }
            is RootState.Paired -> ControlScreen(current.device, adapter, onForget)
        }
    }
}
