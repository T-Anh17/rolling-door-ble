package com.trananh.rollingdoor.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

object DoorMotion {
    // Every transition uses the same spring.
    fun <T> spring(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

    // Press feedback: applied at once on touch down, released with spring().
    const val PRESSED_SCALE = 0.97f
    const val PRESSED_ALPHA = 0.7f

    const val DISABLED_ALPHA = 0.4f
}
