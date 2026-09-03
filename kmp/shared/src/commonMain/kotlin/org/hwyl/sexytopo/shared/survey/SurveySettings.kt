package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm

/**
 * The surveying preferences the engine reads, with the same defaults as the Android original.
 *
 * Defaults come from `control/util/GeneralPreferences` and `SexyTopoConstants`:
 *  - `pref_leg_amalgamation_algorithm` = "angular"
 *  - `pref_max_distance_delta` = 0.05 (metres)
 *  - `pref_max_angle_delta` = 1.7 (degrees)
 *  - `pref_max_endpoint_delta` = 0.1 (metres — the BCRA Grade 5 cell size)
 *  - `pref_max_pairwise_error` = 0.05 (dimensionless, summed relative error)
 *  - `NUM_OF_REPEATS_FOR_NEW_STATION` = 3
 */
data class SurveySettings(
    /** Which strategy decides whether repeated readings agree, and how they are averaged. */
    val legAmalgamationAlgorithm: LegAmalgamationAlgorithm = LegAmalgamationAlgorithm.ANGULAR,
    /** Angular strategy: greatest permitted spread in distance, in metres. */
    val maxDistanceDelta: Float = 0.05f,
    /** Angular strategy: greatest permitted spread in azimuth and in inclination, in degrees. */
    val maxAngleDelta: Float = 1.7f,
    /** Cartesian strategy: greatest permitted gap between any two reading endpoints, in metres. */
    val maxEndpointDelta: Float = 0.1f,
    /** Pairwise strategy: greatest permitted sum of the gap as a fraction of each leg's length. */
    val maxPairwiseError: Float = 0.05f,
    /**
     * How many mutually-compatible repeated readings promote themselves into a leg and a new
     * station. Three is the surveying convention: two agreeing readings could both be wrong in the
     * same way, three catch a fat-fingered repeat.
     */
    val numberOfRepeatsForNewStation: Int = 3,
) {
    companion object {
        val DEFAULT = SurveySettings()
    }
}
