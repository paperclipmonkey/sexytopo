package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drawing the passage outline inside a cross-section.
 *
 * A star of splays is not a passage; the outline a surveyor draws round it is what makes it one.
 * The sub-sketch that holds that outline has been in the model since it was ported, and the SVG
 * exporter has been drawing it, but nothing could put anything into it until the editor existed.
 */
class CrossSectionEditorTest {

    /** A station with wall splays two metres out either side, and one three metres up. */
    private fun survey(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        val station = survey.getStationByName("1")!!
        SurveyBuilder.addSplay(survey, station, Leg(2f, 90f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(2f, 270f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(3f, 0f, 90f))
        return survey
    }

    private fun section() =
        survey().let { survey ->
            val station = survey.getStationByName("1")!!
            survey to
                SketchEditor()
                    .addCrossSection(CrossSectioner.section(survey, station), Coord2D(20f, 5f))
        }

    // ------------------------------------------------------------------------------------
    // The world the editor opens onto
    // ------------------------------------------------------------------------------------

    /**
     * The editor works in the section's own coordinates, not the plan's.
     *
     * The station sits at the origin however far across the plan the drawing has been parked —
     * which is what makes the outline drawn here travel with the section when it is moved.
     */
    @Test
    fun theStationIsAtTheOriginWhereverTheSectionIsParked() {
        val (_, detail) = section()

        val scene = SurveyScene.forCrossSection(detail, Sketch())

        assertEquals(1, scene.stations.size)
        assertEquals(Coord2D.ORIGIN, scene.stations.single().second)
        assertEquals("1", scene.stations.single().first)
    }

    /** Every splay is a ray from the station: there is no centreline inside a cross-section. */
    @Test
    fun theSplaysAreRaysAndThereAreNoLegs() {
        val (_, detail) = section()

        val scene = SurveyScene.forCrossSection(detail, Sketch())

        assertTrue(scene.legs.isEmpty(), "a cross-section has no centreline")
        assertEquals(3, scene.splays.size)
        assertTrue(scene.splays.all { it.start == Coord2D.ORIGIN })
        assertTrue(
            scene.splays.all { it.attachedToActive },
            "every splay here radiates from the station the view is centred on, so fading " +
                "everything not at the working end cannot empty this screen",
        )
    }

    /**
     * The view opens with room to draw *outside* the splays.
     *
     * `CrossSectionView.autoFitZoom` sizes the view so the longest splay takes 0.4 of the smaller
     * screen dimension, and that fraction is load-bearing: the wall outline is drawn beyond the
     * splay ends, so a view fitted tightly to them would open with nowhere to put it.
     */
    @Test
    fun theViewOpensWiderThanTheSplays() {
        val splays =
            listOf(
                SceneSegment(Coord2D.ORIGIN, Coord2D(2f, 0f)),
                SceneSegment(Coord2D.ORIGIN, Coord2D(0f, -3f)),
            )

        val bounds = crossSectionFitBounds(splays)

        // Longest splay is 3 m, so the box is 3 / 0.4 = 7.5 m across, centred on the station.
        assertEquals(7.5f, bounds.width, 0.001f)
        assertEquals(7.5f, bounds.height, 0.001f)
        assertEquals(0f, bounds.centreX, 0.001f)
        assertEquals(0f, bounds.centreY, 0.001f)
    }

    /** A station booked with no wall shots still opens onto a passage-sized piece of paper. */
    @Test
    fun aStationWithNoSplaysStillOpensOntoSomething() {
        val bounds = crossSectionFitBounds(emptyList())

        assertEquals(5f, bounds.width, 0.001f)
        assertEquals(5f, bounds.height, 0.001f)
    }

    // ------------------------------------------------------------------------------------
    // Drawing into it
    // ------------------------------------------------------------------------------------

    /**
     * Cancelling leaves the section alone.
     *
     * The editor draws into a copy for exactly this reason. Without it, "Cancel" would be a lie:
     * the strokes are added to the sketch as they are drawn, so an editor working on the live
     * sub-sketch has already committed everything by the time anybody presses anything.
     */
    @Test
    fun cancellingLeavesTheOriginalUntouched() {
        val (_, detail) = section()
        val working = detail.sketch.copy()
        val editor = SketchEditor(working)

        editor.startPath(Coord2D(-2f, 0f))
        editor.extendPath(Coord2D(0f, -3f))
        editor.finishPath()

        assertEquals(1, working.pathDetails.size)
        assertEquals(0, detail.sketch.pathDetails.size, "cancelling must not have kept the stroke")
    }

    /** Done writes the outline back into the live section, in place. */
    @Test
    fun doneWritesTheOutlineIntoTheSection() {
        val (survey, detail) = section()
        val original = detail.sketch
        val working = detail.sketch.copy()
        val editor = SketchEditor(working)
        editor.startPath(Coord2D(-2f, 0f))
        editor.extendPath(Coord2D(0f, -3f))
        editor.extendPath(Coord2D(2f, 0f))
        editor.finishPath()

        commitCrossSectionSketch(survey, detail, working)

        assertEquals(1, detail.sketch.pathDetails.size)
        assertNotSame(original, detail.sketch)
        assertTrue(!survey.isSaved, "committing must mark the survey unsaved")
    }

    /**
     * The section keeps its identity across a commit.
     *
     * `commitAndFinish` mutates the detail rather than swapping in a new one, and its own comment
     * says why: the plan's undo and redo stacks hold references to this object. Replacing it would
     * leave them pointing at something no longer in the sketch, and undoing past the section's
     * creation would leave a duplicate behind.
     */
    @Test
    fun theSectionKeepsItsIdentitySoThePlansUndoStackStaysValid() {
        val survey = survey()
        val station = survey.getStationByName("1")!!
        val plan = SketchEditor()
        val detail = plan.addCrossSection(CrossSectioner.section(survey, station), Coord2D(20f, 5f))

        val working = detail.sketch.copy()
        SketchEditor(working).apply {
            startPath(Coord2D(-2f, 0f))
            extendPath(Coord2D(2f, 0f))
            finishPath()
        }
        commitCrossSectionSketch(survey, detail, working)

        assertSame(detail, plan.sketch.crossSectionDetails.single())
        // And the plan's own undo still undoes the section's creation, leaving nothing behind.
        plan.undo()
        assertTrue(plan.sketch.crossSectionDetails.isEmpty())
    }

    /**
     * Only paths survive, as in `commitAndFinish`.
     *
     * A cross-section can hold symbols and labels in the model, and the Android app's editor drops
     * them when it commits. Matching that is better than storing something its own editor would
     * throw away the next time the section was opened there.
     */
    @Test
    fun onlyThePathsAreKept() {
        val (survey, detail) = section()
        val working = detail.sketch.copy()
        working.startNewPath(Coord2D(-2f, 0f), Colour.BLACK).lineTo(Coord2D(2f, 0f))
        working.addSymbolDetail(Coord2D(0f, 0f), "water-flow", 1f, 0f, Colour.BLUE)
        working.addTextDetail(Coord2D(0f, 0f), "wide here", 1f, Colour.BLACK)

        commitCrossSectionSketch(survey, detail, working)

        assertEquals(1, detail.sketch.pathDetails.size)
        assertTrue(detail.sketch.symbolDetails.isEmpty())
        assertTrue(detail.sketch.textDetails.isEmpty())
    }

    /** A working copy is a new list of the same details, as the Java's copy constructor is. */
    @Test
    fun aWorkingCopyIsANewListOfTheSameDetails() {
        val sketch = Sketch()
        val path = sketch.startNewPath(Coord2D.ORIGIN, Colour.BLACK)
        sketch.crossSectionScale = 3f

        val copy = sketch.copy()
        copy.startNewPath(Coord2D(1f, 1f), Colour.RED)

        assertEquals(1, sketch.pathDetails.size, "the copy must have its own list")
        assertEquals(2, copy.pathDetails.size)
        assertSame(path, copy.pathDetails.first())
        assertEquals(3f, copy.crossSectionScale)
    }

    /**
     * The outline is drawn in the section's coordinates, so moving the section takes it along.
     *
     * This is what makes the sub-sketch the right place for it: the drawing belongs to the passage
     * profile, not to a spot on the plan.
     */
    @Test
    fun theOutlineTravelsWithTheSection() {
        val (survey, detail) = section()
        val working = detail.sketch.copy()
        working.startNewPath(Coord2D(-2f, 0f), Colour.BLACK).lineTo(Coord2D(2f, 0f))
        commitCrossSectionSketch(survey, detail, working)

        val moved = detail.translate(Coord2D(10f, 10f))

        assertEquals(Coord2D(30f, 15f), moved.position)
        assertEquals(
            listOf(Coord2D(-2f, 0f), Coord2D(2f, 0f)),
            moved.sketch.pathDetails.single().path,
            "the outline stays station-relative, so it moves with the section",
        )
    }
}
