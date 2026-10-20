package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which way the survey goes on along the extended elevation once it leaves a station: the cases
 * `SurveyTraversalTest` gained in upstream 1.13.0, when the lookup moved there out of
 * `SurveyUpdater` so the drawing could ask it which way a cross-section faces. Its older cases,
 * `isInSubtree`'s, are in [SurveyTreeTest].
 */
class SurveyTraversalTest {

    @Test
    fun aVerticalStationGoesOnTheWayTheSurveyResumes() {
        val survey = TestSurveys.createStraightNorth()
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            survey.getStationByName("2")!!,
            ExtendedElevationDirection.LEFT,
        )
        val station3 = survey.getStationByName("3")!!
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            station3,
            ExtendedElevationDirection.VERTICAL,
        )

        assertEquals(
            ExtendedElevationDirection.LEFT,
            SurveyTraversal.getOnwardExtendedElevationDirection(survey, station3),
        )
    }

    @Test
    fun itLooksPastSeveralVerticalStationsInARow() {
        val survey = TestSurveys.createStraightNorth()
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            survey.getStationByName("2")!!,
            ExtendedElevationDirection.LEFT,
        )
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            survey.getStationByName("3")!!,
            ExtendedElevationDirection.VERTICAL,
        )
        val station4 = survey.getStationByName("4")!!
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            station4,
            ExtendedElevationDirection.VERTICAL,
        )

        assertEquals(
            ExtendedElevationDirection.LEFT,
            SurveyTraversal.getOnwardExtendedElevationDirection(survey, station4),
        )
    }

    @Test
    fun aVerticalOriginGoesOnTheDefaultWay() {
        val survey = TestSurveys.createStraightNorth()
        val origin = survey.origin
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            origin,
            ExtendedElevationDirection.VERTICAL,
        )

        assertEquals(
            ExtendedElevationDirection.DEFAULT,
            SurveyTraversal.getOnwardExtendedElevationDirection(survey, origin),
        )
    }
}
