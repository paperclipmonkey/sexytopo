package org.hwyl.sexytopo.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which characters the bundled font has, settled by asking Skia instead of guessing.
 *
 * This exists because the guess was wrong, and wrong in a way that cost the app real typography.
 * A tick beside a checked menu item shipped once as "✓" and came out an empty box on every
 * platform, and the rule drawn from that — *distrust anything outside Latin-1* — was too broad by
 * most of a font. Liberation Sans has 2388 characters, including the whole of the General
 * Punctuation the rule condemned. What it has not got is Dingbats, which is where "✓" lives.
 *
 * So the marks that are drawn are drawn because they have to be, the marks that are typed are
 * typed because they can be, and this test is what makes both of those statements checkable rather
 * than remembered. If the font is ever swapped or subsetted, it names the characters that changed.
 */
class FontCoverageTest {

    /** Every one of these is typed somewhere in the app, and every one of them draws. */
    @Test
    fun theCharactersTheAppTypesAreOnesTheFontHas() {
        // Bullets and chevrons in the About box and the overflow menu; the dash and the ellipsis
        // in menu labels; the multiplication sign that closes a dialog; the arrow in the manual.
        val typed = "•›‹–—…×→‘’“”"
        assertEquals("", FontCoverage.missingFrom(typed), "typed in the app and cannot be drawn")
    }

    /** And every mark this app draws by hand is one it genuinely could not type. */
    @Test
    fun theMarksTheAppDrawsAreOnesTheFontHasNot() {
        // MenuMarks.CheckDot stands in for this one.
        assertTrue(!FontCoverage.has('✓'), "the font has ✓ after all, so stop drawing it")
        // The overflow button stands in for this one.
        assertTrue(!FontCoverage.has('⋮'), "the font has ⋮ after all, so stop drawing it")
        // And the prettier cross the close button does not use.
        assertTrue(!FontCoverage.has('✕'), "the font has ✕ after all, so use it")
    }

    /**
     * Latin-1 was never the boundary; the font's own cmap is.
     *
     * Liberation Sans draws 57 of the 112 characters of General Punctuation — every one anybody
     * types, and none of the reversed pilcrows and dotted crosses. Asserting the number rather
     * than a vague "most" makes this a change detector: subset the font and it says so.
     */
    @Test
    fun theFontIsMuchWiderThanLatin1() {
        val block = (0x2000..0x206F).map { it.toChar() }.joinToString("")
        val present = block.length - FontCoverage.missingFrom(block).length
        assertEquals(57, present, "the bundled font's General Punctuation coverage changed")
    }
}
