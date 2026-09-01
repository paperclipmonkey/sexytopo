package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The eraser rubbing along a drag rather than only where it landed.
 *
 * The behaviour a surveyor expects of anything shaped like a rubber, and *not* what the Android app
 * does: `GraphView.handleErase` works under `case ACTION_DOWN` and its `ACTION_MOVE` case is a bare
 * `break`. This is the arithmetic half of the departure, out here where it can be tested — the
 * gesture that feeds it cannot be, without a finger.
 */
class RubAlongTest {

    private fun rubsFor(from: Coord2D, to: Coord2D, step: Float, maxSteps: Int = 64): List<Coord2D> {
        val visited = mutableListOf<Coord2D>()
        rubAlong(from, to, step, maxSteps) { visited.add(it); false }
        return visited
    }

    /**
     * The far end is always rubbed, so the eraser reaches exactly as far as the finger did.
     *
     * A move shorter than the eraser's own radius needs nothing in between: the circle rubbed at
     * the start already covers the ground.
     */
    @Test
    fun aShortMoveRubsOnlyWhereTheFingerNowIs() {
        val rubs = rubsFor(Coord2D(0f, 0f), Coord2D(0.5f, 0f), step = 1f)

        assertEquals(listOf(Coord2D(0.5f, 0f)), rubs)
    }

    /**
     * A long move is filled in, because that is the whole point.
     *
     * Ten metres at a one-metre eraser is nine gaps that would otherwise be missed. A finger
     * crossing the screen is sampled a dozen times a second, so without this a rub takes out one
     * stroke in three and leaves a wall that looks deliberately dashed.
     */
    @Test
    fun aLongMoveIsFilledInAtTheErasersOwnWidth() {
        val rubs = rubsFor(Coord2D(0f, 0f), Coord2D(10f, 0f), step = 1f)

        assertEquals(Coord2D(10f, 0f), rubs.last(), "the far end should be rubbed")
        assertTrue(rubs.size >= 10, "ten metres at a one-metre step needs ten rubs, got ${rubs.size}")
        // No gap wider than the eraser, which is what "continuous" means here.
        val gaps = rubs.zipWithNext { a, b -> (b - a).mag() }
        assertTrue(gaps.all { it <= 1.0001f }, "a gap wider than the eraser was left: $gaps")
        assertTrue((rubs.first() - Coord2D(0f, 0f)).mag() <= 1.0001f, "the near end was skipped")
    }

    /**
     * The starting point is not rubbed again.
     *
     * The caller has already done it — as the touch-down, or as the previous move's endpoint — so
     * repeating it would double the work of a slow, careful rub, which is exactly the rub somebody
     * doing detailed work is performing.
     */
    @Test
    fun theStartIsNotRubbedTwice() {
        val rubs = rubsFor(Coord2D(0f, 0f), Coord2D(4f, 0f), step = 1f)

        assertFalse(Coord2D(0f, 0f) in rubs, "the start was rubbed again: $rubs")
    }

    /**
     * A flick across a zoomed-out cave is bounded rather than allowed to run away.
     *
     * Each rub is a nearest-detail search over the whole sketch, so an unbounded fill of a
     * thousand-metre drag would be thousands of them inside one frame. Coarser than the eraser
     * beats a frozen app, and that is the only case where it bites.
     */
    @Test
    fun aFlickAcrossTheWholeCaveIsBounded() {
        val rubs = rubsFor(Coord2D(0f, 0f), Coord2D(10_000f, 0f), step = 0.1f, maxSteps = 8)

        assertEquals(9, rubs.size, "the cap should bound the work, plus the endpoint")
        assertEquals(Coord2D(10_000f, 0f), rubs.last(), "the far end is rubbed whatever the cap")
    }

    /** A finger that has not moved still rubs where it is, and only there. */
    @Test
    fun aStationaryFingerRubsOnce() {
        assertEquals(listOf(Coord2D(3f, 3f)), rubsFor(Coord2D(3f, 3f), Coord2D(3f, 3f), step = 1f))
    }

    /** Anything erased anywhere along the rub is reported, not just at the end. */
    @Test
    fun somethingErasedInTheMiddleIsReported() {
        var calls = 0
        val erased =
            rubAlong(Coord2D(0f, 0f), Coord2D(10f, 0f), 1f) {
                calls++
                calls == 3
            }

        assertTrue(erased, "a deletion partway along the rub was not reported")
    }

    /** And a rub that removed nothing says so, so the caller does not mark the survey edited. */
    @Test
    fun aRubOverBlankPaperReportsNothing() {
        assertFalse(rubAlong(Coord2D(0f, 0f), Coord2D(10f, 0f), 1f) { false })
    }
}
