package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.manual.ManualBlock
import org.hwyl.sexytopo.shared.manual.parseManual
import org.hwyl.sexytopo.shared.manual.releaseNotesToHtml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The release notes the app ships, held to `docs/releases.md`, for the reason `ManualContentTest`
 * holds the manual to the guide: the Android build copies the file in as it is, and a copy here
 * that has drifted is a list of changes to some other version of the app.
 */
class ReleaseNotesContentTest {

    private val shipped = File("src/commonMain/composeResources/files/releases.md")
    private val upstream = File("../../docs/releases.md")

    @Test
    fun theBundledNotesAreTheRepositorysOwn() {
        assertTrue(shipped.isFile, "the app ships no release notes: $shipped")
        assertTrue(upstream.isFile, "the release notes have moved: $upstream")
        assertEquals(
            upstream.readBytes().toList(),
            shipped.readBytes().toList(),
            "the bundled release notes have drifted from docs/releases.md; copy it across rather " +
                "than editing the copy",
        )
    }

    /** Parsing is the check: the reader throws on anything it would otherwise drop. */
    @Test
    fun everyReleaseComesOutAsAHeadingWithItsChanges() {
        val blocks = parseManual(releaseNotesToHtml(shipped.readText()))
        val headings = blocks.filterIsInstance<ManualBlock.Heading>()
        assertEquals(
            shipped.readLines().count { it.startsWith("#") },
            headings.size,
            "a release heading went missing on the way in",
        )
        assertTrue(headings.any { it.text.contains("1.14.0") }, "the newest release is not there")
        assertTrue(blocks.filterIsInstance<ManualBlock.Listing>().isNotEmpty())
    }

    @Test
    fun everyCharacterInThemIsOneTheBundledFontHas() {
        val text =
            parseManual(releaseNotesToHtml(shipped.readText())).joinToString(" ") { block ->
                when (block) {
                    is ManualBlock.Heading -> block.text
                    is ManualBlock.Paragraph -> block.spans.joinToString("") { it.text }
                    is ManualBlock.Listing ->
                        block.items.joinToString(" ") { item -> item.spans.joinToString("") { it.text } }
                }
            }
        assertEquals("", FontCoverage.missingFrom(text), "characters the bundled font cannot draw")
    }
}
