package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.graph.translate
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.sketch.SymbolDetail
import org.hwyl.sexytopo.shared.model.sketch.TextDetail
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The drawing as an XVI: the tracing image a surveyor draws over in xtherion.
 *
 * An XVI is Therion's own background-image format, made of nothing but line segments —
 * stations, shot lines, and the sketch — so labels and symbols are rendered as strokes rather
 * than referred to; see [XviGlyphs] and [XviSymbolPaths]. The `.th2` scrap file is the other
 * half, carrying the *semantic* content and positioning this image behind it.
 *
 * Survey space is y north-positive; Therion's canvas is y down, so every emit helper flips y —
 * in exactly one layer, here, and nowhere else, since a second flip upstream would be invisible
 * until a drawing came out mirrored.
 */
object XviExporter {

    private const val GRIDS_COMMAND = "set XVIgrids"
    private const val STATIONS_COMMAND = "set XVIstations"
    private const val SHOT_COMMAND = "set XVIshots"
    private const val SKETCHLINE_COMMAND = "set XVIsketchlines"
    private const val GRID_COMMAND = "set XVIgrid"

    /**
     * @param scale pixels per metre, which is what an XVI's coordinates are in.
     * @param gridFrame the area the grid covers, in the *scaled* coordinates the file uses.
     */
    fun export(
        sketch: Sketch,
        space: Space<Coord2D>,
        scale: Float,
        gridFrame: Frame,
    ): String {
        val stations = mutableListOf<String>()
        val shots = mutableListOf<String>()
        val sketchLines = mutableListOf<String>()

        add(stations, shots, sketchLines, space, sketch, scale)

        // Each cross-section, placed where it sits on the drawing: both its splay star and
        // anything drawn inside it are scaled by the sketch's cross-section scale and moved into place.
        val sectionScale = sketch.crossSectionScale
        for (detail in sketch.crossSectionDetails) {
            val sectionSpace =
                detail.crossSection.getProjection().scale(sectionScale).translate(detail.position)
            val sectionSketch = detail.sketch.scale(sectionScale).translate(detail.position)
            add(stations, shots, sketchLines, sectionSpace, sectionSketch, scale)
            sketchLines.add(connector(detail, space, scale))
        }

        return buildString {
            append(field(GRIDS_COMMAND, "1 m"))
            append(multilineField(STATIONS_COMMAND, stations.joinToString("")))
            append(multilineField(SHOT_COMMAND, shots.joinToString("")))
            append(multilineField(SKETCHLINE_COMMAND, sketchLines.joinToString("")))
            append(field(GRID_COMMAND, grid(gridFrame, scale)))
        }
    }

    private fun add(
        stations: MutableList<String>,
        shots: MutableList<String>,
        sketchLines: MutableList<String>,
        space: Space<Coord2D>,
        sketch: Sketch,
        scale: Float,
    ) {
        for ((station, coord) in space.stationMap) {
            stations.add(row(number(coord.x * scale), number(-coord.y * scale), station.name))
        }
        for (line in space.legMap.values) {
            shots.add(
                row(
                    number(line.start.x * scale),
                    number(-line.start.y * scale),
                    number(line.end.x * scale),
                    number(-line.end.y * scale),
                ),
            )
        }
        for (path in sketch.pathDetails) {
            sketchLines.add(pathRow(path, scale))
        }
        for (label in sketch.textDetails) {
            sketchLines.add(textAsPaths(label).joinToString("") { pathRow(it, scale) })
        }
        for (symbol in sketch.symbolDetails) {
            sketchLines.add(symbolAsPaths(symbol).joinToString("") { pathRow(it, scale) })
        }
    }

    /** The dashed line joining a cross-section to the station it belongs to. */
    private fun connector(
        detail: CrossSectionDetail,
        space: Space<Coord2D>,
        scale: Float,
    ): String {
        val at = space.stationMap[detail.station] ?: return ""
        return row(
            "connect",
            number(at.x * scale),
            number(-at.y * scale),
            number(detail.position.x * scale),
            number(-detail.position.y * scale),
        )
    }

    private fun pathRow(path: PathDetail, scale: Float): String {
        val fields = mutableListOf(path.colour.toString())
        for (coord in path.path) {
            fields.add(number(coord.x * scale))
            // The Java adds 0.0 before scaling to turn -0.0 into 0.0; formatFixed writes a minus
            // sign for negative zero, so the same guard is needed here.
            fields.add(number((-coord.y + 0.0f) * scale))
        }
        return row(*fields.toTypedArray())
    }

    /**
     * The grid: origin, the two axis vectors, and how many squares each way.
     *
     * `{bottom left x, bottom left y, x1 dist, y1 dist, x2 dist, y2 dist, number of x, number of y}`
     */
    private fun grid(frame: Frame, scale: Float): String {
        val acrossCount = (frame.width / scale).roundToInt().toFloat()
        val downCount = (frame.height / scale).roundToInt().toFloat()
        return listOf(
            frame.left,
            frame.bottom,
            scale,
            0.0f,
            0.0f,
            scale,
            acrossCount,
            downCount,
        ).joinToString(" ") { plainFloat(it) }
    }

