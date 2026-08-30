package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol

/**
 * What colour a symbol is stamped in, which is not always the colour the brush is set to.
 *
 * Ported from `Sketch.addSymbolDetail`, which quietly overrides the active colour with blue for the
 * water symbol when `SketchPreferences.Toggle.BLUE_WATER` is on — and it is on by default. Water is
 * drawn blue on every published cave survey there has ever been, and a surveyor who has the brush
 * set to black for wall outlines should not have to change it and change it back to mark a stream.
 *
 * It sits here rather than on [Symbol] because it is a question about a *preference*, and [Symbol]
 * is the artwork; and it is in the shared module rather than in the canvas because the rule is the
 * app's, not the demo's, and the exporters read the colour it produces.
 */
fun colourForSymbol(symbolName: String, brush: Colour, blueWater: Boolean): Colour =
    if (blueWater && isWaterSymbol(symbolName)) Colour.BLUE else brush

/** `Symbol.isWater`, which the Java defines as "is the one water symbol" and nothing more. */
fun isWaterSymbol(symbolName: String): Boolean =
    Symbol.byTherionName(symbolName) == Symbol.WATER_FLOW
