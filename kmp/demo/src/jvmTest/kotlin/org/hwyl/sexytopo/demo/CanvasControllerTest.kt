package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The view must not move while a finger is on it.
 *
 * `centreOn` is how the app follows the survey: a new station is made and the view goes to it.
 * The station most likely to arrive is the one made while the surveyor is drawing the passage
 * they just measured, and moving the view then drags the paper out from under a pen that has not
 * moved. So a centring asked for mid-touch waits, and is applied when the touch lifts.
 */
class CanvasControllerTest {

    private fun controller(): CanvasController =
        CanvasController().also { it.noteViewSize(400f, 300f) }

    private fun CanvasController.centre(): Coord2D = viewport.toSurvey(Coord2D(200f, 150f))

    @Test
    fun centringWithNoTouchDownHappensAtOnce() {
        val canvas = controller()
        canvas.centreOn(Coord2D(10f, 20f))
        assertEquals(Coord2D(10f, 20f), canvas.centre())
    }

    @Test
    fun centringMidTouchWaitsForTheTouchToLift() {
        val canvas = controller()
        val before = canvas.viewport.offset
        val revisionBefore = canvas.revision

        canvas.touchBegan()
        canvas.centreOn(Coord2D(10f, 20f))
        assertEquals(before, canvas.viewport.offset, "the view moved under a touch")
        assertEquals(revisionBefore, canvas.revision, "a redraw was asked for with nothing changed")

        canvas.touchEnded()
        assertEquals(Coord2D(10f, 20f), canvas.centre())
        assertTrue(canvas.revision > revisionBefore, "the deferred move never asked for a redraw")
    }

    @Test
    fun onlyTheLatestDeferredCentreIsApplied() {
        val canvas = controller()
        canvas.touchBegan()
        canvas.centreOn(Coord2D(1f, 1f))
        canvas.centreOn(Coord2D(10f, 20f))
        canvas.touchEnded()
        assertEquals(Coord2D(10f, 20f), canvas.centre())
    }

    @Test
    fun aDeferredCentreIsAppliedOnce() {
        val canvas = controller()
        canvas.touchBegan()
        canvas.centreOn(Coord2D(10f, 20f))
        canvas.touchEnded()

        // The next touch, with nothing asked for in between, must not re-apply the old one.
        canvas.transformBy(Coord2D(200f, 150f), Coord2D(50f, 0f), 1f)
        val panned = canvas.viewport.offset
        canvas.touchBegan()
        canvas.touchEnded()
        assertEquals(panned, canvas.viewport.offset)
    }

    @Test
    fun aTouchEndingWithNothingDeferredStillAsksForAFrame() {
        // The automatic re-fit is skipped while a touch is down and happens on the next draw, so
        // there has to be a next draw.
        val canvas = controller()
        val before = canvas.revision
        canvas.touchBegan()
        canvas.touchEnded()
        assertFalse(canvas.isTouched)
        assertNotEquals(before, canvas.revision)
    }

    @Test
    fun aDeferredCentreStillMarksTheViewAsTheSurveyors() {
        val canvas = controller()
        canvas.touchBegan()
        canvas.centreOn(Coord2D(10f, 20f))
        assertFalse(canvas.fit.userHasTakenControl, "taken before the move was even made")
        canvas.touchEnded()
        assertTrue(canvas.fit.userHasTakenControl)
    }
}
