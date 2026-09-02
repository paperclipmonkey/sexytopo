package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Station
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Undo/redo and erasing, ported from `SketchTest` in the Android app plus the behaviour of
 * `GraphView.handleErase` and `Sketch.deleteDetail`.
 */
class SketchEditorTest {

    private fun editor() = SketchEditor()

    private fun drawHorizontalWall(editor: SketchEditor): PathDetail {
        editor.startPath(Coord2D(0f, 0f))
        for (x in 1..10) {
            editor.extendPath(Coord2D(x.toFloat(), 0f))
        }
        return editor.finishPath()!!
    }

    // -------------------------------------------------------------------------------------
    // Drawing and undo
    // -------------------------------------------------------------------------------------

    @Test
    fun anUnfinishedStrokeIsVisibleButNotUndoable() {
        val editor = editor()
        editor.startPath(Coord2D(0f, 0f))
        editor.extendPath(Coord2D(1f, 1f))

        assertEquals(1, editor.sketch.pathDetails.size, "in-progress stroke should be drawable")
        assertFalse(editor.canUndo, "an unfinished stroke is not yet an undoable edit")

        editor.abandonPath()
        assertEquals(0, editor.sketch.pathDetails.size)
        assertFalse(editor.canUndo, "abandoning leaves no trace")
        assertNull(editor.activePath)
    }

    @Test
    fun finishingAStrokeSimplifiesItAndMakesOneUndoStep() {
        val editor = editor()
        val wall = drawHorizontalWall(editor)

        // Eleven collinear points, epsilon = max(10/500, 0.001) = 0.02, so only the ends survive.
        assertEquals(listOf(Coord2D(0f, 0f), Coord2D(10f, 0f)), wall.path)
        assertEquals(1, editor.sketch.pathDetails.size)
        assertTrue(editor.canUndo)

        assertTrue(editor.undo())
        assertEquals(0, editor.sketch.pathDetails.size)
        assertTrue(editor.canRedo)

        assertTrue(editor.redo())
        assertEquals(1, editor.sketch.pathDetails.size)
        assertSame(wall, editor.sketch.pathDetails[0], "redo restores the very same detail")
    }

    @Test
    fun aTapBecomesATwoPointDot() {
        val editor = editor()
        editor.startPath(Coord2D(3f, 4f))
        // The Android app appends the same point again on touch-up when the finger did not move.
        editor.extendPath(Coord2D(3f, 4f))
        val dot = editor.finishPath()!!

        assertEquals(2, dot.path.size)
        assertEquals(Coord2D(3f, 4f), dot.path[0])
        assertEquals(Coord2D(3f, 4f), dot.path[1])
    }

    @Test
    fun undoAndRedoRunTheWholeStackInOrder() {
        val editor = editor()
        drawHorizontalWall(editor)
        editor.addSymbol(Coord2D(5f, 5f), "SAND", size = 0.5f)
        editor.addText(Coord2D(6f, 6f), "sump", size = 0.3f)

        assertEquals(3, editor.sketch.pathDetails.size + editor.sketch.symbolDetails.size + editor.sketch.textDetails.size)

        repeat(3) { assertTrue(editor.undo()) }
        assertTrue(editor.sketch.isEmpty())
        assertFalse(editor.undo(), "undo on an empty history is a no-op")

        repeat(3) { assertTrue(editor.redo()) }
        assertEquals(1, editor.sketch.pathDetails.size)
        assertEquals(1, editor.sketch.symbolDetails.size)
        assertEquals(1, editor.sketch.textDetails.size)
        assertFalse(editor.redo())
    }

    @Test
    fun aNewEditDiscardsTheRedoStack() {
        val editor = editor()
        drawHorizontalWall(editor)
        editor.undo()
        assertTrue(editor.canRedo)

        editor.addSymbol(Coord2D(0f, 0f), "CLAY", size = 0.5f)

        assertFalse(editor.canRedo, "drawing away from an undo branch discards it")
        assertEquals(0, editor.sketch.pathDetails.size)
    }

    @Test
    fun editingClearsTheSavedFlag() {
        val editor = editor()
        assertTrue(editor.isSaved)
        drawHorizontalWall(editor)
        assertFalse(editor.isSaved)
        editor.markSaved()
        assertTrue(editor.isSaved)
    }

    // -------------------------------------------------------------------------------------
    // Erasing splits paths
    // -------------------------------------------------------------------------------------

