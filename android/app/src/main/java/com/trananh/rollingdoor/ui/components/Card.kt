package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.softShadow

// Card surface, 20dp continuous corners, soft shadow in light mode.
@Composable
fun Card(
    modifier: Modifier = Modifier,
    color: Color = DoorTheme.colors.card,
    contentPadding: PaddingValues = PaddingValues(DoorTheme.spacing.m),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = DoorTheme.radius.card
    Column(
        modifier = modifier
            .softShadow(shape, enabled = !DoorTheme.colors.isDark)
            .clip(shape)
            .background(color)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs),
        content = content,
    )
}

@DoorPreviews
@Composable
private fun CardPreview() = PreviewColumn {
    Card(Modifier.fillMaxWidth()) {
        Text("Ghép đôi đang đóng", style = DoorTheme.type.headline, color = DoorTheme.colors.label)
        Text(
            "Giữ nút BOOT 3 giây rồi thử lại.",
            style = DoorTheme.type.subhead,
            color = DoorTheme.colors.secondaryLabel,
        )
    }
}
