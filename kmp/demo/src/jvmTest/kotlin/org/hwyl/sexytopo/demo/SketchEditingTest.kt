package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editing layer as the canvas actually drives it.
 *
 * [SketchEditor] and [SketchViewport] are covered in their own right in the shared module; what
 * these tests are for is the demo's *use* of them — the seam where a UI most easily stops agreeing
 * with the model it is showing.
 */
class SketchEditingTest {

    private fun straightLine(points: Int, y: Float = 0f): List<Coord2D> =
        (0 until points).map { Coord2D(it.toFloat(), y) }

    private fun fittedViewport(): SketchViewport =
        SketchViewport().apply { fitTo(Bounds(-10f, -10f, 10f, 10f), 800f, 600f) }

    private fun drag(editor: SketchEditor, points: List<Coord2D>): PathDetail? {
        editor.startPath(points.first())
        for (point in points.drop(1)) editor.extendPath(point)
        return editor.finishPath()
    }

    @Test
    fun aDragBecomesOneSimplifiedStroke() {
        val editor = SketchEditor()

        // A stylus emits far more positions than the shape needs; the editor thins them on release.
        val finished = drag(editor, straightLine(50))

        assertEquals(1, editor.sketch.pathDetails.size, "one drag, one stroke")
        assertEquals(2, finished!!.path.size, "a straight run collapses to its endpoints")
        assertEquals(Coord2D(0f, 0f), finished.path.first(), "the ends must not move")
        assertEquals(Coord2D(49f, 0f), finished.path.last())
    }

    @Test
    fun aCornerSurvivesTheDrag() {
        val editor = SketchEditor()
        val finished = drag(editor, listOf(Coord2D(0f, 0f), Coord2D(5f, 0f), Coord2D(10f, 10f)))
        assertEquals(3, finished!!.path.size, "a real corner must survive simplification")
    }

    @Test
    fun aStrokeIsVisibleWhileItIsStillBeingDrawn() {
        // The canvas draws straight from the live sketch, so a partial stroke has to be in it
        // already, or the line only appears when the finger lifts.
        val editor = SketchEditor()
        editor.startPath(Coord2D(0f, 0f))
        editor.extendPath(Coord2D(1f, 1f))

        assertEquals(1, editor.sketch.pathDetails.size, "the in-progress stroke should be drawable")
        assertFalse(editor.canUndo, "but it is not undoable until it is finished")
    }

    @Test
    fun anAbandonedStrokeLeavesNothingBehind() {
        val editor = SketchEditor()
        editor.startPath(Coord2D(0f, 0f))
        editor.extendPath(Coord2D(1f, 1f))
        editor.abandonPath()

        assertEquals(0, editor.sketch.pathDetails.size)
        assertFalse(editor.canUndo, "a cancelled gesture should leave nothing to undo")
    }

    @Test
    fun theBrushColourIsUsedForNewStrokes() {
        val editor = SketchEditor()
        editor.activeColour = Colour.BLUE
        assertEquals(Colour.BLUE, drag(editor, straightLine(5))!!.colour)
    }

    @Test
    fun erasingTheMiddleOfAStrokeLeavesBothEnds() {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(straightLine(21), Colour.BLACK))
        val editor = SketchEditor(sketch)

        val erased = editor.eraseAt(Coord2D(10f, 0f), toleranceInMetres = 1.5f, pixelsPerMetre = 60f)

