package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * What the surveyor actually shows people: the drawing, as a vector file.
 *
 * The fifth and last of the app's selectable exporters, and the only one that is a picture rather
 * than a table of numbers. Ported from `control/io/thirdparty/svg/SvgExporter`.
 *
 * Two deliberate departures from the original, both for the same reason it keeps coming up in this
 * port:
 *
 *  - **Output is deterministic.** The Java walks `projection.getStationMap().keySet()` and collects
 *    the symbols in use into a `HashSet`, so the order of stations, legs and symbol definitions in
 *    the file depends on Java's identity hash codes and changes between runs. Two exports of an
 *    unchanged survey therefore differ, which makes them impossible to diff and impossible to
 *    golden-test. Here everything is written in the survey's own chronological order.
 *  - **Symbols are emitted from their path data** rather than by parsing the app's SVG asset files
 *    at runtime and splicing the markup in between sentinel strings. [Symbol] already carries the
 *    artwork, so a `<symbol>` definition is a `<path>` and needs no escaping dance.
 *
 * Not ported: the legend, north arrow, scale bar, team block and tagline. They are decoration
 * around the drawing rather than the drawing, and each is a block of layout arithmetic; the
 * geometry is the part that has to be right.
 */
object SvgExporter {

    /** Pixels per metre. The Java's `SCALE`, and the only reason the numbers below look large. */
    const val SCALE = 50

    /** The most grid lines drawn in either direction. See [writeGrid]. */
    private const val MAX_GRID_LINES = 200

    /** Station label size in pixels, from the Java's `STATION_FONT`. */
    const val STATION_FONT = 15

    /**
     * What to draw, defaulting to the preference defaults in `GeneralPreferences`.
     *
     * Stroke widths are in pixels at [SCALE] and are the app's own: sketch lines 1, centreline legs
     * 2, splays 1.
     */
    data class Options(
        val whiteBackground: Boolean = true,
        val showGrid: Boolean = true,
        val showSketch: Boolean = true,
        val showSymbols: Boolean = true,
        val showCrossSections: Boolean = true,
        val showCentreline: Boolean = true,
        val showSplays: Boolean = true,
        val showStations: Boolean = true,
        val sketchStrokeWidth: Int = 1,
        val legStrokeWidth: Int = 2,
        val splayStrokeWidth: Int = 1,
    ) {
        companion object {
            val DEFAULT = Options()
        }
    }

