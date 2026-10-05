package com.trananh.rollingdoor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Immutable
data class DoorRadius(
    val control: SquircleShape = SquircleShape(12.dp), // buttons, inputs
    val card: SquircleShape = SquircleShape(20.dp),    // cards, list groups
    val sheet: SquircleShape = SquircleShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 0.dp, bottomStart = 0.dp),
)

// iOS "continuous" corners: the curve starts 1.53 r along each edge and eases into the arc,
// so there is no visible kink where the straight edge meets the corner. Control points are the
// ones UIBezierPath(roundedRect:cornerRadius:) uses. A radius too large for the size is reduced
// so the curves still fit; use CircleShape or a 50% RoundedCornerShape for pills.
@Immutable
data class SquircleShape(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) : Shape {
    constructor(radius: Dp) : this(radius, radius, radius, radius)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val limit = min(size.width, size.height) / 2f / EXTENT
        fun px(radius: Dp) = with(density) { radius.toPx() }.coerceIn(0f, limit)
        val topLeft = px(if (ltr) topStart else topEnd)
        val topRight = px(if (ltr) topEnd else topStart)
        val bottomRight = px(if (ltr) bottomEnd else bottomStart)
        val bottomLeft = px(if (ltr) bottomStart else bottomEnd)
        val w = size.width
        val h = size.height

        val path = Path()
        // Clockwise from the end of the top-left corner. For each corner, u points back along
        // the edge we arrive on and v along the edge we leave on.
        path.moveTo(topLeft * EXTENT, 0f)
        path.corner(Offset(w, 0f), Offset(-1f, 0f), Offset(0f, 1f), topRight)
        path.corner(Offset(w, h), Offset(0f, -1f), Offset(-1f, 0f), bottomRight)
        path.corner(Offset(0f, h), Offset(1f, 0f), Offset(0f, -1f), bottomLeft)
        path.corner(Offset(0f, 0f), Offset(0f, 1f), Offset(1f, 0f), topLeft)
        path.close()
        return Outline.Generic(path)
    }

    private fun Path.corner(c: Offset, u: Offset, v: Offset, r: Float) {
        fun p(a: Float, b: Float) = c + u * (a * r) + v * (b * r)
        if (r == 0f) {
            lineTo(c.x, c.y)
            return
        }
        p(EXTENT, 0f).let { lineTo(it.x, it.y) }
        cubic(p(1.08849323f, 0f), p(0.86840689f, 0f), p(0.66993427f, 0.06549600f))
        p(0.63149399f, 0.07491100f).let { lineTo(it.x, it.y) }
        cubic(p(0.37282392f, 0.16905899f), p(0.16905899f, 0.37282392f), p(0.07491100f, 0.63149399f))
        p(0.06549600f, 0.66993427f).let { lineTo(it.x, it.y) }
        cubic(p(0f, 0.86840689f), p(0f, 1.08849323f), p(0f, EXTENT))
    }

    private fun Path.cubic(c1: Offset, c2: Offset, end: Offset) =
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)

    private companion object {
        const val EXTENT = 1.52866483f
    }
}
