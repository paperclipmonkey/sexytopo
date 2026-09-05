package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.math.SvgSegment
import org.hwyl.sexytopo.shared.math.parseSvgPath

/**
 * The two action-bar icons of `res/menu/cross_section.xml`, drawn from their own path data.
 *
 * `ic_done.xml` and `ic_cancel.xml` are Android vector drawables, which is a format neither
 * Compose Multiplatform nor this port's resource bundle reads — but a vector drawable is an SVG
 * path in XML clothing, and the shared `parseSvgPath` already turns one of those into segments for
 * the cave symbols. So the same two paths are drawn here rather than approximated with a "✕" the
 * bundled font has not got, or replaced with the text buttons this editor used to carry.
 */
private const val ICON_VIEWPORT = 24f

/** `ic_done.xml`'s `pathData`. */
private const val DONE_PATH = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"

/** `ic_cancel.xml`'s. */
private const val CANCEL_PATH =
    "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 " +
        "13.41,12z"

private val donePath: Path by lazy { filledPath(DONE_PATH) }

private val cancelPath: Path by lazy { filledPath(CANCEL_PATH) }

private fun filledPath(data: String): Path {
    val path = Path()
    for (segment in parseSvgPath(data)) {
        when (segment) {
            is SvgSegment.MoveTo -> path.moveTo(segment.x, segment.y)
            is SvgSegment.LineTo -> path.lineTo(segment.x, segment.y)
            is SvgSegment.CubicTo ->
                path.cubicTo(
                    segment.x1,
                    segment.y1,
                    segment.x2,
                    segment.y2,
                    segment.x,
                    segment.y,
                )
            SvgSegment.Close -> path.close()
        }
    }
    path.close()
    return path
}

@Composable
fun DoneIcon(colour: Color, size: Dp = 22.dp) = VectorIcon(donePath, colour, size)

@Composable
fun CancelIcon(colour: Color, size: Dp = 22.dp) = VectorIcon(cancelPath, colour, size)

@Composable
private fun VectorIcon(path: Path, colour: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.minDimension / ICON_VIEWPORT
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(path, colour)
        }
    }
}