    @Test
    fun erasingTheMiddleOfAStrokeLeavesBothEnds() {
        val editor = editor()
        // A deliberately un-simplifiable zigzag so the stroke keeps all its points.
        val points =
            listOf(
                Coord2D(0f, 0f),
                Coord2D(1f, 1f),
                Coord2D(2f, 0f),
                Coord2D(3f, 1f),
                Coord2D(4f, 0f),
                Coord2D(5f, 1f),
                Coord2D(6f, 0f),
            )
        val wall = PathDetail(points, Colour.BLACK)
        editor.sketch.pathDetails.add(wall)

        // Erase at the middle vertex with a radius that swallows only the segments touching it.
        val erased = editor.eraseAt(Coord2D(3f, 1f), toleranceInMetres = 0.6f, pixelsPerMetre = 60f)

        assertTrue(erased)
        assertEquals(2, editor.sketch.pathDetails.size, "the stroke should be split, not deleted")
        val (left, right) = editor.sketch.pathDetails
        assertEquals(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f), Coord2D(2f, 0f)), left.path)
        assertEquals(listOf(Coord2D(4f, 0f), Coord2D(5f, 1f), Coord2D(6f, 0f)), right.path)
        assertFalse(
            editor.sketch.pathDetails.any { it === wall },
            "the original stroke is gone; the fragments are new details",
        )
    }

    @Test
    fun undoingAnEraseRestoresTheWholeStrokeAndRemovesTheFragments() {
        val editor = editor()
        val points =
            listOf(
                Coord2D(0f, 0f),
                Coord2D(1f, 1f),
                Coord2D(2f, 0f),
                Coord2D(3f, 1f),
                Coord2D(4f, 0f),
                Coord2D(5f, 1f),
                Coord2D(6f, 0f),
            )
        val wall = PathDetail(points, Colour.BLACK)
        editor.sketch.pathDetails.add(wall)

        editor.eraseAt(Coord2D(3f, 1f), toleranceInMetres = 0.6f, pixelsPerMetre = 60f)
        assertEquals(2, editor.sketch.pathDetails.size)

        assertTrue(editor.undo())
        assertEquals(1, editor.sketch.pathDetails.size, "fragments removed, original back")
        assertSame(wall, editor.sketch.pathDetails[0])

        assertTrue(editor.redo())
        assertEquals(2, editor.sketch.pathDetails.size, "redo must not leave the original behind")
        assertFalse(editor.sketch.pathDetails.any { it === wall })
    }

    @Test
    fun erasingAcrossAWholeStrokeDeletesIt() {
        val editor = editor()
        val wall = drawHorizontalWall(editor)

        // A tolerance bigger than the whole stroke: every segment is inside the eraser, so no
        // fragment survives and the delete has no replacements.
        val erased = editor.eraseAt(Coord2D(5f, 0f), toleranceInMetres = 20f, pixelsPerMetre = 60f)

        assertTrue(erased)
        assertEquals(0, editor.sketch.pathDetails.size)

        editor.undo()
        assertEquals(1, editor.sketch.pathDetails.size)
        assertSame(wall, editor.sketch.pathDetails[0])
    }

    @Test
    fun erasingWithFragmentSplittingOffDeletesWholeStrokes() {
        val editor = editor()
        val points = listOf(Coord2D(0f, 0f), Coord2D(1f, 1f), Coord2D(2f, 0f), Coord2D(3f, 1f))
        editor.sketch.pathDetails.add(PathDetail(points, Colour.BLACK))

        editor.eraseAt(
            Coord2D(1f, 1f),
            toleranceInMetres = 0.6f,
            pixelsPerMetre = 60f,
            deletePathFragments = false,
        )

        assertEquals(0, editor.sketch.pathDetails.size)
    }

    @Test
    fun erasingNothingChangesNothing() {
        val editor = editor()
        drawHorizontalWall(editor)
        val erased = editor.eraseAt(Coord2D(50f, 50f), toleranceInMetres = 0.2f, pixelsPerMetre = 60f)

        assertFalse(erased)
        assertEquals(1, editor.sketch.pathDetails.size)
        assertEquals(1, historySize(editor), "a miss must not push an undo step")
    }

    @Test
    fun erasingASymbolDeletesItWhole() {
        val editor = editor()
        val symbol = editor.addSymbol(Coord2D(2f, 2f), "STALACTITE", size = 0.4f)

        assertTrue(editor.eraseAt(Coord2D(2.1f, 2f), toleranceInMetres = 0.2f, pixelsPerMetre = 60f))
        assertEquals(0, editor.sketch.symbolDetails.size)

        editor.undo()
        assertSame(symbol, editor.sketch.symbolDetails[0])
    }

    @Test
    fun invisiblyTinyDetailsAreNotSelectable() {
        val editor = editor()
        editor.addSymbol(Coord2D(0f, 0f), "SAND", size = 0.001f)

        val erased = editor.eraseAt(Coord2D(0f, 0f), toleranceInMetres = 1f, pixelsPerMetre = 60f)

        assertFalse(erased, "0.001m at 60px/m is a sixteenth of a pixel")
        assertEquals(1, editor.sketch.symbolDetails.size)
    }

    // -------------------------------------------------------------------------------------
    // Cross-sections
    // -------------------------------------------------------------------------------------

    @Test
    fun replacingACrossSectionIsASingleUndoStep() {
        val editor = editor()
        val crossSection = CrossSection(Station("A1"), 0f)
        val old = editor.addCrossSection(crossSection, Coord2D(1f, 2f))

        val moved = editor.moveCrossSection(old, Coord2D(9f, 18f))

        assertEquals(1, editor.sketch.crossSectionDetails.size)
        assertSame(moved, editor.sketch.crossSectionDetails[0])

        assertTrue(editor.undo())
        assertEquals(1, editor.sketch.crossSectionDetails.size)
        assertSame(old, editor.sketch.crossSectionDetails[0])

        assertTrue(editor.redo())
        assertEquals(1, editor.sketch.crossSectionDetails.size)
        assertSame(moved, editor.sketch.crossSectionDetails[0])
    }

    @Test
    fun createThenDeleteThenUndoRedoNeverDuplicatesACrossSection() {
        val editor = editor()
        val crossSection = CrossSection(Station("A1"), 0f)
        val detail = editor.addCrossSection(crossSection, Coord2D(1f, 2f))

        editor.delete(detail)
        assertEquals(0, editor.sketch.crossSectionDetails.size)

        editor.undo()
        assertEquals(1, editor.sketch.crossSectionDetails.size)
        editor.undo()
        assertEquals(0, editor.sketch.crossSectionDetails.size)

        editor.redo()
        assertEquals(1, editor.sketch.crossSectionDetails.size)
        editor.redo()
        assertEquals(0, editor.sketch.crossSectionDetails.size)
    }

    @Test
    fun rotatingACrossSectionKeepsItsPositionAndSubSketch() {
        val editor = editor()
        val station = Station("A1")
        val subSketch = org.hwyl.sexytopo.shared.model.sketch.Sketch()
        subSketch.startNewPath(Coord2D(0f, 0f))
        val detail = editor.addCrossSection(CrossSectionDetail(Coord2D(4f, 5f), CrossSection(station, 10f), subSketch))

        val rotated = editor.rotateCrossSection(detail, 90f)

        assertEquals(Coord2D(4f, 5f), rotated.position)
        assertEquals(90f, rotated.crossSection.angle)
        assertSame(subSketch, rotated.sketch, "a rotate must not lose what was drawn inside")
        assertSame(station, rotated.crossSection.station)

        editor.undo()
        assertSame(detail, editor.sketch.crossSectionDetails[0])
    }

    @Test
    fun pressingInsideACrossSectionBodyErasesItEvenAwayFromItsCentre() {
        val editor = editor()
        val detail = editor.addCrossSection(CrossSection(Station("A1"), 0f), Coord2D(0f, 0f))

        // 0.9m off centre: outside a 10dp eraser tolerance but well inside the 1m minimum body.
        val erased = editor.eraseAt(Coord2D(0.9f, 0f), toleranceInMetres = 0.1f, pixelsPerMetre = 60f)

        assertTrue(erased)
        assertEquals(0, editor.sketch.crossSectionDetails.size)

        editor.undo()
        assertSame(detail, editor.sketch.crossSectionDetails[0])
    }

    // -------------------------------------------------------------------------------------
    // Snapping
    // -------------------------------------------------------------------------------------

    @Test
    fun snappingFindsTheNearestEndOfAnotherStrokeButNeverItsOwn() {
        val editor = editor()
        drawHorizontalWall(editor) // ends at (0,0) and (10,0)

        assertEquals(Coord2D(10f, 0f), editor.snapPointNear(Coord2D(10.2f, 0.1f), 0.5f))
        assertNull(editor.snapPointNear(Coord2D(5f, 0f), 0.5f), "only stroke ends snap, not middles")

        editor.startPath(Coord2D(20f, 20f))
        editor.extendPath(Coord2D(20.1f, 20f))
        assertNull(
            editor.snapPointNear(Coord2D(20f, 20f), 0.5f),
            "the stroke being drawn must not snap to itself",
        )
    }

    private fun historySize(editor: SketchEditor): Int {
        var steps = 0
        while (editor.undo()) steps++
        repeat(steps) { editor.redo() }
        return steps
    }
}
