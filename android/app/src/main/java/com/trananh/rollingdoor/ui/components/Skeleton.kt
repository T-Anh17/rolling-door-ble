package com.trananh.rollingdoor.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme

// Pulsing gray block standing in for content that is still loading. The caller sets the size.
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = DoorTheme.radius.control) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(DoorTheme.colors.fill),
    )
}

@DoorPreviews
@Composable
private fun SkeletonPreview() = PreviewColumn {
    Skeleton(Modifier.width(140.dp).height(28.dp), CircleShape)
    Row(horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s)) {
        Skeleton(Modifier.weight(1f).height(140.dp), DoorTheme.radius.card)
        Skeleton(Modifier.weight(1f).height(140.dp), DoorTheme.radius.card)
    }
    Skeleton(Modifier.fillMaxWidth().height(20.dp))
    Skeleton(Modifier.size(48.dp), CircleShape)
}
