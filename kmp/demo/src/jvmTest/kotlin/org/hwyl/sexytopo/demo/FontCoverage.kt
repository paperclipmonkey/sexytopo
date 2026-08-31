package org.hwyl.sexytopo.demo

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import java.io.File

/**
 * What the bundled font can actually draw, asked of Skia rather than assumed.
 *
 * The app bundles Liberation Sans because Skia ships no system fonts on the web, so this file is
 * the whole answer to "will that character render?" on every platform at once: the same two files
 * are the only fonts the app has, and Skia resolves a character it has no glyph for to glyph 0.
 */
object FontCoverage {

    private val weights = listOf("LiberationSans_Regular", "LiberationSans_Bold")

    private val typefaces: List<Typeface> by lazy {
        weights.map { name ->
            val file = File("src/commonMain/composeResources/font/$name.ttf")
            check(file.isFile) { "the app's own font is missing: $file" }
            FontMgr.default.makeFromFile(file.absolutePath, 0)
                ?: error("Skia would not read $name")
        }
    }

    /**
     * The characters of [text] that at least one weight of the bundled font cannot draw.
     *
     * Line breaks and the other control characters are skipped: they have no glyph in any font
     * and are not supposed to, so counting them would make every multi-line string fail.
     */
    fun missingFrom(text: String): String {
        val distinct = text.filterNot { it.isISOControl() }.toSortedSet().toList()
        if (distinct.isEmpty()) return ""
        val codes = IntArray(distinct.size) { distinct[it].code }
        val missing = StringBuilder()
        for (typeface in typefaces) {
            val glyphs = typeface.getUTF32Glyphs(codes)
            for (i in distinct.indices) {
                if (glyphs[i].toInt() == 0 && distinct[i] !in missing) missing.append(distinct[i])
            }
        }
        return missing.toString()
    }

    fun has(character: Char) = missingFrom(character.toString()).isEmpty()
}
