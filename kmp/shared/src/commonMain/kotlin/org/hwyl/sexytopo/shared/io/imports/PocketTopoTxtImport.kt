package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.BrushColour
import org.hwyl.sexytopo.shared.survey.SurveyBuilder

/**
 * PocketTopo's text export, read back in.
 *
 * The only importer here that brings a *drawing* in as well as a centreline: the file carries
 * the plan and elevation as polylines in PocketTopo's own coordinates, shifted onto this app's.
 * [extractOffset] finds the origin station in both and subtracts — why the sketch is parsed
 * after the centreline, not with it.
 *
 * ## Fixed rather than reproduced
 *
 * Four places where the Java throws on a file it should be able to read or refuse, each a crash
 * rather than a wrong answer:
 *
 * - A data row with fewer than five columns: the guard is `fields.length < 3` but the code reads
 *   `fields[3]` and `fields[4]`.
 * - A missing section: `getSection` calls `matcher.find()` without checking it matched, so a
 *   file with no PLAN block raises `IllegalStateException`.
 * - A short station line: `getOffsetForNamedStation` reads `tokens[2]` unguarded.
 * - The offset fallback: when the origin isn't in the station list, the second attempt
 *   dereferences a null offset and an absent projected position.
 *
 * Everything else matches the Java, including its fallback of drawing at the origin when no
 * anchor station can be found at all.
 */
object PocketTopoTxtImporter {

    /** @param name what to call it — the TRIP block has no name field. */
    fun read(text: String, name: String): Survey {
        val survey = Survey(name)

        // The Java's own FIXME: the TRIP block's date and declination are never read. Left
        // alone, since applying declination here alone would silently rotate the cave against
        // the rest of the library.
        parseCentreline(survey, text)

        survey.elevationSketch = sketchFrom(survey, text, "ELEVATION", Projection2D.EXTENDED_ELEVATION)
        survey.planSketch = sketchFrom(survey, text, "PLAN", Projection2D.PLAN)

        survey.isSaved = true
        return survey
    }

    internal fun parseCentreline(survey: Survey, fullText: String) {
        val text = section(fullText, "DATA") ?: return

        var firstStation = true
        for (line in text.lines()) {
            val fields = line.split("\t")
            if (fields.size < 5) continue

            val fromStationName = fields[0]
            val toStationName = fields[1]
            val azimuth = fields[2].trim().toFloatOrNull() ?: continue
            val inclination = fields[3].trim().toFloatOrNull() ?: continue
            val distance = fields[4].trim().toFloatOrNull() ?: continue

            if (firstStation) {
                survey.origin.name = fromStationName
                firstStation = false
            }

            // A row naming an unseen station is a file written out of order or with a gap;
            // skipping loses that shot but keeps the rest of the cave.
            val fromStation = survey.getStationByName(fromStationName) ?: continue
            survey.activeStation = fromStation

            if (toStationName.isEmpty()) {
                // No far end: a splay. `SurveyBuilder.addSplay` rather than the Java's
                // `SurveyUpdater.update`, whose triple-shot promotion would otherwise turn three
                // similar splays into a phantom auto-named station. The Java's *binary* importer
                // avoids this for the same reason; its text importer doesn't, which reads as an
                // oversight.
                SurveyBuilder.addSplay(survey, fromStation, Leg(distance, azimuth, inclination))
            } else {
                val leg = Leg(distance, azimuth, inclination, Station(toStationName))
                SurveyBuilder.updateWithNewStation(survey, leg)
            }
        }
    }

    private fun sketchFrom(
        survey: Survey,
        fullText: String,
        header: String,
        projection: Projection2D,
    ): Sketch {
        val sketch = Sketch()
        val text = section(fullText, header) ?: return sketch
        val offset = extractOffset(survey, text, projection.project(survey))
        sketch.pathDetails = parsePolylines(text, offset).toMutableList()
        return sketch
    }

    /**
     * How far PocketTopo's drawing coordinates are from this app's: try the origin station,
     * then the far end of its first connected leg, then give up and draw where it lands.
     */
    internal fun extractOffset(
        survey: Survey,
        text: String,
        projection: Space<Coord2D>,
    ): Coord2D {
        val lines = namedSubSection(text, "STATIONS").lines()

        offsetForNamedStation(lines, survey.origin.name)?.let { return it }

        val onward = survey.origin.onwardLegs.firstOrNull { it.hasDestination() } ?: return Coord2D.ORIGIN
        val anchor = onward.destination
        val offset = offsetForNamedStation(lines, anchor.name) ?: return Coord2D.ORIGIN
        val position = projection.stationMap[anchor] ?: return Coord2D.ORIGIN
        return offset - position.flipVertically()
    }

    internal fun offsetForNamedStation(lines: List<String>, stationName: String): Coord2D? {
        for (line in lines) {
            val tokens = line.split("\t")
            if (tokens.size < 3) continue
            if (tokens[2] != stationName) continue
            val x = tokens[0].trim().toFloatOrNull() ?: continue
            val y = tokens[1].trim().toFloatOrNull() ?: continue
            return Coord2D(x, y)
        }
        return null
    }

    /** Every `POLYLINE <colour>` block as a stroke; y is negated, matching [Projection2D]'s flip. */
    internal fun parsePolylines(text: String, offset: Coord2D): List<PathDetail> {
        val paths = mutableListOf<PathDetail>()
        var inPolyline = false
        var colour = Colour.BLACK
        var current: PathDetail? = null

        for (line in text.lines()) {
            if (line.startsWith(POLYLINE)) {
                current?.let { paths.add(it) }
                current = null
                colour = interpretColour(line.removePrefix(POLYLINE).trim())
                inPolyline = true
                continue
            }
            if (!inPolyline) continue

            val coords = line.split("\t")
            if (coords.size < 2) continue
            val x = coords[0].trim().toFloatOrNull() ?: continue
            val y = coords[1].trim().toFloatOrNull() ?: continue
            val point = Coord2D(x - offset.x, -(y - offset.y))

            if (current == null) current = PathDetail(point, colour) else current.lineTo(point)
        }

        current?.let { paths.add(it) }
        return paths
    }

    /** PocketTopo's colour names are this app's, except GRAY/GREY; unrecognised draws black. */
    internal fun interpretColour(colourText: String): Colour {
        val name = if (colourText == "GRAY") "GREY" else colourText
        return BrushColour.entries.firstOrNull { it.name == name }?.colour ?: Colour.BLACK
    }

    /** The body of a top-level section: header line, content, blank line; null when absent. */
    internal fun section(text: String, header: String): String? {
        // The trailing pair is the Java's own hack, and it is load-bearing: the last section of the
        // file is not followed by a blank line, so without this it never matches.
        val terminated = "$text\n\n"
        val pattern = Regex("^${header.uppercase()}\n(.*?)\n^\n", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
        return pattern.find(terminated)?.groupValues?.get(1)
    }

    /**
     * The body of a sub-section, up to the next all-capitals line — why `POLYLINE BROWN` doesn't
     * end one (the space disqualifies it) but `SHOTS` does.
     */
    internal fun namedSubSection(text: String, header: String): String {
        val lines = mutableListOf<String>()
        var inSubSection = false
        for (line in text.lines()) {
            if (line.trim() == header) {
                inSubSection = true
                continue
            }
            if (!inSubSection) continue
            if (NEXT_SUB_SECTION.matches(line)) break
            lines.add(line)
        }
        return lines.joinToString("\n")
    }

    private const val POLYLINE = "POLYLINE"

    private val NEXT_SUB_SECTION = Regex("[A-Z]+")
}
