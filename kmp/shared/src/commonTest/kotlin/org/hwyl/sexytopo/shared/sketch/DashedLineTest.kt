package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dashes that say "this leg is going into the page". */
class DashedLineTest {

    @Test
    fun aLineIsDashedHalfOnAndHalfOff() {
        val dashes = dashesAlong(Coord2D(0f, 0f), Coord2D(80f, 0f), dashLength = 4f)

        assertEquals(10, dashes.size, "80 pixels of line at 4 on and 4 off")
        for ((from, to) in dashes) {
            assertEquals(4f, getDistance(from, to), 0.001f)
        }
    }

    @Test
    fun theRunStartsAtTheFarEndOfTheLeg() {
        // The Java swaps the ends before laying the dashes out, so it is the station the leg
        // arrives at that lands on ink rather than in a gap. Getting this backwards is invisible
        // on a long leg and obvious on a short one.
        val dashes = dashesAlong(Coord2D(0f, 0f), Coord2D(80f, 0f), dashLength = 4f)

        assertEquals(80f, dashes.first().first.x, 0.001f)
        assertTrue(dashes.first().second.x < dashes.first().first.x, "and walks back towards start")
    }

    @Test
    fun theDashesAreEvenlySpacedAlongTheLine() {
        val dashes = dashesAlong(Coord2D(0f, 0f), Coord2D(40f, 0f), dashLength = 4f)

        assertEquals(5, dashes.size)
        // Each dash begins two dash lengths before the last one did: four of ink, four of gap.
        val starts = dashes.map { it.first.x }
        assertEquals(listOf(40f, 32f, 24f, 16f, 8f), starts)
    }

    @Test
    fun aLegTooShortToDashIsNotDrawnAtAll() {
        // Deliberate, and the Java's: at the zoom where a pitch is a few pixels long, one stubby
        // dash would read as a solid leg and say the opposite of what dashing it means.
        assertEquals(0, dashesAlong(Coord2D(0f, 0f), Coord2D(7f, 0f), 4f).size)
    }

    @Test
    fun aLegOfNoLengthHasNoDirectionAndProducesNothing() {
        // normalise() of a zero vector; the guard is what stops this being NaN coordinates.
        assertEquals(0, dashesAlong(Coord2D(5f, 5f), Coord2D(5f, 5f), 4f).size)
    }

    @Test
    fun aDiagonalIsDashedAlongItsOwnDirection() {
        val dashes = dashesAlong(Coord2D(0f, 0f), Coord2D(30f, 40f), dashLength = 5f)

        assertEquals(5, dashes.size, "a 50-pixel line at 5 on and 5 off")
        val (from, to) = dashes.first()
        assertEquals(30f, from.x, 0.001f)
        assertEquals(40f, from.y, 0.001f)
        assertEquals(27f, to.x, 0.001f, "three across and four down for every five along")
        assertEquals(36f, to.y, 0.001f)
    }

    @Test
    fun aDashLengthOfNothingIsRefusedRatherThanLoopingForever() {
        assertEquals(0, dashesAlong(Coord2D(0f, 0f), Coord2D(80f, 0f), 0f).size)
    }
}
