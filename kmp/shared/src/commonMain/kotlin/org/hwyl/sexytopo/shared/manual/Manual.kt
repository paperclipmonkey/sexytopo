package org.hwyl.sexytopo.shared.manual

/**
 * The user manual, turned from the HTML the Android app ships into something Compose can draw.
 *
 * The obvious objection to reading it is drift: upstream edits the guide, adds a table, and the
 * app quietly drops it. So [parseManual] does not skip what it does not know — it throws, naming
 * the tag, and a test parses the shipped file on every build. Adding a tag upstream turns into a
 * failing build here rather than a hole in the manual.
 */
sealed interface ManualBlock {

    /** `<h1>`, `<h2>` or `<h3>`. [id] is the anchor other sections link to, when it has one. */
    data class Heading(val level: Int, val id: String?, val text: String) : ManualBlock

    data class Paragraph(val spans: List<ManualSpan>) : ManualBlock

    /** `<ul>` or `<ol>`, flattened: one entry per `<li>`, each knowing how deep it sits. */
    data class Listing(val items: List<ManualItem>) : ManualBlock
}

/**
 * One `<li>`.
 *
 * [depth] is 0 for a top-level item and 1 for one inside a nested list — the guide has exactly one
 * of those, under *Import*, and the first version of this reader lost the eleven items that came
 * after it without a word.
 */
data class ManualItem(
    val spans: List<ManualSpan>,
    val depth: Int = 0,
    val numbered: Boolean = false,
)

/**
 * A run of text with one set of marks on it.
 *
 * [link] is the raw `href`. Every link in the shipped guide is an internal `#anchor`.
 */
data class ManualSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** Thrown when the guide contains something this reader was not written for. */
class ManualParseException(message: String) : Exception(message)

/** Tags that carry meaning but no structure of their own, and are simply passed through. */
private val IGNORED = setOf("html", "body", "div", "br", "hr")

/** Tags whose entire contents are dropped: the page furniture, not the manual. */
private val SKIPPED_WHOLE = setOf("head", "script", "style", "title", "meta")

private val INLINE = setOf("strong", "b", "em", "i", "code", "a")

/**
 * Reads the guide.
 *
 * Written for the HTML that is actually in the file rather than for HTML in general, and it says
 * so by throwing on anything else. Two details of the real file that a stricter reader would
 * choke on: a `<p>` is left unclosed before the next one, and `&nbsp;` appears in the middle of a
 * sentence. Both are handled; anything genuinely new is not.
 */
fun parseManual(html: String): List<ManualBlock> {
    val blocks = mutableListOf<ManualBlock>()
    val spans = mutableListOf<ManualSpan>()
    var listItems: MutableList<ManualItem>? = null
    val listStack = mutableListOf<Boolean>()
    var headingLevel = 0
    var headingId: String? = null
    var bold = 0
    var italic = 0
    var code = 0
    var href: String? = null

    /**
     * The spans of the block just ended, with the source's own layout taken off the ends.
     *
     * Empty unless there is something to read. The guide is indented, so between every pair of
     * tags there is a newline and a tab, and a reader that took those at face value would produce
     * a blank paragraph between every real one — which the first version of this did, 109 of them.
     */
    fun flushSpans(): List<ManualSpan> {
        val trimmed = spans.toMutableList()
        while (trimmed.isNotEmpty()) {
            val first = trimmed.first().text.trimStart()
            if (first.isEmpty()) trimmed.removeAt(0) else {
                trimmed[0] = trimmed.first().copy(text = first)
                break
            }
        }
        while (trimmed.isNotEmpty()) {
            val last = trimmed.last().text.trimEnd()
            if (last.isEmpty()) trimmed.removeAt(trimmed.size - 1) else {
                trimmed[trimmed.size - 1] = trimmed.last().copy(text = last)
                break
            }
        }
        spans.clear()
        return trimmed
    }

    fun closeBlock() {
        val done = flushSpans()
        if (done.isEmpty()) {
            headingLevel = 0
            headingId = null
            return
        }
        when {
            headingLevel > 0 -> {
                blocks += ManualBlock.Heading(
                    headingLevel,
                    headingId,
                    done.joinToString("") { it.text }.trim(),
                )
                headingLevel = 0
                headingId = null
            }
            listItems != null ->
                listItems!! += ManualItem(done, listStack.size - 1, listStack.lastOrNull() == true)
            else -> blocks += ManualBlock.Paragraph(done)
        }
    }

    var i = 0
    while (i < html.length) {
        val open = html.indexOf('<', i)
        if (open < 0) {
            appendText(spans, html.substring(i), bold, italic, code, href)
            break
        }
        if (open > i) appendText(spans, html.substring(i, open), bold, italic, code, href)

        val close = html.indexOf('>', open)
        if (close < 0) throw ManualParseException("the guide has an unclosed tag at $open")
        val raw = html.substring(open + 1, close).trim()
        i = close + 1

        if (raw.startsWith("!")) continue // doctype or comment
        val closing = raw.startsWith("/")
        val body = raw.removePrefix("/").removeSuffix("/").trim()
        val name = body.substringBefore(' ').lowercase()

        if (name in SKIPPED_WHOLE) {
            if (!closing) {
                val end = html.indexOf("</$name", i, ignoreCase = true)
                i = if (end < 0) html.length else html.indexOf('>', end) + 1
            }
            continue
        }
        if (name in IGNORED) continue

        when {
            name in INLINE -> {
                when (name) {
                    "strong", "b" -> if (closing) bold-- else bold++
                    "em", "i" -> if (closing) italic-- else italic++
                    "code" -> if (closing) code-- else code++
                    "a" -> href = if (closing) null else attribute(body, "href")
                }
            }

            name == "p" -> closeBlock()

            name == "h1" || name == "h2" || name == "h3" -> {
                closeBlock()
                if (!closing) {
                    headingLevel = name.substring(1).toInt()
                    headingId = attribute(body, "id")
                }
            }

            name == "ul" || name == "ol" -> {
                closeBlock()
                if (closing) {
                    if (listStack.isEmpty()) {
                        throw ManualParseException("the guide closes a list it never opened")
                    }
                    listStack.removeAt(listStack.size - 1)
                    if (listStack.isEmpty()) {
                        val items = listItems
                        listItems = null
                        if (!items.isNullOrEmpty()) blocks += ManualBlock.Listing(items)
                    }
                } else {
                    listStack += (name == "ol")
                    if (listStack.size == 1) listItems = mutableListOf()
                }
            }

            name == "li" -> closeBlock()

            else -> throw ManualParseException(
                "the guide now uses <$name>, which the manual reader does not draw. Add it to " +
                    "ManualBlock and to the Compose renderer, or the manual loses that content.",
            )
        }
    }
    closeBlock()
    return blocks
}

