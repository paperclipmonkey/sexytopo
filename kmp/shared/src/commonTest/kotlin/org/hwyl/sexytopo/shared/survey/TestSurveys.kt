package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
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

    /**
     * `createWithCrossSectionsInPlanAndElevation`: [createStraightNorthWith1EBranch] with
     * cross-sections on the elevation as well as the plan, and splays at their stations so the
     * sections have something to show.
     *
     * The plan has vertical sections at stations 1 and 3, at (2, 0) and (2, 10). The elevation
     * has a vertical one at station 1 and a horizontal one at station 3, at (2, 4) and (12, 4),
     * so each of those stations has a section on both sketches, and at station 3 of different
     * kinds.
     *
     * The splays at each of those stations are 2m east, 1.5m west, 1m north, 1m up and 1m down.
     * Facing north, a vertical section shows east and west to the right and left and up and down
     * above and below, with the north one pointing straight at the viewer; a horizontal section
     * shows east, west and north, and the up and down ones collapse to nothing.
     */
    fun createWithCrossSectionsInPlanAndElevation(): Survey {
        val survey = createStraightNorthWith1EBranch()
        val station1 = survey.getStationByName("1")!!
        val station3 = survey.getStationByName("3")!!

        for (station in listOf(station1, station3)) {
            station.addOnwardLeg(Leg(2f, 90f, 0f))
            station.addOnwardLeg(Leg(1.5f, 270f, 0f))
            station.addOnwardLeg(Leg(1f, 0f, 0f))
            station.addOnwardLeg(Leg(1f, 0f, 90f))
            station.addOnwardLeg(Leg(1f, 0f, -90f))
        }

        val plan = survey.planSketch
        plan.addCrossSection(CrossSectioner.section(survey, station1), Coord2D(2f, 0f))
        plan.addCrossSection(CrossSectioner.section(survey, station3), Coord2D(2f, 10f))

        val elevation = survey.elevationSketch
        elevation.addCrossSection(
            CrossSectioner.section(survey, station1, CrossSection.Orientation.VERTICAL),
            Coord2D(2f, 4f),
        )
        elevation.addCrossSection(
            CrossSectioner.section(survey, station3, CrossSection.Orientation.HORIZONTAL),
            Coord2D(12f, 4f),
        )

        return survey
    }
}
