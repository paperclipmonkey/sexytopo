package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ported from CrossSectionerTest. The heuristic decides which way a passage slice faces. */
class CrossSectionTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")

    @Test
    fun midPassageTheSectionFacesTheAverageOfInAndOut() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 80f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 100f, 0f))

        val middle = survey.getStationByName("2")!!
        assertClose(90f, CrossSectioner.angleOfSection(survey, middle))
    }

    @Test
    fun theAverageIsTakenAcrossTheZeroSeam() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 359f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 1f, 0f))

        val angle = CrossSectioner.angleOfSection(survey, survey.getStationByName("2")!!)
        assertTrue(angle < 1f || angle > 359f, "expected ~0 but was $angle")
    }

    @Test
    fun atADeadEndOnlyTheWayInCounts() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 45f, 0f))
        assertClose(45f, CrossSectioner.angleOfSection(survey, survey.getStationByName("2")!!))
    }

    @Test
    fun atTheOriginOnlyTheWayOnCounts() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 120f, 0f))
        assertClose(120f, CrossSectioner.angleOfSection(survey, survey.origin))
    }

    @Test
    fun anIsolatedOriginHasNothingToGoOn() {
        assertClose(0f, CrossSectioner.angleOfSection(Survey("X"), Survey("X").origin))
    }

    @Test
    fun aJunctionFallsBackToTheWayIn() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 30f, 0f))
        val junction = survey.activeStation
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        survey.activeStation = junction
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 270f, 0f))

        // Two ways on, so the average is meaningless; the incoming leg decides.
        assertClose(30f, CrossSectioner.angleOfSection(survey, junction))
    }

    @Test
    fun theProfilePutsTheStationAtTheOriginAndSplaysAroundIt() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(2f, 0f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(3f, 180f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(1f, 90f, 90f))

        val projection = CrossSectioner.section(survey, station).getProjection()
        assertEquals(1, projection.stationMap.size)
        assertEquals(3, projection.legMap.size, "one line per splay")
        assertTrue(projection.legMap.values.all { it.start == org.hwyl.sexytopo.shared.model.graph.Coord2D.ORIGIN })
    }

    @Test
    fun horizontalRadiusIgnoresVerticalSplays() {
        val survey = Survey("X")
        val station = survey.origin
        SurveyBuilder.addSplay(survey, station, Leg(4f, 0f, 0f))
        // Straight up: reaches nowhere horizontally, so must not set the radius.
        SurveyBuilder.addSplay(survey, station, Leg(20f, 0f, 90f))

        assertClose(4f, CrossSectioner.horizontalRadius(station))
    }

    @Test
    fun aStationWithNoSplaysHasNoRadius() {
        assertClose(0f, CrossSectioner.horizontalRadius(Survey("X").origin))
    }
}
