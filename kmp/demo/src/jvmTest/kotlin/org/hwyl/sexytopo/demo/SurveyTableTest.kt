package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The table's job is to show what the surveyor wrote in their notes, which for a backwards shot is
 * not what is stored. Getting this wrong makes every exported file disagree with the notebook.
 */
class SurveyTableTest {

    @Test
    fun aForwardLegIsShownAsStored() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(5.5f, 90f, 10f))

        val row = asTakenRows(survey).single()
        assertEquals("1", row.from)
        assertEquals("2", row.to)
        assertEquals("5.500", row.distance)
        assertEquals("90.00", row.azimuth)
        assertEquals("+10.00", row.inclination)
    }

    @Test
    fun aBackwardsShotIsShownAsItWasTaken() {
        // Stored 1 -> 2, but read at station 2 pointing back at 1. The table must show 2 -> 1 with
        // the reversed bearing, matching the notebook.
        val survey = Survey("T")
        val destination = Station("2")
        val leg = Leg(5f, 90f, 10f, destination, wasShotBackwards = true)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)

        val row = asTakenRows(survey).single()
        assertEquals("2", row.from, "the reading was taken at the far station")
        assertEquals("1", row.to)
        assertEquals("270.00", row.azimuth, "bearing is reversed")
        assertEquals("-10.00", row.inclination, "inclination is negated")
    }

    @Test
    fun splaysAreShownWithNoDestination() {
        val survey = Survey("T")
        SurveyBuilder.addSplay(survey, survey.origin, Leg(1.25f, 45f, 0f))

        val row = asTakenRows(survey).single()
        assertEquals("1", row.from)
        assertTrue(row.to.isNotEmpty() && row.to != "1", "a splay has no real destination")
        assertEquals("1.250", row.distance)
        assertEquals("+0.00", row.inclination)
    }

    @Test
    fun everyLegAppearsExactlyOnce() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(2f, 90f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(6f, 10f, 0f))

        assertEquals(survey.getAllLegs().size, asTakenRows(survey).size)
    }

    // ---------------------------------------------------------------------------------------
    // Fixed-decimal formatting (commonMain has no String.format)
    // ---------------------------------------------------------------------------------------

    @Test
    fun formattingMatchesJavaPrecision() {
        assertEquals("5.500", formatFixed(5.5f, 3))
        assertEquals("0.000", formatFixed(0f, 3))
        assertEquals("12.35", formatFixed(12.345f, 2))
        assertEquals("90.00", formatFixed(90f, 2))
        assertEquals("100", formatFixed(99.7f, 0))
    }

    @Test
    fun negativeValuesKeepTheirSign() {
        assertEquals("-3.250", formatFixed(-3.25f, 3))
        assertEquals("-0.50", formatFixed(-0.5f, 2), "a value between -1 and 0 must keep its minus")
    }

    @Test
    fun theSignedFormatAlwaysShowsOne() {
        // Java's "%+.2f", used for inclination so up and down are unmistakable.
        assertEquals("+10.00", formatFixed(10f, 2, alwaysSigned = true))
        assertEquals("-10.00", formatFixed(-10f, 2, alwaysSigned = true))
        assertEquals("+0.00", formatFixed(0f, 2, alwaysSigned = true))
    }

    @Test
    fun roundingIsHalfAwayFromZero() {
        // Matching Java's Formatter HALF_UP, so exported numbers agree with the Android app.
        assertEquals("2.5", formatFixed(2.45f, 1))
        assertEquals("-2.5", formatFixed(-2.45f, 1))
        assertEquals("3", formatFixed(2.5f, 0))
    }

    @Test
    fun fractionalDigitsArePadded() {
        assertEquals("1.050", formatFixed(1.05f, 3), "trailing zeros are kept, as %.3f does")
        assertEquals("1.005", formatFixed(1.005f, 3))
    }

    // -------------------------------------------------------------------------------------
    // Which station a cell is about
    // -------------------------------------------------------------------------------------

    @Test
    fun eachCellKnowsTheStationItShows() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))

        val row = asTakenRows(survey).single()

        assertEquals("1", row.fromStationShown.name)
        assertEquals("2", row.toStationShown?.name)
    }

    @Test
    fun aBackwardsShotSwapsWhichStationEachCellIsAbout() {
        // `TableActivity` works this out with `(col == FROM) xor leg.wasShotBackwards()`. The
        // reading was taken standing at 2, so the From column shows 2 — and a tap on it has to
        // open station 2's menu, not station 1's.
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        org.hwyl.sexytopo.shared.survey.SurveyUpdater.reverseLeg(
            survey,
            survey.getStationByName("2")!!,
        )

        val row = asTakenRows(survey).single()

        assertEquals("2", row.from, "shown as taken")
        assertEquals("2", row.fromStationShown.name, "and the cell is about the station it shows")
        assertEquals("1", row.toStationShown?.name)
    }

    @Test
    fun aSplayHasNoStationAtItsFarEnd() {
        // Its To column is a dash, so there is nothing there to open a menu for.
        val survey = Survey("T")
        SurveyBuilder.addSplay(survey, survey.origin, Leg(2f, 10f, 5f))

        val row = asTakenRows(survey).single()

        assertEquals("1", row.fromStationShown.name)
        assertEquals(null, row.toStationShown)
    }
}
