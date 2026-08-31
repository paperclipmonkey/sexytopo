package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picking the station the next leg starts from.
 *
 * The reach is deliberately generous — the app's `SELECTION_SENSITIVITY_DP` is 25dp against the
 * eraser's 10 — because a station is a 10dp dot and the person tapping it has cold hands and is
 * lying in a puddle.
 */
class StationSelectionTest {

    private fun straightNorth(): Survey {
        val survey = Survey("Selectable")
        repeat(3) { SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f)) }
        return survey
    }

    private fun scene(survey: Survey) = SurveyScene.from(survey, Projection2D.PLAN)

    @Test
    fun aTapOnAStationFindsIt() {
        val survey = straightNorth()
        val scene = scene(survey)
        val origin = scene.stations.first { it.first == survey.origin.name }

        assertEquals(survey.origin.name, scene.stationNearest(origin.second, reach = 1f))
    }

    @Test
    fun aTapInEmptySpaceFindsNothing() {
        val scene = scene(straightNorth())
        assertNull(scene.stationNearest(Coord2D(500f, 500f), reach = 2f))
    }

    /**
     * At a junction several stations sit within a finger's width of each other, so the choice has
     * to be the nearest rather than whichever the projection happened to list first — otherwise it
     * feels arbitrary, and a surveyor cannot correct it by tapping more carefully.
     */
    @Test
    fun theNearestStationWinsRatherThanTheFirst() {
        val survey = straightNorth()
        val scene = scene(survey)
        val stations = scene.stations.sortedBy { it.second.y }

        // A point just off the second station, well within reach of the first and third too.
        val target = stations[1]
        val nearby = Coord2D(target.second.x + 0.5f, target.second.y + 0.5f)

        assertEquals(target.first, scene.stationNearest(nearby, reach = 30f))
    }

    @Test
    fun selectingAStationMovesTheHighlight() {
        val state =
            DemoState(
                exampleSurvey = straightNorth(),
                initialProjection = Projection2D.PLAN,
                initialSystemDark = false,
                initialTool = org.hwyl.sexytopo.shared.sketch.SketchTool.SELECT,
                initialMode = SurveyMode.EXAMPLE,
                initialScreen = Screen.SKETCH,
            )
        val other = state.survey.getAllStations().first { it !== state.survey.activeStation }

        assertTrue(state.selectStation(other.name))
        assertEquals(other.name, state.survey.activeStation.name)
        assertEquals(other.name, scene(state.survey).activeStationName)
    }

    @Test
    fun reselectingTheSameStationChangesNothing() {
        val state =
            DemoState(
                exampleSurvey = straightNorth(),
                initialProjection = Projection2D.PLAN,
                initialSystemDark = false,
                initialTool = org.hwyl.sexytopo.shared.sketch.SketchTool.SELECT,
                initialMode = SurveyMode.EXAMPLE,
                initialScreen = Screen.SKETCH,
            )
        assertFalse(
            state.selectStation(state.survey.activeStation.name),
            "no edit means no repaint is needed",
        )
    }

    @Test
    fun selectingAStationThatIsNotInTheSurveyIsRefused() {
        val state =
            DemoState(
                exampleSurvey = straightNorth(),
                initialProjection = Projection2D.PLAN,
                initialSystemDark = false,
                initialTool = org.hwyl.sexytopo.shared.sketch.SketchTool.SELECT,
                initialMode = SurveyMode.EXAMPLE,
                initialScreen = Screen.SKETCH,
            )
        assertFalse(state.selectStation("nowhere"))
        assertNotNull(state.survey.activeStation)
    }
}
