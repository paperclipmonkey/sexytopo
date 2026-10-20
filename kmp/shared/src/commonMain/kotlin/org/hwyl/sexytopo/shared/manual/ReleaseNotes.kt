package org.hwyl.sexytopo.shared.manual

/**
 * The release notes, `docs/releases.md`, turned into the same small page `ReleaseNotesFormatter`
 * gives the Android app's web view.
 *
 * Only the markdown the notes actually use is understood, as upstream: `# ` headings, `- ` items
 * and blank lines, with anything else a plain paragraph. The page is then read by [parseManual]
 * rather than drawn by a browser, so the notes come out in the same headings, lists and paragraphs
 * as the manual, in the app's own font and dark mode.
 *
 * One deliberate difference: upstream escapes with `escapeHtml4`, which also turns every accented
 * letter into a named entity such as `&eacute;`. A browser reads those; [parseManual] knows only
 * the handful the guide uses and throws on the rest. Escaping the four characters that are markup
 * gives the same text on screen, and "thanks Andrew Atkinson" is never one entity away from a
 * release notes screen that will not open.
 */
fun releaseNotesToHtml(markdown: String): String {
    val html = StringBuilder()
    html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        .append("</head><body>\n")

    var inList = false
    for (rawLine in markdown.split(Regex("\\r?\\n"))) {
        val line = rawLine.trim()
        val isItem = line.startsWith("- ")

        if (inList && !isItem) {
            html.append("</ul>\n")
            inList = false
        }

        when {
            line.isEmpty() -> continue
            line.startsWith("#") ->
                html.append("<h2>").append(escape(line.replaceFirst(Regex("^#+"), ""))).append("</h2>\n")
            isItem -> {
                if (!inList) {
                    html.append("<ul>\n")
                    inList = true
                }
                html.append("<li>").append(escape(line.substring(2))).append("</li>\n")
            }
            else -> html.append("<p>").append(escape(line)).append("</p>\n")
        }
    }

    if (inList) {
        html.append("</ul>\n")
    }

    html.append("</body></html>\n")
    return html.toString()
}

private fun escape(text: String): String =
    text.trim()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
