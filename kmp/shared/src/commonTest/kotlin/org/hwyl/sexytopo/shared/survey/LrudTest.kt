package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ported from `LrudTest`, expectations unchanged, plus the up/down and mode-parsing cases. */
class LrudTest {

    /** SexyTopoConstants.ALLOWED_DOUBLE_DELTA. */
    private val allowedDelta = 0.0001f

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < allowedDelta, "expected $expected but was $actual")

    @Test
    fun straightNorthSurveyModeLeftSplay() {
        val survey = TestSurveys.createStraightNorth()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.LEFT.createSplay(survey, s2, LrudMode.SURVEY, 5f)
        assertClose(270f, splay.azimuth)
    }

    @Test
    fun straightNorthSurveyModeRightSplay() {
        val survey = TestSurveys.createStraightNorth()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.RIGHT.createSplay(survey, s2, LrudMode.SURVEY, 5f)
        assertClose(90f, splay.azimuth)
    }

    @Test
    fun cornerSurveyModeLeftSplay() {
        // Station 2 has an incoming leg from the north (0) and an outgoing one east (90).
        // SURVEY mode bisects to 45, so LEFT is 315.
        val survey = TestSurveys.createRightRight()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.LEFT.createSplay(survey, s2, LrudMode.SURVEY, 5f)
        assertClose(315f, splay.azimuth)
    }

    @Test
    fun cornerSurveyModeRightSplay() {
        val survey = TestSurveys.createRightRight()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.RIGHT.createSplay(survey, s2, LrudMode.SURVEY, 5f)
        assertClose(135f, splay.azimuth)
    }

    @Test
    fun cornerShotModeLeftSplay() {
        // SHOT mode ignores the way in and uses the outgoing azimuth 90, so LEFT is 0.
        val survey = TestSurveys.createRightRight()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.LEFT.createSplay(survey, s2, LrudMode.SHOT, 5f)
        assertClose(0f, splay.azimuth)
    }

    @Test
    fun cornerShotModeRightSplay() {
        val survey = TestSurveys.createRightRight()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.RIGHT.createSplay(survey, s2, LrudMode.SHOT, 5f)
        assertClose(180f, splay.azimuth)
    }

    @Test
    fun lrudSplaysAreHorizontalAndKeepTheirDistance() {
        val survey = TestSurveys.createStraightNorth()
        val s2 = assertNotNull(survey.getStationByName("2"))
        val splay = Lrud.LEFT.createSplay(survey, s2, LrudMode.SURVEY, 5f)
        assertClose(0f, splay.inclination)
        assertClose(5f, splay.distance)
        assertTrue(!splay.hasDestination(), "an LRUD is a splay, not a leg")
    }

    @Test
    fun upAndDownAreVerticalAndIgnoreThePassageBearing() {
        val survey = TestSurveys.createRightRight()
        val s2 = assertNotNull(survey.getStationByName("2"))

        val up = Lrud.UP.createSplay(survey, s2, LrudMode.SURVEY, 3f)
        assertClose(90f, up.inclination)
        assertClose(0f, up.azimuth) // literally 0, as in the original

        val down = Lrud.DOWN.createSplay(survey, s2, LrudMode.SHOT, 3f)
        assertClose(-90f, down.inclination)
        assertClose(0f, down.azimuth)
    }

    @Test
    fun shotModeThrowsAtADeadEnd() {
        // Reproduced from the original's unguarded get(0) on the connected onward legs.
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val deadEnd = survey.activeStation
        assertFailsWith<IndexOutOfBoundsException> {
            Lrud.LEFT.createSplay(survey, deadEnd, LrudMode.SHOT, 1f)
        }
    }

    @Test
    fun surveyModeCopesWithADeadEnd() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        val deadEnd = survey.activeStation
        val splay = Lrud.LEFT.createSplay(survey, deadEnd, LrudMode.SURVEY, 1f)
        assertClose(0f, splay.azimuth) // square to an eastward passage
    }

    @Test
    fun modeIsParsedFromThePreferenceValue() {
        assertEquals(LrudMode.SURVEY, LrudMode.fromPreferenceValue("survey"))
        assertEquals(LrudMode.SHOT, LrudMode.fromPreferenceValue("shot"))
        assertEquals(LrudMode.SHOT, LrudMode.fromPreferenceValue("SHOT"))
        assertEquals(LrudMode.SURVEY, LrudMode.fromPreferenceValue("nonsense"))
        assertEquals(LrudMode.SURVEY, LrudMode.fromPreferenceValue(""))
    }
}
