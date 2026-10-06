package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.softShadow

// One remote button: a white icon on a solid circle of the button's own color, over a label.
// color comes from DoorTheme.colors.up / down / lock / unlock. Up and Down use tall = true.
//   sending: this button's command is in flight (spinner in place of the icon)
//   enabled = false: another command is in flight, or the door is not connected (dimmed)
//   placeholder: still connecting, shows a pulsing skeleton of the same size
@Composable
fun DoorButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
    enabled: Boolean = true,
    sending: Boolean = false,
    placeholder: Boolean = false,
) {
    val minHeight = if (tall) 140.dp else 104.dp
    val shape = DoorTheme.radius.card
    if (placeholder) {
        Skeleton(modifier.heightIn(min = minHeight), shape)
        return
    }
    val colors = DoorTheme.colors
    val circle = if (tall) 56.dp else 44.dp
    PressableSurface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = minHeight)
            // A full shadow under a dimmed card looks muddy.
            .softShadow(shape, enabled = enabled && !colors.isDark),
        enabled = enabled && !sending,
        dimmed = !enabled,
        shape = shape,
        color = colors.card,
    ) {
        Column(
            Modifier.padding(DoorTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(circle)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    Spinner(size = circle * 0.5f, color = Color.White)
                } else {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(circle * 0.62f))
                }
            }
            Text(label, style = DoorTheme.type.headline, color = colors.label, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun RemotePreview(
    placeholder: Boolean = false,
    sending: Int? = null,
    connected: Boolean = true,
) {
    val colors = DoorTheme.colors
    val spacing = DoorTheme.spacing.s
    val enabled = connected && sending == null
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            DoorButton(
                "Lên", Icons.Rounded.KeyboardArrowUp, colors.up, {}, Modifier.weight(1f), tall = true,
                enabled = enabled || sending == 0, sending = sending == 0, placeholder = placeholder,
            )
            DoorButton(
                "Xuống", Icons.Rounded.KeyboardArrowDown, colors.down, {}, Modifier.weight(1f), tall = true,
                enabled = enabled, placeholder = placeholder,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            DoorButton(
                "Khóa", Icons.Rounded.Lock, colors.lock, {}, Modifier.weight(1f),
                enabled = enabled, placeholder = placeholder,
            )
            DoorButton(
                "Mở khóa", Icons.Rounded.LockOpen, colors.unlock, {}, Modifier.weight(1f),
                enabled = enabled, placeholder = placeholder,
            )
        }
    }
}

@DoorPreviews
@DoorFontScalePreview
@Composable
private fun DoorButtonReadyPreview() = PreviewColumn { RemotePreview() }

@DoorPreviews
@Composable
private fun DoorButtonSendingPreview() = PreviewColumn { RemotePreview(sending = 0) }

@DoorPreviews
@Composable
private fun DoorButtonConnectingPreview() = PreviewColumn { RemotePreview(placeholder = true) }

@DoorPreviews
@Composable
private fun DoorButtonDisconnectedPreview() = PreviewColumn { RemotePreview(connected = false) }
