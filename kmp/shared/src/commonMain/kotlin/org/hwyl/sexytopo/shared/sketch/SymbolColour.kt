package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol

/**
 * What colour a symbol is stamped in, which is not always the colour the brush is set to.
 *
 * The active colour is quietly overridden with blue for the water symbol when
 * `SketchPreferences.Toggle.BLUE_WATER` is on — and it is on by default.
 */
fun colourForSymbol(symbolName: String, brush: Colour, blueWater: Boolean): Colour =
    if (blueWater && isWaterSymbol(symbolName)) Colour.BLUE else brush

fun isWaterSymbol(symbolName: String): Boolean =
    Symbol.byTherionName(symbolName) == Symbol.WATER_FLOW
