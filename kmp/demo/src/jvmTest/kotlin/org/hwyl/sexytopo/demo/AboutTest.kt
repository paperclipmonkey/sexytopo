package org.hwyl.sexytopo.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The About text, checked because leaving it out is the failure mode and nothing else would notice.
 *
 * This build carries several thousand lines of somebody else's GPL-3.0 code. A test that the names
 * and the licence are present is not testing a feature; it is testing that a later edit does not
 * quietly drop them, which is exactly what happened to them the first time.
 */
class AboutTest {

    private val text = ABOUT.flatMap { it.lines }.joinToString("\n")

    @Test
    fun theAuthorIsNamedAndSoAreTheContributors() {
        assertTrue(text.contains("Rich Smith"), "the app's author is not in its About box")
        // The eight named in `about_text.xml`, each of whom holds copyright in what is ported here.
        for (contributor in
            listOf(
                "Dan Workman",
                "Phil Underwood",
                "Siwei Tian",
                "Olly Legg",
                "Michael Glazer",
                "Thomas Holder",
                "Damian Ivereigh",
                "Andrew Atkinson",
            )) {
            assertTrue(text.contains(contributor), "$contributor is not credited")
        }
    }

    @Test
    fun theCalibrationAuthorIsThanked() {
        // Beat Heeb's solver is ported line for line, iteration counts and all, and his is the
        // one name in the thanks that is also a copyright holder in this code.
        assertTrue(text.contains("Beat Heeb"))
    }

    @Test
    fun theLicenceIsStated() {
        assertTrue(text.contains("GNU General Public License version 3"), "no licence")
        assertTrue(text.contains("WITHOUT ANY WARRANTY"), "no warranty disclaimer")
        assertTrue(text.contains("http://www.gnu.org/licenses/"), "nowhere to read the licence")
    }

    @Test
    fun itSaysWhatThisBuildIsAndIsNot() {
        // Passing a proof of concept off as the app would be wrong in both directions: it is not
        // what Rich Smith maintains, and what it cannot do is not his to answer for.
        assertTrue(text.contains("proof of concept"))
        assertTrue(text.contains("not supported by its author"))
        assertTrue(text.contains("No instrument has ever been connected"))
    }

    /**
     * The bundled Liberation Sans has no General Punctuation block, so a bullet or a smart quote
     * renders as an empty box on every platform — which this port shipped once already, as a tick
     * beside every checked menu item. Latin-1 only, everywhere in this text.
     */
    @Test
    fun everyCharacterIsOneTheBundledFontHas() {
        val outside = text.filter { it.code > 0xFF }
        assertEquals("", outside, "characters the bundled font cannot draw")

        val headings = ABOUT.joinToString("") { it.heading }
        assertEquals("", headings.filter { it.code > 0xFF })
    }
}
