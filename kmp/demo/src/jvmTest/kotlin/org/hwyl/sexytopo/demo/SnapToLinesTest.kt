package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Snapping a new stroke to the end of an old one.
 *
 * A wall with gaps in it is one that no tracing or filling tool downstream can close, so the gaps
 * survive into whatever the survey is eventually drawn up in. The hit test was ported with the
 * rest of `shared/sketch`; these cover the rules around it, which decide whether snapping helps or
 * gets in the way.
 */
class SnapToLinesTest {

    private fun wall(): SketchEditor {
        val editor = SketchEditor()
        editor.startPath(Coord2D(0f, 0f))
        editor.extendPath(Coord2D(5f, 0f))
        editor.finishPath()
        return editor
    }

    @Test
    fun onlyTheEndsOfAStrokeAreSnappedTo() {
        val editor = wall()

        assertEquals(Coord2D(5f, 0f), editor.snapPointNear(Coord2D(5.2f, 0.2f), 1f))
        assertEquals(Coord2D(0f, 0f), editor.snapPointNear(Coord2D(-0.1f, 0.1f), 1f))
        assertNull(editor.snapPointNear(Coord2D(2.5f, 0.2f), 1f))
    }

    @Test
    fun nothingWithinReachMeansNoSnap() {
        assertNull(wall().snapPointNear(Coord2D(5f, 9f), 1f))
    }

    /**
     * A stroke never snaps to itself: without this, a stroke drawn back towards where it started
     * would jump to its own beginning partway through, turning an open wall into a closed loop the
     * surveyor did not draw.
     */
    @Test
    fun aStrokeInProgressDoesNotSnapToItself() {
        val editor = SketchEditor()
        editor.startPath(Coord2D(0f, 0f))
        editor.extendPath(Coord2D(1f, 0f))

        assertNull(editor.snapPointNear(Coord2D(0.1f, 0.1f), 1f))

        editor.finishPath()
        assertEquals(Coord2D(0f, 0f), editor.snapPointNear(Coord2D(0.1f, 0.1f), 1f))
    }

    @Test
    fun theNearestEndWins() {
        val editor = wall()
        editor.startPath(Coord2D(6f, 0f))
        editor.extendPath(Coord2D(9f, 0f))
        editor.finishPath()

        assertEquals(Coord2D(6f, 0f), editor.snapPointNear(Coord2D(5.6f, 0f), 1f))
        assertEquals(Coord2D(5f, 0f), editor.snapPointNear(Coord2D(5.4f, 0f), 1f))
    }

    @Test
    fun aWallDrawnInThreeStrokesClosesUp() {
        val editor = wall()
        val reach = 1f

        // Second stroke, started a little short of the first one's end and finished a little past
        // the first one's start — both ends snapped, as the canvas does.
        val start = editor.snapPointNear(Coord2D(5.3f, 0.3f), reach) ?: Coord2D(5.3f, 0.3f)
        editor.startPath(start)
        editor.extendPath(Coord2D(5f, 3f))
        editor.extendPath(Coord2D(0.2f, 0.2f))
        editor.snapPointNear(Coord2D(0.2f, 0.2f), reach)?.let { editor.extendPath(it) }
        val finished = editor.finishPath()!!

        assertEquals(Coord2D(5f, 0f), finished.path.first())
        assertEquals(Coord2D(0f, 0f), finished.path.last())
    }

    @Test
    fun theStrokeIsUntouchedWhenSnappingIsOff() {
        val editor = wall()
        val where = Coord2D(5.3f, 0.3f)
        editor.startPath(where)
        editor.extendPath(Coord2D(8f, 0f))
        val finished = editor.finishPath()!!

        assertEquals(where, finished.path.first())
        assertTrue(DisplayOptions().snapToLines == false, "the app's own default is off")
    }
}
