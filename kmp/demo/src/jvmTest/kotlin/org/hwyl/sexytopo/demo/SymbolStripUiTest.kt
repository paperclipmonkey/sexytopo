package org.hwyl.sexytopo.demo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The selected symbol can be seen.
 *
 * Reported from a phone: the symbol in hand was a plain white square. The strip drew its glyphs in
 * white on its dark green, `selectSymbol`'s `buttonHighlight` is white, and white on white is
 * nothing. The Android app has no such problem because its `symbol_uis_*.xml` are black and
 * `Symbol.createDrawable` never tints them, lit or not.
 *
 * Checked by looking: the strip is opened the way a surveyor opens it and the lit square's own
 * pixels are read back, so this fails on the picture rather than on the colour constant.
 */
@OptIn(ExperimentalTestApi::class)
class SymbolStripUiTest {

    private fun ImageBitmap.count(predicate: (Int, Int, Int) -> Boolean): Int {
        val pixels = toPixelMap()
        var n = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (predicate((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())) n++
            }
        }
        return n
    }

    private fun ImageBitmap.dark() = count { r, g, b -> r < 90 && g < 90 && b < 90 }
    private fun ImageBitmap.white() = count { r, g, b -> r > 240 && g > 240 && b > 240 }
    private fun ImageBitmap.stripGreen() = count { r, g, b -> r in 0x30..0x44 && g in 0x4d..0x61 && b in 0x2e..0x42 }

    @Test
    fun theSymbolInHandIsDrawnOnItsHighlightNotLostInIt() = runComposeUiTest {
        setContent { App(survey = ExampleSurvey.create()) }
        // The first tap on the symbol tool opens the strip, and the tool's own symbol lights up.
        onNodeWithTag("symbol-tool").performClick()

        val lit = onNodeWithTag("symbol-entrance").captureToImage()
        assertTrue(lit.white() > 200, "the selected square should carry buttonHighlight: ${lit.white()} white pixels")
        // The entrance is a one-pixel-stroke triangle in a 32dp square, so "drawn" is a couple of
        // dozen dark pixels; before the fix it was none, which is the whole difference.
        assertTrue(lit.dark() > 10, "the selected symbol should be drawn on its highlight: ${lit.dark()} dark pixels")
    }

    @Test
    fun theOtherSymbolsAreBlackOnTheStripAsTheAppDrawsThem() = runComposeUiTest {
        setContent { App(survey = ExampleSurvey.create()) }
        onNodeWithTag("symbol-tool").performClick()

        val unlit = onNodeWithTag("symbol-gradient").captureToImage()
        assertTrue(unlit.stripGreen() > 200, "an unselected square is the strip's own green: ${unlit.stripGreen()}")
        assertTrue(unlit.dark() > 10, "its glyph should be the drawable's black: ${unlit.dark()} dark pixels")
        assertTrue(unlit.white() == 0, "nothing on an unselected square should be white: ${unlit.white()}")
    }
}
