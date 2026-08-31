package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.test.assertContains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The station menu the sketch never had.
 *
 * Until it existed the only station a surveyor could name, comment or measure was the *active* one,
 * reached through the chip on the field bar — fine while the survey is being pushed forward, and
 * useless the moment somebody wants to go back and write "sump" on a junction they passed. What is
 * tested here is which actions a station offers, because every wrong answer is invisible until
 * somebody taps it underground: a delete that silently does nothing on the origin, a cross-section
 * offered on the extended elevation where it means nothing, a "make active" on the station that
 * already is one.
 */
class StationMenuTest {

    /** Three stations in a line, with a splay off the middle one. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        return survey
    }

    private fun actions(
        survey: Survey,
        name: String,
        projection: Projection2D = Projection2D.PLAN,
        editor: SketchEditor? = null,
        fromTable: Boolean = false,
    ) = stationActionsFor(
        survey,
        survey.getStationByName(name)!!,
        projection,
        editor?.sketch ?: survey.getSketch(projection),
        fromTable,
    )

    // -------------------------------------------------------------------------------------
    // The table's menu is not the sketch's
    // -------------------------------------------------------------------------------------

    /**
     * `menu_navigate` offers the two views you are *not* looking at, which is the same rule read
     * from either end: never the one you are already in, and never nothing.
     */
    @Test
    fun eachMenuOffersTheViewsTheOtherOneIsNotShowing() {
        val survey = passage()

        val fromTable = actions(survey, "2", fromTable = true)
        assertContains(fromTable, StationAction.SHOW_IN_PLAN)
        assertContains(fromTable, StationAction.SHOW_IN_ELEVATION)
        assertFalse(StationAction.SHOW_IN_TABLE in fromTable, "already in the table")

        val fromSketch = actions(survey, "2")
        assertContains(fromSketch, StationAction.SHOW_IN_TABLE)
        assertFalse(StationAction.SHOW_IN_PLAN in fromSketch, "already on a drawing")
        assertFalse(StationAction.SHOW_IN_ELEVATION in fromSketch)
    }

    @Test
    fun theTableDoesNotOfferCrossSectionsAndTheSketchDoes() {
        // The other half of the same difference: a cross-section is a thing you draw, and the
        // table has nothing to draw on.
        val survey = passage()

        val fromTable = actions(survey, "2", fromTable = true)
        assertFalse(StationAction.CROSS_SECTION_CREATE in fromTable)

        assertContains(actions(survey, "2"), StationAction.CROSS_SECTION_CREATE)
    }

    @Test
    fun bothMenusStillOfferWhatIsCommonToThem() {
        val survey = passage()
        for (fromTable in listOf(true, false)) {
            val offered = actions(survey, "2", fromTable = fromTable)
            assertContains(offered, StationAction.EDIT)
            assertContains(offered, StationAction.INCOMING_LEG)
            assertContains(offered, StationAction.DELETE)
            assertContains(offered, StationAction.MAKE_ACTIVE)
        }
    }

    @Test
    fun theOriginOffersNeitherADeleteNorALeg() {
        val survey = passage()
        val offered = actions(survey, "1")
        assertFalse(
            StationAction.DELETE in offered,
            "SurveyUpdater.deleteStation is a no-op on the origin, so offering it would lie",
        )
        assertFalse(StationAction.INCOMING_LEG in offered, "no leg made the origin")
    }

    @Test
    fun aStationMadeByALegOffersBoth() {
        val survey = passage()
        val offered = actions(survey, "2")
        assertTrue(StationAction.DELETE in offered)
        assertTrue(StationAction.INCOMING_LEG in offered)
    }

    @Test
    fun theActiveStationIsNotOfferedAsTheActiveStation() {
        val survey = passage()
        assertEquals("3", survey.activeStation.name)
        assertFalse(StationAction.MAKE_ACTIVE in actions(survey, "3"))
        assertTrue(StationAction.MAKE_ACTIVE in actions(survey, "2"))
    }

    @Test
    fun everyStationCanBeNamedAndCommented() {
        val survey = passage()
        for (name in listOf("1", "2", "3")) {
            assertTrue(
                StationAction.EDIT in actions(survey, name),
                "$name should be nameable — that is the whole point of the menu",
            )
        }
    }

