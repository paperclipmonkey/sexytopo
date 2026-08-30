package org.hwyl.sexytopo.shared.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One piece of a drawn shape, in the path's own coordinates.
 *
 * Deliberately only three kinds. Every SVG command reduces to a move, a straight line or a cubic
 * Bézier — quadratics widen to cubics exactly, and elliptical arcs approximate to a handful of them
 * — which means a renderer has three cases to handle instead of twenty, and the awkward maths lives
 * here where it can be tested on every target rather than in a platform drawing layer where it
 * cannot be tested at all.
 */
sealed interface SvgSegment {
    data class MoveTo(val x: Float, val y: Float) : SvgSegment

    data class LineTo(val x: Float, val y: Float) : SvgSegment

    data class CubicTo(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
    ) : SvgSegment

    data object Close : SvgSegment
}

/**
 * Parses SVG path data into segments.
 *
 * Enough of the grammar for the cave symbols this port carries, which is: `M m L l H h V v C c
 * S s Q q T t A a Z z`. That is nearly all of it — what is missing is only the parts no symbol
 * uses.
 *
 * Unparseable input yields the segments understood so far rather than throwing. A symbol that draws
 * as half a shape is a cosmetic problem; one that throws takes the sketch down with the survey in
 * it, and this runs on every frame the canvas paints.
 */
fun parseSvgPath(data: String): List<SvgSegment> {
    val segments = mutableListOf<SvgSegment>()
    val tokens = SvgPathScanner(data)

    var currentX = 0f
    var currentY = 0f
    var startX = 0f
    var startY = 0f
    // The reflected control point that S and T mirror. Null when the previous command was not a
    // curve of the matching kind, in which case the specification says to use the current point.
    var lastCubicControlX: Float? = null
    var lastCubicControlY: Float? = null
    var lastQuadraticControlX: Float? = null
    var lastQuadraticControlY: Float? = null

    var command = ' '
    while (true) {
        val next = tokens.nextCommandOrNull(command) ?: break
        command = next
        val relative = command.isLowerCase()
        val absolute = command.uppercaseChar()

        // Every command below consumes a fixed number of numbers; a truncated tail stops parsing.
        fun number(): Float? = tokens.nextNumberOrNull()

        when (absolute) {
            'M' -> {
                val x = number() ?: break
                val y = number() ?: break
                currentX = if (relative) currentX + x else x
                currentY = if (relative) currentY + y else y
                startX = currentX
                startY = currentY
                segments += SvgSegment.MoveTo(currentX, currentY)
                // A second coordinate pair after M is an implicit lineto, per the specification.
                command = if (relative) 'l' else 'L'
            }

            'L' -> {
                val x = number() ?: break
                val y = number() ?: break
                currentX = if (relative) currentX + x else x
                currentY = if (relative) currentY + y else y
                segments += SvgSegment.LineTo(currentX, currentY)
            }

            'H' -> {
                val x = number() ?: break
                currentX = if (relative) currentX + x else x
                segments += SvgSegment.LineTo(currentX, currentY)
            }

            'V' -> {
                val y = number() ?: break
                currentY = if (relative) currentY + y else y
                segments += SvgSegment.LineTo(currentX, currentY)
            }

            'C', 'S' -> {
                val x1: Float
                val y1: Float
                if (absolute == 'C') {
                    val cx = number() ?: break
                    val cy = number() ?: break
                    x1 = if (relative) currentX + cx else cx
                    y1 = if (relative) currentY + cy else cy
                } else {
                    // S reflects the previous cubic's second control point about the current point.
                    x1 = lastCubicControlX?.let { 2 * currentX - it } ?: currentX
                    y1 = lastCubicControlY?.let { 2 * currentY - it } ?: currentY
                }
                val cx2 = number() ?: break
                val cy2 = number() ?: break
                val x2 = if (relative) currentX + cx2 else cx2
                val y2 = if (relative) currentY + cy2 else cy2
                val ex = number() ?: break
                val ey = number() ?: break
                val x = if (relative) currentX + ex else ex
                val y = if (relative) currentY + ey else ey

                segments += SvgSegment.CubicTo(x1, y1, x2, y2, x, y)
                lastCubicControlX = x2
                lastCubicControlY = y2
                lastQuadraticControlX = null
                lastQuadraticControlY = null
                currentX = x
                currentY = y
            }

            'Q', 'T' -> {
                val qx: Float
                val qy: Float
                if (absolute == 'Q') {
                    val cx = number() ?: break
                    val cy = number() ?: break
                    qx = if (relative) currentX + cx else cx
                    qy = if (relative) currentY + cy else cy
                } else {
                    qx = lastQuadraticControlX?.let { 2 * currentX - it } ?: currentX
                    qy = lastQuadraticControlY?.let { 2 * currentY - it } ?: currentY
                }
                val ex = number() ?: break
                val ey = number() ?: break
                val x = if (relative) currentX + ex else ex
                val y = if (relative) currentY + ey else ey

                // A quadratic is exactly a cubic whose controls sit two thirds of the way along.
                segments +=
                    SvgSegment.CubicTo(
                        currentX + 2f / 3f * (qx - currentX),
                        currentY + 2f / 3f * (qy - currentY),
                        x + 2f / 3f * (qx - x),
                        y + 2f / 3f * (qy - y),
                        x,
                        y,
                    )
                lastQuadraticControlX = qx
                lastQuadraticControlY = qy
                lastCubicControlX = null
                lastCubicControlY = null
                currentX = x
                currentY = y
            }

            'A' -> {
                val rx = number() ?: break
                val ry = number() ?: break
                val rotation = number() ?: break
                val largeArc = number() ?: break
                val sweep = number() ?: break
                val ex = number() ?: break
                val ey = number() ?: break
                val x = if (relative) currentX + ex else ex
                val y = if (relative) currentY + ey else ey

                segments +=
                    arcToCubics(
                        currentX,
                        currentY,
                        rx,
                        ry,
                        rotation,
                        largeArc != 0f,
                        sweep != 0f,
                        x,
                        y,
                    )
                lastCubicControlX = null
                lastCubicControlY = null
                currentX = x
                currentY = y
            }

            'Z' -> {
                segments += SvgSegment.Close
                currentX = startX
                currentY = startY
            }

            else -> break
        }

        if (absolute != 'C' && absolute != 'S') {
            lastCubicControlX = null
            lastCubicControlY = null
        }
        if (absolute != 'Q' && absolute != 'T') {
            lastQuadraticControlX = null
            lastQuadraticControlY = null
        }
    }

    return segments
}

