package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolColourTest {

    @Test
    fun aStreamIsBlueWhateverTheBrushIsSetTo() {
        assertEquals(
            Colour.BLUE,
            colourForSymbol(Symbol.WATER_FLOW.therionName, Colour.BLACK, blueWater = true),
        )
    }

    @Test
    fun everyOtherSymbolIsStampedInTheBrushColour() {
        assertEquals(
            Colour.BLACK,
            colourForSymbol(Symbol.BLOCKS.therionName, Colour.BLACK, blueWater = true),
        )
    }

    @Test
    fun turningTheRuleOffLeavesEvenWaterInTheBrushColour() {
        assertEquals(
            Colour.RED,
            colourForSymbol(Symbol.WATER_FLOW.therionName, Colour.RED, blueWater = false),
        )
    }

    @Test
    fun aSymbolThisVersionDoesNotKnowIsNotWater() {
        // A .th2 from a newer app, or a typo. Answering "water" to an unknown name would repaint
        // somebody's sketch blue on the strength of not recognising it.
        assertFalse(isWaterSymbol("u:something-from-2030"))
        assertEquals(
            Colour.BLACK,
            colourForSymbol("u:something-from-2030", Colour.BLACK, blueWater = true),
        )
    }

    @Test
    fun exactlyOneSymbolIsWater() {
        assertTrue(isWaterSymbol(Symbol.WATER_FLOW.therionName))
        assertEquals(
            1,
            Symbol.entries.count { isWaterSymbol(it.therionName) },
            "the Java's isWater() is an identity test against WATER_FLOW alone",
        )
    }
}
