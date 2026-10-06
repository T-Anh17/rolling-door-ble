package com.trananh.rollingdoor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorTheme

enum class StepState { Waiting, Running, Done, Failed }

class Step(val label: String, val state: StepState)

// Progress of the pairing steps: Find device -> Connect -> Exchange key -> Confirm.
@Composable
fun StepList(steps: List<Step>, modifier: Modifier = Modifier) {
    Column(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
    ) {
        steps.forEach { step ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepIndicator(step.state)
                Text(
                    step.label,
                    style = if (step.state == StepState.Running) DoorTheme.type.headline else DoorTheme.type.body,
                    color = if (step.state == StepState.Waiting) {
                        DoorTheme.colors.secondaryLabel
                    } else {
                        DoorTheme.colors.label
                    },
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(state: StepState) {
    val colors = DoorTheme.colors
    Box(Modifier.size(INDICATOR), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.Waiting -> Box(
                Modifier
                    .size(INDICATOR)
                    .border(1.5.dp, colors.separator, CircleShape),
            )
            StepState.Running -> Spinner(size = INDICATOR)
            StepState.Done -> Badge(Icons.Rounded.Check, colors.green)
            StepState.Failed -> Badge(Icons.Rounded.Close, colors.red)
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, color: Color) {
    Box(
        Modifier
            .size(INDICATOR)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

private val INDICATOR = 24.dp

@DoorPreviews
@DoorFontScalePreview
@Composable
private fun StepListPreview() = PreviewColumn {
    Card(Modifier.fillMaxWidth()) {
        StepList(
            listOf(
                Step("Tìm thiết bị", StepState.Done),
                Step("Kết nối", StepState.Done),
                Step("Trao khóa", StepState.Running),
                Step("Xác nhận", StepState.Waiting),
            ),
        )
    }
    Card(Modifier.fillMaxWidth()) {
        StepList(
            listOf(
                Step("Tìm thiết bị", StepState.Done),
                Step("Kết nối", StepState.Failed),
                Step("Trao khóa", StepState.Waiting),
                Step("Xác nhận", StepState.Waiting),
            ),
        )
    }
}