    fun export(
        survey: Survey,
        projection: Projection2D = Projection2D.PLAN,
        options: Options = Options.DEFAULT,
    ): String {
        val sketch = survey.getSketch(projection)
        val space = projection.project(survey)

        val content = exportFrame(survey, projection).scale(SCALE.toFloat())
        val frame = addBorder(exportFrame(survey, projection)).scale(SCALE.toFloat())

        val out = StringBuilder(4096)
        out.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        out.append("<svg")
            .append(" width=\"").append(number(frame.width)).append('"')
            .append(" height=\"").append(number(frame.height)).append('"')
            .append(" viewBox=\"")
            .append(number(frame.left)).append(' ')
            .append(number(frame.top)).append(' ')
            .append(number(frame.width)).append(' ')
            .append(number(frame.height)).append('"')
            .append(" xmlns=\"http://www.w3.org/2000/svg\">")
            .append('\n')

        out.append("  <title>").append(escape(survey.name)).append("</title>\n")
        survey.trip?.let { trip ->
            if (trip.hasCopyrightHolder() || trip.hasLicence()) {
                out.append("  <desc>")
                    .append(escape(copyrightLine(trip.copyrightHolder, trip.licence)))
                    .append("</desc>\n")
            }
        }

        if (options.whiteBackground) {
            out.append("  <g id=\"background\">\n    <rect")
                .append(" x=\"").append(number(frame.left)).append('"')
                .append(" y=\"").append(number(frame.top)).append('"')
                .append(" width=\"").append(number(frame.width)).append('"')
                .append(" height=\"").append(number(frame.height)).append('"')
                .append(" fill=\"").append(Colour.WHITE.toSvg()).append("\"/>\n  </g>\n")
        }

        if (options.showGrid) {
            out.append("  <g id=\"grid\">\n")
            writeGrid(out, content)
            out.append("  </g>\n")
        }

        if (options.showSketch) {
            out.append("  <g id=\"sketch\">\n")
            if (options.showSymbols) writeSymbolDefinitions(out, sketch)
            writeSketch(out, sketch, options)
            out.append("  </g>\n")
        }

        if (options.showCrossSections) {
            out.append("  <g id=\"cross-sections\">\n")
            writeCrossSections(out, sketch, options)
            out.append("  </g>\n")
        }

        out.append("  <g id=\"data\">\n")
        if (options.showCentreline) {
            out.append("    <g id=\"centreline\">\n")
            for ((index, leg) in survey.getAllLegsInChronoOrder().withIndex()) {
                if (!leg.hasDestination()) continue
                val line = space.legMap[leg] ?: continue
                val from = survey.getOriginatingStation(leg)?.name ?: "?"
                writeLine(
                    out,
                    line.start,
                    line.end,
                    "$from-${leg.destination.name}",
                    "red",
                    options.legStrokeWidth,
                )
                // The index keeps ids unique even where two stations share a name, which the
                // model permits.
                if (index < 0) break
            }
            out.append("    </g>\n")
        }

        if (options.showSplays) {
            out.append("    <g id=\"splays\">\n")
            for (station in survey.getAllStationsInChronoOrder()) {
                var count = 0
                for (leg in station.onwardLegs) {
                    if (leg.hasDestination()) continue
                    val line = space.legMap[leg] ?: continue
                    writeLine(
                        out,
                        line.start,
                        line.end,
                        "${station.name}-Splay$count",
                        "red",
                        options.splayStrokeWidth,
                    )
                    count++
                }
            }
            out.append("    </g>\n")
        }

        if (options.showStations) {
            out.append("    <g id=\"stations\">\n")
            for (station in survey.getAllStationsInChronoOrder()) {
                val at = space.stationMap[station] ?: continue
                out.append("      <text")
                    .append(" id=\"").append(escape(station.name)).append('"')
                    .append(" x=\"").append(number(at.x * SCALE)).append('"')
                    .append(" y=\"").append(number(at.y * SCALE)).append('"')
                    .append(" font-size=\"").append(STATION_FONT).append('"')
                    .append(" stroke=\"black\">")
                    .append(escape(station.name))
                    .append("</text>\n")
            }
            out.append("    </g>\n")
        }
        out.append("  </g>\n")

        out.append("</svg>\n")
        return out.toString()
    }

    // -------------------------------------------------------------------------------------
    // The frame
    // -------------------------------------------------------------------------------------

    /** The union of everything drawn and everything surveyed, from `ExportFrameFactory`. */
    fun exportFrame(survey: Survey, projection: Projection2D): Frame =
        Frame.from(survey.getSketch(projection)).union(Frame.from(projection.project(survey)))

    /**
     * Padding by size band and then rounding out to whole metres, exactly as the Java does — which
     * is what puts the grid on round numbers rather than wherever the cave happens to end.
     */
    fun addBorder(frame: Frame): Frame =
        frame.addPadding(paddingFor(frame.width), paddingFor(frame.height)).expandToNearest(1)

    private fun paddingFor(dimension: Float): Int =
        when {
            dimension <= 10 -> 1
            dimension <= 50 -> 5
            else -> 10
        }

    /**
     * A round number near an eighth of the drawing's width: 1, 2 or 5 times a power of ten.
     *
     * Used for both the scale bar and the grid spacing, so they always agree.
     */
    fun scaleBarLength(widthInMetres: Double): Double {
        val target = widthInMetres / 8.0
        val exponent = floor(log10(max(target, 1e-6)))
        val base = 10.0.pow(exponent)
        var best = base
        for (mantissa in listOf(1.0, 2.0, 5.0)) {
            val candidate = mantissa * base
            if (candidate <= target) best = candidate
        }
        return best
    }

