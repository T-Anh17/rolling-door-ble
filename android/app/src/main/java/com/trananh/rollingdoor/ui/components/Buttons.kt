package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme

// Filled accent button, 50dp high (taller when large text wraps).
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    FilledButton(text, onClick, modifier, icon, enabled, loading, DoorTheme.colors.accent, DoorTheme.colors.onAccent)
}

// Tinted button: accent at 12% behind accent text.
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val accent = DoorTheme.colors.accent
    FilledButton(text, onClick, modifier, icon, enabled, loading, accent.copy(alpha = 0.12f), accent)
}

// Plain accent text, for secondary actions such as "Nhập mã thủ công".
@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = DoorTheme.colors.accent,
) {
    PressableSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        dimmed = !enabled,
        shape = DoorTheme.radius.control,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(horizontal = DoorTheme.spacing.xs, vertical = DoorTheme.spacing.xxs)
                .alpha(if (loading) 0f else 1f),
            style = DoorTheme.type.body,
            color = color,
            textAlign = TextAlign.Center,
        )
        if (loading) Spinner(color = color)
    }
}

@Composable
private fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    loading: Boolean,
    container: Color,
    content: Color,
) {
    PressableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        enabled = enabled && !loading,
        dimmed = !enabled,
        shape = DoorTheme.radius.control,
        color = container,
    ) {
        // The label stays in place (invisible) while loading, so the button keeps its size.
        Row(
            modifier = Modifier
                .padding(horizontal = DoorTheme.spacing.l, vertical = DoorTheme.spacing.s)
                .alpha(if (loading) 0f else 1f),
            horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Text(text, style = DoorTheme.type.headline, color = content, textAlign = TextAlign.Center)
        }
        if (loading) Spinner(color = content)
    }
}

@DoorPreviews
@DoorFontScalePreview
@Composable
private fun ButtonsPreview() = PreviewColumn {
    PrimaryButton("Quét mã QR", {}, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner)
    PrimaryButton("Quét mã QR", {}, Modifier.fillMaxWidth(), loading = true)
    PrimaryButton("Quét mã QR", {}, Modifier.fillMaxWidth(), enabled = false)
    SecondaryButton("Thử lại", {}, Modifier.fillMaxWidth())
    SecondaryButton("Thử lại", {}, Modifier.fillMaxWidth(), loading = true)
    SecondaryButton("Thử lại", {}, Modifier.fillMaxWidth(), enabled = false)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextLink("Nhập mã thủ công", {})
    }
    TextLink("Nhập mã thủ công", {}, enabled = false)
}
