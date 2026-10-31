package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

/**
 * Four viewfinder corners round an upright oval, in the same 24-unit box as the two above.
 *
 * Written as segments rather than as path data because there is no drawable it is copied from, and
 * an oval is a thing [Path] draws better than four cubics spell.
 */
private val scanPath: Path by lazy {
    Path().apply {
        // Top left, top right, bottom left, bottom right: two strokes each, out from the corner.
        for ((cornerX, cornerY, towardsX, towardsY) in CORNERS) {
            moveTo(cornerX, cornerY + towardsY)
            lineTo(cornerX, cornerY)
            lineTo(cornerX + towardsX, cornerY)
        }
        addOval(Rect(9f, 7.5f, 15f, 16.5f))
    }
}

/** Each corner as its point and the direction the two arms run in. */
private val CORNERS =
    listOf(
        listOf(3f, 3f, 4f, 4f),
        listOf(21f, 3f, -4f, 4f),
        listOf(3f, 21f, 4f, -4f),
        listOf(21f, 21f, -4f, -4f),
    )

private const val SCAN_STROKE = 2f

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

/**
 * The scanner: a viewfinder's four corners with a passage outline caught inside them.
 *
 * Invented rather than transcribed, because the Android app has no scanner and so no drawable to
 * be faithful to — the same position [CameraGlyph] is in. The four corners are the convention
 * every phone uses for "point this at something", and the shape inside them is what this
 * particular one is pointed at: the section of a passage, taller than it is wide, as most of them
 * are.
 *
 * Stroked rather than filled, unlike the two beside it, and that is a deliberate difference rather
 * than an oversight: a filled reticle at twenty-two pixels is a black square. [SCAN_PATH] is
 * therefore drawn by [strokedPath] and not [filledPath], whose closing `close()` would join the
 * corners into a box.
 */
@Composable
fun ScanIcon(colour: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.minDimension / ICON_VIEWPORT
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(scanPath, colour, style = Stroke(width = SCAN_STROKE))
        }
    }
}

@Composable
private fun VectorIcon(path: Path, colour: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.minDimension / ICON_VIEWPORT
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(path, colour)
        }
    }
}
