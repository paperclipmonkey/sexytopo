package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D

/**
 * The dashes a leg is drawn with when it does not lie in the plane being drawn.
 *
 * A pitch in a plan projects to a stub a few pixels long, indistinguishable from a short
 * horizontal crawl; `Projection2D.isLegInPlane` decides which legs are foreshortened like that,
 * and dashing them is the only thing on the drawing that says so.
 *
 * Two visible details of the original are kept: the run is laid out from [end] backwards, so the
 * far end of the leg lands on a dash rather than a gap; and the count is `length / dashLength / 2`
 * truncated, so a leg shorter than two dash lengths draws nothing rather than one stubby dash that
 * would read as a solid line.
 *
 * Coordinates are the view's, not the survey's: dashes are a constant length on screen, so they
 * cannot be worked out in metres.
 */
fun dashesAlong(start: Coord2D, end: Coord2D, dashLength: Float): List<Pair<Coord2D, Coord2D>> {
    if (dashLength <= 0f) return emptyList()

    val from = end
    val to = start

    val dashes = (getDistance(from, to) / dashLength / 2f).toInt()
    // Also guards the normalise below: a zero-length line has no direction to walk along.
    if (dashes <= 0) return emptyList()

    val step = (to - from).normalise().scale(dashLength)

    val out = ArrayList<Pair<Coord2D, Coord2D>>(dashes)
    var previous = Coord2D.ORIGIN
    for (index in 0 until dashes) {
        val dashStart = if (index == 0) from else previous + step
        val dashEnd = dashStart + step
        out.add(dashStart to dashEnd)
        previous = dashEnd
    }
    return out
}
