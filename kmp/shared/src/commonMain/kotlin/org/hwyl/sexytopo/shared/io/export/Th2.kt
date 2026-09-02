package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The drawing as a Therion scrap file.
 *
 * [XviExporter] writes the tracing image; this writes the `.th2` that positions it and carries
 * what Therion treats as *data* rather than pixels — stations by name, cross-section anchors,
 * labels, and symbols as Therion points. The passage walls themselves are deliberately not in
 * here: they're traced by the surveyor in xtherion, into Therion's own line objects.
 *
 * Survey space is y north-positive; Therion's canvas is y down, so every point emitted here is
 * flipped once, in the same place — see the XVI exporter's note.
 */
object Th2Exporter {

    /**
     * What to write, from the app's `pref_therion_*` preferences. `#` in a cross-section suffix
     * is where the station's name goes; `##`/`###` zero-pad it, so scraps sort the way a
     * surveyor expects.
     */
    data class Options(
        val planScrapSuffix: String = "-plan",
        val elevationScrapSuffix: String = "-ee",
        val planCrossSectionSuffix: String = "PX#",
        val elevationCrossSectionSuffix: String = "EEX#",
        val crossSections: Boolean = true,
        val labels: Boolean = true,
        val symbols: Boolean = true,
        /**
         * How many scraps this drawing is written as. Only the first carries content —
         * stations, labels, symbols; the rest are empty, pre-named scraps ready to be drawn
         * into, since Therion is slow on one enormous scrap and large caves are usually split
         * by area between surveyors.
         */
        val scrapCount: Int = 1,
        /**
         * Whether the station points go in the first scrap. Off when the stations are their own
         * scrap, so a centreline change doesn't mean re-exporting a drawing someone has worked
         * on. Cross-section anchors travel with the stations either way, since a `-scrap`
         * reference sits at its station.
         */
        val stationsInFirstScrap: Boolean = true,
        /** The XVI to show behind the drawing; omitted when there is none. */
        val xviFileName: String? = null,
    ) {
        companion object {
            val DEFAULT = Options()
        }
    }

    fun export(
        survey: Survey,
        projection: Projection2D,
        space: Space<Coord2D> = projection.project(survey),
        innerFrame: Frame,
        outerFrame: Frame,
        scale: Float,
        options: Options = Options.DEFAULT,
    ): String {
        val sections = mutableListOf<String>()
        sections.add(ENCODING)

        // Only when there is an image to place: a th2 referring to a missing xvi opens in
        // xtherion with a missing-file complaint rather than just having no background.
        options.xviFileName?.let { sections.add(xviBlock(survey, space, it, outerFrame)) }

        val sketch = survey.getSketch(projection)
        val baseName = scrapBaseName(survey)

        val sectionScrapNames =
            if (options.crossSections) {
                crossSectionScrapNames(sketch, projection, baseName, options)
            } else {
                emptyMap()
            }

        val total = if (options.scrapCount < 1) 1 else options.scrapCount
        for (index in 1..total) {
            val first = index == 1
            val stations = first && options.stationsInFirstScrap
            sections.add(
                scrap(
                    survey = survey,
                    name = scrapName(baseName, scrapSuffix(projection, options), index, total),
                    projection = projection,
                    sketch = sketch,
                    space = space,
                    scale = scale,
                    sectionScrapNames = if (stations) sectionScrapNames else emptyMap(),
                    includeStations = stations,
                    includeSketchContent = first,
                    options = options,
                ),
            )
        }

        if (options.crossSections) {
            sections.addAll(
                crossSectionScraps(survey, sketch, sectionScrapNames, scale),
            )
        }

        return sections.joinToString("\n\n")
    }

    /** A survey name safe to use as a scrap name: spaces, tabs, newlines and colons become `_`/`-`. */
    fun scrapBaseName(survey: Survey): String = sanitise(survey.name)

    internal fun sanitise(text: String): String {
        val joiner = if ('_' in text) '_' else '-'
        var result = text
        for (character in PROBLEMATIC) {
            result = result.replace(character, joiner)
        }
        return result
    }

    private fun scrapSuffix(projection: Projection2D, options: Options): String =
        if (projection == Projection2D.PLAN) {
            options.planScrapSuffix
        } else {
            options.elevationScrapSuffix
        }