    // -------------------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------------------

    /**
     * A faint grid at the scale-bar interval, so the drawing and the bar always agree.
     *
     * The spacing comes from the *width*, as the Java's does, which bounds the vertical lines to
     * about eight — and says nothing at all about the horizontal ones. A tall narrow drawing, which
     * is exactly what a deep shaft series in extended elevation is, therefore asks the Java for as
     * many horizontal lines as it takes to cross the height at a spacing chosen for the width. A
     * test with a label a long way off the passage found that here as an out-of-memory error; on a
     * phone it would be an export that never finishes.
     *
     * So the spacing is widened when it would produce more than [MAX_GRID_LINES] in either
     * direction. For any ordinary survey that is not reached and the output is the Java's.
     */
    private fun writeGrid(out: StringBuilder, content: Frame) {
        val widthInMetres = content.width / SCALE.toDouble()
        if (widthInMetres <= 0) return
        val preferred = scaleBarLength(widthInMetres) * SCALE
        if (preferred <= 0) return
        val longest = max(content.width, content.height).toDouble()
        val spacing = max(preferred, longest / MAX_GRID_LINES)
        if (spacing <= 0) return

        var x = kotlin.math.ceil(content.left / spacing) * spacing
        while (x <= content.right) {
            writeGridLine(out, x, content.top.toDouble(), x, content.bottom.toDouble())
            x += spacing
        }
        var y = kotlin.math.ceil(content.top / spacing) * spacing
        while (y <= content.bottom) {
            writeGridLine(out, content.left.toDouble(), y, content.right.toDouble(), y)
            y += spacing
        }
    }

    private fun writeGridLine(out: StringBuilder, x1: Double, y1: Double, x2: Double, y2: Double) {
        out.append("    <line")
            .append(" x1=\"").append(number(x1.toFloat())).append('"')
            .append(" y1=\"").append(number(y1.toFloat())).append('"')
            .append(" x2=\"").append(number(x2.toFloat())).append('"')
            .append(" y2=\"").append(number(y2.toFloat())).append('"')
            .append(" stroke=\"#cccccc\" stroke-width=\"1\"/>\n")
    }

    /**
     * One `<symbol>` per kind actually used, in enum order.
     *
     * The Java collects these into a `HashSet` and writes them in whatever order that yields, which
     * is one of the two things making its output differ between runs.
     */
    private fun writeSymbolDefinitions(out: StringBuilder, sketch: Sketch) {
        val used = sketch.symbolDetails.mapNotNull { Symbol.byTherionName(it.symbolName) }.toSet()
        if (used.isEmpty()) return
        out.append("    <defs>\n")
        for (symbol in Symbol.entries) {
            if (symbol !in used) continue
            out.append("      <symbol id=\"").append(symbol.therionName)
                .append("\" viewBox=\"0 0 ").append(number(Symbol.VIEWPORT)).append(' ')
                .append(number(Symbol.VIEWPORT)).append("\">\n")
            for (path in symbol.paths) {
                out.append("        <path d=\"").append(path)
                    .append("\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1\"/>\n")
            }
            out.append("      </symbol>\n")
        }
        out.append("    </defs>\n")
    }

