package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.softShadow

enum class ListRowStyle { Normal, Destructive }

class ListGroupScope internal constructor() {
    internal val rows = mutableListOf<ListRowSpec>()

    // onClick = null makes a read-only row (no press feedback).
    fun row(
        title: String,
        icon: ImageVector? = null,
        value: String? = null,
        chevron: Boolean = false,
        style: ListRowStyle = ListRowStyle.Normal,
        loading: Boolean = false,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
    ) {
        rows += ListRowSpec(title, icon, value, chevron, style, loading, enabled, onClick)
    }
}

internal class ListRowSpec(
    val title: String,
    val icon: ImageVector?,
    val value: String?,
    val chevron: Boolean,
    val style: ListRowStyle,
    val loading: Boolean,
    val enabled: Boolean,
    val onClick: (() -> Unit)?,
)

// Rows grouped in one card, like iOS Settings: optional header above, footnote below.
// Separators are inset on both sides, starting where the row text starts.
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: ListGroupScope.() -> Unit,
) {
    val rows = ListGroupScope().apply(content).rows
    val shape = DoorTheme.radius.card
    val inset = DoorTheme.spacing.m
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs)) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                modifier = Modifier.padding(horizontal = inset),
                style = DoorTheme.type.footnote,
                color = DoorTheme.colors.secondaryLabel,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .softShadow(shape, enabled = !DoorTheme.colors.isDark)
                .clip(shape)
                .background(DoorTheme.colors.card),
        ) {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    val previous = rows[index - 1]
                    Separator(start = textStart(previous.icon != null), end = inset)
                }
                ListRow(row)
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                modifier = Modifier.padding(horizontal = inset),
                style = DoorTheme.type.footnote,
                color = DoorTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun ListRow(row: ListRowSpec) {
    val colors = DoorTheme.colors
    val tint = if (row.style == ListRowStyle.Destructive) colors.red else colors.accent
    val titleColor = if (row.style == ListRowStyle.Destructive) colors.red else colors.label
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = DoorTheme.spacing.m, vertical = DoorTheme.spacing.s),
            horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.icon != null) {
                Icon(row.icon, contentDescription = null, tint = tint, modifier = Modifier.size(ICON_SIZE))
            }
            Text(row.title, Modifier.weight(1f), style = DoorTheme.type.body, color = titleColor)
            if (row.value != null) {
                Text(
                    row.value,
                    style = DoorTheme.type.body,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.End,
                )
            }
            if (row.loading) Spinner()
            if (row.chevron) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.secondaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    if (row.onClick == null) {
        body()
    } else {
        PressableSurface(
            onClick = row.onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = row.enabled && !row.loading,
            dimmed = !row.enabled,
            pressedColor = colors.fill,
            contentAlignment = Alignment.CenterStart,
        ) { body() }
    }
}

@Composable
private fun Separator(start: Dp, end: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .height(0.5.dp)
            .background(DoorTheme.colors.separator),
    )
}

@Composable
private fun textStart(hasIcon: Boolean): Dp {
    val spacing = DoorTheme.spacing
    return if (hasIcon) spacing.m + ICON_SIZE + spacing.s else spacing.m
}

private val ICON_SIZE = 24.dp

@DoorPreviews
@DoorFontScalePreview
@Composable
private fun ListGroupPreview() = PreviewColumn {
    ListGroup(header = "Thiết bị") {
        row("Địa chỉ", value = "24:6F:28:A1:B2:C3")
        row("Vai trò", value = "Quản trị")
    }
    ListGroup(
        header = "Quản trị",
        footer = "Điện thoại mới cần quét mã QR trong vòng 60 giây.",
    ) {
        row("Thêm điện thoại", icon = Icons.Rounded.AddCircleOutline, loading = true, onClick = {})
    }
    ListGroup(header = "Truy cập nhanh") {
        row("Thêm vào bảng thao tác nhanh", icon = Icons.Rounded.GridView, chevron = true, onClick = {})
    }
    ListGroup {
        row("Quên thiết bị", icon = Icons.Rounded.Delete, style = ListRowStyle.Destructive, onClick = {})
    }
}
