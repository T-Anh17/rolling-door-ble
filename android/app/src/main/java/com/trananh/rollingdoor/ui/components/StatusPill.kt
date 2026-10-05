package com.trananh.rollingdoor.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme

enum class StatusTone { Ready, Pending, Problem, Neutral }

// Colored dot and a short status text, such as "Ready" or "Out of range".
// Pending pulses the dot. Screen readers announce changes.
@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = DoorTheme.colors
    val dot = when (tone) {
        StatusTone.Ready -> colors.green
        StatusTone.Pending -> colors.orange
        StatusTone.Problem -> colors.red
        StatusTone.Neutral -> colors.secondaryLabel
    }
    Row(
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(CircleShape)
            .background(colors.fill)
            .padding(horizontal = DoorTheme.spacing.s, vertical = DoorTheme.spacing.xxs + 2.dp),
        horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot, pulsing = tone == StatusTone.Pending)
        Text(text, style = DoorTheme.type.subhead, color = colors.label)
    }
}

@Composable
private fun Dot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        value
    } else {
        1f
    }
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}

@DoorPreviews
@Composable
private fun StatusPillPreview() = PreviewColumn {
    StatusPill("Sẵn sàng", StatusTone.Ready)
    StatusPill("Đang kết nối…", StatusTone.Pending)
    StatusPill("Ngoài vùng, sẽ tự kết nối khi lại gần", StatusTone.Pending)
    StatusPill("Bluetooth đang tắt", StatusTone.Problem)
    StatusPill("Chưa kết nối", StatusTone.Neutral)
}
