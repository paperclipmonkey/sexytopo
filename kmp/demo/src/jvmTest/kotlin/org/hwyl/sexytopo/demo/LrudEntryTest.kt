package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Passage size from a tape rather than an instrument: four numbers per station, and the app works
 * out the directions. This is how a survey gets booked when there is no DistoX in the party, and
 * it is what makes cross-sections work on a hand-booked survey.
 */
class LrudEntryTest {

    /** Station 2 mid-passage, so left and right have a bearing to be square to. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        return survey
    }

    @Test
    fun fourNumbersMakeFourSplays() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(4, addLruds(survey, middle, listOf("1.5", "2", "3", "0.5")))
        assertEquals(4, middle.getUnconnectedOnwardLegs().size)
    }

    /**
     * Left and right go square to the passage; up and down are vertical. A survey running due
     * north therefore has its walls at 270 and 90.
     */
    @Test
    fun theDirectionsAreWorkedOutFromThePassage() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        addLruds(survey, middle, listOf("1.5", "2", "3", "0.5"))
        val splays = middle.getUnconnectedOnwardLegs()

        assertEquals(270f, splays[0].azimuth)
        assertEquals(90f, splays[1].azimuth)
        assertEquals(90f, splays[2].inclination)
        assertEquals(-90f, splays[3].inclination)
    }

    /** Only the fields that were filled in. */
    @Test
    fun blanksAddNothing() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(1, addLruds(survey, middle, listOf("", "2", "  ", "")))
        assertEquals(1, middle.getUnconnectedOnwardLegs().size)
    }

    /**
     * A zero is what somebody types for a wall they are standing against, and [Leg] rejects a
     * non-positive distance by throwing — so it has to be skipped rather than passed on.
     */
    @Test
    fun aZeroOrNonsenseIsSkippedRatherThanThrowing() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(0, addLruds(survey, middle, listOf("0", "-1", "wide", "")))
        assertTrue(middle.getUnconnectedOnwardLegs().isEmpty())
    }

    @Test
    fun aCommaIsAcceptedAsADecimalPoint() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        addLruds(survey, middle, listOf("1,5", "", "", ""))

        assertEquals(1.5f, middle.getUnconnectedOnwardLegs().single().distance)
    }
}
