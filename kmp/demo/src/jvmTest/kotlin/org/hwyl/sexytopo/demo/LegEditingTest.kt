package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Correcting a mistyped reading, which is the difference between a survey that can be trusted and
 * one that cannot.
 *
 * The dangerous case is the one these tests are mostly about: [SurveyUpdater.editLeg] swaps the
 * whole leg object, so an edited leg that has forgotten its destination takes every station beyond
 * it out of the survey. A dialog that produced a bare splay would lose half a cave to a typo.
 */
class LegEditingTest {

    @Test
    fun editingALegKeepsWhatWasSurveyedBeyondIt() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(7f, 90f, 0f))
        val first = survey.origin.onwardLegs.single()
        val beyond = first.destination

        SurveyUpdater.editLeg(survey, first, inOrientationOf(first, Leg(6f, 91f, -2f)))

        val edited = survey.origin.onwardLegs.single()
        assertSame(beyond, edited.destination)
        assertEquals(listOf("1", "2", "3"), survey.getAllStations().map { it.name })
        assertEquals(6f, edited.distance)
        assertEquals(91f, edited.azimuth)
        assertEquals(-2f, edited.inclination)
    }

    @Test
    fun editingASplayLeavesItASplay() {
        val survey = Survey("T")
        SurveyUpdater.update(survey, Leg(2f, 10f, 5f))
        val splay = survey.origin.onwardLegs.single()

        val replacement = inOrientationOf(splay, Leg(3f, 20f, -5f))

        assertTrue(!replacement.hasDestination())
        assertEquals(3f, replacement.distance)
    }

    /**
     * The table shows a backwards shot turned round, so that is what gets typed into the edit
     * dialog. It has to be turned back before storing, or the leg would silently reverse itself.
     */
    @Test
    fun editingABacksightStoresItStillAsABacksight() {
        val destination = Station("2")
        val leg = Leg(5f, 90f, 10f, destination, wasShotBackwards = true)

        // The row for this leg reads 2 -> 1 at 270 degrees, -10. Say the surveyor corrects the
        // distance and leaves the angles alone.
        val replacement = inOrientationOf(leg, Leg(5.5f, 270f, -10f))

        assertTrue(replacement.wasShotBackwards)
        assertSame(destination, replacement.destination)
        assertEquals(5.5f, replacement.distance)
        assertEquals(90f, replacement.azimuth)
        assertEquals(10f, replacement.inclination)
    }

    @Test
    fun aCommentSurvivesAnEdit() {
        val leg = Leg(5f, 90f, 10f)
        leg.comment = "tight rift"

        assertEquals("tight rift", inOrientationOf(leg, Leg(6f, 90f, 10f)).comment)
    }

    /**
     * No phone numeric keypad has a minus key, so the sign has to come from somewhere else. It has
     * to work on a half-typed field, because that is when it gets pressed.
     */
    @Test
    fun theSignToggleWorksOnPartlyTypedNumbers() {
        assertEquals("-4.20", withSignFlipped("4.20"))
        assertEquals("4.20", withSignFlipped("-4.20"))
        assertEquals("-5.00", withSignFlipped("+5.00"))
        assertEquals("-", withSignFlipped(""))
        assertEquals("", withSignFlipped("-"))
        // Mid-edit, with nothing after the decimal point yet.
        assertEquals("-12.", withSignFlipped("12."))
    }
}
