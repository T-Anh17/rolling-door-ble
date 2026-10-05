package com.trananh.rollingdoor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.trananh.rollingdoor.ui.theme.DoorMotion
import com.trananh.rollingdoor.ui.theme.DoorTheme

// Base of everything tappable. No ripple: on touch down it shrinks to 0.97 and fades to 0.7 at
// once, and springs back on release. With pressedColor set (list rows) the background changes
// instead. The touch area is at least 48dp even when the visible shape is smaller.
@Composable
fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = !enabled,
    shape: Shape = RectangleShape,
    color: Color = Color.Transparent,
    pressedColor: Color? = null,
    role: Role = Role.Button,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scalesOnPress = pressedColor == null
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(pressed, scalesOnPress) {
        if (pressed && scalesOnPress) {
            scale.snapTo(DoorMotion.PRESSED_SCALE)
            alpha.snapTo(DoorMotion.PRESSED_ALPHA)
        } else {
            alpha.snapTo(1f)
            scale.animateTo(1f, DoorMotion.spring())
        }
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value * if (dimmed) DoorMotion.DISABLED_ALPHA else 1f
            }
            .clip(shape)
            .background(if (pressed && pressedColor != null) pressedColor else color)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@DoorPreviews
@Composable
private fun PressableSurfacePreview() = PreviewColumn {
    PressableSurface(onClick = {}, shape = DoorTheme.radius.control, color = DoorTheme.colors.card) {
        Text("Bấm được", Modifier.padding(DoorTheme.spacing.m), color = DoorTheme.colors.label)
    }
    PressableSurface(onClick = {}, enabled = false, shape = DoorTheme.radius.control, color = DoorTheme.colors.card) {
        Text("Không bấm được", Modifier.padding(DoorTheme.spacing.m), color = DoorTheme.colors.label)
    }
}
