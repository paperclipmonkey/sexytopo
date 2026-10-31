package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.max
import kotlin.math.min

/**
 * The section so far, fitted into a small square, so a surveyor can watch it fill.
 *
 * A scan that shows nothing until it ends is a scan somebody has to do twice. The count of points
 * on the screen says the sensor is alive and says nothing at all about whether the *passage* has
 * been covered — a surveyor sweeping the same wall for half a minute sees the same rising number as
 * one who swept the whole way round. What answers that question is the section itself: the parts of
 * it that are drawn are the directions that have been measured, and the gaps are what is left to do.
 * `PassageScan.wallDistances` returns a null per unmeasured sector for exactly this reason, and
 * `PassageScan.outlines` breaks its strokes at the gaps, so the shape a preview draws is already the
 * answer.
 *
 * ## Why this is here rather than in the drawing code
 *
 * It is the same argument as `DepthCamera`'s, one layer up. Fitting a drawing into a box is four
 * lines of arithmetic and every one of them fails quietly: a scale that divides by the wrong extent
 * squashes the passage, a centre worked out from the walls alone loses the surveyor off the edge,
 * and a y that is flipped once too often draws the roof at the surveyor's feet. None of those
 * throws, and all of them look plausible on a phone in a cave — which is the definition of the kind
 * of thing that belongs where a test can hold it to a known answer.
 *
 * ## What the box is
 *
 * Screen coordinates: x to the right, y *downwards*, origin at the box's top-left corner. That
 * matches every drawing surface this could be handed to, and it also matches the input — a
 * cross-section's own axes are across and down, which is what `Projection2D.CROSS_SECTION` builds
 * and what `PassageScan` returns. So nothing here flips anything, and that is worth stating out
 * loud, because "no flip" is a decision that looks identical to having forgotten one.
 */
object ScanPreview {

    /**
     * A section fitted to a box: where to draw it, and how big a metre came out.
     *
     * The station is handed back rather than assumed to be the middle. It is only the middle when
     * the passage happens to be symmetrical about the surveyor, and a rift scanned from against one
     * wall is the ordinary case rather than the exception.
     */
    class Fitted(
        val strokes: List<List<Coord2D>>,
        val station: Coord2D,
        val pixelsPerMetre: Float,
    )

    /**
     * Fits [outlines] — in metres, in the section's own axes, about the station at the origin —
     * into a square box [size] pixels across, keeping [padding] pixels clear at every edge.
     *
     * The station is always inside the fitted drawing, because the extent measured includes the
     * origin whether or not any wall was found near it. That is what makes a half-finished scan
     * readable: with one wall measured and nothing else, the surveyor sees that wall *and* a mark
     * showing where they are standing relative to it, which is the whole of what tells them which
     * way to turn next. Fitting the walls alone would draw that one wall filling the box, looking
     * for all the world like a finished section of a passage that does not exist.
     *
     * One scale for both axes, so the shape is the passage's rather than the box's. A rift comes
     * back tall and narrow here exactly as it does on the drawing.
     *
     * A scan that has found nothing yet fits to nothing: empty strokes, the station in the middle,
     * and a scale of zero, which callers should read as "there is nothing to draw" rather than as a
     * number to divide by.
     */
    fun fit(outlines: List<List<Coord2D>>, size: Float, padding: Float = 0f): Fitted {
        require(size > 0) { "a box of no size fits nothing" }
        require(padding >= 0 && padding * 2 < size) {
            "padding of $padding leaves no room in a box $size across"
        }

        val drawable = size - padding * 2
        val points = outlines.flatten()
        if (points.isEmpty()) {
            return Fitted(emptyList(), Coord2D(size / 2, size / 2), 0f)
        }

        // The origin is in the extent whether or not the scan reached anywhere near it: see above.
        var left = 0f
        var right = 0f
        var top = 0f
        var bottom = 0f
        for (point in points) {
            left = min(left, point.x)
            right = max(right, point.x)
            top = min(top, point.y)
            bottom = max(bottom, point.y)
        }

        val width = right - left
        val height = bottom - top
        val widest = max(width, height)
        // A section with no extent at all — one point, or a scan of a wall exactly at the station —
        // is drawn at its centre rather than at an infinite magnification.
        val scale = if (widest > 0f) drawable / widest else 0f

        // Centred: whatever the drawing does not use of the box is shared equally between the two
        // sides of it, so a lopsided passage sits in the middle rather than against an edge.
        val originX = padding + (drawable - width * scale) / 2 - left * scale
        val originY = padding + (drawable - height * scale) / 2 - top * scale

        return Fitted(
            strokes = outlines.map { stroke ->
                stroke.map { Coord2D(originX + it.x * scale, originY + it.y * scale) }
            },
            station = Coord2D(originX, originY),
            pixelsPerMetre = scale,
        )
    }

    /**
     * How many of the section's directions have been measured, out of how many there are.
     *
     * The number worth putting on the screen while a scan is running, in place of a count of
     * points. A point count rises just as fast for a surveyor sweeping one wall over and over as
     * for one who has been all the way round; this does not, and it is the same list of sectors
     * that decides where the drawn strokes break.
     */
    fun sectorsMeasured(wallDistances: List<Float?>): Int = wallDistances.count { it != null }
}
