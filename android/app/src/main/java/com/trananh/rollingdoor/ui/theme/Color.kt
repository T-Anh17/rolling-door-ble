package com.trananh.rollingdoor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// iOS system colors. Accent is the only brand color: primary buttons, links, active states.
// red / green / orange mark status only, never decoration.
@Immutable
data class DoorColors(
    val isDark: Boolean,
    val accent: Color,
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
)

val LightDoorColors = DoorColors(
    isDark = false,
    accent = Color(0xFF007AFF),
    onAccent = Color.White,
    background = Color(0xFFF2F2F7),
    card = Color.White,
    sheet = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    separator = Color(0xFFC6C6C8),
    fill = Color(0x33787880),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
)

val DarkDoorColors = DoorColors(
    isDark = true,
    accent = Color(0xFF0A84FF),
    onAccent = Color.White,
    background = Color.Black,
    card = Color(0xFF1C1C1E),
    sheet = Color(0xFF2C2C2E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    separator = Color(0xFF38383A),
    fill = Color(0x5C787880),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
)
