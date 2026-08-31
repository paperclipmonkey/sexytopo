package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * A dead end falls back to the passage bearing rather than throwing.
     *
     * This assertion used to be the opposite. The original indexes the first connected onward leg
     * directly — `getConnectedOnwardLegs().get(0)` — and this port reproduced the
     * `IndexOutOfBoundsException` faithfully, which was safe for exactly as long as nothing could
     * *select* [LrudMode.SHOT]: the Android app reads `pref_lrud_direction` and declares it in no
     * preference screen, so on Android the value is always `"survey"`.
     *
     * Offering the choice makes it reachable, and reachable at the worst moment: passage size is
     * booked at the station the surveyor is standing at, which at the working end of a survey is a
     * station with nothing beyond it yet. The first person to choose this setting and measure a
     * wall would have met it — and on Kotlin/Native an uncaught throw is not an exception, it is
     * the process ending. So the behaviour changed with the setting that made it reachable, and
     * this test changed with it rather than being deleted.
     */
    @Test
    fun shotModeAtADeadEndFallsBackToThePassage() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val deadEnd = survey.activeStation

        val shot = Lrud.LEFT.createSplay(survey, deadEnd, LrudMode.SHOT, 1f)
        val passage = Lrud.LEFT.createSplay(survey, deadEnd, LrudMode.SURVEY, 1f)

        assertClose(passage.azimuth, shot.azimuth)
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
