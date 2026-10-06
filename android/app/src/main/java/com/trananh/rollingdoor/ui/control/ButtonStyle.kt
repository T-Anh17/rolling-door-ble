package com.trananh.rollingdoor.ui.control

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.protocol.ButtonIcon
import com.trananh.rollingdoor.protocol.RemoteButton
import com.trananh.rollingdoor.ui.theme.DoorColors
import com.trananh.rollingdoor.ui.theme.DoorTheme

// How each icon looks: the vector, the circle color and the name a button shows when the admin
// left its name empty.

internal val ButtonIcon.vector: ImageVector
    get() = when (this) {
        ButtonIcon.Up -> Icons.Rounded.KeyboardArrowUp
        ButtonIcon.Down -> Icons.Rounded.KeyboardArrowDown
        ButtonIcon.Lock -> Icons.Rounded.Lock
        ButtonIcon.Unlock -> Icons.Rounded.LockOpen
        ButtonIcon.Stop -> Icons.Rounded.Stop
        ButtonIcon.Gate -> Icons.Rounded.Fence
        ButtonIcon.Garage -> Icons.Rounded.Garage
        ButtonIcon.Light -> Icons.Rounded.Lightbulb
        ButtonIcon.Power -> Icons.Rounded.PowerSettingsNew
        ButtonIcon.Bell -> Icons.Rounded.Notifications
    }

internal fun ButtonIcon.color(colors: DoorColors): Color = when (this) {
    ButtonIcon.Up -> colors.up
    ButtonIcon.Down -> colors.down
    ButtonIcon.Lock -> colors.lock
    ButtonIcon.Unlock -> colors.unlock
    ButtonIcon.Stop -> colors.stop
    ButtonIcon.Gate -> colors.gate
    ButtonIcon.Garage -> colors.garage
    ButtonIcon.Light -> colors.light
    ButtonIcon.Power -> colors.power
    ButtonIcon.Bell -> colors.bell
}

internal val ButtonIcon.label: Int
    get() = when (this) {
        ButtonIcon.Up -> R.string.control_up
        ButtonIcon.Down -> R.string.control_down
        ButtonIcon.Lock -> R.string.control_lock
        ButtonIcon.Unlock -> R.string.control_unlock
        ButtonIcon.Stop -> R.string.button_stop
        ButtonIcon.Gate -> R.string.button_gate
        ButtonIcon.Garage -> R.string.button_garage
        ButtonIcon.Light -> R.string.button_light
        ButtonIcon.Power -> R.string.button_power
        ButtonIcon.Bell -> R.string.button_bell
    }

@Composable
internal fun RemoteButton.displayName(): String = name.ifBlank { stringResource(icon.label) }

@Composable
internal fun ButtonIcon.circleColor(): Color = color(DoorTheme.colors)
