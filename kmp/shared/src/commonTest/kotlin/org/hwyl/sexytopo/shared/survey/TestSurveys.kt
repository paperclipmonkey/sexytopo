package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The fixtures the Java survey-engine tests use, ported from `testutils/BasicTestSurveyCreator`.
 */
object TestSurveys {

    /** Origin "1" and stations "2", "3", "4" in a straight line due north, 5m apart. */
    fun createStraightNorth(): Survey {
        val survey = Survey()
        repeat(3) { SurveyUpdater.updateWithNewStation(survey, Leg(5f, 0f, 0f)) }
        return survey
    }

    /** Origin "1" and stations "2", "3", "4" in a straight line due south, 5m apart. */
    fun createStraightSouth(): Survey {
        val survey = Survey()
        repeat(3) { SurveyUpdater.updateWithNewStation(survey, Leg(5f, 180f, 0f)) }
        return survey
    }

    /** North, then east, then south: station "2" has a 90 degree corner at it. */
    fun createRightRight(): Survey {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 180f, 0f))
        return survey
    }

    /**
     * Station "2" has an incoming leg at 350 degrees and an outgoing one at 10 degrees, i.e. a
     * passage running almost due north straddling the 0/360 seam. Fixture for issue #176.
     */
    fun createSpanningZeroBoundary(): Survey {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 350f, 0f))
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 10f, 0f))
        return survey
    }

    /** [createStraightNorth] plus one leg east off station "1", which becomes station "5". */
    fun createStraightNorthWith1EBranch(): Survey {
        val survey = createStraightNorth()
        survey.activeStation = survey.getStationByName("1")!!
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        return survey
    }

    /** [createStraightNorthWith1EBranch] extended by a second eastward leg, station "6". */
    fun createStraightNorthWith2EBranch(): Survey {
        val survey = createStraightNorthWith1EBranch()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        return survey
    }
}
