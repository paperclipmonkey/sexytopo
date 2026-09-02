package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D

/**
 * Whether a line is entirely off the drawing, and so need not be drawn at all.
 *
 * This is the first half of Cohen and Sutherland's clipping algorithm and none of the second: each
 * end gets a four-bit code saying which sides of the rectangle it is outside, and if the two codes
 * share a bit then both ends are off the same side and nothing between them can be on screen.
 * Sharing no bits is not proof that a line *is* visible — one end above and the other to the left
 * may still miss the corner — so this is conservative in the only direction that is safe: it never
 * hides a line that should be drawn.
 */
fun whollyOutside(
    start: Coord2D,
    end: Coord2D,
    corner: Coord2D,
    oppositeCorner: Coord2D,
): Boolean {
    val startCode = bitcode(corner, oppositeCorner, start)
    val endCode = bitcode(corner, oppositeCorner, end)
    // The Java also tests that each code is non-zero, which `and` being non-zero already implies.
    return (startCode and endCode) != 0
}

/** Which sides of the rectangle [point] is outside, one bit each. */
private fun bitcode(corner: Coord2D, oppositeCorner: Coord2D, point: Coord2D): Int {
    var code = 0
    if (point.y < minOf(corner.y, oppositeCorner.y)) code = code or ABOVE
    if (point.y > maxOf(corner.y, oppositeCorner.y)) code = code or BELOW
    if (point.x < minOf(corner.x, oppositeCorner.x)) code = code or LEFT
    if (point.x > maxOf(corner.x, oppositeCorner.x)) code = code or RIGHT
    return code
}

private const val ABOVE = 0x8
private const val BELOW = 0x4
private const val RIGHT = 0x2
private const val LEFT = 0x1

/**
 * Whether a point, and whatever is drawn around it, is entirely off the drawing.
 *
 * [margin] is how far outside the rectangle a station can sit and still put something inside it:
 * its name is drawn up and to the right, and the active one wears brackets wider than the dot. Too
 * small a margin makes labels flicker at the edge as the drawing is dragged; too large only costs
 * a little work, so this errs large.
 */
fun whollyOutside(
    point: Coord2D,
    corner: Coord2D,
    oppositeCorner: Coord2D,
    margin: Float,
): Boolean {
    val left = minOf(corner.x, oppositeCorner.x) - margin
    val right = maxOf(corner.x, oppositeCorner.x) + margin
    val top = minOf(corner.y, oppositeCorner.y) - margin
    val bottom = maxOf(corner.y, oppositeCorner.y) + margin
    return point.x < left || point.x > right || point.y < top || point.y > bottom
}
