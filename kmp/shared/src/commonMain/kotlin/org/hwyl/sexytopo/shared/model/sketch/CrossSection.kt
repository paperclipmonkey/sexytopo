package org.hwyl.sexytopo.shared.model.sketch

import org.hwyl.sexytopo.shared.math.toCartesian
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Line
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.survey.Station

/**
 * A slice through the passage at one station, drawn on the plan.
 *
 * Ported from `model/sketch/CrossSection`.
 *
 * A cross-section is what a caver draws when the plan alone cannot say what shape the passage is:
 * standing at the station and looking along the passage, how high and how wide is it. The data it
 * is built from is the station's splays — the off-centreline shots taken at the walls, floor and
 * roof. To turn those splays into that view, the whole star of splays is rotated so that the
 * section's own bearing ([angle], a compass azimuth in degrees) becomes grid north, and then
 * projected east-west: what survives is across-passage on the x axis and height on the y axis,
 * while anything shot along the passage collapses onto the station.
 */
class CrossSection(val station: Station, val angle: Float) {

    /**
     * The splays as a 2D profile, with the station at the origin.
     *
     * Rotate first, then project. `leg.rotate(-angle)` turns the splay backwards by the section's
     * bearing (so a splay shot straight down the passage ends up pointing north), and
     * [Projection2D.CROSS_SECTION] then projects `(x, -z)`: east-west becomes screen x, height
     * becomes screen y with the sign flipped because sketch space has y increasing downwards. The
     * north-south component is discarded, which is exactly the depth axis we are looking along.
     *
     * Two faithfully-reproduced quirks of the original:
     *  - the map is keyed on the **rotated** leg, not on the station's original splay, so a caller
     *    cannot look a splay up in this space by identity;
     *  - every line starts at [Coord2D.ORIGIN], i.e. the projection is a star of rays from the
     *    station rather than a wall outline. The outline, if any, is drawn by hand into the
     *    cross-section's own sub-sketch (see [CrossSectionDetail.sketch]).
     */
    fun getProjection(): Space<Coord2D> {
        val projection = Space<Coord2D>()
        projection.addStation(station, Coord2D.ORIGIN)

        for (leg in station.getUnconnectedOnwardLegs()) {
            val rotated = leg.rotate(-angle)
            val coord3D = toCartesian(Coord3D.ORIGIN, rotated)
            val coord2D = Projection2D.CROSS_SECTION.project(coord3D)
            projection.addLeg(rotated, Line(Coord2D.ORIGIN, coord2D))
        }

        return projection
    }
}

/**
 * A cross-section placed on a sketch, with its own sub-sketch for anything drawn inside it.
 *
 * Ported from `model/sketch/CrossSectionDetail`. [position] is where the surveyor dropped the
 * section on the plan — deliberately *not* the station's own position, because a section drawn on
 * top of the centreline would be unreadable; it is normally parked in blank space beside the
 * passage (see `CrossSectioner.horizontalRadius` for how far clear that has to be).
 *
 * The sub-sketch is the one mutable part: it holds the wall outline and any symbols drawn in the
 * cross-section editor, in the same station-relative coordinates the projection uses, so moving the
 * section around the plan moves its drawing with it.
 *
 * Unlike the Java, this is not a `SketchDetail` subclass (there it is one, with `Colour.NONE`): the
 * shared model keeps ink and cross-sections apart and rejoins them in `shared.sketch.SketchItem`,
 * where the hit-testing and bounding-box behaviour of `CrossSectionDetail.refreshBoundingBox` also
 * lives.
 */
class CrossSectionDetail(
    val position: Coord2D,
    val crossSection: CrossSection,
    sketch: Sketch = Sketch(),
) {

    /** Replaced wholesale when an edit is committed from the cross-section editor. */
    var sketch: Sketch = sketch

    val station: Station
        get() = crossSection.station

    /** The splay star moved from station-relative coordinates onto the sketch. */
    fun getProjection(): Space<Coord2D> = crossSection.getProjection().translate(position)

    /**
     * The same section at the same place with the same sub-sketch, sliced at a new bearing. Used by
     * the plan-level rotate gesture, which re-aims a section the heuristic guessed wrong.
     */
    fun withAngle(newAngle: Float): CrossSectionDetail =
        CrossSectionDetail(position, CrossSection(station, newAngle), sketch)

    /**
     * Note the sub-sketch is shared, not copied — as in the original, where a moved detail keeps
     * drawing the same sub-sketch object. Safe only because the moved-from detail is discarded.
     */
    fun translate(translation: Coord2D): CrossSectionDetail =
        CrossSectionDetail(position + translation, crossSection, sketch)

    /**
     * Returns itself unscaled, reproducing the original: `CrossSectionDetail` never overrode
     * `SinglePositionDetail.scale`, which returns `this`. So scaling a sketch (as the exporters do)
     * leaves every cross-section at its unscaled position. Preserved deliberately — "fixing" it
     * would silently move cross-sections relative to what the Android app exports.
     */
    fun scale(scale: Float): CrossSectionDetail = this
}

/** Ported from `Space2DUtils.translate`; only cross-sections need it so far. */
private fun Space<Coord2D>.translate(translation: Coord2D): Space<Coord2D> {
    val translated = Space<Coord2D>()
    for ((station, coord) in stationMap) {
        translated.addStation(station, coord + translation)
    }
    for ((leg, line) in legMap) {
        translated.addLeg(leg, Line(line.start + translation, line.end + translation))
    }
    return translated
}
