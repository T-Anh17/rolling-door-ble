package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.ElevatedColors

// Modal sheet with 20dp top corners and a grab handle. ModalBottomSheet provides the scrim,
// drag to dismiss and the back gesture; only its look is replaced. Content uses the elevated
// palette. grouped = true for a sheet of ListGroups: a gray sheet behind white group cards,
// as in iOS grouped sheets.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    grouped: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedColors {
        val colors = DoorTheme.colors
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = DoorTheme.radius.sheet,
            containerColor = if (grouped) colors.background else colors.sheet,
            tonalElevation = 0.dp,
            scrimColor = Color.Black.copy(alpha = 0.4f),
            dragHandle = { SheetHandle() },
        ) {
            SheetBody(title, content)
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = DoorTheme.spacing.xs), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(DoorTheme.colors.separator),
        )
    }
}

@Composable
private fun SheetBody(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = DoorTheme.spacing.screenMargin)
            .padding(bottom = DoorTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.l),
    ) {
        if (title != null) {
            Text(
                title,
                Modifier.fillMaxWidth(),
                style = DoorTheme.type.headline,
                color = DoorTheme.colors.label,
                textAlign = TextAlign.Center,
            )
        }
        content()
    }
}

// The sheet as it looks open, without the modal window (which previews cannot show).
@DoorPreviews
@Composable
private fun BottomSheetPreview() = PreviewColumn {
    ElevatedColors {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(DoorTheme.radius.sheet)
                .background(DoorTheme.colors.background),
        ) {
            SheetHandle()
            SheetBody("Cài đặt") {
                ListGroup(header = "Thiết bị") {
                    row("Địa chỉ", value = "24:6F:28:A1:B2:C3")
                    row("Vai trò", value = "Quản trị")
                }
                ListGroup {
                    row("Quên thiết bị", icon = Icons.Rounded.Delete, style = ListRowStyle.Destructive, onClick = {})
                }
            }
        }
    }
}
