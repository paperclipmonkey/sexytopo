package org.hwyl.sexytopo.shared.survey.amalgamation

import kotlin.math.max
import kotlin.math.min
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * Compares distance, azimuth and inclination separately, each against its own tolerance, and
 * averages each component independently.
 *
 * It works well for gently-sloping legs but breaks down for steep ones: near the vertical a tiny
 * variation in the endpoint produces a large swing in azimuth even though the readings are in
 * practice identical, so genuinely-repeated steep readings can be rejected. The spatial strategies
 * avoid this.
 */
internal object AngularAmalgamator {

    fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean {
        var minDistance = Float.POSITIVE_INFINITY
        var maxDistance = Float.NEGATIVE_INFINITY
        var minAzimuth = Float.POSITIVE_INFINITY
        var maxAzimuth = Float.NEGATIVE_INFINITY
        var minInclination = Float.POSITIVE_INFINITY
        var maxInclination = Float.NEGATIVE_INFINITY

        // Rotate every azimuth so the first reading lands exactly on 180. Readings that agree then
        // cluster around 180, well away from the 0/360 seam, so a plain max-minus-min works.
        // (Readings more than 180 degrees apart still wrap and are compared nonsensically, but
        // those are rejected by the tolerance anyway.)
        val offsetAzimuth = 540 - legs[0].azimuth

        for (leg in legs) {
            minDistance = min(leg.distance, minDistance)
            maxDistance = max(leg.distance, maxDistance)
            val shiftedAzimuth = (leg.azimuth + offsetAzimuth) % 360
            minAzimuth = min(shiftedAzimuth, minAzimuth)
            maxAzimuth = max(shiftedAzimuth, maxAzimuth)
            minInclination = min(leg.inclination, minInclination)
            maxInclination = max(leg.inclination, maxInclination)
        }

        val distanceDiff = maxDistance - minDistance
        val azimuthDiff = maxAzimuth - minAzimuth
        val inclinationDiff = maxInclination - minInclination

        return distanceDiff <= settings.maxDistanceDelta &&
            azimuthDiff <= settings.maxAngleDelta &&
            inclinationDiff <= settings.maxAngleDelta
    }

    fun average(legs: List<Leg>): Leg = averageComponents(legs)
}
