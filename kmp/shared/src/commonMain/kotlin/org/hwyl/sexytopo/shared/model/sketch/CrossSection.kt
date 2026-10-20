package org.hwyl.sexytopo.shared.model.sketch

import org.hwyl.sexytopo.shared.math.toCartesian
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Line
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.graph.translate
import org.hwyl.sexytopo.shared.model.survey.Station

/**
 * A slice through the passage at one station.
 *
 * Built from the station's splays — the off-centreline shots taken at the walls, floor and roof.
 * It is one of two kinds ([orientation]). A vertical section is a plane facing a compass bearing
 * ([angle], in degrees): the whole star of splays is rotated so that bearing becomes grid north,
 * then projected east-west, so across-passage becomes the x axis and height the y axis, while
 * anything shot along the passage collapses onto the station. A horizontal section is a level
 * slice through the station, drawn as the plan is, with north at the top; it always lies flat in
 * the real world, so it has no bearing to choose and its [angle] is always 0.
 *
 * Horizontal sections are the elevation's: on the plan a level slice would only repeat what the
 * plan already shows, but on the elevation it is how a shaft's shape gets drawn.
 */
class CrossSection
private constructor(
    val station: Station,
    val angle: Float,
    val orientation: Orientation,
) {

    /** A vertical cross-section facing [angle], a compass azimuth in degrees. */
    constructor(station: Station, angle: Float) : this(station, angle, Orientation.VERTICAL)

    enum class Orientation {
        VERTICAL,
        HORIZONTAL,
    }

    /** Whether the section has a direction that can be set. Only a vertical one does. */
    val isRotatable: Boolean
        get() = orientation == Orientation.VERTICAL

    /**
     * The projection that flattens the splays into the section's own drawing: looking along the
     * way it faces, with up as up, for a vertical one, and looking down with north up the page for
     * a horizontal one. It is also what decides which splays lie in the section's plane, and so
     * are drawn solid, both in the section editor and in the section's thumbnail on the sketch.
     */
    val projectionType: Projection2D
        get() =
            if (orientation == Orientation.HORIZONTAL) {
                Projection2D.PLAN
            } else {
                Projection2D.CROSS_SECTION
            }

    /**
     * The splays as a 2D profile, with the station at the origin.
     *
     * For a vertical section `leg.rotate(-angle)` turns the splay backwards by the section's
     * bearing, so a splay shot straight down the passage ends up pointing north;
     * [Projection2D.CROSS_SECTION] then projects `(x, -z)`, discarding the north-south depth axis.
     * A horizontal section already lies in the real-world horizontal plane, so its splays are
     * projected as they are.
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
        val projectionType = projectionType

        for (leg in station.getUnconnectedOnwardLegs()) {
            val drawn = if (isRotatable) leg.rotate(-angle) else leg
            val coord3D = toCartesian(Coord3D.ORIGIN, drawn)
            val coord2D = projectionType.project(coord3D)
            projection.addLeg(drawn, Line(Coord2D.ORIGIN, coord2D))
        }

        return projection
    }

    companion object {
        /** A horizontal cross-section: a slice lying flat through [station]. */
        fun horizontal(station: Station): CrossSection =
            CrossSection(station, 0f, Orientation.HORIZONTAL)
    }
}

/**
 * A cross-section placed on a sketch, with its own sub-sketch for anything drawn inside it.
 *
 * [position] is where the surveyor dropped the section on the sketch — deliberately *not* the
 * station's own position, because a section drawn on top of the centreline would be unreadable; it
 * is normally parked in blank space beside the passage (see `CrossSectioner.horizontalRadius` for
 * how far clear that has to be).
 *
 * Unlike the Java, this is not a `SketchDetail` subclass: the shared model keeps ink and
 * cross-sections apart and rejoins them in `shared.sketch.SketchItem`.
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

    fun getProjection(): Space<Coord2D> = crossSection.getProjection().translate(position)

    /**
     * The same section at the same place with the same sub-sketch, sliced at a new bearing. A
     * section that cannot be turned (a horizontal one) is left as it is.
     */
    fun withAngle(newAngle: Float): CrossSectionDetail =
        if (!crossSection.isRotatable) {
            this
        } else {
            CrossSectionDetail(position, CrossSection(station, newAngle), sketch)
        }

    /** Note the sub-sketch is shared, not copied. Safe only because the moved-from detail is discarded. */
    fun translate(translation: Coord2D): CrossSectionDetail =
        CrossSectionDetail(position + translation, crossSection, sketch)

    /**
     * Returns itself unscaled: scaling a sketch (as the exporters do) leaves every cross-section at
     * its unscaled position. Preserved deliberately — "fixing" it would silently move cross-sections
     * relative to what the Android app exports.
     */
    fun scale(scale: Float): CrossSectionDetail = this
}

