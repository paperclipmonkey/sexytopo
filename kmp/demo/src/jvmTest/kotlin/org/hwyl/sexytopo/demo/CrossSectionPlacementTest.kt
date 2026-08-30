package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A cross-section is what a caver draws when the plan alone cannot say what shape the passage is.
 * The model, the projection and the bearing heuristic were all ported and tested; these check the
 * placement the tool performs on top of them.
 */
class CrossSectionPlacementTest {

    /** Station 2, mid-passage, with splays at the walls. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        val middle = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 45f, 0f))
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 225f, 0f))
        return survey
    }

    /**
     * Mid-passage the bearing bisects the corner, which is the heuristic
     * [CrossSectioner.angleOfSection] carries over from the Android app: a section drawn straight
     * across the passage rather than along either leg.
     */
    @Test
    fun aSectionMidPassageBisectsTheCorner() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(45f, CrossSectioner.section(survey, middle).angle)
    }

    @Test
    fun theSectionIsPlacedWhereItWasTappedAndKeepsItsStation() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!
        val editor = SketchEditor()

        val placed = editor.addCrossSection(CrossSectioner.section(survey, middle), Coord2D(30f, -8f))

        assertEquals(Coord2D(30f, -8f), placed.position)
        assertSame(middle, placed.crossSection.station)
        assertEquals(1, editor.sketch.crossSectionDetails.size)
    }

    /** The splays are what the section is made of, so they have to reach the projection. */
    @Test
    fun theSectionProjectsTheStationsSplays() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        val projection = CrossSectioner.section(survey, middle).getProjection()

        assertEquals(2, projection.legMap.size)
    }

    /** One undo step, like every other sketch item. */
    @Test
    fun aSectionCanBeUndone() {
        val survey = passage()
        val editor = SketchEditor()
        editor.addCrossSection(
            CrossSectioner.section(survey, survey.getStationByName("2")!!),
            Coord2D.ORIGIN,
        )

        assertTrue(editor.canUndo)
        editor.undo()
        assertTrue(editor.sketch.crossSectionDetails.isEmpty())
    }

    /**
     * A station with no splays still makes a section — an empty one. That is the app's behaviour
     * and it is the right one: the surveyor drops the section first and shoots the walls into it
     * afterwards, which is how it is done when the passage is awkward to stand in.
     */
    @Test
    fun aStationWithNoSplaysStillMakesASection() {
        val survey = Survey("T")
        SurveyUpdater.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        val editor = SketchEditor()

        val placed =
            editor.addCrossSection(CrossSectioner.section(survey, survey.origin), Coord2D.ORIGIN)

        assertEquals(0, placed.crossSection.getProjection().legMap.size)
    }
}
