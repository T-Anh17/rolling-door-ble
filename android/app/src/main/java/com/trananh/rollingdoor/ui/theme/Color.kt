package com.trananh.rollingdoor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// iOS system colors. Accent is the only brand color: primary buttons, links, active states.
// red / green / orange mark status only, never decoration. The exception is the four remote
// buttons, which each get a color, in pairs: Up / Down cool (blue, indigo), Lock / Unlock
// red and green (Lock also stops a moving door).
// Darker than iOS where text or icons would fall below WCAG contrast: accent and accentFill
// (white text 5.8:1), secondaryLabel (4.8:1 on the light page), red text, Unlock's circle.
@Immutable
data class DoorColors(
    val isDark: Boolean,
    val accent: Color,
    val accentFill: Color,     // primary button background, under onAccent text
    val onAccent: Color,
    val background: Color,     // page
    val card: Color,           // cards, list groups, inputs
    val sheet: Color,          // bottom sheets, dialogs
    val label: Color,
    val secondaryLabel: Color,
    val separator: Color,
    val fill: Color,           // skeletons, neutral fills
    val red: Color,            // errors, destructive rows
    val green: Color,          // "Ready" dot
    val orange: Color,         // connecting, out of range
    val up: Color,
    val down: Color,
    val lock: Color,
    val unlock: Color,
)

val LightDoorColors = DoorColors(
    isDark = false,
    accent = Color(0xFF0062CC),
    accentFill = Color(0xFF0062CC),
    onAccent = Color.White,
    background = Color(0xFFF2F2F7),
    card = Color.White,
    sheet = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0xBF3C3C43),
    separator = Color(0xFFC6C6C8),
    fill = Color(0x33787880),
    red = Color(0xFFD70015),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    up = Color(0xFF007AFF),
    down = Color(0xFF5856D6),
    lock = Color(0xFFFF3B30),
    unlock = Color(0xFF248A3D),
)

val DarkDoorColors = DoorColors(
    isDark = true,
    accent = Color(0xFF0A84FF),
    accentFill = Color(0xFF0062CC),
    onAccent = Color.White,
    background = Color.Black,
    card = Color(0xFF1C1C1E),
    sheet = Color(0xFF2C2C2E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    separator = Color(0xFF38383A),
    fill = Color(0x5C787880),
    red = Color(0xFFFF6961),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    up = Color(0xFF0A84FF),
    down = Color(0xFF5E5CE6),
    lock = Color(0xFFFF453A),
    unlock = Color(0xFF248A3D),
)

// iOS elevated palette, for sheets and dialogs: in dark mode each surface is one step lighter,
// so the sheet stands out from the page behind it and grouped cards from the sheet.
val DarkElevatedDoorColors = DarkDoorColors.copy(
    background = Color(0xFF1C1C1E),
    card = Color(0xFF2C2C2E),
    sheet = Color(0xFF3A3A3C),
)
