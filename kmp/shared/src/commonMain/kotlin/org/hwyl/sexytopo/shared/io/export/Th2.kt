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
 * Ported from `io/thirdparty/therion/Th2Exporter`. This is the other half of getting a survey into
 * Therion: [XviExporter] writes the tracing image, and this writes the `.th2` that positions it and
 * carries the parts Therion understands as *data* rather than as pixels — stations by name, the
 * anchors that link a cross-section scrap to the station it belongs to, labels, and symbols as
 * Therion points.
 *
 * Which is to say the passage walls are deliberately not in here. They are in the XVI, because that
 * is what an XVI is for: the surveyor traces them in xtherion, where the result is Therion's own
 * line objects rather than a phone's polylines. A `.th2` that tried to guess which strokes were
 * walls and which were annotation would be guessing.
 *
 * ## Coordinates
 *
 * Survey space is y north-positive; Therion's canvas is y down. Every point emitted here is flipped
 * once, in the same place, for the same reason the XVI exporter flips once — see its note.
 */
object Th2Exporter {

    /**
     * What to write, from the app's `pref_therion_*` preferences with their own defaults.
     *
     * The `#` in a cross-section suffix is where the station's name goes; `##` and `###` zero-pad a
     * numeric one, so station 7 becomes `PX07` rather than `PX7` and a list of scraps sorts the way
     * a surveyor expects.
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
         * How many scraps this drawing is written as.
         *
         * One is a drawing; more is a set of containers to divide it into. Only the first carries
         * anything — the stations, the labels, the symbols; the rest are empty scraps with the same
         * projection and the same header, pre-named and ready to be drawn into.
         *
         * That sounds like nothing and is the way a large cave gets drawn up. Therion is slow on
         * one enormous scrap and a survey is worked on by several people, so a project is split
         * into scraps by area: one per chamber, one per level. Making them by hand means typing a
         * `scrap` header, remembering the projection, and keeping the names in step with the ones
         * the app generated — which is exactly the kind of copying that puts a plan scrap into an
         * elevation.
         */
        val scrapCount: Int = 1,
        /**
         * Whether the station points go in the first scrap.
         *
         * Off is for a project where the stations are their own scrap, so a change to the
         * centreline does not mean re-exporting a drawing somebody has since worked on. The cross-
         * section anchors travel with the stations, because a `-scrap` reference is written at the
         * station it belongs to.
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

        // Only when there is an image to place: a th2 referring to an xvi that was not exported
        // opens in xtherion with a missing-file complaint and no background at all, which is worse
        // than a scrap with no image.
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
            // Everything is in the first one. The others exist to be drawn into.
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
                    // Only alongside the stations they anchor to: a `-scrap` reference is written
                    // at its station, so with the stations left out there is nothing to hang it on.
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

    // ---------------------------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------------------------

    /**
     * A survey name safe to use as a scrap name.
     *
     * Ported from `TextTools.intelligentlySanitise`: spaces, tabs, newlines and colons become a
     * joining character — an underscore if the name already contains one, otherwise a hyphen, so a
     * name that was written `Swildons_Hole` does not come back half-hyphenated.
     */
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
     * A scrap name per cross-section, in the order the sections are held.
     *
     * A map rather than a computed name, because the main scrap and the section scraps have to
     * agree: the `-scrap` argument on a section point must name a scrap that exists further down
     * the file, or Therion reports a dangling reference.
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

    // ---------------------------------------------------------------------------------------
    // Scraps
    // ---------------------------------------------------------------------------------------

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
     * What one scrap of a drawing is called.
     *
     * The suffix is a *pattern* once there is more than one scrap: `#` takes the number, `##`
     * zero-pads it to two, so `-plan-##` gives `Name-plan-01`, `Name-plan-02`. A suffix with no
     * placeholder gets the number appended, and a single unnumbered scrap keeps the plain name it
     * has always had — so an existing project's file does not change because this option arrived.
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

    /**
     * The `scrap ...` line, with the copyright line on the very next physical line rather than
     * separated by a blank one, as the Java does.
     */
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

            // The anchor that ties a cross-section scrap to its station. Without it the section is
            // a drawing on its own with nothing saying where in the cave it was taken.
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
     * A cross-section as its own scrap.
     *
     * Empty, deliberately: the Java writes the scrap header and `endscrap` with nothing between,
     * because the section's *drawing* is in the XVI for the surveyor to trace. The scrap exists so
     * that the anchor in the main scrap has something to point at, and so xtherion opens it as a
     * page to draw on.
     */
    private fun crossSectionScraps(
        survey: Survey,
        sketch: Sketch,
        sectionScrapNames: Map<String, String>,
        scale: Float,
    ): List<String> {
        // Sorted by the order the stations were surveyed, so the file reads in the order somebody
        // walked the cave rather than in whatever order the sections were drawn.
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
        // Therion's -scale takes two reference points: [px1 py1 px2 py2 rx1 ry1 rx2 ry2 unit].
        // Ten metres in the world against the same ten metres in picture units.
        val realWorld = 10.0f
        val picture = realWorld * scale * sectionScale
        val scaleArgument =
            "[0 0 ${twoDp(picture)} ${twoDp(picture)} 0 0 ${twoDp(realWorld)} ${twoDp(realWorld)} m]"
        val start = startLines("scrap $name -projection none -scale $scaleArgument", survey)
        return listOf(start, "endscrap").joinToString("\n\n")
    }

    // ---------------------------------------------------------------------------------------
    // The XVI block
    // ---------------------------------------------------------------------------------------

    /**
     * The `##XTHERION##` lines that put the tracing image behind the scrap.
     *
     * Not part of the Therion language: these are comments that xtherion, its editor, reads back.
     */
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

    // ---------------------------------------------------------------------------------------
    // Bits and pieces
    // ---------------------------------------------------------------------------------------

    private fun point(at: Coord2D, name: String, vararg arguments: String): String =
        point(at, name, arguments.toList())

    private fun point(at: Coord2D, name: String, arguments: List<String>): String =
        "point ${at.x} ${at.y} $name " + arguments.joinToString(" ")

    /**
     * A size in metres as one of Therion's five point sizes.
     *
     * The Java's comment: "These numbers were determined by creating some stal in Therion at
     * different scales and seeing how big they came out."
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