        assertTrue(erased)
        assertEquals(2, sketch.pathDetails.size, "one stroke should become two fragments")
        assertTrue(sketch.pathDetails.all { it.path.size >= 2 })
        assertTrue(sketch.pathDetails[0].path.first().x < 10f)
        assertTrue(sketch.pathDetails[1].path.last().x > 10f)
    }

    @Test
    fun erasingAwayFromAStrokeDoesNothing() {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(straightLine(10), Colour.BLACK))
        val editor = SketchEditor(sketch)

        assertFalse(editor.eraseAt(Coord2D(0f, 50f), 1f, 60f))
        assertEquals(1, sketch.pathDetails.size)
        assertEquals(10, sketch.pathDetails.first().path.size)
        assertFalse(editor.canUndo, "and nothing to undo, since nothing happened")
    }

    /**
     * The eraser hit-tests through the same visibility rule the renderer uses: you cannot rub out
     * what is too small to see. A plain radius test could silently delete a detail not on screen.
     */
    @Test
    fun aDetailTooSmallToSeeCannotBeErased() {
        val sketch = Sketch()
        sketch.addSymbolDetail(Coord2D(0f, 0f), "STALACTITE", size = 0.001f, angle = 0f)
        val editor = SketchEditor(sketch)

        assertFalse(
            editor.eraseAt(Coord2D(0f, 0f), toleranceInMetres = 1f, pixelsPerMetre = 1f),
            "at 1 pixel per metre this symbol is a thousandth of a pixel across",
        )
        assertTrue(
            editor.eraseAt(Coord2D(0f, 0f), toleranceInMetres = 1f, pixelsPerMetre = 5000f),
            "zoomed in far enough, it is both visible and erasable",
        )
    }

    @Test
    fun erasingRemovesLabelsAndSymbolsWholeRatherThanSplittingThem() {
        val sketch = Sketch()
        sketch.addTextDetail(Coord2D(5f, 5f), "Sump", 1f, Colour.BLUE)
        val editor = SketchEditor(sketch)

        assertTrue(editor.eraseAt(Coord2D(5f, 5f), 1f, 60f))
        assertEquals(0, sketch.textDetails.size)
    }

    @Test
    fun onePressErasesOneThing() {
        val sketch = Sketch()
        sketch.addTextDetail(Coord2D(5f, 5f), "Sump", 1f, Colour.BLUE)
        sketch.addSymbolDetail(Coord2D(5f, 5f), "STALACTITE", 1f, 0f, Colour.BLACK)
        val editor = SketchEditor(sketch)

        editor.eraseAt(Coord2D(5f, 5f), 1f, 60f)
        assertEquals(
            1,
            sketch.textDetails.size + sketch.symbolDetails.size,
            "the other one should still be there",
        )
    }

    @Test
    fun undoRemovesTheStrokeAndRedoPutsItBack() {
        val editor = SketchEditor()
        editor.activeColour = Colour.RED
        drag(editor, straightLine(5))
        assertEquals(1, editor.sketch.pathDetails.size)

        assertTrue(editor.undo())
        assertEquals(0, editor.sketch.pathDetails.size, "undo removes the stroke")

        assertTrue(editor.redo())
        assertEquals(1, editor.sketch.pathDetails.size, "redo puts it back")
        assertEquals(Colour.RED, editor.sketch.pathDetails.first().colour)
    }

    /** An erase that turns one stroke into two is a single undoable exchange. */
    @Test
    fun undoingAnEraseRestoresTheWholeStroke() {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(straightLine(21), Colour.BLACK))
        val editor = SketchEditor(sketch)

        editor.eraseAt(Coord2D(10f, 0f), 1.5f, 60f)
        assertEquals(2, sketch.pathDetails.size)

        assertTrue(editor.undo())
        assertEquals(1, sketch.pathDetails.size, "the original single stroke is back")
        assertEquals(21, sketch.pathDetails.first().path.size, "with all of its points")
    }

    @Test
    fun aNewEditDiscardsTheRedoStack() {
        val editor = SketchEditor()
        drag(editor, straightLine(3))
        editor.undo()
        assertTrue(editor.canRedo)

        drag(editor, straightLine(3, y = 5f))
        assertFalse(editor.canRedo, "a fresh edit invalidates redo")
    }

    @Test
    fun undoAndRedoAreNoOpsWhenThereIsNothingToDo() {
        val editor = SketchEditor()
        assertFalse(editor.canUndo)
        assertFalse(editor.canRedo)
        assertFalse(editor.undo())
        assertFalse(editor.redo())
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")

    @Test
    fun screenAndSurveyCoordinatesRoundTrip() {
        val viewport = fittedViewport()

        for (point in listOf(Coord2D(0f, 0f), Coord2D(7.5f, -3.25f), Coord2D(-9f, 9f))) {
            val roundTripped = viewport.toSurvey(viewport.toScreen(point))
            assertClose(point.x, roundTripped.x)
            assertClose(point.y, roundTripped.y)
        }
    }

    @Test
    fun fittingCentresTheSurveyAndShowsAllOfIt() {
        val viewport = fittedViewport()

        val centre = viewport.toScreen(Coord2D(0f, 0f))
        assertClose(400f, centre.x, tolerance = 1f)
        assertClose(300f, centre.y, tolerance = 1f)

        val corner = viewport.toScreen(Coord2D(-10f, -10f))
        assertTrue(corner.x >= 0f && corner.y >= 0f, "the whole survey should be on screen")
    }

    @Test
    fun zoomingKeepsThePointUnderTheFingerStill() {
        val viewport = fittedViewport()
        val focus = Offset(250f, 180f)
        val before = viewport.toSurvey(focus)

        assertTrue(viewport.adjustZoomBy(1.5f, focus.toCoord2D()))

        val after = viewport.toSurvey(focus)
        assertClose(before.x, after.x)
        assertClose(before.y, after.y)
    }

    /**
     * The zoom range is the Android app's, and it refuses rather than clamps — so a pinch that
     * overshoots simply stops moving instead of snapping to a limit.
     */
    @Test
    fun zoomingBeyondTheLimitsIsRefused() {
        val viewport = fittedViewport()
        assertFalse(viewport.adjustZoomBy(100000f, Coord2D.ORIGIN), "past MAX_ZOOM")
        assertFalse(viewport.adjustZoomBy(0.000001f, Coord2D.ORIGIN), "past MIN_ZOOM")
        assertTrue(viewport.pixelsPerMetre > 0f && viewport.pixelsPerMetre.isFinite())
    }

    /**
     * How fast a mouse wheel or a Chrome/Firefox trackpad pinch zooms the drawing: a standard 100px
     * wheel notch has to cover a meaningful fraction of the zoom range, not a barely-perceptible
     * nudge. Not exact-value-pinned, since the number is a judgement call about feel — a range
     * catches a regression back to something as timid as the original 0.0015 without breaking on a
     * reasonable future adjustment.
     */
    @Test
    fun aWheelNotchZoomsByAGenerousAmount() {
        val factorPerNotch = exp(-100f * ZOOM_PER_SCROLLED_PIXEL)
        assertTrue(
            factorPerNotch in 0.5f..0.8f,
            "a 100px wheel notch should zoom by a generous, but not dizzying, amount: was $factorPerNotch",
        )
    }

    @Test
    fun aDegenerateSurveyStillProducesAFiniteScale() {
        val viewport = SketchViewport()
        viewport.fitTo(Bounds.of(listOf(Coord2D(3f, 3f))), 500f, 500f)
        assertTrue(viewport.pixelsPerMetre.isFinite())
        assertTrue(viewport.pixelsPerMetre > 0f)
    }

    @Test
    fun aZeroSizedViewportDoesNotProduceNonsense() {
        // The first frame, before layout has run.
        val viewport = SketchViewport()
        viewport.fitTo(Bounds(-1f, -1f, 1f, 1f), 0f, 0f)
        assertTrue(viewport.pixelsPerMetre.isFinite() && viewport.pixelsPerMetre > 0f)
        assertTrue(viewport.toSurvey(Offset.Zero).x.isFinite())
    }
}
