package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How big the cave is — the numbers a surveyor asks for underground rather than afterwards.
 *
 * Ported from `StatsActivity`, and worth a test because two of its numbers reproduce arithmetic in
 * the original that looks like a mistake: the station count is one less than the number of
 * stations, and the longest and shortest legs count splays while the length does not.
 */
class StatsTest {

    private fun cave(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        SurveyBuilder.addSplay(survey, survey.getStationByName("2")!!, Leg(2f, 45f, 0f))
        return survey
    }

    @Test
    fun theSevenNumbersAreTheOnesTheAppShows() {
        val stats = statsOf(cave()).toMap()

        // Splays are wall shots, not passage, so the length is 10 + 5.
        assertEquals("15.00 m", stats["Length"])
        // Flat cave: no vertical range at all.
        assertEquals("0.00 m", stats["Depth"])
        // Three stations, minus the origin nobody surveyed *to*.
        assertEquals("2", stats["Stations"])
        assertEquals("2", stats["Legs"])
        assertEquals("1", stats["Splays"])
        // The shortest and longest do count the splay, as in the original.
        assertEquals("2.00 m", stats["Shortest leg"])
        assertEquals("10.00 m", stats["Longest leg"])
    }

    @Test
    fun theyAreListedInTheAppsOwnOrder() {
        assertEquals(
            listOf("Length", "Depth", "Stations", "Legs", "Splays", "Shortest leg", "Longest leg"),
            statsOf(cave()).map { it.first },
        )
    }

    @Test
    fun anEmptySurveyReportsZeroes() {
        val stats = statsOf(Survey("T")).toMap()

        assertEquals("0.00 m", stats["Length"])
        assertEquals("0", stats["Stations"])
        assertEquals("0.00 m", stats["Shortest leg"])
    }

    @Test
    fun aDeepCaveReportsItsRange() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, -90f))
        SurveyBuilder.updateWithNewStation(survey, Leg(4f, 0f, 90f))

        assertEquals("10.00 m", statsOf(survey).toMap()["Depth"])
    }
}