    /**
     * A label as line segments. Upper case only, since the glyph table has none — "sump" becomes
     * "SUMP" rather than four unknown boxes. Per-character advance is the widest stroke in the
     * glyph, floored at one space, since some glyphs ("1", "I") are a single line with no width
     * and would otherwise draw the next character on top.
     */
    internal fun textAsPaths(detail: TextDetail): List<PathDetail> {
        val paths = mutableListOf<PathDetail>()
        val scale = detail.size * XviGlyphs.SCALE_FACTOR
        val lineHeight = XviGlyphs.CHAR_HEIGHT * XviGlyphs.LINE_SPACING * scale
        val interCharSpace = XviGlyphs.INTER_CHAR_SPACE * scale

        for ((lineIndex, line) in detail.text.split("\n").withIndex()) {
            val linePosition = detail.position + Coord2D(0f, lineIndex * lineHeight)
            var x = 0f
            for (character in line.uppercase()) {
                var advance = interCharSpace
                for (glyphPath in glyph(character, detail)) {
                    val scaled = glyphPath.scale(scale)
                    paths.add(scaled.translate(Coord2D(x, 0f)).translate(linePosition))
                    advance = max(advance, widthOf(scaled))
                }
                x += advance + interCharSpace
            }
        }
        return paths
    }

    private fun glyph(character: Char, detail: TextDetail): List<PathDetail> {
        val segments = XviGlyphs.segmentsFor(character)
        val paths = mutableListOf<PathDetail>()
        var index = 0
        while (index + 3 < segments.size) {
            paths.add(
                PathDetail(
                    listOf(
                        Coord2D(
                            segments[index].toFloat(),
                            -segments[index + 1] + XviGlyphs.CHAR_HEIGHT,
                        ),
                        Coord2D(
                            segments[index + 2].toFloat(),
                            -segments[index + 3] + XviGlyphs.CHAR_HEIGHT,
                        ),
                    ),
                    detail.colour,
                ),
            )
            index += 4
        }
        return paths
    }

    private fun widthOf(path: PathDetail): Float {
        if (path.path.isEmpty()) return 0f
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (point in path.path) {
            left = minOf(left, point.x)
            right = max(right, point.x)
        }
        return right - left
    }

    /**
     * A stamped symbol as line segments: centred on its own grid, scaled to the stamped size,
     * rotated if aimed, moved into place. A symbol with no polyline contributes nothing.
     */
    internal fun symbolAsPaths(detail: SymbolDetail): List<PathDetail> {
        // A SymbolDetail carries the Therion name rather than the enum, so that a sketch from a
        // newer app version round-trips a symbol this build has never heard of.
        val symbol = Symbol.byTherionName(detail.symbolName) ?: return emptyList()
        val polylines = XviSymbolPaths.PATHS[symbol] ?: return emptyList()
        val scale = detail.size / XviSymbolPaths.VIEWBOX
        val centre = XviSymbolPaths.VIEWBOX / 2.0f
        val radians = detail.angle.toDouble() * kotlin.math.PI / 180.0
        val cosine = cos(radians)
        val sine = sin(radians)

        val paths = mutableListOf<PathDetail>()
        for (polyline in polylines) {
            val coords = mutableListOf<Coord2D>()
            var index = 0
            while (index + 1 < polyline.size) {
                var x = (polyline[index] - centre) * scale
                var y = (polyline[index + 1] - centre) * scale
                if (detail.angle != 0f) {
                    val rotatedX = (x * cosine - y * sine).toFloat()
                    val rotatedY = (x * sine + y * cosine).toFloat()
                    x = rotatedX
                    y = rotatedY
                }
                coords.add(Coord2D(x + detail.position.x, y + detail.position.y))
                index += 2
            }
            if (coords.size >= 2) paths.add(PathDetail(coords, detail.colour))
        }
        return paths
    }

    /**
     * Two decimal places with a dot. Rounds halves up, not to even, matching this port's other
     * formatting and Java's own `Formatter` — the difference from `DecimalFormat` is at most 0.01px.
     */
    private fun number(value: Float): String = formatFixed(value, 2)

    /**
     * Writes floats the way Java's `String.valueOf(float)` does — "50.0", not "50" — and not
     * `Float.toString()`, whose exponent notation differs between the JVM and Kotlin/Wasm/Native
     * and would produce a grid line xtherion can't read.
     */
    private fun plainFloat(value: Float): String =
        if (value == value.toInt().toFloat()) {
            "${value.toInt()}.0"
        } else {
            formatFixedTrimmed(value, 4)
        }

    private fun row(vararg fields: String): String = field("\t", fields.joinToString(" "))

    private fun field(name: String, content: String): String = "$name {$content}\n"

    private fun multilineField(name: String, content: String): String = "$name {\n$content}\n"
}
