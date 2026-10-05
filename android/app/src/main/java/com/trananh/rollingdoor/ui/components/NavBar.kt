package com.trananh.rollingdoor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.ui.theme.DoorMotion
import com.trananh.rollingdoor.ui.theme.DoorTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.haze

// Screen frame with an iOS navigation bar: the 34sp large title scrolls with the content, and
// once it is gone a 17sp title fades in, centered in a frosted bar (real 20dp blur on Android 12+,
// 92% opaque background below). Content gets the screen margins and edge-to-edge insets.
// topOverlay draws above everything, for BannerHost.
@Composable
fun NavBarScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    topOverlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DoorTheme.colors
    val spacing = DoorTheme.spacing
    val scroll = rememberScrollState()
    val hazeState = remember { HazeState() }
    var largeTitleHeight by remember { mutableIntStateOf(0) }
    val collapsed by remember { derivedStateOf { largeTitleHeight > 0 && scroll.value >= largeTitleHeight } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, DoorMotion.spring(), label = "navBar")
    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Box(modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .haze(hazeState)
                .verticalScroll(scroll)
                .windowInsetsPadding(topInsets)
                .padding(top = BAR_HEIGHT)
                .padding(horizontal = spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(
                title,
                Modifier
                    .onSizeChanged { largeTitleHeight = it.height }
                    .semantics { heading() },
                style = DoorTheme.type.largeTitle,
                color = colors.label,
            )
            content()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (collapsed) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = colors.background,
                                tint = HazeTint(colors.background.copy(alpha = 0.7f)),
                                blurRadius = 20.dp,
                                noiseFactor = 0f,
                                fallbackTint = HazeTint(colors.background.copy(alpha = 0.92f)),
                            ),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(topInsets)
                    .height(BAR_HEIGHT)
                    .padding(horizontal = spacing.screenMargin - spacing.xs),
            ) {
                Text(
                    title,
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp)
                        .graphicsLayer { alpha = barAlpha }
                        .clearAndSetSemantics { }, // the large title already reads it
                    style = DoorTheme.type.headline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .graphicsLayer { alpha = barAlpha }
                    .background(colors.separator),
            )
        }

        topOverlay()
    }
}

// Icon button for NavBarScaffold actions, such as Settings.
@Composable
fun NavBarIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    PressableSurface(onClick = onClick, shape = CircleShape) {
        Icon(icon, contentDescription, tint = DoorTheme.colors.accent, modifier = Modifier.size(26.dp))
    }
}

private val BAR_HEIGHT = 44.dp

@DoorPreviews
@Composable
private fun NavBarScaffoldPreview() = PreviewColumn {
    Box(Modifier.height(560.dp)) {
        NavBarScaffold(
            title = "Cửa cuốn",
            actions = { NavBarIconButton(Icons.Rounded.Settings, "Cài đặt") {} },
        ) {
            StatusPill("Sẵn sàng", StatusTone.Ready)
            repeat(6) {
                Card(Modifier.fillMaxWidth()) {
                    Text("Thẻ ${it + 1}", style = DoorTheme.type.body, color = DoorTheme.colors.label)
                }
            }
        }
    }
}
