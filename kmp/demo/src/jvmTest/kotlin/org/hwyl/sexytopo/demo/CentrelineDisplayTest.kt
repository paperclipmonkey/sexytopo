package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.sketch.colourForSymbol
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the centreline says about the survey beyond where it goes.
 *
 * Three of the Android app's display behaviours that this port drew without: the leg just taken is
 * magenta, everything away from the working end can be faded back, and a leg that does not lie in
 * the plane being drawn is dashed. All three are answers to the same question — "where am I on
 * this" — which is the one a surveyor keeps asking of a plan that has grown past a screenful.
 *
 * They are tested through [SurveyScene] rather than through the canvas because that is where the
 * survey is turned into things that can be drawn: by the time a leg reaches `drawSegment` it is a
 * pair of screen points, and the questions have already been settled.
 */
class CentrelineDisplayTest {

    /** 1 → 2 → 3 along a level passage, with a splay off the middle station. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        SurveyBuilder.addSplay(survey, survey.getStationByName("2")!!, Leg(2f, 180f, 0f))
        return survey
    }

    private fun scene(survey: Survey, projection: Projection2D = Projection2D.PLAN) =
        SurveyScene.from(survey, projection)

    // -------------------------------------------------------------------------------------
    // The leg just taken
    // -------------------------------------------------------------------------------------

    @Test
    fun exactlyOneReadingIsTheLatestOne() {
        val scene = scene(passage())

        val marked = scene.legs.count { it.isLatest } + scene.splays.count { it.isLatest }
        assertEquals(1, marked, "two legs and a splay, and only one of them was taken last")
    }

    @Test
    fun theMarkGoesOnTheLastReadingTakenEvenWhenThatIsASplay() {
        // The splay off station 2 went in after both legs. The Java tests
        // `getMostRecentLeg() == leg` before it asks whether the reading is a splay at all, so the
        // splay is what gets the magenta — and that is the right answer to "what did I just take",
        // which is the question the mark exists to answer.
        val scene = scene(passage())

        assertTrue(scene.legs.none { it.isLatest })
        assertTrue(scene.splays.single().isLatest)
    }

    @Test
    fun takingAnotherLegMovesTheMark() {
        val survey = passage()
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 45f, 0f))

        val scene = scene(survey)

        assertTrue(scene.splays.none { it.isLatest }, "the splay is no longer the last thing taken")
        val latest = scene.legs.single { it.isLatest }
        val newStation =
            Projection2D.PLAN.project(survey).stationMap[survey.getStationByName("4")]
        assertEquals(newStation, latest.end, "the leg that made station 4")
    }

    // -------------------------------------------------------------------------------------
    // The working end
    // -------------------------------------------------------------------------------------

    @Test
    fun onlyWhatHangsOffTheActiveStationIsAtTheWorkingEnd() {
        val survey = passage()
        // Active is station 3, the far end: nothing hangs off it yet.
        assertEquals("3", survey.activeStation.name)

        val scene = scene(survey)

        assertTrue(
            scene.legs.none { it.attachedToActive },
            "the legs behind the working station are behind it, however recently they went in",
        )
        assertTrue(scene.splays.none { it.attachedToActive })
    }

    @Test
    fun movingTheActiveStationMovesWhatStaysSolid() {
        val survey = passage()
        survey.activeStation = survey.getStationByName("2")!!

        val scene = scene(survey)

        assertEquals(
            1,
            scene.legs.count { it.attachedToActive },
            "the leg onward from station 2, not the one that arrived at it",
        )
        assertTrue(
            scene.splays.single().attachedToActive,
            "and the wall shot taken from where the surveyor is standing",
        )
    }

    @Test
    fun aLegIsMatchedByIdentityRatherThanByItsReadings() {
        // Leg has no equals, and two shots down a straight passage can read identically. Matching
        // on value would light up the wrong leg wherever a passage repeats itself - which in a
        // stream passage surveyed in ten-metre legs is most of the time.
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        survey.activeStation = survey.getStationByName("2")!!

        val scene = scene(survey)

        assertEquals(1, scene.legs.count { it.attachedToActive })
    }

    // -------------------------------------------------------------------------------------
    // Legs that go into the page
    // -------------------------------------------------------------------------------------

    @Test
    fun aPitchIsNotInThePlanAndALevelPassageIs() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(30f, 0f, -85f))

        val plan = scene(survey).legs

        assertEquals(1, plan.count { it.inPlane }, "the level leg")
        assertEquals(
            1,
            plan.count { !it.inPlane },
            "the pitch, which the plan foreshortens to a stub and so draws dashed",
        )
    }

    @Test
    fun theSameSurveyAnswersDifferentlyInTheExtendedElevation() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(30f, 0f, -85f))

        // Unrolling puts every leg in the plane by construction, so the pitch that is dashed on
        // the plan is solid here. Which is the point of the whole display: "into the page" is a
        // property of the drawing, not of the cave.
        val elevation = scene(survey, Projection2D.EXTENDED_ELEVATION).legs

        assertTrue(elevation.all { it.inPlane }, "nothing is foreshortened in an unrolled section")
    }

    // -------------------------------------------------------------------------------------
    // Water
    // -------------------------------------------------------------------------------------

    @Test
    fun aStreamStampedWithABlackBrushComesOutBlue() {
        val editor = SketchEditor(org.hwyl.sexytopo.shared.model.sketch.Sketch())
        editor.activeColour = Colour.BLACK

        editor.addSymbol(
            Coord2D.ORIGIN,
            Symbol.WATER_FLOW.therionName,
            size = 0.5f,
            colour = colourForSymbol(Symbol.WATER_FLOW.therionName, editor.activeColour, true),
        )

        assertEquals(Colour.BLUE, editor.sketch.symbolDetails.single().colour)
    }

    // -------------------------------------------------------------------------------------
    // Remembering the choice
    // -------------------------------------------------------------------------------------

    @Test
    fun theDisplayDefaultsAreTheAndroidApps() {
        val defaults = AppPreferences.DEFAULT

        assertFalse(defaults.fadeNonActive, "SketchPreferences.Toggle.FADE_NON_ACTIVE is off")
        assertTrue(defaults.highlightLatestLeg, "pref_highlight_latest_leg is on")
        assertTrue(defaults.blueWater, "SketchPreferences.Toggle.BLUE_WATER is on")
    }

    @Test
    fun theyReachTheCanvasFromThePreferencesRatherThanFromNowhere() {
        val state =
            DemoState(
                exampleSurvey = passage(),
                initialProjection = Projection2D.PLAN,
                initialDarkMode = false,
                initialTool = SketchTool.DRAW,
                initialMode = SurveyMode.EXAMPLE,
                initialScreen = Screen.SKETCH,
            )
        state.updatePreferences(
            state.preferences.copy(
                fadeNonActive = true,
                highlightLatestLeg = false,
                blueWater = false,
            ),
        )

        val options = state.displayOptions

        assertTrue(options.fadeNonActive)
        assertFalse(options.highlightLatestLeg)
        assertFalse(options.blueWater)
    }
}
