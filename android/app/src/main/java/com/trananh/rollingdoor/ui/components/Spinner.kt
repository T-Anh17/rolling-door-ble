package com.trananh.rollingdoor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme

// iOS activity indicator: 8 spokes, the bright one stepping round, instead of a Material arc.
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = DoorTheme.colors.secondaryLabel,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = SPOKES.toFloat(),
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "spinnerStep",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val lead = step.toInt()
        val stroke = this.size.minDimension * 0.09f
        val outer = this.size.minDimension / 2f
        val inner = outer * 0.48f
        for (i in 0 until SPOKES) {
            // Spoke `lead` is fully opaque, the ones behind it fade out.
            val age = (lead - i + SPOKES) % SPOKES
            rotate(degrees = i * 360f / SPOKES) {
                drawLine(
                    color = color.copy(alpha = color.alpha * (1f - age * 0.11f)),
                    start = Offset(center.x, center.y - inner),
                    end = Offset(center.x, center.y - outer + stroke / 2),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private const val SPOKES = 8

@DoorPreviews
@Composable
private fun SpinnerPreview() = PreviewColumn {
    Row {
        Spinner()
        Spinner(size = 32.dp, color = DoorTheme.colors.accent)
    }
}
