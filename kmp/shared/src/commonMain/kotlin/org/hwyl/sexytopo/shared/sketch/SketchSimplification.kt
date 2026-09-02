package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.math.getDistanceFromLine
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.max
import kotlin.math.min

/**
 * A finger dragged across the screen produces a touch sample every few milliseconds, so a single
 * passage wall can arrive as several hundred near-collinear points; the raw stroke is thinned with
 * Douglas-Peucker the moment the finger lifts. The tolerance is relative to the stroke's own size,
 * not to the screen or the survey: a long wall may lose a lot of detail, a small scallop symbol
 * almost none.
 */

/**
 * The Douglas-Peucker tolerance for a stroke whose bounding box is [width] x [height] metres.
 *
 * A five-hundredth of the stroke's longer side, with a 1 mm floor so a degenerate (single-point or
 * perfectly axis-aligned) stroke still gets a positive epsilon and is not rejected by the
 * `epsilon <= 0` guard in [simplify].
 */
fun simplificationEpsilon(width: Float, height: Float): Float = max(max(width, height) / 500f, 0.001f)

/** The tolerance for a stroke, derived from the bounding box of its own points. */
fun simplificationEpsilon(path: List<Coord2D>): Float {
    val bounds = boundsOf(path)
    return simplificationEpsilon(bounds.width, bounds.height)
}

/**
 * Douglas-Peucker line simplification.
 *
 * Reproduces two quirks of the original that callers and tests depend on:
 *  - an empty path, or a non-positive [epsilon], returns the input unchanged;
 *  - any other path always comes back with at least two points, so a single-point "dot" stroke
 *    becomes a two-point path with coincident ends. That is what makes a tap render as a dot.
 */
fun simplify(path: List<Coord2D>, epsilon: Float): List<Coord2D> {
    if (path.isEmpty() || epsilon <= 0) return path
    return douglasPeuckerIteration(path, epsilon)
}

private fun douglasPeuckerIteration(path: List<Coord2D>, epsilon: Float): List<Coord2D> {
    val pathSize = path.size
    var indexMax = 0
    var distMax = 0f

    // Find the point furthest from the chord between the ends. Note the distance is measured to the
    // *segment*, so on a closed stroke (ends coincident) it degenerates to distance from that
    // shared end point — which is what makes a densified circle simplify to a diamond.
    for (i in 1 until pathSize) {
        val dist = getDistanceFromLine(path[i], path[0], path[pathSize - 1])
        if (dist > distMax) {
            distMax = dist
            indexMax = i
        }
    }

    if (distMax <= epsilon) {
        return listOf(path[0], path[pathSize - 1])
    }

    val first = douglasPeuckerIteration(path.subList(0, indexMax + 1), epsilon)
    val second = douglasPeuckerIteration(path.subList(indexMax, pathSize), epsilon)
    // The split point appears at the end of the first half and the start of the second; drop one.
    return first + second.subList(1, second.size)
}

internal fun boundsOf(points: List<Coord2D>): DetailBounds {
    var bounds = DetailBounds.EMPTY
    for (point in points) {
        bounds += point
    }
    return bounds
}

/**
 * An axis-aligned bounding box in survey coordinates.
 *
 * SexyTopo works in screen-style coordinates throughout: y increases *downwards*, so "top" is the
 * minimum y. An unpopulated box keeps infinite bounds internally (so it never reports intersecting
 * anything) but reports zero for its edges and size.
 */
class DetailBounds
internal constructor(
    internal val rawLeft: Float,
    internal val rawRight: Float,
    internal val rawTop: Float,
    internal val rawBottom: Float,
) {
    val left: Float get() = if (rawLeft == Float.POSITIVE_INFINITY) 0f else rawLeft
    val right: Float get() = if (rawRight == Float.NEGATIVE_INFINITY) 0f else rawRight
    val top: Float get() = if (rawTop == Float.POSITIVE_INFINITY) 0f else rawTop
    val bottom: Float get() = if (rawBottom == Float.NEGATIVE_INFINITY) 0f else rawBottom

    val width: Float
        get() =
            if (rawLeft == Float.POSITIVE_INFINITY || rawRight == Float.NEGATIVE_INFINITY) {
                0f
            } else {
                rawRight - rawLeft
            }

    val height: Float
        get() =
            if (rawTop == Float.POSITIVE_INFINITY || rawBottom == Float.NEGATIVE_INFINITY) {
                0f
            } else {
                rawBottom - rawTop
            }

    val maxDimension: Float get() = max(width, height)

    val topLeft: Coord2D get() = Coord2D(left, top)

    val bottomRight: Coord2D get() = Coord2D(right, bottom)

    val isEmpty: Boolean get() = rawLeft == Float.POSITIVE_INFINITY

    operator fun plus(point: Coord2D): DetailBounds =
        DetailBounds(
            min(rawLeft, point.x),
            max(rawRight, point.x),
            min(rawTop, point.y),
            max(rawBottom, point.y),
        )

    /**
     * Merge another box in, via its corners.
     *
     * Deliberately faithful to the Java, which reads the *zeroed* corner getters: merging an empty
     * box therefore drags the origin into this one, reproduced here rather than fixed.
     */
    operator fun plus(other: DetailBounds): DetailBounds = this + other.topLeft + other.bottomRight

    /** True if this box overlaps the given rectangle. An empty box never intersects anything. */
    fun intersects(rectangleTopLeft: Coord2D, rectangleBottomRight: Coord2D): Boolean =
        (rawRight >= rectangleTopLeft.x && rawLeft <= rectangleBottomRight.x) &&
            (rawTop <= rectangleBottomRight.y && rawBottom >= rectangleTopLeft.y)

    fun contains(point: Coord2D): Boolean = intersects(point, point)

    companion object {
        val EMPTY =
            DetailBounds(
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )
    }
}
