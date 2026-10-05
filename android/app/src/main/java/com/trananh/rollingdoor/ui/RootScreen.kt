package com.trananh.rollingdoor.ui

import android.bluetooth.BluetoothAdapter
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.RollingDoorApp
import com.trananh.rollingdoor.data.DeviceRepository
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.ui.components.NavBarScaffold
import com.trananh.rollingdoor.ui.components.SecondaryButton
import com.trananh.rollingdoor.ui.gate.GateScreen
import com.trananh.rollingdoor.ui.gate.GateStatus
import com.trananh.rollingdoor.ui.gate.rememberBluetoothGate
import com.trananh.rollingdoor.ui.pairing.PairingScreen
import com.trananh.rollingdoor.ui.theme.DoorMotion
import com.trananh.rollingdoor.ui.theme.DoorTheme
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

class RootViewModel(private val repository: DeviceRepository) : ViewModel() {
    val state: StateFlow<RootState> = flow {
        // A pairing that crashed before its confirming Ping leaves nothing usable: drop it.
        runCatching { repository.discardIncomplete() }
        emitAll(repository.device.map { if (it == null) RootState.NotPaired else RootState.Paired(it) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    fun forget() {
        viewModelScope.launch { repository.forget() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                RootViewModel((this[APPLICATION_KEY] as RollingDoorApp).container.repository)
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
            is RootState.Paired -> ControlPlaceholder(current.device, onForget)
        }
    }
}

// Stand-in until the control screen (plan step 9): proves pairing worked and allows starting over.
@Composable
private fun ControlPlaceholder(device: SavedDevice, onForget: () -> Unit) {
    NavBarScaffold(title = stringResource(R.string.control_title)) {
        Text(
            stringResource(R.string.control_placeholder, device.mac, device.keyId),
            style = DoorTheme.type.body,
            color = DoorTheme.colors.secondaryLabel,
        )
        SecondaryButton(stringResource(R.string.settings_forget), onForget, Modifier.fillMaxWidth())
    }
}