    private fun writeSketch(out: StringBuilder, sketch: Sketch, options: Options) {
        for (detail in sketch.pathDetails) {
            if (detail.path.isEmpty()) continue
            out.append("    <polyline points=\"")
                .append(detail.path.joinToString(" ") { point(it) })
                .append("\" stroke=\"").append(detail.colour.toSvg())
                .append("\" stroke-width=\"").append(options.sketchStrokeWidth)
                .append("\" fill=\"none\"/>\n")
        }

        for (label in sketch.textDetails) {
            out.append("    <text")
                .append(" x=\"").append(number(label.position.x * SCALE)).append('"')
                .append(" y=\"").append(number(label.position.y * SCALE)).append('"')
                .append(" font-size=\"").append(number(label.size * SCALE)).append('"')
                .append(" stroke=\"").append(label.colour.toSvg()).append("\">")
                .append(escape(label.text))
                .append("</text>\n")
        }

        if (!options.showSymbols) return
        for (stamp in sketch.symbolDetails) {
            val symbol = Symbol.byTherionName(stamp.symbolName) ?: continue
            val size = stamp.size * SCALE
            val centreX = stamp.position.x * SCALE
            val centreY = stamp.position.y * SCALE
            out.append("    <use href=\"#").append(symbol.therionName).append('"')
                .append(" width=\"").append(number(size)).append('"')
                .append(" height=\"").append(number(size)).append('"')
                .append(" x=\"").append(number(centreX - size / 2)).append('"')
                .append(" y=\"").append(number(centreY - size / 2)).append('"')
                .append(" color=\"").append(stamp.colour.toSvg()).append('"')
            if (symbol.isDirectional) {
                out.append(" transform=\"rotate(").append(number(stamp.angle)).append(',')
                    .append(number(centreX)).append(',').append(number(centreY)).append(")\"")
            }
            out.append("/>\n")
        }
    }

    /**
     * The passage profile at each station, drawn where it was dropped on the plan.
     *
     * The rays are the station's splays, rotated onto the section's bearing by
     * [org.hwyl.sexytopo.shared.model.sketch.CrossSection.getProjection], and the sub-sketch is
     * whatever was drawn inside the section afterwards.
     */
    private fun writeCrossSections(out: StringBuilder, sketch: Sketch, options: Options) {
        val sectionScale = sketch.crossSectionScale
        for (detail in sketch.crossSectionDetails) {
            val centre = detail.position
            out.append("    <g id=\"x-section-")
                .append(escape(detail.crossSection.station.name)).append("\">\n")
            for (line in detail.crossSection.getProjection().legMap.values) {
                val end = line.end.scale(sectionScale)
                writeLine(
                    out,
                    centre,
                    Coord2D(centre.x + end.x, centre.y + end.y),
                    null,
                    "red",
                    options.splayStrokeWidth,
                )
            }
            out.append("    </g>\n")
        }
    }

    private fun writeLine(
        out: StringBuilder,
        start: Coord2D,
        end: Coord2D,
        id: String?,
        stroke: String,
        strokeWidth: Int,
    ) {
        out.append("      <polyline")
        if (id != null) out.append(" id=\"").append(escape(id)).append('"')
        out.append(" points=\"").append(point(start)).append(' ').append(point(end)).append('"')
            .append(" stroke=\"").append(stroke)
            .append("\" stroke-width=\"").append(strokeWidth)
            .append("\" fill=\"none\"/>\n")
    }

    // -------------------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------------------

    private fun point(coord: Coord2D): String =
        number(coord.x * SCALE) + "," + number(coord.y * SCALE)

    /**
     * Numbers, to three decimal places and with no trailing zeros.
     *
     * `Float.toString` cannot be used: it renders large and small values in exponent notation, and
     * it does so *differently* on the JVM and on Kotlin/Wasm — a divergence this port has already
     * been bitten by once. Exponent notation is also not valid in an SVG path, so the risk is a
     * file that silently will not open.
     */
    internal fun number(value: Float): String = formatFixedTrimmed(value, 3)

    private fun copyrightLine(holder: String, licence: String): String =
        listOf(holder, licence).filter { it.isNotBlank() }.joinToString(", ")

    private fun escape(text: String): String =
        buildString(text.length) {
            for (character in text) {
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(character)
                }
            }
        }
}

/** SVG wants `#rrggbb`; [Colour] holds an ARGB integer. */
private fun Colour.toSvg(): String {
    val rgb = intValue and 0xFFFFFF
    val hex = rgb.toString(16).padStart(6, '0')
    return "#$hex"
}
