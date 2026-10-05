package com.trananh.rollingdoor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorMotion
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.softShadow
import kotlinx.coroutines.delay

enum class BannerKind { Error, Info, Success }

// durationMs = null keeps the banner until it is dismissed (use it for banners with an action).
class BannerMessage(
    val text: String,
    val kind: BannerKind,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val durationMs: Long? = 3_000,
)

// Slides in from the top, below the status bar, instead of a Material Snackbar.
// Put it in the top overlay of NavBarScaffold.
@Composable
fun BannerHost(message: BannerMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keeps the last message on screen while it slides out.
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message

    LaunchedEffect(message) {
        val duration = message?.durationMs ?: return@LaunchedEffect
        delay(duration)
        onDismiss()
    }

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = DoorTheme.spacing.screenMargin, vertical = DoorTheme.spacing.xs),
        enter = slideInVertically(DoorMotion.spring()) { -it } + fadeIn(),
        exit = slideOutVertically(DoorMotion.spring()) { -it } + fadeOut(),
    ) {
        shown?.let { Banner(it, onDismiss) }
    }
}

@Composable
fun Banner(message: BannerMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DoorTheme.colors
    val shape = DoorTheme.radius.card
    val (icon, tint) = when (message.kind) {
        BannerKind.Error -> Icons.Rounded.ErrorOutline to colors.red
        BannerKind.Info -> Icons.Rounded.Info to colors.accent
        BannerKind.Success -> Icons.Rounded.CheckCircle to colors.green
    }
    // Tapping the banner dismisses it; the action, if any, is a separate link.
    PressableSurface(
        onClick = onDismiss,
        modifier = modifier
            .fillMaxWidth()
            .softShadow(shape, enabled = !colors.isDark, blur = 30.dp, alpha = 0.15f)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = shape,
        color = colors.sheet,
        pressedColor = colors.fill,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = DoorTheme.spacing.m, end = DoorTheme.spacing.xs)
                .padding(vertical = DoorTheme.spacing.s),
            horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Text(message.text, Modifier.weight(1f), style = DoorTheme.type.subhead, color = colors.label)
            if (message.actionLabel != null && message.onAction != null) {
                TextLink(message.actionLabel, message.onAction)
            }
        }
    }
}

@DoorPreviews
@DoorFontScalePreview
@Composable
private fun BannerPreview() = PreviewColumn {
    Banner(BannerMessage("Cửa không phản hồi. Thử lại nhé.", BannerKind.Error), {})
    Banner(BannerMessage("Đã mở ghép đôi trong 60 giây", BannerKind.Success), {})
    Banner(
        BannerMessage(
            "Khóa không còn hợp lệ (thiết bị đã xóa khóa?)",
            BannerKind.Error,
            actionLabel = "Cài đặt",
            onAction = {},
            durationMs = null,
        ),
        {},
    )
    Banner(BannerMessage("Đã kết nối", BannerKind.Info), {})
}
