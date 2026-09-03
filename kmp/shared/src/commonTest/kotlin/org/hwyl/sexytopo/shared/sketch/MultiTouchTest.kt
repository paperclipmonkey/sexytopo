package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiTouchTest {

    private fun points(vararg pairs: Pair<Float, Float>) = pairs.map { Coord2D(it.first, it.second) }

    @Test
    fun theCentroidOfNoFingersIsTheOrigin() {
        assertEquals(Coord2D.ORIGIN, centroidOf(emptyList()))
    }

    @Test
    fun oneFingerIsItsOwnCentroid() {
        assertEquals(Coord2D(3f, 7f), centroidOf(points(3f to 7f)))
    }

    @Test
    fun twoFingersMeetInTheMiddle() {
        assertEquals(Coord2D(5f, 10f), centroidOf(points(0f to 0f, 10f to 20f)))
    }

    @Test
    fun oneFingerHasNoSpread() {
        assertEquals(0f, spreadOf(points(3f to 7f)))
    }

    @Test
    fun spreadIsTheMeanDistanceFromTheCentroid() {
        // Two fingers 10 apart sit 5 each from the middle.
        assertEquals(5f, spreadOf(points(0f to 0f, 10f to 0f)))
    }

    @Test
    fun aThirdFingerAtTheCentreDoesNotUndoTheSpread() {
        // Mean rather than sum: adding a finger in the middle lowers the mean but keeps it real.
        val spread = spreadOf(points(0f to 0f, 10f to 0f, 5f to 0f))
        assertTrue(spread > 0f && spread < 5f, "expected a smaller but non-zero spread, got $spread")
    }

    @Test
    fun fingersMovingApartZoomIn() {
        val zoom = zoomBetween(points(0f to 0f, 10f to 0f), points(0f to 0f, 20f to 0f))
        assertEquals(2f, zoom)
    }

    @Test
    fun fingersMovingTogetherZoomOut() {
        val zoom = zoomBetween(points(0f to 0f, 20f to 0f), points(0f to 0f, 10f to 0f))
        assertEquals(0.5f, zoom)
    }

    @Test
    fun slidingTwoFingersWithoutSpreadingThemDoesNotZoom() {
        val zoom = zoomBetween(points(0f to 0f, 10f to 0f), points(100f to 50f, 110f to 50f))
        assertEquals(1f, zoom)
    }

    @Test
    fun oneFingerNeverZooms() {
        assertEquals(1f, zoomBetween(points(0f to 0f), points(50f to 50f)))
        assertEquals(1f, zoomBetween(points(0f to 0f, 10f to 0f), points(50f to 50f)))
    }

    @Test
    fun fingersOnTopOfEachOtherDoNotDivideByZero() {
        // The moment a second finger lands it can be a pixel from the first; a naive ratio would
        // send the zoom to infinity and the viewport with it.
        val zoom = zoomBetween(points(0f to 0f, 0f to 0f), points(0f to 0f, 100f to 0f))
        assertEquals(1f, zoom)
        assertTrue(zoom.isFinite())
    }

    @Test
    fun theHotCornerSquaresAreWhereTheHitTestSaysTheyAre() {
        val width = 1000f
        val height = 500f
        val side = hotCornerSide(width, height)
        assertEquals(25f, side)

        val corners = hotCornerTopLefts(width, height)
        assertEquals(4, corners.size, "all four corners are live, so all four are drawn")

        // Every square's own middle is a hit, and the middle of the view is not.
        for (corner in corners) {
            val x = corner.x + side / 2f
            val y = corner.y + side / 2f
            assertTrue(hitsHotCorner(x, y, width, height), "($x, $y) should be a hot corner")
        }
        assertTrue(!hitsHotCorner(width / 2f, height / 2f, width, height))
    }

    @Test
    fun theSquaresStayInsideTheView() {
        val width = 300f
        val height = 800f
        val side = hotCornerSide(width, height)
        for (corner in hotCornerTopLefts(width, height)) {
            assertTrue(corner.x >= 0f && corner.y >= 0f)
            assertTrue(corner.x + side <= width && corner.y + side <= height)
        }
    }
}
