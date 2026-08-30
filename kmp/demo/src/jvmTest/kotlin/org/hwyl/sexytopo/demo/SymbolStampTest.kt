package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stamping a UIS symbol on the sketch.
 *
 * The artwork and the parser are tested in the shared module; what is left here is the bearing a
 * drag produces, which is the part with a coordinate-system trap in it.
 */
class SymbolStampTest {

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    /**
     * Screen y grows downwards and compass bearings run clockwise from north, so a drag *up* the
     * screen is 0 and a drag right is 90. Getting this wrong points every water-flow arrow the
     * opposite way, which is worse than not drawing it.
     */
    @Test
    fun aDragUpTheScreenIsNorth() {
        assertNear(0f, bearingOf(Offset(0f, -10f)))
        assertNear(90f, bearingOf(Offset(10f, 0f)))
        assertNear(180f, bearingOf(Offset(0f, 10f)))
        assertNear(270f, bearingOf(Offset(-10f, 0f)))
        assertNear(45f, bearingOf(Offset(10f, -10f)))
    }

    /** A tap is a drag of no length, and leaves the symbol upright rather than snapping it. */
    @Test
    fun aTapLeavesTheSymbolUpright() {
        assertEquals(0f, bearingOf(Offset.Zero))
    }

    @Test
    fun aStampedSymbolCarriesItsTherionNameAndAngle() {
        val editor = SketchEditor()

        editor.addSymbol(Coord2D(4f, -2f), Symbol.WATER_FLOW.therionName, size = 0.8f, angle = 135f)

        val stamped = editor.sketch.symbolDetails.single()
        assertEquals("water-flow", stamped.symbolName)
        assertEquals(Coord2D(4f, -2f), stamped.position)
        assertEquals(135f, stamped.angle)
        assertEquals(0.8f, stamped.size)
    }

    /** One undo step, like every other sketch item. */
    @Test
    fun aStampCanBeUndone() {
        val editor = SketchEditor()
        editor.addSymbol(Coord2D.ORIGIN, Symbol.BLOCKS.therionName, size = 0.5f)

        editor.undo()

        assertTrue(editor.sketch.symbolDetails.isEmpty())
    }

    /** Every symbol has artwork the canvas can draw, so none of them stamps as an empty mark. */
    @Test
    fun everySymbolHasADrawablePath() {
        for (symbol in Symbol.entries) {
            val path = symbolPaths[symbol]
            assertTrue(path != null && !path.isEmpty, "${symbol.name} has no drawable path")
        }
    }

    /**
     * The name written into the sketch is the name the canvas looks the artwork up by. If those
     * two ever disagreed, every stamped symbol would silently draw as the fallback circle.
     */
    @Test
    fun aStampedNameResolvesBackToItsSymbol() {
        for (symbol in Symbol.entries) {
            assertEquals(symbol, Symbol.byTherionName(symbol.therionName))
        }
    }
}