    private fun crossSectionSuffix(projection: Projection2D, options: Options): String =
        if (projection == Projection2D.PLAN) {
            options.planCrossSectionSuffix
        } else {
            options.elevationCrossSectionSuffix
        }

    /**
     * A scrap name per cross-section. A map, not a computed name, since the main scrap and
     * section scraps must agree on names Therion can find further down the file.
     */
    private fun crossSectionScrapNames(
        sketch: Sketch,
        projection: Projection2D,
        baseName: String,
        options: Options,
    ): Map<String, String> {
        val suffix = crossSectionSuffix(projection, options)
        val names = LinkedHashMap<String, String>()
        for (detail in sketch.crossSectionDetails) {
            names[detail.station.name] = baseName + expandHashes(suffix, detail.station.name)
        }
        return names
    }

    /** `PX#` → `PX7`, `PX##` → `PX07`, `PX###` → `PX007`; a non-numeric name is substituted raw. */
    internal fun expandHashes(suffix: String, stationName: String): String =
        when {
            "###" in suffix -> suffix.replace("###", pad(stationName, 3))
            "##" in suffix -> suffix.replace("##", pad(stationName, 2))
            "#" in suffix -> suffix.replace("#", stationName)
            else -> suffix
        }

    private fun pad(stationName: String, width: Int): String {
        val number = stationName.toIntOrNull() ?: return stationName
        return number.toString().padStart(width, '0')
    }

    private fun scrap(
        survey: Survey,
        name: String,
        projection: Projection2D,
        sketch: Sketch,
        space: Space<Coord2D>,
        scale: Float,
        sectionScrapNames: Map<String, String>,
        includeStations: Boolean = true,
        includeSketchContent: Boolean = true,
        options: Options,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(startLines("scrap $name -projection ${therionProjection(projection)}", survey))
        parts.addAll(
            scrapCommands(
                survey,
                sketch,
                space,
                scale,
                sectionScrapNames,
                includeStations,
                includeSketchContent,
                options,
            ),
        )
        parts.add("endscrap")
        return parts.joinToString("\n\n")
    }

    /**
     * What one scrap of a drawing is called. The suffix is a *pattern* once there's more than
     * one scrap (`#`/`##` take the number); a single unnumbered scrap keeps its plain name, so
     * existing projects don't change.
     */
    internal fun scrapName(baseName: String, suffix: String, index: Int, total: Int): String {
        if (total == 1 && '#' !in suffix) return baseName + suffix
        val numbered =
            when {
                "##" in suffix -> suffix.replace("##", index.toString().padStart(2, '0'))
                "#" in suffix -> suffix.replace("#", index.toString())
                total > 1 -> suffix + index
                else -> suffix
            }
        return baseName + numbered
    }

    private fun therionProjection(projection: Projection2D): String =
        when (projection) {
            Projection2D.PLAN -> "plan"
            Projection2D.EXTENDED_ELEVATION -> "extended"
            else -> ""
        }

    /** The `scrap ...` line, with the copyright line on the next physical line, as the Java does. */
    private fun startLines(startLine: String, survey: Survey): String {
        val copyright = SurvexTherionWriter.copyrightLine(survey, SurveyFormat.THERION)
        if (copyright.isEmpty()) return startLine
        return startLine + "\n" + copyright.trimEnd('\n')
    }

    private fun scrapCommands(
        survey: Survey,
        sketch: Sketch,
        space: Space<Coord2D>,
        scale: Float,
        sectionScrapNames: Map<String, String>,
        includeStations: Boolean,
        includeSketchContent: Boolean,
        options: Options,
    ): List<String> {
        val commands = mutableListOf<String>()

        for (station in if (includeStations) survey.getAllStationsInChronoOrder() else emptyList()) {
            val at = space.stationMap[station] ?: continue
            val point = at.flipVertically().scale(scale)
            commands.add(point(point, "station", "-name", station.name))

            // The anchor tying a cross-section scrap to its station, so it isn't a drawing
            // floating in space.
            val scrapName = sectionScrapNames[station.name] ?: continue
            val detail = sketch.crossSectionDetails.firstOrNull { it.station == station } ?: continue
            val sectionAt = detail.position.flipVertically().scale(scale)
            commands.add(point(sectionAt, "section", "-scrap", scrapName))
        }

        if (includeSketchContent && options.labels) {
            for (label in sketch.textDetails) {
                val at = label.position.scale(scale).flipVertically()
                commands.add(
                    point(at, "label", "-text \"", label.text, "\" -scale", therionSize(label.size)),
                )
            }
        }

        if (includeSketchContent && options.symbols) {
            for (stamp in sketch.symbolDetails) {
                val at = stamp.position.scale(scale).flipVertically()
                val arguments = mutableListOf("-scale " + therionSize(stamp.size))
                val symbol = Symbol.byTherionName(stamp.symbolName)
                if (symbol != null && symbol.isDirectional) {
                    arguments.add("-orientation " + stamp.angle)
                }
                commands.add(point(at, stamp.symbolName, arguments))
            }
        }

        return commands
    }

