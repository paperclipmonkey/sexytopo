package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.manual.ManualBlock
import org.hwyl.sexytopo.shared.manual.contentsOf
import org.hwyl.sexytopo.shared.manual.parseManual
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manual the app ships, held to the manual the Android app ships.
 *
 * Two things have to stay true and neither is true by construction. The bundled copy has to be the
 * *same file* — one byte of drift and this port is answering questions about a version of the app
 * nobody has. And every tag in it has to be one the reader draws, which is what stops a rewritten
 * guide from quietly losing a section: `parseManual` throws on anything it was not written for, so
 * simply parsing the real file here is the whole check.
 *
 * Both would pass vacuously if the file went missing, so both start by insisting it is there.
 */
class ManualContentTest {

    private val shipped = File("src/commonMain/composeResources/files/manual.html")
    private val android = File("../../app/src/main/assets/guide/index.html")

    @Test
    fun theBundledManualIsTheAndroidAppsOwn() {
        assertTrue(shipped.isFile, "the app ships no manual: $shipped")
        assertTrue(android.isFile, "the Android guide has moved: $android")
        assertEquals(
            android.readBytes().toList(),
            shipped.readBytes().toList(),
            "the bundled manual has drifted from app/src/main/assets/guide/index.html; copy it " +
                "across rather than editing the copy, so this port answers for the real guide",
        )
    }

    @Test
    fun everyTagInItIsOneTheAppCanDraw() {
        parseManual(shipped.readText())
    }

    /**
     * Nothing in the manual is lost on the way in — counted against the file's own tags.
     *
     * "It parsed" is not the check. The first version of this reader parsed the whole guide
     * without complaint and produced 69 list items where the file has 79: the guide nests one list
     * inside another, under *Import*, and the inner `</ul>` was taken for the end of the outer one,
     * so the eleven items after it disappeared silently. Counting is what caught it, and counting
     * against the source rather than against a number somebody wrote down is what keeps it caught.
     */
    @Test
    fun nothingInItIsLostOnTheWayIn() {
        val html = shipped.readText()
        val blocks = parseManual(html)

        fun opens(tag: String) = Regex("<$tag[ >]").findAll(html).count()

        assertEquals(
            opens("h1") + opens("h2") + opens("h3"),
            blocks.count { it is ManualBlock.Heading },
            "headings were lost between the file and the app",
        )
        assertEquals(
            opens("p"),
            blocks.count { it is ManualBlock.Paragraph },
            "paragraphs were lost between the file and the app",
        )
        assertEquals(
            opens("li"),
            blocks.filterIsInstance<ManualBlock.Listing>().sumOf { it.items.size },
            "list items were lost between the file and the app",
        )
        val nested = blocks.filterIsInstance<ManualBlock.Listing>()
            .flatMap { it.items }
            .filter { it.depth > 0 }
        assertEquals(2, nested.size, "the guide's one nested list has ${nested.size} items")
    }

    @Test
    fun itHasTheSectionsTheGuidesOwnContentsListWouldHave() {
        val contents = contentsOf(parseManual(shipped.readText()))
        assertEquals(13, contents.size, contents.map { it.text }.toString())
        assertEquals("Overview", contents.first().text)
        assertEquals("Troubleshooting", contents.last().text)
        assertTrue(contents.all { it.id != null }, "a section with no anchor cannot be jumped to")
    }

    @Test
    fun everyLinkInItPointsAtASectionThatExists() {
        val blocks = parseManual(shipped.readText())
        val anchors = blocks.filterIsInstance<ManualBlock.Heading>().mapNotNull { it.id }.toSet()
        val links = blocks.flatMap { block ->
            when (block) {
                is ManualBlock.Paragraph -> block.spans
                is ManualBlock.Listing -> block.items.flatMap { it.spans }
                is ManualBlock.Heading -> emptyList()
            }
        }.mapNotNull { it.link }
        assertTrue(links.isNotEmpty(), "no links were found, so this test proves nothing")
        val broken = links.filterNot { it.startsWith("#") && it.drop(1) in anchors }
        assertEquals(emptyList(), broken, "links in the manual that go nowhere")
    }

    @Test
    fun everyCharacterInItIsOneTheBundledFontHas() {
        val blocks = parseManual(shipped.readText())
        val text = blocks.joinToString(" ") { block ->
            when (block) {
                is ManualBlock.Heading -> block.text
                is ManualBlock.Paragraph -> block.spans.joinToString("") { it.text }
                is ManualBlock.Listing ->
                    block.items.joinToString(" ") { item ->
                        item.spans.joinToString("") { it.text }
                    }
            }
        }
        assertEquals(
            "",
            FontCoverage.missingFrom(text),
            "characters in the manual the bundled font cannot draw",
        )
    }
}
