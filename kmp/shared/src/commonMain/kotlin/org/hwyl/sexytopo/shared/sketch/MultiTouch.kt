package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The pan and zoom a set of fingers is asking for, as plain arithmetic over their positions.
 *
 * Compose's gesture detector fires for a single finger too, so a canvas that is also being drawn on
 * cannot use it directly — this is the arithmetic `SurveyCanvas` needs instead.
 */
fun centroidOf(points: List<Coord2D>): Coord2D {
    if (points.isEmpty()) return Coord2D.ORIGIN
    var x = 0f
    var y = 0f
    for (point in points) {
        x += point.x
        y += point.y
    }
    return Coord2D(x / points.size, y / points.size)
}

/**
 * How far the fingers are spread, as their mean distance from their own centroid.
 *
 * Mean distance rather than the distance between the first two, so a three-fingered pinch behaves
 * like a two-fingered one instead of ignoring a finger, and so lifting one of three does not make
 * the zoom jump.
 */
fun spreadOf(points: List<Coord2D>, centroid: Coord2D = centroidOf(points)): Float {
    if (points.size < 2) return 0f
    var total = 0f
    for (point in points) {
        val dx = point.x - centroid.x
        val dy = point.y - centroid.y
        total += sqrt(dx * dx + dy * dy)
    }
    return total / points.size
}

/**
 * The zoom factor between two frames of the same fingers: greater than one for a pinch outwards.
 *
 * Returns exactly 1 whenever the question is meaningless — fewer than two fingers, or a spread so
 * small that the ratio would be noise or a division by zero. A caller can therefore apply the
 * result unconditionally.
 */
fun zoomBetween(before: List<Coord2D>, after: List<Coord2D>): Float {
    if (before.size < 2 || after.size < 2) return 1f
    val previous = spreadOf(before)
    val current = spreadOf(after)
    if (abs(previous) < MINIMUM_SPREAD_PIXELS || abs(current) < MINIMUM_SPREAD_PIXELS) return 1f
    return current / previous
}

/** Below this spread in pixels the fingers are effectively at one point and the ratio is noise. */
private const val MINIMUM_SPREAD_PIXELS = 1f
