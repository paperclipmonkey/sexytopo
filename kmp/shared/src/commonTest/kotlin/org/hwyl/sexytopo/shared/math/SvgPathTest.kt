package org.hwyl.sexytopo.shared.math

import org.hwyl.sexytopo.shared.model.sketch.Symbol
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SVG path data, which the cave symbols are drawn from.
 *
 * The grammar is terse on purpose — separators are optional wherever the next character cannot
 * continue the current number, and a command repeats when more numbers follow it — so most of what
 * can go wrong here is a shape that is subtly wrong rather than a crash. Hence the coordinates are
 * checked rather than the segment count.
 */
class SvgPathTest {

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.001f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    @Test
    fun absoluteMovesAndLines() {
        assertEquals(
            listOf(
                SvgSegment.MoveTo(15f, 29f),
                SvgSegment.LineTo(20f, 9f),
                SvgSegment.LineTo(25f, 29f),
                SvgSegment.Close,
            ),
            parseSvgPath("M15,29L20,9L25,29Z"),
        )
    }

    /** Lower case is relative to where the pen is, which is how most of the symbols are written. */
    @Test
    fun relativeCommandsAccumulate() {
        assertEquals(
            listOf(
                SvgSegment.MoveTo(5f, 12.5f),
                SvgSegment.LineTo(35f, 12.5f),
                SvgSegment.LineTo(35f, 17.5f),
            ),
            parseSvgPath("m5,12.5l30,0l0,5"),
        )
    }

    /**
     * A command letter followed by more numbers than it needs repeats. `symbol_uis_helictite` is
     * one path with a second moveto in the middle, so getting this wrong loses half the symbol.
     */
    @Test
    fun aRepeatedCommandDoesNotNeedItsLetterAgain() {
        assertEquals(
            listOf(
                SvgSegment.MoveTo(20f, 8f),
                SvgSegment.LineTo(20f, 32f),
                SvgSegment.MoveTo(12f, 8f),
                SvgSegment.LineTo(12f, 20f),
                SvgSegment.LineTo(28f, 20f),
                SvgSegment.LineTo(28f, 32f),
            ),
            parseSvgPath("M20,8L20,32M12,8L12,20 28,20 28,32"),
        )
    }

    /** A second coordinate pair after a moveto is a lineto, not another move. */
    @Test
    fun aMovetoWithExtraPairsDrawsLines() {
        assertEquals(
            listOf(
                SvgSegment.MoveTo(1f, 1f),
                SvgSegment.LineTo(2f, 2f),
                SvgSegment.LineTo(3f, 3f),
            ),
            parseSvgPath("M1,1 2,2 3,3"),
        )
    }

    @Test
    fun cubicsKeepTheirControlPoints() {
        assertEquals(
            listOf(SvgSegment.MoveTo(0f, 0f), SvgSegment.CubicTo(1f, 2f, 3f, 4f, 5f, 6f)),
            parseSvgPath("M0,0c1,2 3,4 5,6"),
        )
    }

    /** A quadratic widens to a cubic exactly, with controls two thirds of the way along. */
    @Test
    fun quadraticsBecomeExactCubics() {
        val cubic = parseSvgPath("M0,0Q3,0 3,3")[1] as SvgSegment.CubicTo

        assertNear(2f, cubic.x1)
        assertNear(0f, cubic.y1)
        assertNear(3f, cubic.x2)
        assertNear(1f, cubic.y2)
        assertNear(3f, cubic.x)
        assertNear(3f, cubic.y)
    }

    /** Separators are optional: "2.5.5" is two numbers, and a minus sign starts one. */
    @Test
    fun numbersRunTogetherWithoutSeparators() {
        assertEquals(
            listOf(SvgSegment.MoveTo(2.5f, 0.5f), SvgSegment.LineTo(1.5f, -0.5f)),
            parseSvgPath("M2.5.5L1.5-.5"),
        )
    }

