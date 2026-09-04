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
        assertEquals("15.00", stats[Strings.statsLength])
        // Flat cave: no vertical range at all.
        assertEquals("0.00", stats[Strings.statsVerticalRange])
        // Three stations, minus the origin nobody surveyed *to*.
        assertEquals("2", stats[Strings.statsNumberStations])
        assertEquals("2", stats[Strings.statsNumberLegs])
        assertEquals("1", stats[Strings.statsNumberSplays])
        // The shortest and longest do count the splay, as in the original.
        assertEquals("2.00", stats[Strings.statsShortestLeg])
        assertEquals("10.00", stats[Strings.statsLongestLeg])
    }

    @Test
    fun theyAreListedInTheAppsOwnOrder() {
        assertEquals(
            listOf(
                Strings.statsLength,
                Strings.statsVerticalRange,
                Strings.statsNumberStations,
                Strings.statsNumberLegs,
                Strings.statsNumberSplays,
                Strings.statsShortestLeg,
                Strings.statsLongestLeg,
            ),
            statsOf(cave()).map { it.first },
        )
    }

    @Test
    fun anEmptySurveyReportsZeroes() {
        val stats = statsOf(Survey("T")).toMap()

        assertEquals("0.00", stats[Strings.statsLength])
        assertEquals("0", stats[Strings.statsNumberStations])
        assertEquals("0.00", stats[Strings.statsShortestLeg])
    }

    @Test
    fun aDeepCaveReportsItsRange() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, -90f))
        SurveyBuilder.updateWithNewStation(survey, Leg(4f, 0f, 90f))

        assertEquals("10.00", statsOf(survey).toMap()[Strings.statsVerticalRange])
    }

    /** `TextTools.formatWithComma`: the app groups thousands, and a kilometre of cave has them. */
    @Test
    fun longNumbersAreGroupedTheWayTheAppGroupsThem() {
        assertEquals("1,234.50", withThousands("1234.50"))
        assertEquals("999", withThousands("999"))
        assertEquals("1,000", withThousands("1000"))
        assertEquals("12,345,678", withThousands("12345678"))
        assertEquals("-1,234.00", withThousands("-1234.00"))
        assertEquals("0.00", withThousands("0.00"))
    }
}
