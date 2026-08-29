package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.SketchDetail
import org.hwyl.sexytopo.shared.model.survey.Station

/**
 * Picking things out of a sketch with a fingertip.
 *
 * Ported from `Sketch.findNearestVisibleDetailWithin`, `Sketch.findEligibleSnapPointWithin`,
 * `GraphView.findCrossSectionBodyAt` and `GraphView.findNearestStationWithinDelta`.
 *
 * Every threshold reaching these functions is in survey metres, having been converted from the
 * screen by dividing by the current pixels-per-metre. That is the whole trick of the interaction
 * model: the finger has a constant size in millimetres, so its reach in the cave shrinks as you
 * zoom in, which is exactly what a surveyor expects.
 */

/**
 * Anything the editor can add to, or remove from, a [Sketch].
 *
 * The Android app has a single `SketchDetail` hierarchy that cross-sections belong to; the shared
 * Kotlin model keeps [CrossSectionDetail] separate (it is not ink — it owns a sub-sketch and a
 * station). This union puts them back together for the operations that treat them alike: undo,
 * redo, deletion and hit-testing.
 */
sealed interface SketchItem {

    /** A path, symbol or text detail. */
    class Drawn(val detail: SketchDetail) : SketchItem

    /** A cross-section component placed on the plan. */
    class Section(val detail: CrossSectionDetail) : SketchItem
}

fun SketchDetail.asItem(): SketchItem.Drawn = SketchItem.Drawn(this)

fun CrossSectionDetail.asItem(): SketchItem.Section = SketchItem.Section(this)

/**
 * The nearest detail to [point] within [delta] metres that is large enough to see at
 * [pixelsPerMetre], or null.
 *
 * Two details of the original are load-bearing and reproduced:
 *  - both comparisons are strict (`distance < delta` and `distance < best`), so a detail exactly at
 *    the tolerance is not selectable and the *first* of two equidistant details wins;
 *  - the scan order is paths, then symbols, then text, then cross-sections, which is what decides
 *    those ties.
 */
fun findNearestVisibleItemWithin(
    sketch: Sketch,
    point: Coord2D,
    delta: Float,
    pixelsPerMetre: Float,
    minCrossSectionPixels: Float = SketchDefaults.MIN_VISIBLE_CROSS_SECTION_DP,
): SketchItem? {
    var closest: SketchItem? = null
    var minDistance = Float.MAX_VALUE

    fun consider(item: SketchItem, distance: Float) {
        if (distance < delta && distance < minDistance) {
            closest = item
            minDistance = distance
        }
    }

    for (detail in sketch.pathDetails + sketch.symbolDetails + sketch.textDetails) {
        if (!couldBeVisibleAtScale(detail, pixelsPerMetre)) continue
        consider(detail.asItem(), distanceFrom(detail, point))
    }

    for (section in sketch.crossSectionDetails) {
        if (!couldBeVisibleAtScale(section, pixelsPerMetre, minCrossSectionPixels)) continue
        consider(section.asItem(), distanceFrom(section, point))
    }

    return closest
}

/** As [findNearestVisibleItemWithin] but ignoring how small things are drawn. */
fun findNearestItemWithin(sketch: Sketch, point: Coord2D, delta: Float): SketchItem? =
    findNearestVisibleItemWithin(sketch, point, delta, Float.MAX_VALUE, minCrossSectionPixels = 0f)

/**
 * The end point of another stroke within [delta] metres of [point], for snap-to-line, or null.
 *
 * Only the two *ends* of each existing path are candidates: the point of snapping is to close up
 * the joins between the strokes making up a passage wall, not to weld a new stroke anywhere along
 * an old one. [exclude] is the stroke currently being drawn, which must never snap to itself.
 */
fun findEligibleSnapPointWithin(
    sketch: Sketch,
    point: Coord2D,
    delta: Float,
    exclude: PathDetail? = null,
): Coord2D? {
    var closest: Coord2D? = null
    var minDistance = Float.MAX_VALUE

    for (path in sketch.pathDetails) {
        if (path === exclude) continue
        val points = path.path
        if (points.isEmpty()) continue
        for (candidate in listOf(points.first(), points.last())) {
            val distance = getDistance(point, candidate)
            if (distance < delta && distance < minDistance) {
                closest = candidate
                minDistance = distance
            }
        }
    }

    return closest
}

/**
 * The cross-section component whose body contains [point], or null.
 *
 * Distinct from [findNearestVisibleItemWithin], which only ever measures to a cross-section's
 * centre: pressing anywhere inside the frame — on a splay, on the sub-sketch — should hit it. There
 * is no distance tolerance; the bounding box either contains the point or it does not. Overlapping
 * components are resolved by whichever centre is nearer, first-wins on a tie.
 */
fun findCrossSectionBodyAt(sketch: Sketch, point: Coord2D): CrossSectionDetail? {
    var best: CrossSectionDetail? = null
    var bestDistance = Float.MAX_VALUE

    for (detail in sketch.crossSectionDetails) {
        if (!boundsOf(detail).contains(point)) continue
        val distance = distanceFrom(detail, point)
        if (distance < bestDistance) {
            best = detail
            bestDistance = distance
        }
    }

    return best
}

/**
 * The station nearest [target] within [delta] metres, for the select tool and long-press menus.
 *
 * Note the tolerance is inclusive here (`distance > delta` is skipped) where the detail search is
 * exclusive — a small inconsistency in the original, preserved.
 */
fun findNearestStationWithin(space: Space<Coord2D>, target: Coord2D, delta: Float): Station? {
    var best: Station? = null
    var shortest = Float.MAX_VALUE

    for ((station, point) in space.stationMap) {
        val distance = getDistance(point, target)
        if (distance > delta) continue
        if (best == null || distance < shortest) {
            best = station
            shortest = distance
        }
    }

    return best
}
