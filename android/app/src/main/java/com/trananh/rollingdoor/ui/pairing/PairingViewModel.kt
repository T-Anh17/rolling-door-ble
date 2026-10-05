package com.trananh.rollingdoor.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trananh.rollingdoor.RollingDoorApp
import com.trananh.rollingdoor.ble.PairingError
import com.trananh.rollingdoor.ble.PairingProgress
import com.trananh.rollingdoor.ble.PairingResult
import com.trananh.rollingdoor.ble.PairingSession
import com.trananh.rollingdoor.protocol.SetupCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Steps shown in the StepList, in order.
enum class PairingStage { Find, Connect, ExchangeKey, Confirm }

enum class PairingProblem {
    InvalidCode,        // the QR code is not an RDOOR1 setup code
    ScannerUnavailable, // Google code scanner could not start
    BluetoothOff,
    NoPermission,
    NotFound,
    ConnectionFailed,
    PairingClosed,
    WrongCode,
    LockedOut,
    TableFull,
    InvalidResponse,
}

sealed interface PairingUiState {
    data object Idle : PairingUiState

    // pairingClosedHint: the scan saw no "pairing open" flag, so the device will likely refuse.
    data class Running(val stage: PairingStage, val pairingClosedHint: Boolean) : PairingUiState

    // All four steps done; the root screen switches to the control screen right after.
    data object Done : PairingUiState

    // stage = null when the failure came before any step (bad code, scanner).
    // canRetry: the same code can be tried again.
    data class Failed(val problem: PairingProblem, val stage: PairingStage?, val canRetry: Boolean) : PairingUiState
}

class PairingViewModel(private val newSession: () -> PairingSession) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    // Kept for "Try again"; its secret is wiped when replaced or when the screen goes away.
    private var code: SetupCode? = null
    private var job: Job? = null

    fun onScanned(text: String) {
        val parsed = SetupCode.parse(text)
        if (parsed == null) {
            _state.value = PairingUiState.Failed(PairingProblem.InvalidCode, stage = null, canRetry = false)
        } else {
            start(parsed)
        }
    }

    // Returns false if the text is not a setup code; the screen shows that under the field.
    fun submitManual(text: String): Boolean {
        val parsed = SetupCode.parse(text) ?: return false
        start(parsed)
        return true
    }

    fun onScannerUnavailable() {
        _state.value = PairingUiState.Failed(PairingProblem.ScannerUnavailable, stage = null, canRetry = false)
    }

    fun retry() {
        code?.let(::start)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = PairingUiState.Idle
    }

    // "Scan another code": back to the start, forgetting the current code.
    fun reset() {
        cancel()
        replaceCode(null)
    }

    private fun start(newCode: SetupCode) {
        if (job?.isActive == true) return
        if (newCode !== code) replaceCode(newCode)
        job = viewModelScope.launch {
            var stage = PairingStage.Find
            var hint = false
            _state.value = PairingUiState.Running(stage, hint)
            val result = newSession().run(newCode) { progress ->
                when (progress) {
                    PairingProgress.Searching -> stage = PairingStage.Find
                    is PairingProgress.Connecting -> {
                        stage = PairingStage.Connect
                        hint = !progress.pairingAdvertised
                    }
                    PairingProgress.ExchangingKey -> stage = PairingStage.ExchangeKey
                    PairingProgress.Confirming -> stage = PairingStage.Confirm
                }
                _state.value = PairingUiState.Running(stage, hint)
            }
            _state.value = when (result) {
                is PairingResult.Success -> {
                    replaceCode(null)
                    PairingUiState.Done
                }
                is PairingResult.Failure -> PairingUiState.Failed(result.error.toProblem(), stage, canRetry = true)
            }
        }
    }

    private fun replaceCode(newCode: SetupCode?) {
        code?.secret?.fill(0)
        code = newCode
    }

    override fun onCleared() {
        replaceCode(null)
    }

    private fun PairingError.toProblem() = when (this) {
        PairingError.BluetoothOff -> PairingProblem.BluetoothOff
        PairingError.NoPermission -> PairingProblem.NoPermission
        PairingError.NotFound -> PairingProblem.NotFound
        PairingError.ConnectionFailed -> PairingProblem.ConnectionFailed
        PairingError.PairingClosed -> PairingProblem.PairingClosed
        PairingError.WrongCode -> PairingProblem.WrongCode
        PairingError.LockedOut -> PairingProblem.LockedOut
        PairingError.TableFull -> PairingProblem.TableFull
        PairingError.InvalidResponse -> PairingProblem.InvalidResponse
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as RollingDoorApp).container
                PairingViewModel(container::pairingSession)
            }
        }
    }
}
