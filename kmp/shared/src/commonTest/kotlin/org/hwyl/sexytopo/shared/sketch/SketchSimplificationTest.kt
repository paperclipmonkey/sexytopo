package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ported directly from `Space2DUtilsTest`, expectations included. */
class SketchSimplificationTest {

    @Test
    fun simplifyingAnEmptyPathReturnsIt() {
        assertEquals(emptyList(), simplify(emptyList(), 1f))
    }

    @Test
    fun simplifyingASinglePointGivesALineWithCoincidentEnds() {
        val path = listOf(Coord2D(0f, 0f))
        assertEquals(listOf(Coord2D(0f, 0f), Coord2D(0f, 0f)), simplify(path, 1f))
    }

    @Test
    fun simplifyingATwoPointLineLeavesItAlone() {
        val path = listOf(Coord2D(0f, 0f), Coord2D(10f, 0f))
        assertEquals(path, simplify(path, 0.01f))
    }

    @Test
    fun aStraightRunOfPointsCollapsesToItsEnds() {
        val path =
            listOf(
                Coord2D(0f, 0f),
                Coord2D(10f, 0f),
                Coord2D(20f, 0f),
                Coord2D(30f, 0f),
                Coord2D(40f, 0f),
                Coord2D(50f, 0f),
            )
        val simplified = simplify(path, simplificationEpsilon(50f, 50f))
        assertEquals(listOf(Coord2D(0f, 0f), Coord2D(50f, 0f)), simplified)
    }

    @Test
    fun cornersSurvive() {
        val path =
            listOf(
                Coord2D(0f, 0f),
                Coord2D(5f, 0f),
                Coord2D(10f, 0f),
                Coord2D(10f, 5f),
                Coord2D(10f, 10f),
            )
        val simplified = simplify(path, simplificationEpsilon(10f, 10f))
        assertEquals(listOf(Coord2D(0f, 0f), Coord2D(10f, 0f), Coord2D(10f, 10f)), simplified)
    }

    @Test
    fun aDensifiedCircleSurvivesItsOwnEpsilonButCollapsesToADiamondUnderAHarshOne() {
        val twoPi = 2 * PI.toFloat()
        val step = twoPi / 12
        val radius = 5f
        val offset = 10f

        // theta accumulates in double and the trig is done in double, as in the Java, so that the
        // loop produces exactly twelve points rather than eleven or thirteen.
        val path = mutableListOf<Coord2D>()
        var theta = 0.0
        while (theta < twoPi) {
            path.add(
                Coord2D(
                    offset + radius * cos(theta).toFloat(),
                    offset - radius * sin(theta).toFloat(),
                ),
            )
            theta += step
        }
        path.add(path[0])

        // At the sketch's own tolerance (10m box → 0.02m) every point is worth keeping.
        assertEquals(path.toList(), simplify(path, simplificationEpsilon(10f, 10f)))

        // A 4m tolerance on a 5m-radius circle leaves the four extremes.
        assertEquals(
            listOf(
                Coord2D(15f, 10f),
                Coord2D(10f, 5f),
                Coord2D(5f, 10f),
                Coord2D(10f, 15f),
                Coord2D(15f, 10f),
            ),
            simplify(path, 4f),
        )
    }

    @Test
    fun theEpsilonIsAFiveHundredthOfTheStrokeWithAMillimetreFloor() {
        assertEquals(0.1f, simplificationEpsilon(50f, 20f))
        assertEquals(0.1f, simplificationEpsilon(20f, 50f))
        // Anything under half a metre across is floored at 1mm, so tiny strokes keep their shape.
        assertEquals(0.001f, simplificationEpsilon(0.2f, 0.2f))
        assertEquals(0.001f, simplificationEpsilon(0f, 0f))
    }

    @Test
    fun theEpsilonOfAStrokeComesFromItsOwnBoundingBox() {
        val stroke = listOf(Coord2D(-5f, 2f), Coord2D(0f, 2.5f), Coord2D(5f, 2f))
        // Box is 10 wide, 0.5 high → 10 / 500.
        assertEquals(0.02f, simplificationEpsilon(stroke))
    }
}
