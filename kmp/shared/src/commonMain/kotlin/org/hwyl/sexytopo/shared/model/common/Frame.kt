package org.hwyl.sexytopo.shared.model.common

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned rectangle in sketch coordinates.
 *
 * Ported from `model/common/Frame`. Note that [top] is the *smaller* y and [bottom] the larger:
 * sketch space has y increasing downwards, as screen space does, which is why
 * [org.hwyl.sexytopo.shared.model.graph.Projection2D] flips it on the way in. Reading this as a
 * maths rectangle instead is the quickest way to get an export upside down.
 */
data class Frame(val left: Float, val right: Float, val top: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val topLeft: Coord2D get() = Coord2D(left, top)

    fun union(other: Frame): Frame =
        Frame(
            min(left, other.left),
            max(right, other.right),
            min(top, other.top),
            max(bottom, other.bottom),
        )

    fun addPadding(x: Int, y: Int): Frame = Frame(left - x, right + x, top - y, bottom + y)

    /** Grows outwards to whole multiples of [n], which is what makes a grid land on round numbers. */
    fun expandToNearest(n: Int): Frame =
        Frame(
            roundDownTo(left, n),
            roundUpTo(right, n),
            roundDownTo(top, n),
            roundUpTo(bottom, n),
        )

    fun scale(factor: Float): Frame =
        Frame(left * factor, right * factor, top * factor, bottom * factor)

    companion object {
        /** An empty survey has no extent; a zero-sized frame would divide by zero downstream. */
        val EMPTY = Frame(0f, 0f, 0f, 0f)

        fun of(points: List<Coord2D>): Frame {
            if (points.isEmpty()) return EMPTY
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (point in points) {
                left = min(left, point.x)
                right = max(right, point.x)
                top = min(top, point.y)
                bottom = max(bottom, point.y)
            }
            return Frame(left, right, top, bottom)
        }

        /** Everything drawn on a sketch: strokes, labels, symbols and cross-section anchors. */
        fun from(sketch: Sketch): Frame =
            of(
                buildList {
                    for (detail in sketch.pathDetails) addAll(detail.path)
                    for (detail in sketch.textDetails) add(detail.position)
                    for (detail in sketch.symbolDetails) add(detail.position)
                    for (detail in sketch.crossSectionDetails) add(detail.position)
                },
            )

        /** Everything in a projected survey: both ends of every leg and splay. */
        fun from(space: Space<Coord2D>): Frame =
            of(
                buildList {
                    addAll(space.stationMap.values)
                    for (line in space.legMap.values) {
                        add(line.start)
                        add(line.end)
                    }
                },
            )

        private fun roundUpTo(value: Float, n: Int): Float = ceil(value / n) * n

        private fun roundDownTo(value: Float, n: Int): Float = floor(value / n) * n
    }
}
