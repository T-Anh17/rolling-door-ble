package com.trananh.rollingdoor.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme

// Every component preview renders light and dark, on a 360dp phone.
@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class DoorPreviews

// Same component at 2x system font size, to catch clipped or overflowing text.
@Preview(name = "Font 2x", widthDp = 360, fontScale = 2f)
annotation class DoorFontScalePreview

@Composable
internal fun PreviewColumn(content: @Composable ColumnScope.() -> Unit) {
    RollingDoorTheme {
        Column(
            modifier = Modifier
                .background(DoorTheme.colors.background)
                .padding(DoorTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
            content = content,
        )
    }
}
