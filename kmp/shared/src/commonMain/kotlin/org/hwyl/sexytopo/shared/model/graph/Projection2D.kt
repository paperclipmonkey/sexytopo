package org.hwyl.sexytopo.shared.model.graph

import org.hwyl.sexytopo.shared.math.Space3DTransformer
import org.hwyl.sexytopo.shared.math.Space3DTransformerForElevation
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Each projection drops one axis and negates y, because mathematically the origin is bottom left
 * but on screen it is top left. That flip is load-bearing: exporters have to reverse it, and a port
 * that loses it mirrors every sketch.
 */
enum class Projection2D(val displayName: String, val abbreviation: String) {

    PLAN("Plan", "plan") {
        override fun project(coord3D: Coord3D): Coord2D = Coord2D(coord3D.x, -coord3D.y)

        override fun isLegInPlane(leg: Leg): Boolean {
            val inc = leg.inclination
            return (inc > -45 && inc < 45) ||
                (inc >= Leg.MIN_THEODOLITE_INC + 45 && inc <= Leg.MAX_THEODOLITE_INC)
        }
    },

    ELEVATION_NS("Elevation NS", "elev_ns") {
        override fun project(coord3D: Coord3D): Coord2D = Coord2D(coord3D.y, -coord3D.z)

        override fun isLegInPlane(leg: Leg): Boolean {
            val azimuth = leg.azimuth
            return azimuth < 45 || azimuth > 315 || (azimuth > 135 && azimuth < 225)
        }
    },

    ELEVATION_EW("Elevation EW", "elev_ew") {
        override fun project(coord3D: Coord3D): Coord2D = Coord2D(coord3D.x, -coord3D.z)

        override fun isLegInPlane(leg: Leg): Boolean {
            val azimuth = leg.azimuth
            return (azimuth > 45 && azimuth < 135) || (azimuth > 225 && azimuth < 315)
        }
    },

    EXTENDED_ELEVATION("Extended Elevation", "ee") {
        override fun project(coord3D: Coord3D): Coord2D = ELEVATION_NS.project(coord3D)

        override fun isLegInPlane(leg: Leg): Boolean {
            // Unrolling puts every leg in the plane by construction, except one drawn vertical:
            // that keeps its height and drops its horizontal run, so it points into the page.
            val isLegDrawnAsVertical =
                leg.hasDestination() &&
                    leg.destination.extendedElevationDirection ==
                        ExtendedElevationDirection.VERTICAL
            return !isLegDrawnAsVertical
        }
    },

    CROSS_SECTION("Cross Section", "xs") {
        override fun project(coord3D: Coord3D): Coord2D = ELEVATION_EW.project(coord3D)

        override fun isLegInPlane(leg: Leg): Boolean = ELEVATION_EW.isLegInPlane(leg)
    },
    ;

    /**
     * Whether a surveyor can draw on this projection — which is to say whether [Survey] keeps a
     * sketch for it. Two of the five: the plan and the unrolled elevation.
     */
    val isDrawable: Boolean
        get() = this == PLAN || this == EXTENDED_ELEVATION

    abstract fun project(coord3D: Coord3D): Coord2D

    abstract fun isLegInPlane(leg: Leg): Boolean

    fun transform(survey: Survey): Space<Coord3D> =
        if (this == EXTENDED_ELEVATION) {
            elevationTransformer.transformTo3D(survey)
        } else {
            transformer.transformTo3D(survey)
        }

    fun project(survey: Survey): Space<Coord2D> {
        val space3D = transform(survey)
        val space2D = Space<Coord2D>()

        for ((station, coord3D) in space3D.stationMap) {
            space2D.addStation(station, project(coord3D))
        }

        for ((leg, line3D) in space3D.legMap) {
            space2D.addLeg(leg, Line(project(line3D.start), project(line3D.end)))
        }

        return space2D
    }

    companion object {
        private val transformer = Space3DTransformer()
        private val elevationTransformer = Space3DTransformerForElevation()
    }
}
