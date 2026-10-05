package com.trananh.rollingdoor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class DoorSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val s: Dp = 12.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val screenMargin: Dp = 16.dp, // left and right page margin, see forScreenWidth()
) {
    companion object {
        // 16dp on phones narrower than 400dp, 20dp on wider screens.
        fun forScreenWidth(widthDp: Int) = DoorSpacing(screenMargin = if (widthDp < 400) 16.dp else 20.dp)
    }
}
