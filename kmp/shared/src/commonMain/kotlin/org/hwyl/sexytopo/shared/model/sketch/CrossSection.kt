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
 * Ported from `model/sketch/CrossSection`. The station's splays are rotated so the section's own
 * bearing becomes "straight ahead", then projected: what is left is the passage profile as you
 * would see it looking along the passage.
 */
class CrossSection(val station: Station, val angle: Float) {

    /**
     * The splays as a 2D profile, with the station at the origin.
     *
     * Note the map is keyed on the *rotated* leg, not the original — matching the Java, which puts
     * `rotated` in the space. The rotated legs exist only inside this projection.
     */
    fun getProjection(): Space<Coord2D> {
        val projection = Space<Coord2D>()
        projection.addStation(station, Coord2D.ORIGIN)

        for (leg in station.getUnconnectedOnwardLegs()) {
            // Normalise to the section's bearing first, so the profile faces the viewer.
            val rotated = leg.rotate(-angle)
            val coord3D = toCartesian(Coord3D.ORIGIN, rotated)
            val coord2D = Projection2D.CROSS_SECTION.project(coord3D)
            projection.addLeg(rotated, Line(Coord2D.ORIGIN, coord2D))
        }

        return projection
    }
}

/**
 * A cross-section placed on the plan sketch, with its own sub-sketch for anything drawn inside it.
 *
 * Ported from `model/sketch/CrossSectionDetail`.
 */
class CrossSectionDetail(
    val position: Coord2D,
    val crossSection: CrossSection,
    val sketch: Sketch = Sketch(),
) {
    val station: Station get() = crossSection.station

    fun translate(translation: Coord2D): CrossSectionDetail =
        CrossSectionDetail(position + translation, crossSection, sketch)
}