private fun appendText(
    into: MutableList<ManualSpan>,
    raw: String,
    bold: Int,
    italic: Int,
    code: Int,
    href: String?,
) {
    val text = collapse(decodeEntities(raw))
    if (text.isEmpty()) return
    // Two adjacent runs with the same marks are one run; keeps the span list the size of the
    // formatting rather than the size of the markup.
    val last = into.lastOrNull()
    val span = ManualSpan(text, bold > 0, italic > 0, code > 0, href)
    if (last != null &&
        last.bold == span.bold &&
        last.italic == span.italic &&
        last.code == span.code &&
        last.link == span.link
    ) {
        into[into.size - 1] = last.copy(text = last.text + span.text)
    } else {
        into += span
    }
}

/**
 * Turns the source's line breaks and indentation into single spaces, the way a browser would.
 *
 * A leading or trailing space is kept when there was whitespace there, because "</strong> (or"
 * needs it — dropping it entirely would run words together at every tag boundary.
 */
private fun collapse(text: String): String {
    val builder = StringBuilder()
    var lastWasSpace = false
    for (character in text) {
        val isSpace = character.isWhitespace()
        if (isSpace) {
            if (!lastWasSpace) builder.append(' ')
        } else {
            builder.append(character)
        }
        lastWasSpace = isSpace
    }
    return builder.toString()
}

private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val builder = StringBuilder()
    var i = 0
    while (i < text.length) {
        val character = text[i]
        if (character != '&') {
            builder.append(character)
            i++
            continue
        }
        val end = text.indexOf(';', i)
        if (end < 0 || end - i > 10) {
            builder.append(character)
            i++
            continue
        }
        val entity = text.substring(i + 1, end)
        val decoded = when {
            entity == "nbsp" -> " "
            entity == "amp" -> "&"
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.drop(2).toIntOrNull(16)?.let { charOf(it) }
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { charOf(it) }
            else -> null
        }
        if (decoded == null) {
            throw ManualParseException("the guide uses the entity &$entity; which is not decoded")
        }
        builder.append(decoded)
        i = end + 1
    }
    return builder.toString()
}

private fun charOf(codePoint: Int): String =
    when {
        codePoint in 0..0xFFFF -> codePoint.toChar().toString()
        codePoint <= 0x10FFFF -> {
            val offset = codePoint - 0x10000
            val high = (0xD800 + (offset shr 10)).toChar()
            val low = (0xDC00 + (offset and 0x3FF)).toChar()
            "$high$low"
        }
        else -> throw ManualParseException("the guide has a character reference past Unicode")
    }

/** The value of one attribute, for the two the guide uses: `id` and `href`. */
private fun attribute(tagBody: String, name: String): String? {
    val marker = "$name="
    val at = tagBody.indexOf(marker, ignoreCase = true)
    if (at < 0) return null
    var start = at + marker.length
    if (start >= tagBody.length) return null
    val quote = tagBody[start]
    return if (quote == '"' || quote == '\'') {
        start++
        val end = tagBody.indexOf(quote, start)
        if (end < 0) null else tagBody.substring(start, end)
    } else {
        tagBody.substring(start).substringBefore(' ')
    }
}

/** The `<h2>`s, which is what the guide's own script builds its table of contents from. */
fun contentsOf(blocks: List<ManualBlock>): List<ManualBlock.Heading> =
    blocks.filterIsInstance<ManualBlock.Heading>().filter { it.level == 2 }
