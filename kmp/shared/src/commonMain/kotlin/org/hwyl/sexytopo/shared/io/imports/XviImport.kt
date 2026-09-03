package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Reading a Therion tracing image back in. The Android app has read `.xvi` files since the
 * format was added.
 *
 * Two ways in: a loose `.xvi` (becomes a survey with no legs, traced lines as its plan), or a
 * `.th` with `NameP.xvi`/`NameEE.xvi` beside it — how a Therion project actually arrives, since
 * dropping the drawing silently loses most of the trip's work.
 *
 * An `.xvi` is a *tracing image* with no notion of a cross-section, symbol, label or leg, so a
 * round trip through it is lossy by design:
 *
 * - symbols and text come back as the strokes [org.hwyl.sexytopo.shared.io.export.XviExporter]
 *   wrote them as;
 * - a cross-section's `connect` line comes back as a two-point black stroke, matching Android;
 * - stations and shots aren't read at all — the centreline belongs to the `.th`.
 */
object XviImporter {

    private const val GRID_COMMAND = "set XVIgrid"
    private const val SKETCHLINE_COMMAND = "set XVIsketchlines"

    /** The scale is the sixth number of the grid line — `grid * cfactor` in Therion's terms. */
    private const val SCALE_FIELD = 5

    /** The traced lines, in survey coordinates. */
    fun sketchFrom(text: String): Sketch {
        val sketch = Sketch()
        val scale = scaleOf(text) ?: return sketch
        sketch.pathDetails = pathsFrom(text, scale)
        return sketch
    }

    /** A loose tracing image as a survey of its own: no centreline, just the drawing on the plan. */
    fun read(text: String, name: String): Survey {
        val survey = Survey(name)
        survey.planSketch = sketchFrom(text)
        return survey
    }

    internal fun scaleOf(text: String): Float? {
        val block = blockContents(text, GRID_COMMAND) ?: return null
        val values = block.trim().split(Regex("\\s+"))
        if (values.size <= SCALE_FIELD) return null
        val scale = values[SCALE_FIELD].toFloatOrNull() ?: return null
        // A zero or negative scale would divide every coordinate into infinity; refusing the
        // file beats drawing nothing and reporting nothing.
        return if (scale > 0f) scale else null
    }

    internal fun pathsFrom(text: String, scale: Float): MutableList<PathDetail> {
        val block = blockContents(text, SKETCHLINE_COMMAND) ?: return mutableListOf()
        return entries(block).mapNotNull { pathFrom(scale, it) }.toMutableList()
    }

    /**
     * One `{...}` entry as a stroke, or null if unreadable. The Java throws on a malformed
     * entry, failing the whole import; this skips just that stroke instead.
     */
    internal fun pathFrom(scale: Float, entry: String): PathDetail? {
        val tokens = entry.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size <= 1) return null

        // `connect` joins a cross-section to its station; it arrives as a plain black line, as in Android.
        if (tokens[0] == "connect") {
            if (tokens.size < 5) return null
            val numbers = tokens.subList(1, 5).map { it.toFloatOrNull() ?: return null }
            return PathDetail(
                listOf(
                    Coord2D(numbers[0] / scale, -numbers[1] / scale),
                    Coord2D(numbers[2] / scale, -numbers[3] / scale),
                ),
                Colour.BLACK,
            )
        }

        // A colour and then pairs of coordinates, so the token count is always odd.
        if (tokens.size % 2 != 1) return null
        val colour = Colour.entries.firstOrNull { it.name == tokens[0].uppercase() } ?: return null

        val points = mutableListOf<Coord2D>()
        var i = 1
        while (i < tokens.size) {
            val x = tokens[i].toFloatOrNull() ?: return null
            val y = tokens[i + 1].toFloatOrNull() ?: return null
            points.add(Coord2D(x / scale, -y / scale))
            i += 2
        }
        if (points.isEmpty()) return null
        return PathDetail(points, colour)
    }

    /** Every `{...}` group in a block, in order. */
    internal fun entries(block: String): List<String> =
        Regex("\\{(.*?)}", RegexOption.DOT_MATCHES_ALL).findAll(block).map { it.groupValues[1] }.toList()

    /**
     * What is inside the braces that follow [command], counting nesting — a scan rather than
     * regex, since blocks like `set XVIsketchlines` nest one `{...}` per stroke.
     */
    internal fun blockContents(text: String, command: String): String? {
        var from = text.indexOf(command)
        while (from >= 0) {
            var i = from + command.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == '{') {
                i++
                val start = i
                var open = 1
                while (i < text.length) {
                    when (text[i]) {
                        '{' -> open++
                        '}' -> open--
                    }
                    if (open == 0) return text.substring(start, i)
                    i++
                }
                // An unterminated block: everything from the brace to the end is the best reading.
                return text.substring(start)
            }
            from = text.indexOf(command, from + 1)
        }
        return null
    }
}