    // -------------------------------------------------------------------------------------
    // Arcs, which three of the symbols need
    // -------------------------------------------------------------------------------------

    /**
     * A quarter circle, checked by where it ends and by every approximating curve's endpoints
     * staying on the circle. Getting the endpoint right and the middle wrong is the characteristic
     * arc bug, and it draws a symbol that is the wrong shape rather than one that fails.
     */
    @Test
    fun aQuarterCircleArcStaysOnTheCircle() {
        val cubics = parseSvgPath("M10,0A10,10 0 0 1 0,10").filterIsInstance<SvgSegment.CubicTo>()

        assertTrue(cubics.isNotEmpty())
        val last = cubics.last()
        assertNear(0f, last.x, 0.01f)
        assertNear(10f, last.y, 0.01f)
        for (cubic in cubics) assertNear(10f, hypot(cubic.x, cubic.y), 0.02f)
    }

    /** Zero radii degenerate to a straight line, which the specification requires. */
    @Test
    fun aZeroRadiusArcIsALine() {
        assertEquals(
            listOf(SvgSegment.MoveTo(0f, 0f), SvgSegment.LineTo(5f, 5f)),
            parseSvgPath("M0,0A0,0 0 0 1 5,5"),
        )
    }

    /** Radii too small to span the endpoints are grown until they just reach, per F.6.6. */
    @Test
    fun tooSmallRadiiAreGrownRatherThanRefused() {
        val last =
            parseSvgPath("M0,0A1,1 0 0 1 10,0").filterIsInstance<SvgSegment.CubicTo>().last()

        assertNear(10f, last.x, 0.01f)
        assertNear(0f, last.y, 0.01f)
    }

    // -------------------------------------------------------------------------------------
    // The symbols themselves
    // -------------------------------------------------------------------------------------

    /**
     * Every symbol parses to something drawable, and every coordinate lands on the grid the artwork
     * was drawn for. A path that parsed to nothing, or that wandered off the viewport, would be a
     * symbol that is invisible or enormous — and neither shows up in a compile.
     */
    @Test
    fun everySymbolParsesOntoItsGrid() {
        for (symbol in Symbol.entries) {
            assertTrue(symbol.paths.isNotEmpty(), "${symbol.name} has no artwork")
            for (path in symbol.paths) {
                val segments = parseSvgPath(path)
                assertTrue(segments.isNotEmpty(), "${symbol.name} parsed to nothing: $path")
                assertTrue(
                    segments.first() is SvgSegment.MoveTo,
                    "${symbol.name} does not start with a move: $path",
                )
                for (point in segments.flatMap(::pointsOf)) {
                    assertTrue(
                        point in -Symbol.VIEWPORT..(2 * Symbol.VIEWPORT),
                        "${symbol.name} draws at $point, off its ${Symbol.VIEWPORT} grid: $path",
                    )
                }
            }
        }
    }

    /** Therion names are the part that has to be exactly right to mean anything to another tool. */
    @Test
    fun therionNamesAreUniqueAndLookUpBothWays() {
        val names = Symbol.entries.map { it.therionName }
        assertEquals(names.size, names.toSet().size, "two symbols share a Therion name")

        assertEquals(Symbol.ENTRANCE, Symbol.byTherionName("entrance"))
        assertEquals(Symbol.TOO_TIGHT, Symbol.byTherionName("narrow-end"))
        assertEquals(null, Symbol.byTherionName("not-a-symbol"))
    }

    private fun pointsOf(segment: SvgSegment): List<Float> =
        when (segment) {
            is SvgSegment.MoveTo -> listOf(segment.x, segment.y)
            is SvgSegment.LineTo -> listOf(segment.x, segment.y)
            is SvgSegment.CubicTo ->
                listOf(segment.x1, segment.y1, segment.x2, segment.y2, segment.x, segment.y)
            SvgSegment.Close -> emptyList()
        }
}
