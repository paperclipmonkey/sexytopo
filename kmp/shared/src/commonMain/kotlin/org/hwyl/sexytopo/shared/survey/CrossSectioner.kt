package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.math.PI_OVER_180
import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.math.averageAzimuths
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.abs
import kotlin.math.cos

/**
 * Chooses the bearing to slice a passage at, from whatever the survey knows.
 *
 * The heuristic is deliberately crude — the surveyor can always re-aim the section afterwards —
 * but the reasoning is standard practice: a cross-section
 * should cut the passage square, so in the middle of a passage it faces the average of the way in
 * and the way on (bisecting a corner), at a dead end or a junction only the way in is meaningful,
 * and at the origin only the way on is.
 */
object CrossSectioner {

    fun section(survey: Survey, station: Station): CrossSection =
        CrossSection(station, angleOfSection(survey, station))

    /**
     * A section of the given kind. A vertical one faces the bearing worked out from the survey; a
     * horizontal one has no bearing to work out, as it always lies flat.
     */
    fun section(
        survey: Survey,
        station: Station,
        orientation: CrossSection.Orientation,
    ): CrossSection =
        if (orientation == CrossSection.Orientation.HORIZONTAL) {
            CrossSection.horizontal(station)
        } else {
            section(survey, station)
        }

    /**
     * Whether a vertical section at [angle] faces right on the extended elevation.
     *
     * The elevation has no bearings, so this compares the section's bearing with the way the
     * survey heads on from the station: within 90 degrees of it the section faces along the
     * survey, whichever way the survey is drawn there, and otherwise back against it.
     */
    fun isFacingRightOnElevation(survey: Survey, station: Station, angle: Float): Boolean {
        val forward = angleOfSection(survey, station)
        val facesForward = angleDifference(angle, forward) <= 90f
        return facesForward == isSurveyDrawnRight(survey, station)
    }

    /**
     * The bearing for a vertical section that faces right, or left, on the extended elevation:
     * the way the survey heads on from the station, turned round if the survey is drawn the other
     * way there. Setting a section's direction on the elevation stores one of these two, so
     * nothing new needs saving and the file reads the same in the Android app.
     */
    fun angleFacingOnElevation(survey: Survey, station: Station, facingRight: Boolean): Float {
        val forward = angleOfSection(survey, station)
        return if (facingRight == isSurveyDrawnRight(survey, station)) {
            forward
        } else {
            adjustAngle(forward, 180f)
        }
    }

    private fun isSurveyDrawnRight(survey: Survey, station: Station): Boolean =
        SurveyTraversal.getOnwardExtendedElevationDirection(survey, station) !=
            ExtendedElevationDirection.LEFT

    /** How far apart two bearings are, the short way round: 0 to 180. */
    private fun angleDifference(a: Float, b: Float): Float {
        val difference = abs(a - b) % 360f
        return if (difference > 180f) 360f - difference else difference
    }

    /**
     * The section bearing in degrees, always in `[0, 360)`.
     *
     * The survey is a tree, so any station other than the origin has exactly one incoming leg by
     * construction — which is why the original counts it as `station == origin ? 0 : 1` rather than
     * searching. Outgoing legs are the *connected* onward legs only; splays do not count.
     */
    fun angleOfSection(survey: Survey, station: Station): Float {
        val numIncomingLegs = if (survey.isOrigin(station)) 0 else 1
        val numOutgoingLegs = station.getConnectedOnwardLegs().size

        return when {
            // Mid-passage: bisect the corner. averageAzimuths is wraparound-safe, so a passage
            // running 350 degrees then 10 degrees sections at 0, not at 180.
            numIncomingLegs == 1 && numOutgoingLegs == 1 ->
                averageAzimuths(incomingAzimuth(survey, station), outgoingAzimuth(station))
            numIncomingLegs == 1 -> incomingAzimuth(survey, station)
            numOutgoingLegs == 1 -> outgoingAzimuth(station)
            // The origin with no onward legs, or with several: nothing to go on, so due north.
            else -> 0f
        }
    }

    /**
     * How far any splay reaches from the station in the horizontal plane, in metres. Used to park
     * a cross-section clear of the passage it belongs to.
     *
     * Beware: a purely vertical splay yields ~1e-16 rather than a true zero, since `cos(90 deg)` in
     * floating point is not exactly zero. Callers only ever compare it against zero to pick a
     * fallback offset, so the difference never shows — but do not write an exact-equality test.
     */
    fun horizontalRadius(station: Station): Float {
        var maximum: Double? = null
        for (splay in station.getUnconnectedOnwardLegs()) {
            val reach = splay.distance.toDouble() * cos(splay.inclination.toDouble() * PI_OVER_180)
            if (maximum == null || reach > maximum) {
                maximum = reach
            }
        }
        return (maximum ?: 0.0).toFloat()
    }

    /**
     * The azimuth of the leg that arrives at this station.
     *
     * The original catches a NullPointerException here and returns 0, with the comment that it is
     * not sure how it can happen but that it has been reported in the wild — a station detached
     * from the tree, presumably. The null-safe equivalent is kept rather than throwing.
     */
    private fun incomingAzimuth(survey: Survey, station: Station): Float =
        survey.getReferringLeg(station)?.azimuth ?: 0f

    /** The first connected onward leg; only called when there is exactly one. */
    private fun outgoingAzimuth(station: Station): Float =
        station.getConnectedOnwardLegs()[0].azimuth
}
