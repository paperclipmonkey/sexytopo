package org.hwyl.sexytopo.shared.survey.amalgamation

import org.hwyl.sexytopo.shared.math.averageAzimuths
import org.hwyl.sexytopo.shared.math.toCartesian
import org.hwyl.sexytopo.shared.math.toLeg
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * Geometry helpers shared between the leg amalgamation strategies.
 *
 * Ported from `control/util/amalgamation/Amalgamation`.
 */

/** The cartesian endpoints of the readings, each taken from a common origin. */
internal fun endpointsOf(legs: List<Leg>): List<Coord3D> =
    legs.map { toCartesian(Coord3D.ORIGIN, it) }

/**
 * Averages each component (distance, azimuth, inclination) of the readings independently.
 *
 * Azimuths go through [averageAzimuths] so that readings straddling the 0/360 seam average to
 * north rather than to south; distance and inclination are plain arithmetic means.
 */
internal fun averageComponents(legs: List<Leg>): Leg {
    val count = legs.size
    var distance = 0.0f
    var inclination = 0.0f
    val azimuths = FloatArray(count)
    for (i in 0 until count) {
        val leg = legs[i]
        distance += leg.distance
        inclination += leg.inclination
        azimuths[i] = leg.azimuth
    }
    return Leg(distance / count, averageAzimuths(*azimuths), inclination / count)
}

/**
 * Averages the readings as vectors by taking the centroid of their cartesian endpoints and
 * converting back to a distance, azimuth and inclination.
 *
 * This is the only average that behaves near the vertical: scalar-averaging azimuths 4 and 356
 * gives a meaningless 180, whereas the centroid of the two endpoints points due north as it should.
 * Note that the averaged distance is the length of the centroid vector, so readings that disagree
 * in direction come out slightly *shorter* than the readings themselves.
 */
internal fun averageVectors(legs: List<Leg>): Leg {
    var x = 0f
    var y = 0f
    var z = 0f
    for (leg in legs) {
        val endpoint = toCartesian(Coord3D.ORIGIN, leg)
        x += endpoint.x
        y += endpoint.y
        z += endpoint.z
    }
    val count = legs.size
    return toLeg(Coord3D(x / count, y / count, z / count))
}