    @Test
    fun crossSectionsAreOfferedInThePlanOnly() {
        val survey = passage()
        val onThePlan = actions(survey, "2", Projection2D.PLAN)
        val onTheElevation = actions(survey, "2", Projection2D.EXTENDED_ELEVATION)

        assertTrue(StationAction.CROSS_SECTION_CREATE in onThePlan)
        assertTrue(
            onTheElevation.none { it.name.startsWith("CROSS_SECTION") },
            "a cross-section is a plan object; the elevation has nothing to anchor one to",
        )
    }

    @Test
    fun aStationWithASectionOffersOpeningItRatherThanMakingAnother() {
        val survey = passage()
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        val station = survey.getStationByName("2")!!
        editor.addCrossSection(sectionFor(survey, station), Coord2D(20f, -5f))

        val offered = actions(survey, "2", Projection2D.PLAN, editor)
        assertFalse(StationAction.CROSS_SECTION_CREATE in offered)
        assertTrue(StationAction.CROSS_SECTION_EDIT in offered)
        assertTrue(StationAction.CROSS_SECTION_DELETE in offered)

        // And its neighbour, which has none, still offers to make one.
        assertTrue(StationAction.CROSS_SECTION_CREATE in actions(survey, "3", Projection2D.PLAN, editor))
    }

    @Test
    fun theSectionFoundIsTheOneDrawnAtThatStation() {
        val survey = passage()
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        val two = survey.getStationByName("2")!!
        val detail = editor.addCrossSection(sectionFor(survey, two), Coord2D(20f, -5f))

        assertSame(detail, crossSectionAt(editor.sketch, two))
        assertNull(crossSectionAt(editor.sketch, survey.getStationByName("3")!!))
    }

    @Test
    fun aSectionFromTheMenuLandsBesideTheStationRatherThanOnIt() {
        val survey = passage()
        val two = survey.getStationByName("2")!!
        val at = Projection2D.PLAN.project(survey).stationMap[two]!!
        val placed = crossSectionPositionFor(survey, two, Projection2D.PLAN)

        assertNotNull(placed)
        // The finger that opened the menu was on the centreline, so a section drawn where it
        // pressed would cover the passage it describes.
        assertTrue(
            (placed - at).mag() > 1f,
            "expected the section clear of the station, got ${placed.x}, ${placed.y}",
        )
    }

    @Test
    fun theIncomingLegIsTheOneThatMadeTheStation() {
        val survey = passage()
        val row = incomingLegRow(survey, survey.getStationByName("3")!!)
        assertNotNull(row)
        assertEquals("2", row.from)
        assertEquals("3", row.to)
        // The second leg was shot east.
        assertEquals("90.00", row.azimuth)
    }

    @Test
    fun theOriginHasNoIncomingLeg() {
        val survey = passage()
        assertNull(incomingLegRow(survey, survey.getStationByName("1")!!))
    }

    /**
     * A backwards shot is stored attached the way the survey grows but with the reading as it was
     * taken at the far end, so the row has to swap the two stations and reverse the shot. If the
     * menu did not do that, the leg dialog would offer to edit a bearing of 90 where the surveyor
     * wrote 270, and the delete confirmation would name the stations the wrong way round.
     */
    @Test
    fun aBacksightIsNormalisedTheSameWayTheTableNormalisesIt() {
        val survey = Survey("T")
        val destination = org.hwyl.sexytopo.shared.model.survey.Station("2")
        val leg = Leg(5f, 90f, 10f, destination, wasShotBackwards = true)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)

        val row = incomingLegRow(survey, destination)
        assertNotNull(row)
        assertEquals("2", row.from, "the reading was taken at the far station")
        assertEquals("1", row.to)
        assertEquals("270.00", row.azimuth, "bearing is reversed")
        assertEquals("-10.00", row.inclination, "inclination is negated")
        assertFalse(row.isSplay)
    }

    @Test
    fun deletingAStationTakesThePassageBeyondItAndLeavesTheRest() {
        val survey = passage()
        SurveyUpdater.deleteStation(survey, survey.getStationByName("2")!!)

        assertNull(survey.getStationByName("2"))
        assertNull(survey.getStationByName("3"), "everything beyond it goes too")
        assertNotNull(survey.getStationByName("1"))
    }
}
