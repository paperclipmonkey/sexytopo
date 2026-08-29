package org.hwyl.sexytopo.shared.survey.amalgamation

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * Compares the readings using the relative pairwise method from TopoDroid: for each pair of
 * readings the gap between their endpoints is expressed as a fraction of each reading's length and
 * the two fractions are summed; the readings are compatible if this stays below a threshold for
 * every pair. Readings are averaged as vectors (the centroid of their endpoints).
 *
 * The tolerance is relative rather than absolute, so longer legs are allowed a proportionally
 * larger gap. Like the cartesian method it behaves sensibly at any inclination.
 *
 * Ported from `control/util/amalgamation/PairwiseAmalgamator`.
 */
internal object PairwiseAmalgamator {

    fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean {
        val maxRelativeError = settings.maxPairwiseError
        val endpoints = endpointsOf(legs)
        for (i in endpoints.indices) {
            for (j in i + 1 until endpoints.size) {
                val gap = getDistance(endpoints[i], endpoints[j])
                val lengthI = legs[i].distance
                val lengthJ = legs[j].distance
                if (lengthI == 0f || lengthJ == 0f) {
                    // Degenerate zero-length reading; fall back to an absolute comparison.
                    // Note this compares metres against a dimensionless threshold, as the
                    // original does — it only bites on a 0m shot, which is a bad reading anyway.
                    if (gap > maxRelativeError) {
                        return false
                    }
                    continue
                }
                if ((gap / lengthI) + (gap / lengthJ) > maxRelativeError) {
                    return false
                }
            }
        }
        return true
    }

    fun average(legs: List<Leg>): Leg = averageVectors(legs)
}
