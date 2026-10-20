package org.hwyl.sexytopo.shared.manual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Android app's `ReleaseNotesFormatterTest`, case for case, and the step it does not have. */
class ReleaseNotesFormatterTest {

    @Test
    fun headingsBecomeH2() {
        val html = releaseNotesToHtml("# 2026-09-25 1.13.1\n")
        assertTrue("<h2>2026-09-25 1.13.1</h2>" in html)
    }

    @Test
    fun consecutiveItemsShareOneList() {
        val html = releaseNotesToHtml("# 1.0\n- One\n- Two\n\n# 0.9\n- Three\n")
        assertTrue("<ul>\n<li>One</li>\n<li>Two</li>\n</ul>\n<h2>0.9</h2>" in html)
        assertTrue("<ul>\n<li>Three</li>\n</ul>\n</body>" in html)
    }

    @Test
    fun htmlIsEscaped() {
        val html = releaseNotesToHtml("- Text containing < or > & stuff\n")
        assertTrue("<li>Text containing &lt; or &gt; &amp; stuff</li>" in html)
    }

    @Test
    fun otherLinesBecomeParagraphs() {
        val html = releaseNotesToHtml("Some prose\n")
        assertTrue("<p>Some prose</p>" in html)
    }

    /**
     * Upstream hands its page to a web view, which reads anything. This one goes through
     * [parseManual], which throws on what it does not know, so the page has to be one it reads,
     * escaped text and accented names included.
     */
    @Test
    fun thePageReadsBackAsHeadingsListsAndParagraphs() {
        val blocks =
            parseManual(releaseNotesToHtml("# 1.0\n- Fix < and > (thanks Zoë)\n\nSome prose\n"))
        assertEquals(
            listOf(
                ManualBlock.Heading(2, null, "1.0"),
                ManualBlock.Listing(listOf(ManualItem(listOf(ManualSpan("Fix < and > (thanks Zoë)"))))),
                ManualBlock.Paragraph(listOf(ManualSpan("Some prose"))),
            ),
            blocks,
        )
    }
}
