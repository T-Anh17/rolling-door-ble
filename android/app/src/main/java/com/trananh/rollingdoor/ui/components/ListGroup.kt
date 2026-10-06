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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.softShadow

enum class ListRowStyle { Normal, Destructive }

class ListGroupScope internal constructor() {
    internal val rows = mutableListOf<ListRowSpec>()

    // onClick = null makes a read-only row (no press feedback). action adds a second tap
    // target at the end of the row, separate from the row itself.
    fun row(
        title: String,
        icon: ImageVector? = null,
        value: String? = null,
        chevron: Boolean = false,
        style: ListRowStyle = ListRowStyle.Normal,
        loading: Boolean = false,
        enabled: Boolean = true,
        action: ListRowAction? = null,
        onClick: (() -> Unit)? = null,
    ) {
        rows += ListRowSpec(title, icon, value, chevron, style, loading, enabled, action, onClick)
    }
}

// A button at the end of a row: an icon, or an icon and text. label is read by TalkBack. While
// loading, a spinner takes the button's place.
class ListRowAction(
    val icon: ImageVector,
    val label: String,
    val text: String? = null,
    val style: ListRowStyle = ListRowStyle.Normal,
    val loading: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal class ListRowSpec(
    val title: String,
    val icon: ImageVector?,
    val value: String?,
    val chevron: Boolean,
    val style: ListRowStyle,
    val loading: Boolean,
    val enabled: Boolean,
    val action: ListRowAction?,
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
                .padding(
                    start = DoorTheme.spacing.m,
                    // The action's 48dp touch area fills the row height, and its icon ends
                    // where values and chevrons end in other rows.
                    end = if (row.action == null) DoorTheme.spacing.m else DoorTheme.spacing.m - ACTION_INSET,
                    top = if (row.action == null) DoorTheme.spacing.s else 0.dp,
                    bottom = if (row.action == null) DoorTheme.spacing.s else 0.dp,
                ),
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
            if (row.action != null) RowAction(row.action)
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
private fun RowAction(action: ListRowAction) {
    val colors = DoorTheme.colors
    if (action.loading) {
        Box(Modifier.size(ACTION_SIZE), contentAlignment = Alignment.Center) { Spinner() }
        return
    }
    val tint = if (action.style == ListRowStyle.Destructive) colors.red else colors.accent
    PressableSurface(
        onClick = action.onClick,
        modifier = Modifier
            .heightIn(min = ACTION_SIZE)
            .widthIn(min = ACTION_SIZE)
            .semantics { contentDescription = action.label },
        enabled = action.enabled,
    ) {
        Row(
            Modifier.padding(horizontal = ACTION_INSET),
            horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = if (action.text == null) ICON_SIZE else 20.dp
            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
            if (action.text != null) Text(action.text, style = DoorTheme.type.body, color = tint)
        }
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
private val ACTION_SIZE = 48.dp
private val ACTION_INSET = (ACTION_SIZE - ICON_SIZE) / 2

@DoorPreviews
@DoorFontScalePreview
@Composable
private fun ListGroupPreview() = PreviewColumn {
    ListGroup(header = "Thiết bị") {
        row("Địa chỉ", value = "24:6F:28:A1:B2:C3")
        row("Vai trò", value = "Quản trị")
    }
    ListGroup {
        val clear = ListRowAction(Icons.Rounded.Delete, "Xóa", style = ListRowStyle.Destructive, onClick = {})
        row("Lên", icon = Icons.Rounded.AddCircleOutline, value = "Đã học", action = clear, onClick = {})
        val learn = ListRowAction(Icons.Rounded.Link, "Học lệnh nút Xuống", text = "Học lệnh", onClick = {})
        row("Xuống", icon = Icons.Rounded.AddCircleOutline, action = learn, onClick = {})
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
