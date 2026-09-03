package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.math.Camera3D
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 3D view's trackpad path: a MacBook reports two fingers as a `wheel` event rather than as two
 * touches, so [Camera3D.afterScroll] is what stands in for the touch loop when nothing with a
 * `pressed` change ever arrives. Reported as "panning doesn't work, and zoom barely does" - this is
 * the fix, tested the way [SketchEditingTest.aWheelNotchZoomsByAGenerousAmount] tests the 2D one.
 */
class ThreeDViewTest {

    @Test
    fun pinchingOutOnAWheelZoomsIn() {
        // A negative deltaY under ctrl is a MacBook trackpad's own report of a pinch-out - the same
        // sign [SurveyCanvas]'s own wheel handler zooms the 2D drawing in on.
        val camera = Camera3D(distance = 50f)
        val zoomed = camera.afterScroll(Coord2D(0f, -100f), zoomModifierHeld = true, pinchToZoom = true)
        assertTrue(zoomed.distance < camera.distance, "a pinch-out should move the camera closer")
    }

    @Test
    fun pinchingInOnAWheelZoomsOut() {
        val camera = Camera3D(distance = 50f)
        val zoomed = camera.afterScroll(Coord2D(0f, 100f), zoomModifierHeld = true, pinchToZoom = true)
        assertTrue(zoomed.distance > camera.distance, "a pinch-in should move the camera further away")
    }

    @Test
    fun aNotchOutAndBackReturnsToWhereItStarted() {
        // The same claim finding 97's fix makes for the 2D canvas: the arithmetic is a scale, not a
        // step, so undoing a notch has to land back where it started rather than walking away.
        val camera = Camera3D(distance = 50f)
        val roundTripped =
            camera
                .afterScroll(Coord2D(0f, -100f), zoomModifierHeld = true, pinchToZoom = true)
                .afterScroll(Coord2D(0f, 100f), zoomModifierHeld = true, pinchToZoom = true)
        assertTrue(
            abs(camera.distance - roundTripped.distance) < 0.01f,
            "expected ${camera.distance} but was ${roundTripped.distance}",
        )
    }

    @Test
    fun theZoomModifierIsIgnoredWithPinchToZoomOff() {
        val camera = Camera3D(distance = 50f)
        val unchanged = camera.afterScroll(Coord2D(0f, -100f), zoomModifierHeld = true, pinchToZoom = false)
        assertEquals(camera.distance, unchanged.distance)
    }

    @Test
    fun aPlainScrollPansRatherThanZooms() {
        // This is the half that was entirely missing: a plain two-finger trackpad slide is a wheel
        // event with no modifier, and nothing here used to read it at all.
        val camera = Camera3D(distance = 50f)
        val panned = camera.afterScroll(Coord2D(40f, 0f), zoomModifierHeld = false, pinchToZoom = true)
        assertEquals(camera.distance, panned.distance, "a plain scroll must not change the zoom")
        assertTrue(
            panned.panX != camera.panX || panned.panY != camera.panY || panned.panZ != camera.panZ,
            "a plain scroll should move the cave",
        )
    }

    @Test
    fun scrollingAndDraggingMoveItTheSameWay() {
        // The touch loop above pans with the raw finger movement: `pannedBy(moved.x, moved.y)`. A
        // trackpad's plain scroll is the same gesture reported the other way round - the wheel
        // handler negates it - so scrolling down by the same amount a finger would have dragged up
        // has to land the camera exactly where that drag would have.
        val camera = Camera3D(distance = 50f)
        val dragged = camera.pannedBy(-40f, -25f)
        val scrolled = camera.afterScroll(Coord2D(40f, 25f), zoomModifierHeld = false, pinchToZoom = true)
        assertTrue(abs(dragged.panX - scrolled.panX) < 0.0001f)
        assertTrue(abs(dragged.panY - scrolled.panY) < 0.0001f)
        assertTrue(abs(dragged.panZ - scrolled.panZ) < 0.0001f)
    }
}
