package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.findCrossSectionBodyAt
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Re-aiming and moving a cross-section — the two things the app can do to one after it exists, and
 * the two the port could not do until now.
 *
 * Both matter because both of the decisions the app makes when a section is created are guesses.
 * The bearing comes from [CrossSectioner]'s heuristic and the position comes from wherever a finger
 * landed, and a section that cuts the passage at the wrong angle is not a rough drawing — it is a
 * wrong one, that anybody reading the survey afterwards will believe.
 */
class CrossSectionDragTest {

    /** A dogleg: station 2 sits between a leg due north and a leg due east, with wall splays. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        val middle = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 45f, 0f))
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 225f, 0f))
        return survey
    }

    private fun placed(editor: SketchEditor, at: Coord2D = Coord2D(30f, -8f)) =
        passage().let { survey ->
            val middle = survey.getStationByName("2")!!
            survey to editor.addCrossSection(CrossSectioner.section(survey, middle), at)
        }

    /**
     * The bearing is measured from the *station*, not from where the section is parked.
     *
     * This is `GraphView.getRotationPivot`, and it is the whole reason the gesture is meaningful: a
     * cross-section is a slice through the passage at a station, so "which way am I looking" is a
     * direction from that station. Measuring from the drawing instead would give an angle that
     * depended on where somebody had dragged the picture to.
     */
    @Test
    fun theBearingIsMeasuredFromTheStationNotFromTheDrawing() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)
        val station = Coord2D(0f, 0f)

        // Sketch space has y increasing downwards, so due north is a finger above the station.
        val north =
            SectionDrag(SectionDragMode.ROTATE, detail, from = Coord2D(30f, -8f), pivot = station)
                .movedTo(Coord2D(0f, -10f))
        val east =
            SectionDrag(SectionDragMode.ROTATE, detail, from = Coord2D(30f, -8f), pivot = station)
                .movedTo(Coord2D(10f, 0f))

        assertEquals(0f, north.azimuth)
        assertEquals(90f, east.azimuth)
    }

    /** All four quarters, so a sign error cannot hide in one of them. */
    @Test
    fun theBearingCoversTheWholeCompass() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)
        fun aimedAt(x: Float, y: Float): Float? =
            SectionDrag(
                SectionDragMode.ROTATE,
                detail,
                from = Coord2D.ORIGIN,
                pivot = Coord2D.ORIGIN,
            ).movedTo(Coord2D(x, y)).azimuth

        assertEquals(0f, aimedAt(0f, -1f))
        assertEquals(45f, aimedAt(1f, -1f))
        assertEquals(90f, aimedAt(1f, 0f))
        assertEquals(135f, aimedAt(1f, 1f))
        assertEquals(180f, aimedAt(0f, 1f))
        assertEquals(225f, aimedAt(-1f, 1f))
        assertEquals(270f, aimedAt(-1f, 0f))
        assertEquals(315f, aimedAt(-1f, -1f))
    }

    /**
     * A finger resting exactly on the station aims at nothing.
     *
     * The Java guards this with `if (dx != 0 || dy != 0)`. Without the guard `atan2(0, 0)` is 0, so
     * a section would silently snap to north — the one bearing a surveyor is most likely to
     * believe, and least likely to check.
     */
    @Test
    fun aFingerOnTheStationCommitsNothing() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)
        val before = detail.crossSection.angle

        val drag =
            SectionDrag(
                SectionDragMode.ROTATE,
                detail,
                from = Coord2D(1f, 1f),
                pivot = Coord2D(1f, 1f),
            )

        assertNull(drag.azimuth)
        assertFalse(drag.commit(editor))
        assertEquals(before, editor.sketch.crossSectionDetails.single().crossSection.angle)
    }

    /**
     * A section whose station is not in this projection cannot be aimed.
     *
     * `getRotationPivot` returns null for it in the Java too. It is not hypothetical: an extended
     * elevation does not contain every station the plan does.
     */
    @Test
    fun aSectionWithNoPivotCannotBeAimed() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)

        val drag =
            SectionDrag(SectionDragMode.ROTATE, detail, from = Coord2D.ORIGIN, pivot = null)
                .movedTo(Coord2D(5f, 5f))

        assertNull(drag.azimuth)
        assertFalse(drag.commit(editor))
    }

    @Test
    fun aimingReplacesTheSectionInPlace() {
        val editor = SketchEditor()
        val (survey, detail) = placed(editor)
        val station = survey.getStationByName("2")!!
        detail.sketch.startNewPath(Coord2D(0f, 0f)).lineTo(Coord2D(1f, 1f))

        val drag =
            SectionDrag(
                SectionDragMode.ROTATE,
                detail,
                from = Coord2D(30f, -8f),
                pivot = Coord2D.ORIGIN,
            ).movedTo(Coord2D(10f, 0f))

        assertTrue(drag.commit(editor))

        val after = editor.sketch.crossSectionDetails.single()
        assertEquals(90f, after.crossSection.angle)
        assertEquals(Coord2D(30f, -8f), after.position)
        assertSame(station, after.crossSection.station)
        // The sub-sketch travels with it: anything drawn inside the section is part of the section.
        assertEquals(1, after.sketch.pathDetails.size)
    }

    /**
     * Re-aiming really does re-slice the passage, rather than just relabelling it.
     *
     * The splay star is recomputed from the new bearing, so a splay that pointed one way across the
     * drawing points another afterwards. Without this the gesture would change a number nobody
     * could see.
     */
    @Test
    fun aimingChangesTheProjectedSplays() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)
        fun ends(section: org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail) =
            section.crossSection.getProjection().legMap.values.map { it.end.x }.sorted()

        val before = ends(detail)

        val drag =
            SectionDrag(
                SectionDragMode.ROTATE,
                detail,
                from = Coord2D(30f, -8f),
                pivot = Coord2D.ORIGIN,
            ).movedTo(Coord2D(0f, -10f))
        assertTrue(drag.commit(editor))

        assertTrue(
            ends(editor.sketch.crossSectionDetails.single()) != before,
            "re-aiming should re-project the splays, not just store a different angle",
        )
    }

    @Test
    fun movingTranslatesBySoFarAsTheFingerWent() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor, at = Coord2D(30f, -8f))

        val drag =
            SectionDrag(SectionDragMode.MOVE, detail, from = Coord2D(30f, -8f))
                .movedTo(Coord2D(34f, -3f))

        assertEquals(Coord2D(4f, 5f), drag.delta)
        assertTrue(drag.commit(editor))
        assertEquals(Coord2D(34f, -3f), editor.sketch.crossSectionDetails.single().position)
    }

    /**
     * The finger does not have to start on the section's centre — it starts wherever it grabbed it.
     *
     * So the section moves *with* the finger rather than jumping so that its centre lands under it,
     * which is what makes a drag feel like picking something up.
     */
    @Test
    fun theSectionKeepsItsOffsetFromTheFinger() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor, at = Coord2D(30f, -8f))

        // Grabbed a metre away from the centre, dragged two metres east.
        val drag =
            SectionDrag(SectionDragMode.MOVE, detail, from = Coord2D(31f, -8f))
                .movedTo(Coord2D(33f, -8f))

        assertTrue(drag.commit(editor))
        assertEquals(Coord2D(32f, -8f), editor.sketch.crossSectionDetails.single().position)
    }

    @Test
    fun aDragThatWentNowhereCommitsNothing() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)
        val undoBefore = editor.canUndo

        val drag = SectionDrag(SectionDragMode.MOVE, detail, from = Coord2D(30f, -8f))

        assertFalse(drag.commit(editor))
        assertEquals(undoBefore, editor.canUndo)
    }

    @Test
    fun aMoveIsOneUndoStep() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor, at = Coord2D(30f, -8f))

        SectionDrag(SectionDragMode.MOVE, detail, from = Coord2D.ORIGIN)
            .movedTo(Coord2D(5f, 5f))
            .commit(editor)
        assertEquals(Coord2D(35f, -3f), editor.sketch.crossSectionDetails.single().position)

        editor.undo()

        assertEquals(Coord2D(30f, -8f), editor.sketch.crossSectionDetails.single().position)
    }

    /**
     * The preview and the commit are the same computation.
     *
     * They are two uses of one method precisely so they cannot disagree: a preview drawn from the
     * finger and a result computed from the event is exactly how a drag comes to end up somewhere
     * other than where it looked like it would.
     */
    @Test
    fun thePreviewIsWhatGetsCommitted() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor, at = Coord2D(30f, -8f))

        val drag =
            SectionDrag(SectionDragMode.MOVE, detail, from = Coord2D(30f, -8f))
                .movedTo(Coord2D(36f, -1f))
        val previewed = drag.preview()
        assertTrue(drag.commit(editor))

        assertEquals(previewed.position, editor.sketch.crossSectionDetails.single().position)
    }

    /** Ditto for aiming: what is drawn spinning under the finger is what is left behind. */
    @Test
    fun theAimedPreviewIsWhatGetsCommitted() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor)

        val drag =
            SectionDrag(
                SectionDragMode.ROTATE,
                detail,
                from = Coord2D(30f, -8f),
                pivot = Coord2D.ORIGIN,
            ).movedTo(Coord2D(-7f, -7f))
        val previewed = drag.preview()
        assertTrue(drag.commit(editor))

        assertEquals(
            previewed.crossSection.angle,
            editor.sketch.crossSectionDetails.single().crossSection.angle,
        )
    }

    /**
     * A press inside the section's frame grabs it; a press well away from it grabs nothing.
     *
     * This is the shared [findCrossSectionBodyAt], which the canvas uses to start the drag. Worth
     * asserting here because "the tool did nothing" is the failure a surveyor would report, and the
     * hit test is where it would come from.
     */
    @Test
    fun onlyAPressOnTheSectionGrabsIt() {
        val editor = SketchEditor()
        val (_, detail) = placed(editor, at = Coord2D(30f, -8f))

        assertSame(detail, findCrossSectionBodyAt(editor.sketch, Coord2D(30.2f, -8.2f)))
        assertNull(findCrossSectionBodyAt(editor.sketch, Coord2D(0f, 0f)))
    }

    @Test
    fun theNearerSectionIsTheOneGrabbed() {
        val editor = SketchEditor()
        val survey = passage()
        val middle = survey.getStationByName("2")!!
        val near = editor.addCrossSection(CrossSectioner.section(survey, middle), Coord2D(0f, 0f))
        val far = editor.addCrossSection(CrossSectioner.section(survey, middle), Coord2D(20f, 0f))

        assertSame(near, findCrossSectionBodyAt(editor.sketch, Coord2D(0.1f, 0f)))
        assertSame(far, findCrossSectionBodyAt(editor.sketch, Coord2D(20.1f, 0f)))
        assertNotNull(near)
    }
}
