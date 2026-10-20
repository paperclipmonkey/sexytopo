package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Questions about where the survey goes, asked by walking it.
 *
 * The Android app's `SurveyTraversal` (until 1.13.0 `SurveyTools`) also holds the walks
 * themselves; here those are methods on [Survey], so this keeps only what is not. It sits apart
 * from [SurveyUpdater] for the reason upstream moved it: the drawing code asks it which way a
 * cross-section faces on the elevation, and drawing code has no business calling the class that
 * changes the survey.
 */
object SurveyTraversal {

    /**
     * The direction the survey is heading in on the extended elevation once it leaves [station].
     * This is the direction a newly-created station inherits from it, and the direction a
     * cross-section at the station faces.
     *
     * A direction that doesn't propagate applies to the leg into the station alone, so it says
     * nothing about where the survey goes next. In that case this walks up to the nearest
     * ancestor whose direction does propagate, so the survey resumes the direction it was heading
     * in before.
     *
     * The walk up only happens for a station whose own direction doesn't propagate (a vertical
     * leg), so the usual case is immediate. When it does happen each step looks the parent up
     * from the whole survey, which is quadratic, but a long run of vertical legs ought to be very
     * rare.
     */
    tailrec fun getOnwardExtendedElevationDirection(
        survey: Survey,
        station: Station,
    ): ExtendedElevationDirection {
        val direction = station.extendedElevationDirection
        if (direction.propagates) {
            return direction
        }
        // The origin, with nothing above it to inherit from
        val referringLeg =
            survey.getReferringLeg(station) ?: return ExtendedElevationDirection.DEFAULT
        val parent =
            survey.getOriginatingStation(referringLeg) ?: return ExtendedElevationDirection.DEFAULT
        return getOnwardExtendedElevationDirection(survey, parent)
    }
}
