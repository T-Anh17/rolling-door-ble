package com.trananh.rollingdoor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// iOS text styles at their default sizes, in the system font (Roboto: SF Pro may not ship in an
// Android app). In sp, so they follow the system font size. No negative tracking: the iOS values
// are tuned for SF Pro and make Roboto look cramped.
@Immutable
data class DoorTypography(
    val largeTitle: TextStyle = style(34.sp, 41.sp, FontWeight.Bold),
    val title1: TextStyle = style(28.sp, 34.sp),
    val title2: TextStyle = style(22.sp, 28.sp),
    val headline: TextStyle = style(17.sp, 22.sp, FontWeight.SemiBold),
    val body: TextStyle = style(17.sp, 22.sp),
    val subhead: TextStyle = style(15.sp, 20.sp),
    val footnote: TextStyle = style(13.sp, 18.sp),
    val caption: TextStyle = style(12.sp, 16.sp),
)

private fun style(size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
    )
