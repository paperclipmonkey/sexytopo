package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two things a surveyor does often and this port could not do at all: find a station by name, and
 * take back the shot they just took.
 *
 * Neither is exotic. A survey of any size does not fit on a phone screen, so "where is AV12" has no
 * answer without a search; and a leg taken from the wrong station wants to be gone before the next
 * one goes in, which through the table is three taps and a scroll away from where the surveyor is
 * standing.
 */
class FindStationTest {

    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        return survey
    }

    @Test
    fun anEmptyQueryListsTheWholeCave() {
        val survey = passage()
        assertEquals(listOf("1", "2", "3"), stationsMatching(survey, "").map { it.name })
        assertEquals(listOf("1", "2", "3"), stationsMatching(survey, "   ").map { it.name })
    }

    @Test
    fun aNameIsMatchedAnywhereInIt() {
        val survey = passage()
        survey.getStationByName("2")!!.name = "AV12"
        assertEquals(listOf("AV12"), stationsMatching(survey, "12").map { it.name })
        assertEquals(listOf("AV12"), stationsMatching(survey, "av").map { it.name }, "case-blind")
    }

    /**
     * The point of the feature rather than a flourish: stations are called 1, 2, 3, and what the
     * surveyor remembers is "the one where the draught was" — which is in the comment.
     */
    @Test
    fun theCommentIsSearchedAsWellAsTheName() {
        val survey = passage()
        survey.getStationByName("3")!!.comment = "strong draught, worth pushing"
        val found = stationsMatching(survey, "draught")
        assertEquals(listOf("3"), found.map { it.name })
        assertTrue(describe(found.single()).contains("strong draught"))
    }

    @Test
    fun aStationWithNoCommentIsDescribedByItsNameAlone() {
        val survey = passage()
        assertEquals("2", describe(survey.getStationByName("2")!!))
    }

    @Test
    fun nothingMatchesAnUnknownName() {
        assertTrue(stationsMatching(passage(), "sump").isEmpty())
    }

    @Test
    fun aStationCanBeLocatedInTheProjectionOnScreen() {
        val survey = passage()
        val three = survey.getStationByName("3")!!
        val at = stationPositionIn(survey, Projection2D.PLAN, three)
        assertNotNull(at)
        // Ten metres north then ten east, and plan y is inverted for the screen.
        assertEquals(10f, at.x, 0.001f)
        assertEquals(-10f, at.y, 0.001f)
    }

    /**
     * The projection keys its map on station identity, so a station that has left the survey since
     * the dialog listed it is not in the map. Centring the view on the origin instead would be a
     * lie about where it went.
     */
    @Test
    fun aStationTheSurveyNoLongerHoldsHasNoPosition() {
        val survey = passage()
        val gone = survey.getStationByName("3")!!
        survey.undoAddLeg()
        assertNull(stationPositionIn(survey, Projection2D.PLAN, gone))
    }

    @Test
    fun theLastLegIsTheLastOneTakenAndIsNamedInTheSurveyorsTerms() {
        val survey = passage()
        val description = lastLegDescription(survey)
        assertNotNull(description)
        assertTrue(description.startsWith("2 to 3 — "), description)
        assertTrue(description.contains("10.000 m"), description)
        assertTrue(description.contains("90.00°"), description)
    }

    @Test
    fun aSplayIsNamedAsOneRatherThanAsALegToNowhere() {
        val survey = passage()
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(2.5f, 45f, -3f))
        val description = lastLegDescription(survey)
        assertNotNull(description)
        assertTrue(description.contains("to a splay"), description)
        assertTrue(description.contains("2.500 m"), description)
        assertTrue(description.contains("-3.00°"), description)
    }

    @Test
    fun aSurveyWithNoLegsHasNothingToTakeBack() {
        assertNull(lastLegDescription(Survey("T")))
    }

    @Test
    fun deletingTheLastLegLeavesTheRestOfTheSurveyStanding() {
        val survey = passage()
        survey.undoAddLeg()
        assertNull(survey.getStationByName("3"))
        assertNotNull(survey.getStationByName("2"))
        assertTrue(
            lastLegDescription(survey)!!.startsWith("1 to 2 — "),
            "the leg before it becomes the last one",
        )
    }
}
