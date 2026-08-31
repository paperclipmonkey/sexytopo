package org.hwyl.sexytopo.shared.manual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The manual reader, on the shapes the guide actually contains.
 *
 * The drift guard — the real file, every tag in it — is `ManualContentTest` in the demo module,
 * where the shipped resource can be read off disk. This is the reader itself, and it runs on all
 * three targets because the app draws the manual on all three.
 */
class ManualParserTest {

    private fun text(block: ManualBlock) = when (block) {
        is ManualBlock.Heading -> block.text
        is ManualBlock.Paragraph -> block.spans.joinToString("") { it.text }
        is ManualBlock.Listing -> block.items.joinToString("|") { item ->
            item.spans.joinToString("") { it.text }
        }
    }

    @Test
    fun itReadsHeadingsWithTheAnchorsOtherSectionsLinkTo() {
        val blocks = parseManual("""<h2 id="trip">Trip</h2><h3>Later</h3>""")
        assertEquals(
            listOf(ManualBlock.Heading(2, "trip", "Trip"), ManualBlock.Heading(3, null, "Later")),
            blocks,
        )
    }

    @Test
    fun itKeepsTheMarksOnTheWordsTheyAreOn() {
        val blocks = parseManual("<p>A <strong>station</strong> and an <em>edge</em>.</p>")
        val spans = (blocks.single() as ManualBlock.Paragraph).spans
        assertEquals("A ", spans[0].text)
        assertEquals(ManualSpan("station", bold = true), spans[1])
        assertEquals(" and an ", spans[2].text)
        assertEquals(ManualSpan("edge", italic = true), spans[3])
        assertEquals(".", spans[4].text)
    }

    /**
     * The space either side of a tag has to survive, or every emphasised word joins the one before
     * it. This is the whole reason whitespace is collapsed rather than trimmed.
     */
    @Test
    fun itDoesNotRunWordsTogetherAtATagBoundary() {
        val blocks = parseManual("<p>known as <strong>legs</strong> (or <strong>shots</strong>)</p>")
        assertEquals("known as legs (or shots)", text(blocks.single()))
    }

    /** The source is indented and wrapped; a reader should not see any of that. */
    @Test
    fun itCollapsesTheSourcesOwnLayout() {
        val blocks = parseManual("<p>one\n\ttwo   three</p>")
        assertEquals("one two three", text(blocks.single()))
    }

    /** The real guide leaves a `<p>` unclosed before the next one. A browser copes; so does this. */
    @Test
    fun itClosesAParagraphThatWasLeftOpen() {
        val blocks = parseManual("<p>first<p>second</p>")
        assertEquals(listOf("first", "second"), blocks.map { text(it) })
    }

    @Test
    fun itReadsBothKindsOfList() {
        val bullets = parseManual("<ul><li>Draw</li><li>Erase</li></ul>").single()
        assertEquals(
            ManualBlock.Listing(
                listOf(
                    ManualItem(listOf(ManualSpan("Draw"))),
                    ManualItem(listOf(ManualSpan("Erase"))),
                ),
            ),
            bullets,
        )

        val numbered = parseManual("<ol><li>Pair</li></ol>").single() as ManualBlock.Listing
        assertTrue(numbered.items.single().numbered)
    }

    /**
     * The guide has one nested list, under *Import*, and the first version of this reader treated
     * the inner `</ul>` as the end of the outer one — so the eleven items after it vanished with
     * no error at all. Silently losing content is the exact failure this whole approach exists to
     * avoid, so it gets its own test and a count assertion against the real file.
     */
    @Test
    fun aListInsideAListKeepsEverythingAroundIt() {
        val listing = parseManual(
            "<ul><li>before</li><li>outer<ul><li>inner</li></ul></li><li>after</li></ul>",
        ).single() as ManualBlock.Listing
        assertEquals(
            listOf("before" to 0, "outer" to 0, "inner" to 1, "after" to 0),
            listing.items.map { item -> item.spans.joinToString("") { it.text } to item.depth },
        )
    }

    @Test
    fun itKeepsTheLinkOnTheWordsItIsOn() {
        val blocks = parseManual("""<p>see the <a href="#3d-view">3D view</a></p>""")
        val spans = (blocks.single() as ManualBlock.Paragraph).spans
        assertEquals("#3d-view", spans.last().link)
        assertEquals("3D view", spans.last().text)
    }

    @Test
    fun itDecodesTheEntitiesTheGuideUses() {
        assertEquals("a b", text(parseManual("<p>a&nbsp;b</p>").single()))
        assertEquals("&<>\"", text(parseManual("<p>&amp;&lt;&gt;&quot;</p>").single()))
        assertEquals("→", text(parseManual("<p>&#8594;</p>").single()))
        assertEquals("→", text(parseManual("<p>&#x2192;</p>").single()))
    }

    @Test
    fun itThrowsAwayThePageFurnitureRatherThanReadingIt() {
        val blocks = parseManual(
            "<head><title>x</title></head><body><style>p{color:red}</style>" +
                "<script>var a = 1 < 2;</script><h1>Manual</h1></body>",
        )
        assertEquals(listOf(ManualBlock.Heading(1, null, "Manual")), blocks)
    }

    /**
     * The point of the whole exercise. Upstream adds a table to the guide; this build fails, and
     * somebody adds a table to the renderer. It does not silently lose a section.
     */
    @Test
    fun itRefusesToQuietlyDropSomethingItCannotDraw() {
        val thrown = assertFailsWith<ManualParseException> {
            parseManual("<p>before</p><table><tr><td>1</td></tr></table>")
        }
        assertTrue("table" in thrown.message!!, thrown.message!!)
    }

    @Test
    fun anUnknownEntityIsAlsoAFailureRatherThanAMess() {
        assertFailsWith<ManualParseException> { parseManual("<p>&hellip;</p>") }
    }
}
