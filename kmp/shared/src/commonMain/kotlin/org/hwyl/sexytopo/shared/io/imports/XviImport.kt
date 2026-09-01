package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Reading a Therion tracing image back in. Ported from `control/io/thirdparty/xvi/XviImporter`.
 *
 * This port could already *write* an `.xvi` and not read one, which is the worse half of that pair:
 * a format the app emits and cannot take back is one where its own export does not round-trip, and
 * a surveyor who sends a Therion project to a colleague and gets it back loses the drawing without
 * being told. The Android app has read them since the format was added.
 *
 * Two ways in, and the second is the one that matters:
 *
 * - a loose `.xvi`, which becomes a survey with no legs and the traced lines as its plan; and
 * - a `.th` with `NameP.xvi` or `NameEE.xvi` beside it, where the `.th` carries the centreline and
 *   the `.xvi` files carry the two drawings. That is how a Therion project actually arrives, and
 *   it is the same shape as the loose-file import that was fixed for this app's own format: the
 *   numbers take a minute a station and the drawing takes the whole trip, so dropping the drawing
 *   silently is most of what was lost.
 *
 * ## What the format cannot carry back
 *
 * An `.xvi` is a *tracing image*: strokes, station marks and shot lines, drawn for a human to trace
 * over in Therion. It has no notion of a cross-section, a symbol, a label or a leg, so a round trip
 * through it is lossy by design and not by omission:
 *
 * - symbols and text were written out as strokes by [org.hwyl.sexytopo.shared.io.export.XviExporter]
 *   and come back as strokes, because that is all the file says they are;
 * - a cross-section's `connect` line comes back as a two-point black stroke, which is what the
 *   Android importer makes of it too;
 * - stations and shots are not read at all. The Java ignores `XVIstations` and `XVIshots` on the
 *   way in, and so does this: the centreline belongs to the `.th`, and a `.th` is what supplies it.
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

    /**
     * A loose tracing image as a survey of its own: no legs, and the drawing on the plan.
     *
     * Worth knowing what this is for. It is not a survey — there is no centreline in an `.xvi` —
     * so it imports as a drawing with nothing under it, which is exactly what somebody tracing a
     * scanned survey has before they book a single leg.
     */
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
        // A zero or negative scale would divide every coordinate into infinity. The Java divides
        // without looking; this refuses the file instead, because a plan of infinities draws
        // nothing and reports nothing.
        return if (scale > 0f) scale else null
    }

    internal fun pathsFrom(text: String, scale: Float): MutableList<PathDetail> {
        val block = blockContents(text, SKETCHLINE_COMMAND) ?: return mutableListOf()
        return entries(block).mapNotNull { pathFrom(scale, it) }.toMutableList()
    }

    /**
     * One `{...}` entry as a stroke, or null if it is not one this app can read.
     *
     * The Java throws on a malformed entry, which fails the whole import on one bad line. This
     * skips it, which is the convention the rest of this port settled on: an unreadable *part* of a
     * drawing should cost that part, not the trip's work. A colour name from a newer version of the
     * app takes the same route as an unknown symbol does in the exporter.
     */
    internal fun pathFrom(scale: Float, entry: String): PathDetail? {
        val tokens = entry.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size <= 1) return null

        // `connect` joins a cross-section to its station. The file gives no way to say that is what
        // it is, so it arrives as a plain black line, as it does in the Android app.
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
     * What is inside the braces that follow [command], counting nesting.
     *
     * A scan rather than a regular expression, because the blocks nest: `set XVIsketchlines` holds
     * a `{...}` per stroke, so stopping at the first closing brace would return one stroke and call
     * it the drawing.
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
