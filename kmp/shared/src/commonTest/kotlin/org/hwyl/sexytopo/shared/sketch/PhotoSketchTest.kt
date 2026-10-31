package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PhotoDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.common.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A photograph's pin behaves like every other mark on the sketch.
 *
 * Each of these covers a place a new detail kind has to be threaded through and where being
 * forgotten is silent rather than loud. The erase one is not hypothetical: `couldBeVisibleAtScale`
 * fell through to measuring a photograph's bounding box, a pin's box is a single point, and the
 * eraser skips anything it believes is too small to see — so before this the pin was on the
 * drawing and no rubber on earth could take it off again.
 */
class PhotoSketchTest {

    private fun sketchWithAPin(at: Coord2D = Coord2D(3f, 4f)): Sketch =
        Sketch().apply { addPhotoDetail(at, photoId = "1", size = 1f, angle = 90f) }

    @Test
    fun aPinCanBeErased() {
        val sketch = sketchWithAPin()
        val editor = SketchEditor(sketch)

        editor.eraseAt(Coord2D(3f, 4f), toleranceInMetres = 0.5f, pixelsPerMetre = 60f)

        assertTrue(sketch.photoDetails.isEmpty(), "the pin survived the eraser")
    }

    @Test
    fun erasingAPinCanBeUndone() {
        val sketch = sketchWithAPin()
        val editor = SketchEditor(sketch)
        editor.eraseAt(Coord2D(3f, 4f), toleranceInMetres = 0.5f, pixelsPerMetre = 60f)

        editor.undo()

        assertEquals(1, sketch.photoDetails.size, "undo did not bring the pin back")
        assertEquals("1", sketch.photoDetails.single().photoId)
    }

    @Test
    fun placingAPinCanBeUndoneAndRedone() {
        val sketch = Sketch()
        val editor = SketchEditor(sketch)
        editor.addPhoto(Coord2D(1f, 2f), photoId = "7", size = 1f, angle = 45f)

        editor.undo()
        assertTrue(sketch.photoDetails.isEmpty(), "undo left the pin on the drawing")

        editor.redo()
        assertEquals("7", sketch.photoDetails.single().photoId, "redo did not bring it back")
    }

    /** Auto-fit reads the frame; a pin outside it would be scrolled off the edge of the screen. */
    @Test
    fun aPinCountsTowardsTheExtentOfTheDrawing() {
        val sketch = sketchWithAPin(Coord2D(100f, 50f))

        val frame = Frame.from(sketch)

        assertTrue(frame.right >= 100f, "the drawing's frame stops short of the pin: $frame")
        assertTrue(frame.bottom >= 50f || frame.top <= 50f, "the pin is outside the frame: $frame")
    }

    /**
     * A pin is a stamp, so inside its own body it beats a line running underneath it — the same
     * advantage `distanceFrom` gives a symbol, and for the same reason: a mark stamped on top of a
     * passage wall should come off before the wall does.
     *
     * The tap is deliberately beside the line rather than exactly on it. Dead on it both report
     * zero, and a tie goes to whichever the loop reaches first, which is the line — that is true
     * of a symbol too and is not something this feature changes.
     */
    @Test
    fun aPinBeatsALineRunningUnderneathIt() {
        val sketch = Sketch()
        val path = sketch.startNewPath(Coord2D(0f, 4f), Colour.BLACK)
        path.lineTo(Coord2D(10f, 4f))
        sketch.addPhotoDetail(Coord2D(3f, 4f), photoId = "1", size = 2f, angle = 0f)
        val editor = SketchEditor(sketch)

        // 0.4 m off the line, well inside the pin's half-metre radius: the line reports 0.4 and
        // the pin half of it.
        editor.eraseAt(Coord2D(3f, 4.4f), toleranceInMetres = 0.5f, pixelsPerMetre = 60f)

        assertTrue(sketch.photoDetails.isEmpty(), "the eraser took the line instead of the pin")
        assertEquals(1, sketch.pathDetails.size, "the line should have survived")
    }

    /** Growing the marks grows the pin without dragging it away from what it annotates. */
    @Test
    fun scalingGrowsAPinInPlace() {
        val scaled = PhotoDetail(Coord2D(5f, 5f), "1", 1f, 90f, "", Colour.BLACK).scale(3f)

        assertEquals(Coord2D(5f, 5f), scaled.position, "scaling moved the pin")
        assertEquals(3f, scaled.size)
        assertEquals(90f, scaled.angle, "scaling turned the pin")
    }
}
