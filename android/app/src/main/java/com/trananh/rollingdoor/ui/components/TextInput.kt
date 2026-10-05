package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme

// Single text field on a card background, 12dp corners. error != null draws a red border and
// shows the message under the field.
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    textStyle: TextStyle = DoorTheme.type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = DoorTheme.colors
    val shape = DoorTheme.radius.control
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = textStyle.copy(color = colors.label),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(colors.card)
                        .then(if (error != null) Modifier.border(1.dp, colors.red, shape) else Modifier)
                        .padding(horizontal = DoorTheme.spacing.m, vertical = DoorTheme.spacing.s),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle, color = colors.secondaryLabel)
                    }
                    field()
                }
            },
        )
        if (error != null) {
            Text(
                error,
                modifier = Modifier.padding(horizontal = DoorTheme.spacing.m),
                style = DoorTheme.type.footnote,
                color = colors.red,
            )
        }
    }
}

@DoorPreviews
@Composable
private fun TextInputPreview() = PreviewColumn {
    val mono = DoorTheme.type.body.copy(fontFamily = FontFamily.Monospace)
    TextInput("", {}, Modifier.fillMaxWidth(), placeholder = "RDOOR1:…", textStyle = mono)
    TextInput("RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIOB4", {}, Modifier.fillMaxWidth(), textStyle = mono)
    TextInput("RDOOR1:246F", {}, Modifier.fillMaxWidth(), error = "Mã thiết lập không hợp lệ", textStyle = mono)
}
