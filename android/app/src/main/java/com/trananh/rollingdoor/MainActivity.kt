package com.trananh.rollingdoor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trananh.rollingdoor.ui.RootScreen
import com.trananh.rollingdoor.ui.RootState
import com.trananh.rollingdoor.ui.RootViewModel
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme

class MainActivity : ComponentActivity() {
    private val rootViewModel: RootViewModel by viewModels { RootViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Until the saved device is read (tens of ms), so the wrong screen never flashes.
        splash.setKeepOnScreenCondition { rootViewModel.state.value == RootState.Loading }
        enableEdgeToEdge()
        val adapter = (application as RollingDoorApp).container.bluetoothAdapter
        setContent {
            val state by rootViewModel.state.collectAsStateWithLifecycle()
            RollingDoorTheme {
                RootScreen(state, adapter, onForget = rootViewModel::forget)
            }
        }
    }
}
