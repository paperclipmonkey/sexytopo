package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How big the cave is — the numbers that go on the drawing and in the trip report.
 *
 * Ported from `control/util/SurveyStats`, including two pieces of arithmetic that look like
 * mistakes and are not corrected: the station count is one less than the number of stations, and
 * the longest and shortest legs count splays while the total length does not. The Android app has
 * shown those numbers for years, so a port that quietly fixed them would be reporting a different
 * cave from the one the surveyor is used to.
 */
class SurveyStatsTest {

    private fun junction(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        survey.activeStation = survey.getStationByName("2")!!
        SurveyBuilder.updateWithNewStation(survey, Leg(4f, 270f, 0f))
        SurveyBuilder.addSplay(survey, survey.getStationByName("2")!!, Leg(2f, 45f, 0f))
        return survey
    }

    /**
     * Splays are wall shots, not passage.
     *
     * A survey whose length included them would report a cave several times longer than it is, and
     * this is the number that goes into a club's records.
     */
    @Test
    fun theTotalLengthIsConnectingLegsOnly() {
        assertEquals(19f, SurveyStats.totalLength(junction()), 0.001f)
    }

    /**
     * The longest and shortest *do* count splays. Reproduced from the original, which filters in
     * `calcTotalLength` and not in these two.
     */
    @Test
    fun theLongestAndShortestLegsCountSplaysToo() {
        assertEquals(10f, SurveyStats.longestLeg(junction()), 0.001f)
        assertEquals(2f, SurveyStats.shortestLeg(junction()), 0.001f)
    }

    @Test
    fun anEmptySurveyHasNoLegsAndNoLength() {
        val empty = Survey("T")
        assertEquals(0f, SurveyStats.totalLength(empty))
        assertEquals(0f, SurveyStats.longestLeg(empty))
        assertEquals(0f, SurveyStats.shortestLeg(empty))
    }

    /**
     * The station count is the number of stations *minus one*: the origin is not somewhere anybody
     * surveyed to. The original's own arithmetic, and the number the app displays.
     */
    @Test
    fun theStationCountExcludesTheOrigin() {
        assertEquals(3, SurveyStats.numberOfStations(junction()))
        assertEquals(0, SurveyStats.numberOfStations(Survey("T")))
    }

    /** A survey is a tree, so a station's counts are its branch and nothing above it. */
    @Test
    fun aStationsCountsCoverItsBranchAndNothingElse() {
        val survey = junction()
        val origin = survey.origin
        val junctionStation = survey.getStationByName("2")!!

        assertEquals(4, SurveyStats.numberOfStationsUnder(origin))
        assertEquals(3, SurveyStats.numberOfStationsUnder(junctionStation))

        // From the junction: two onward legs and a splay.
        assertEquals(3, SurveyStats.numberOfLegsUnder(junctionStation))
        assertEquals(2, SurveyStats.numberOfFullLegsUnder(junctionStation))
        assertEquals(1, SurveyStats.numberOfSplaysUnder(junctionStation))

        // A leaf has nothing under it but itself.
        assertEquals(1, SurveyStats.numberOfStationsUnder(survey.getStationByName("3")!!))
        assertEquals(0, SurveyStats.numberOfLegsUnder(survey.getStationByName("3")!!))
    }

    @Test
    fun theHeightRangeIsTheVerticalExtentOfTheStations() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, -90f))
        SurveyBuilder.updateWithNewStation(survey, Leg(4f, 0f, 90f))

        val (bottom, top) = SurveyStats.heightRangeOf(survey)
        assertEquals(-10f, bottom, 0.001f)
        assertEquals(0f, top, 0.001f)
        assertEquals(10f, SurveyStats.heightRange(survey), 0.001f)
    }

    /**
     * A survey of one station has no range, and must not report one made out of `Float.MAX_VALUE`
     * and `Float.MIN_VALUE` — which is what the loop would leave behind without the guard the
     * original has.
     */
    @Test
    fun oneStationIsNotARange() {
        assertEquals(0f to 0f, SurveyStats.heightRangeOf(Survey("T")))
        assertEquals(0f, SurveyStats.heightRange(Survey("T")))
    }
}
