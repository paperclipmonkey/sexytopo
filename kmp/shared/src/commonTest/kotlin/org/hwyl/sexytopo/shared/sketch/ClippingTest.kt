package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deciding what not to draw. Getting it wrong either wastes the work or loses the cave. */
class ClippingTest {

    private val topLeft = Coord2D(0f, 0f)
    private val bottomRight = Coord2D(100f, 100f)

    private fun outside(x0: Float, y0: Float, x1: Float, y1: Float) =
        whollyOutside(Coord2D(x0, y0), Coord2D(x1, y1), topLeft, bottomRight)

    @Test
    fun aLineInsideTheScreenIsDrawn() {
        assertFalse(outside(10f, 10f, 90f, 90f))
    }

    @Test
    fun aLineWithOneEndInsideIsDrawn() {
        assertFalse(outside(50f, 50f, 500f, 50f))
    }

    @Test
    fun aLineCrossingRightThroughIsDrawn() {
        assertFalse(outside(-500f, 50f, 500f, 50f), "both ends outside, but it crosses the screen")
    }

    @Test
    fun aLineOffOneSideIsNotDrawn() {
        assertTrue(outside(-50f, 10f, -10f, 90f), "both ends left of the screen")
        assertTrue(outside(110f, 10f, 500f, 90f), "both right")
        assertTrue(outside(10f, -50f, 90f, -10f), "both above")
        assertTrue(outside(10f, 110f, 90f, 500f), "both below")
    }

    @Test
    fun aLineOffACornerIsNotDrawnWhenBothEndsShareASide() {
        // Both ends are above *and* left; sharing either bit is enough.
        assertTrue(outside(-50f, -50f, -10f, -10f))
    }

    @Test
    fun aLineNearACornerIsDrawnRatherThanRiskedEvenIfItMissesIt() {
        // One end above, the other to the left: no shared bit, so this is drawn although it in
        // fact passes outside the corner. Conservative in the only direction that is safe - the
        // cost of drawing a line nobody sees is a draw call, and the cost of hiding one that
        // should be there is a passage missing from the survey on screen.
        assertFalse(outside(10f, -10f, -10f, 10f))
    }

    @Test
    fun aPointOnTheEdgeIsInside() {
        assertFalse(outside(0f, 0f, 0f, 0f), "the boundary counts as on screen, as in the Java")
        assertFalse(outside(100f, 100f, 200f, 200f))
    }

    @Test
    fun theCornersCanBeGivenInAnyOrder() {
        val line = Coord2D(-50f, 10f) to Coord2D(-10f, 90f)
        assertTrue(whollyOutside(line.first, line.second, topLeft, bottomRight))
        assertTrue(
            whollyOutside(line.first, line.second, bottomRight, topLeft),
            "bitcode takes min and max of the two corners, so which is which does not matter",
        )
    }
}
