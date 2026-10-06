package com.trananh.rollingdoor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.trananh.rollingdoor.ui.RootScreen
import com.trananh.rollingdoor.ui.RootState
import com.trananh.rollingdoor.ui.RootViewModel
import com.trananh.rollingdoor.ui.control.ControlViewModel
import com.trananh.rollingdoor.ui.gate.canConnectToSavedDevice
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val rootViewModel: RootViewModel by viewModels { RootViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Until the saved device is read (tens of ms), so the wrong screen never flashes.
        splash.setKeepOnScreenCondition { rootViewModel.state.value == RootState.Loading }
        enableEdgeToEdge()
        connectEarly()
        val adapter = (application as RollingDoorApp).container.bluetoothAdapter
        setContent {
            val state by rootViewModel.state.collectAsStateWithLifecycle()
            RollingDoorTheme {
                RootScreen(state, adapter, onForget = rootViewModel::forget)
            }
        }
    }

    // Starts the control screen's connection as soon as the saved device is read, instead of
    // after the screen's first frame. Without the permission the screen's gate asks for it and
    // starts the connection itself once granted.
    private fun connectEarly() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                rootViewModel.state.collectLatest { state ->
                    if (state !is RootState.Paired || !canConnectToSavedDevice()) return@collectLatest
                    val control = ViewModelProvider(this@MainActivity, ControlViewModel.factory(state.device))[
                        ControlViewModel.key(state.device), ControlViewModel::class.java,
                    ]
                    control.start()
                    try {
                        awaitCancellation()
                    } finally {
                        control.stop()
                    }
                }
            }
        }
    }
}
