package com.trananh.rollingdoor.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LocalDoorColors = staticCompositionLocalOf { LightDoorColors }
private val LocalDoorTypography = staticCompositionLocalOf { DoorTypography() }
private val LocalDoorSpacing = staticCompositionLocalOf { DoorSpacing() }
private val LocalDoorRadius = staticCompositionLocalOf { DoorRadius() }

// The single source of design tokens. UI code reads DoorTheme.*, not MaterialTheme.
object DoorTheme {
    val colors: DoorColors
        @Composable @ReadOnlyComposable get() = LocalDoorColors.current
    val type: DoorTypography
        @Composable @ReadOnlyComposable get() = LocalDoorTypography.current
    val spacing: DoorSpacing
        @Composable @ReadOnlyComposable get() = LocalDoorSpacing.current
    val radius: DoorRadius
        @Composable @ReadOnlyComposable get() = LocalDoorRadius.current
}

// Light or dark follows the system setting, with no in-app switch. No dynamic color: the app
// keeps the iOS palette on every phone. MaterialTheme is only a frame, mapped to the same tokens
// so a stray Material component still matches, and without ripples.
@OptIn(ExperimentalMaterial3Api::class) // LocalRippleConfiguration
@Composable
fun RollingDoorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkDoorColors else LightDoorColors
    val type = remember { DoorTypography() }
    val widthDp = LocalConfiguration.current.screenWidthDp
    val spacing = remember(widthDp) { DoorSpacing.forScreenWidth(widthDp) }
    val radius = remember { DoorRadius() }
    SystemBarIcons(darkTheme)

    CompositionLocalProvider(
        LocalDoorColors provides colors,
        LocalDoorTypography provides type,
        LocalDoorSpacing provides spacing,
        LocalDoorRadius provides radius,
    ) {
        MaterialTheme(
            colorScheme = materialColors(colors),
            typography = Typography(
                bodyLarge = type.body,
                bodyMedium = type.subhead,
                bodySmall = type.footnote,
                titleLarge = type.title2,
                titleMedium = type.headline,
                labelLarge = type.headline,
                labelSmall = type.caption,
            ),
        ) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
        }
    }
}

// Status and navigation bar icons follow the theme the app actually draws, not the system
// setting that enableEdgeToEdge() reads: light icons on a dark page, dark icons on a light one.
@Composable
private fun SystemBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Content of sheets and dialogs. Light mode is unchanged.
@Composable
fun ElevatedColors(content: @Composable () -> Unit) {
    val colors = DoorTheme.colors
    CompositionLocalProvider(
        LocalDoorColors provides if (colors.isDark) DarkElevatedDoorColors else colors,
        content = content,
    )
}

private fun materialColors(c: DoorColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        background = c.background,
        onBackground = c.label,
        surface = c.card,
        onSurface = c.label,
        surfaceContainer = c.sheet,
        onSurfaceVariant = c.secondaryLabel,
        outlineVariant = c.separator,
        error = c.red,
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        background = c.background,
        onBackground = c.label,
        surface = c.card,
        onSurface = c.label,
        surfaceContainer = c.sheet,
        onSurfaceVariant = c.secondaryLabel,
        outlineVariant = c.separator,
        error = c.red,
    )
}
