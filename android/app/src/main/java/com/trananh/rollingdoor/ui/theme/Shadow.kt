package com.trananh.rollingdoor.ui.theme

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Soft, wide shadow like iOS cards, instead of Material elevation. Pass
// enabled = !DoorTheme.colors.isDark: dark mode has no shadows, as on iOS.
// Below Android 9 the hardware canvas ignores setShadowLayer, so there is no shadow there.
fun Modifier.softShadow(
    shape: Shape,
    enabled: Boolean = true,
    y: Dp = 4.dp,
    blur: Dp = 20.dp,
    alpha: Float = 0.08f,
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return this
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val paint = Paint()
        paint.asFrameworkPaint().apply {
            // Transparent fill: only the shadow shows; the content draws the surface on top.
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur.toPx(), 0f, y.toPx(), Color.Black.copy(alpha = alpha).toArgb())
        }
        drawIntoCanvas { it.drawOutline(outline, paint) }
    }
}