/**
 * The elliptical-arc-to-cubic conversion from the SVG specification's implementation notes
 * (appendix F.6), which is the only genuinely awkward part of the grammar.
 *
 * Three of the cave symbols need it — sand, pebbles and rimstone dam are all drawn from ellipses —
 * and the alternative to doing it properly would be three symbols that are the wrong shape.
 *
 * The out-of-range handling is the specification's, not an invention: zero radii degenerate to a
 * straight line, negative radii take their absolute value, and radii too small to span the endpoints
 * are scaled up until they just reach. All three are reachable from real files.
 */
private fun arcToCubics(
    fromX: Float,
    fromY: Float,
    radiusX: Float,
    radiusY: Float,
    rotationDegrees: Float,
    largeArc: Boolean,
    sweep: Boolean,
    toX: Float,
    toY: Float,
): List<SvgSegment> {
    if (fromX == toX && fromY == toY) return emptyList()

    var rx = abs(radiusX)
    var ry = abs(radiusY)
    if (rx == 0f || ry == 0f) return listOf(SvgSegment.LineTo(toX, toY))

    val phi = rotationDegrees.toDouble() * PI_OVER_180
    val cosPhi = cos(phi)
    val sinPhi = sin(phi)

    // Step 1: the endpoints in the ellipse's own frame, about their midpoint.
    val dx2 = (fromX - toX) / 2.0
    val dy2 = (fromY - toY) / 2.0
    val x1p = cosPhi * dx2 + sinPhi * dy2
    val y1p = -sinPhi * dx2 + cosPhi * dy2

    // Step 2: grow the radii if they are too small to reach, per F.6.6.
    val lambda = (x1p * x1p) / (rx * rx).toDouble() + (y1p * y1p) / (ry * ry).toDouble()
    if (lambda > 1.0) {
        val scale = sqrt(lambda)
        rx = (rx * scale).toFloat()
        ry = (ry * scale).toFloat()
    }

    val rxSquared = rx.toDouble() * rx
    val rySquared = ry.toDouble() * ry
    val numerator = rxSquared * rySquared - rxSquared * y1p * y1p - rySquared * x1p * x1p
    val denominator = rxSquared * y1p * y1p + rySquared * x1p * x1p
    val factor =
        if (denominator == 0.0) 0.0 else sqrt(maxOf(0.0, numerator / denominator))
            .let { if (largeArc == sweep) -it else it }

    val cxp = factor * rx * y1p / ry
    val cyp = -factor * ry * x1p / rx

    val centreX = cosPhi * cxp - sinPhi * cyp + (fromX + toX) / 2.0
    val centreY = sinPhi * cxp + cosPhi * cyp + (fromY + toY) / 2.0

    val startAngle = angleBetween(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    var sweepAngle =
        angleBetween((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if (!sweep && sweepAngle > 0) sweepAngle -= TWO_PI
    if (sweep && sweepAngle < 0) sweepAngle += TWO_PI

    // A cubic approximates at most a quarter turn well, so split into that many pieces.
    val pieces = maxOf(1, ceil(abs(sweepAngle) / QUARTER_TURN).toInt())
    val perPiece = sweepAngle / pieces
    // The magic control-point distance for a circular arc of this angle.
    val alpha = 4.0 / 3.0 * tan(perPiece / 4)

    val out = mutableListOf<SvgSegment>()
    var angle = startAngle
    for (piece in 0 until pieces) {
        val end = angle + perPiece

        val cosStart = cos(angle)
        val sinStart = sin(angle)
        val cosEnd = cos(end)
        val sinEnd = sin(end)

        fun pointAt(c: Double, s: Double): Pair<Double, Double> =
            Pair(
                centreX + rx * cosPhi * c - ry * sinPhi * s,
                centreY + rx * sinPhi * c + ry * cosPhi * s,
            )

        fun derivativeAt(c: Double, s: Double): Pair<Double, Double> =
            Pair(
                -rx * cosPhi * s - ry * sinPhi * c,
                -rx * sinPhi * s + ry * cosPhi * c,
            )

        val (px, py) = pointAt(cosStart, sinStart)
        val (dx, dy) = derivativeAt(cosStart, sinStart)
        val (qx, qy) = pointAt(cosEnd, sinEnd)
        val (ex, ey) = derivativeAt(cosEnd, sinEnd)

        out +=
            SvgSegment.CubicTo(
                (px + alpha * dx).toFloat(),
                (py + alpha * dy).toFloat(),
                (qx - alpha * ex).toFloat(),
                (qy - alpha * ey).toFloat(),
                qx.toFloat(),
                qy.toFloat(),
            )

        angle = end
    }

    return out
}

/** The signed angle from one vector to another, as F.6.5 defines it. */
private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double {
    val dot = ux * vx + uy * vy
    val lengths = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
    if (lengths == 0.0) return 0.0
    val angle = acos((dot / lengths).coerceIn(-1.0, 1.0))
    return if (ux * vy - uy * vx < 0) -angle else angle
}

// PI_OVER_180 is the port's own, from SpaceUtils: the same constant the projections use.
private const val TWO_PI = 2 * kotlin.math.PI
private const val QUARTER_TURN = kotlin.math.PI / 2

/**
 * Pulls commands and numbers out of path data.
 *
 * SVG path data is deliberately terse: separators are optional wherever the next character cannot
 * continue the current number, so `M15,29L20,9` and `M 15 29 L 20 9` and `M15 29L20 9` are all the
 * same thing, and `2.5.5` is two numbers. The scanner reads a number by taking the longest prefix
 * that still is one, which handles all of that without a table of special cases.
 */
private class SvgPathScanner(private val data: String) {

    private var index = 0

    /**
     * The next command letter, or [previous] repeated when a number follows instead.
     *
     * Repeating is the specification's rule and is used by nearly every symbol here: `l` followed
     * by six numbers is three line segments.
     */
    fun nextCommandOrNull(previous: Char): Char? {
        skipSeparators()
        if (index >= data.length) return null
        val character = data[index]
        if (character.isLetter()) {
            index++
            return character
        }
        // A number where a command was expected repeats the last one — except after a moveto,
        // which the caller has already turned into a lineto.
        return if (previous == ' ') null else previous
    }

    fun nextNumberOrNull(): Float? {
        skipSeparators()
        val start = index
        if (index < data.length && (data[index] == '-' || data[index] == '+')) index++
        var sawDigit = false
        while (index < data.length && data[index].isDigit()) {
            index++
            sawDigit = true
        }
        if (index < data.length && data[index] == '.') {
            index++
            while (index < data.length && data[index].isDigit()) {
                index++
                sawDigit = true
            }
        }
        if (!sawDigit) {
            index = start
            return null
        }
        if (index < data.length && (data[index] == 'e' || data[index] == 'E')) {
            val exponentStart = index
            index++
            if (index < data.length && (data[index] == '-' || data[index] == '+')) index++
            if (index < data.length && data[index].isDigit()) {
                while (index < data.length && data[index].isDigit()) index++
            } else {
                index = exponentStart
            }
        }
        return data.substring(start, index).toFloatOrNull().also { if (it == null) index = start }
    }

    private fun skipSeparators() {
        while (index < data.length && (data[index] == ',' || data[index].isWhitespace())) index++
    }
}
