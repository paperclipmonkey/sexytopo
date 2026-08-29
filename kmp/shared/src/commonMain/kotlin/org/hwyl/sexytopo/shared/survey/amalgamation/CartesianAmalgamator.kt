package org.hwyl.sexytopo.shared.survey.amalgamation

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * Compares the cartesian endpoints of the readings: they are compatible if every pair of endpoints
 * lies within a fixed distance of each other. Readings are averaged as vectors (the centroid of
 * their endpoints).
 *
 * This behaves sensibly at any inclination because it measures the actual spatial spread of the
 * readings rather than their angular spread. The tolerance is an absolute distance, so it bounds
 * the readings to a sphere of that radius regardless of the leg's direction — by default 0.1m,
 * the BCRA Grade 5 cell size.
 *
 * Ported from `control/util/amalgamation/CartesianAmalgamator`.
 */
internal object CartesianAmalgamator {

    fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean {
        val maxEndpointDelta = settings.maxEndpointDelta
        val endpoints = endpointsOf(legs)
        for (i in endpoints.indices) {
            for (j in i + 1 until endpoints.size) {
                if (getDistance(endpoints[i], endpoints[j]) > maxEndpointDelta) {
                    return false
                }
            }
        }
        return true
    }

    fun average(legs: List<Leg>): Leg = averageVectors(legs)
}