    /**
     * A cross-section as its own empty scrap: the Java writes header and `endscrap` with
     * nothing between, since the drawing lives in the XVI. Exists so the main scrap's anchor has
     * something to point at.
     */
    private fun crossSectionScraps(
        survey: Survey,
        sketch: Sketch,
        sectionScrapNames: Map<String, String>,
        scale: Float,
    ): List<String> {
        // Sorted by survey order, so the file reads in the order the cave was walked.
        val order = survey.getAllStationsInChronoOrder()
        val sections =
            sketch.crossSectionDetails.sortedBy { detail ->
                order.indexOfFirst { it == detail.station }
            }

        return sections.mapNotNull { detail ->
            val name = sectionScrapNames[detail.station.name] ?: return@mapNotNull null
            crossSectionScrap(survey, name, scale, sketch.crossSectionScale)
        }
    }

    private fun crossSectionScrap(
        survey: Survey,
        name: String,
        scale: Float,
        sectionScale: Float,
    ): String {
        // Therion's -scale takes two reference points: ten world metres against the same in
        // picture units.
        val realWorld = 10.0f
        val picture = realWorld * scale * sectionScale
        val scaleArgument =
            "[0 0 ${twoDp(picture)} ${twoDp(picture)} 0 0 ${twoDp(realWorld)} ${twoDp(realWorld)} m]"
        val start = startLines("scrap $name -projection none -scale $scaleArgument", survey)
        return listOf(start, "endscrap").joinToString("\n\n")
    }

    /** The `##XTHERION##` lines that put the tracing image behind the scrap (xtherion reads these back as comments). */
    internal fun xviBlock(
        survey: Survey,
        space: Space<Coord2D>,
        fileName: String,
        outerFrame: Frame,
    ): String {
        val lines = mutableListOf<String>()
        lines.add(
            xtherion(
                "xth_me_area_adjust",
                twoDp(outerFrame.left),
                twoDp(outerFrame.bottom),
                twoDp(outerFrame.right),
                twoDp(outerFrame.top),
            ),
        )

        val origin = survey.origin
        val at = space.stationMap[origin] ?: return lines.joinToString("\n")

        lines.add(
            xtherion(
                "xth_me_image_insert",
                "{${at.x} 1 1.0}",
                "{${-at.y} ${origin.name}}",
                "\"$fileName\"",
                "0",
                "{}",
            ),
        )
        lines.add(xtherion("xth_me_area_zoom_to", "25"))
        return lines.joinToString("\n")
    }

    private fun point(at: Coord2D, name: String, vararg arguments: String): String =
        point(at, name, arguments.toList())

    private fun point(at: Coord2D, name: String, arguments: List<String>): String =
        "point ${at.x} ${at.y} $name " + arguments.joinToString(" ")

    /**
     * A size in metres as one of Therion's five point sizes. The Java's comment: "determined by
     * creating some stal in Therion at different scales and seeing how big they came out."
     */
    internal fun therionSize(sizeInMetres: Float): String =
        when {
            sizeInMetres < 0.45f -> "xs"
            sizeInMetres < 0.6f -> "s"
            sizeInMetres < 0.9f -> "m"
            sizeInMetres < 1.3f -> "l"
            else -> "xl"
        }

    private fun twoDp(value: Float): String = formatFixed(value, 2)

    private fun xtherion(command: String, vararg values: String): String =
        (listOf("##XTHERION##", command) + values).joinToString(" ")

    private const val ENCODING = "encoding utf-8"

    /** `TextTools.PROBLEMATIC`: what cannot appear in a scrap name. */
    private val PROBLEMATIC = charArrayOf(' ', '\t', '\n', '\r', ':')
}
