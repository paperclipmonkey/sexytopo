package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the interactive editing layer: the parts a stylus actually drives.
 *
 * These live in the demo module because the viewport deals in Compose geometry types; the sketch
 * model they operate on is the shared ported one.
 */
class SketchEditingTest {

    private fun straightLine(points: Int, y: Float = 0f): List<Coord2D> =
        (0 until points).map { Coord2D(it.toFloat(), y) }

    // -------------------------------------------------------------------------------------
    // Simplification
    // -------------------------------------------------------------------------------------

    @Test
    fun aStraightRunCollapsesToItsEndpoints() {
        // Douglas-Peucker's whole point: a stylus emits far more points than the shape needs.
        val simplified = simplifyPath(straightLine(50), epsilon = 0.01f)
        assertEquals(2, simplified.size)
        assertEquals(Coord2D(0f, 0f), simplified.first())
        assertEquals(Coord2D(49f, 0f), simplified.last())
    }

    @Test
    fun aCornerIsKept() {
        val corner = listOf(Coord2D(0f, 0f), Coord2D(5f, 0f), Coord2D(10f, 10f))
        val simplified = simplifyPath(corner, epsilon = 0.5f)
        assertEquals(3, simplified.size, "a real corner must survive simplification")
    }

    @Test
    fun simplificationNeverMovesTheEndpoints() {
        val wobbly = (0..30).map { Coord2D(it.toFloat(), if (it % 2 == 0) 0.05f else -0.05f) }
        val simplified = simplifyPath(wobbly, epsilon = 0.5f)
        assertEquals(wobbly.first(), simplified.first())
        assertEquals(wobbly.last(), simplified.last())
        assertTrue(simplified.size < wobbly.size, "noise should be dropped")
    }

    @Test
    fun aTwoPointStrokeIsLeftAlone() {
        val two = listOf(Coord2D(0f, 0f), Coord2D(1f, 1f))
        assertEquals(two, simplifyPath(two, epsilon = 10f))
    }

    // -------------------------------------------------------------------------------------
    // Erasing
    // -------------------------------------------------------------------------------------

    @Test
    fun erasingTheMiddleOfAStrokeLeavesBothEnds() {
        // The behaviour that makes erasing feel right: rubbing out the middle of a passage wall
        // leaves the wall either side, rather than deleting the whole stroke.
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(straightLine(21), Colour.BLACK))

        val erased = eraseAt(sketch, Coord2D(10f, 0f), radius = 1.5f)

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

        assertFalse(eraseAt(sketch, Coord2D(0f, 50f), radius = 1f))
        assertEquals(1, sketch.pathDetails.size)
        assertEquals(10, sketch.pathDetails.first().path.size)
    }

    @Test
    fun erasingRemovesLabelsAndSymbolsWholeRatherThanSplittingThem() {
        val sketch = Sketch()
        sketch.addTextDetail(Coord2D(5f, 5f), "Sump", 1f, Colour.BLUE)
        sketch.addSymbolDetail(Coord2D(5f, 5f), "STALACTITE", 0.5f, 0f, Colour.BLACK)

        assertTrue(eraseAt(sketch, Coord2D(5f, 5f), radius = 1f))
        assertEquals(0, sketch.textDetails.size)
        assertEquals(0, sketch.symbolDetails.size)
    }

    // -------------------------------------------------------------------------------------
    // Undo / redo
    // -------------------------------------------------------------------------------------

    @Test
    fun undoRestoresTheSketchAndRedoReappliesTheEdit() {
        val sketch = Sketch()
        val history = SketchHistory()

        history.record(sketch)
        sketch.pathDetails.add(PathDetail(straightLine(5), Colour.RED))
        assertEquals(1, sketch.pathDetails.size)

        assertTrue(history.undo(sketch))
        assertEquals(0, sketch.pathDetails.size, "undo removes the stroke")

        assertTrue(history.redo(sketch))
        assertEquals(1, sketch.pathDetails.size, "redo puts it back")
        assertEquals(Colour.RED, sketch.pathDetails.first().colour)
    }

    @Test
    fun anEraseCanBeUndone() {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(straightLine(21), Colour.BLACK))
        val history = SketchHistory()

        history.record(sketch)
        eraseAt(sketch, Coord2D(10f, 0f), radius = 1.5f)
        assertEquals(2, sketch.pathDetails.size)

        history.undo(sketch)
        assertEquals(1, sketch.pathDetails.size, "the original single stroke is back")
        assertEquals(21, sketch.pathDetails.first().path.size)
    }

    @Test
    fun aNewEditDiscardsTheRedoStack() {
        val sketch = Sketch()
        val history = SketchHistory()

        history.record(sketch)
        sketch.pathDetails.add(PathDetail(straightLine(3), Colour.BLACK))
        history.undo(sketch)
        assertTrue(history.canRedo)

        history.record(sketch)
        sketch.pathDetails.add(PathDetail(straightLine(3), Colour.BLUE))
        assertFalse(history.canRedo, "a fresh edit invalidates redo")
    }

    @Test
    fun undoAndRedoAreNoOpsWhenThereIsNothingToDo() {
        val history = SketchHistory()
        val sketch = Sketch()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.undo(sketch))
        assertFalse(history.redo(sketch))
    }

    // -------------------------------------------------------------------------------------
    // Viewport
    // -------------------------------------------------------------------------------------

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")

    @Test
    fun screenAndSurveyCoordinatesRoundTrip() {
        // Drawing depends on this inverse: a touch in pixels must become the right point in metres.
        val viewport =
            Viewport(
                bounds = Bounds(-10f, -10f, 10f, 10f),
                size = Size(800f, 600f),
                zoom = 1f,
                pan = Offset(30f, -20f),
            )

        for (point in listOf(Coord2D(0f, 0f), Coord2D(7.5f, -3.25f), Coord2D(-9f, 9f))) {
            val roundTripped = viewport.toSurvey(viewport.toScreen(point))
            assertClose(point.x, roundTripped.x)
            assertClose(point.y, roundTripped.y)
        }
    }

    @Test
    fun zoomingChangesTheScaleButNotTheRoundTrip() {
        val viewport =
            Viewport(Bounds(-5f, -5f, 5f, 5f), Size(400f, 400f), zoom = 4f, pan = Offset.Zero)
        val point = Coord2D(2f, -1f)
        val roundTripped = viewport.toSurvey(viewport.toScreen(point))
        assertClose(point.x, roundTripped.x)
        assertClose(point.y, roundTripped.y)
        assertTrue(viewport.pixelsPerMetre > 0f)
    }

    @Test
    fun aZeroSizedViewportDoesNotProduceNonsense() {
        // The first frame, before layout has run: this used to make pixelsPerMetre infinite.
        val viewport = Viewport(Bounds(-1f, -1f, 1f, 1f), Size.Zero, zoom = 1f, pan = Offset.Zero)
        assertTrue(viewport.pixelsPerMetre.isFinite())
        assertTrue(viewport.toSurvey(Offset.Zero).x.isFinite())
    }

    @Test
    fun aDegenerateSurveyStillProducesAFiniteScale() {
        // A survey with one station has no extent; fitting it must not divide by zero.
        val viewport = Viewport(Bounds.of(listOf(Coord2D(3f, 3f))), Size(500f, 500f), 1f, Offset.Zero)
        assertTrue(viewport.pixelsPerMetre.isFinite())
        assertTrue(viewport.pixelsPerMetre > 0f)
    }
}
