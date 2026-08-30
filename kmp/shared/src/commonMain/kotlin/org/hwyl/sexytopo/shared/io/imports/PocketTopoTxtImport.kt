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
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * PocketTopo's text export, read back in.
 *
 * Ported from `PocketTopoTxtImporter`. The only importer here that brings a *drawing* in as well as
 * a centreline: the file carries the plan and the extended elevation as lists of polylines, in
 * PocketTopo's own coordinates, which have to be shifted onto this app's before they mean anything.
 *
 * ## The offset
 *
 * PocketTopo draws in whatever coordinates its own layout happened to use; SexyTopo draws relative
 * to the origin station. [extractOffset] finds the same station in both and subtracts, which is why
 * the sketch is parsed after the centreline rather than with it.
 *
 * ## What is fixed rather than reproduced
 *
 * Four places where the Java throws on a file it should be able to read or refuse. All four are
 * crashes rather than wrong answers, and an import that crashes takes the app down with a file the
 * surveyor cannot then get in at all:
 *
 * - A data row with fewer than five columns. The guard is `fields.length < 3` and the code reads
 *   `fields[3]` and `fields[4]`.
 * - A missing section. `getSection` calls `matcher.find()` without checking it matched and then
 *   `matcher.group(1)`, so a file with no PLAN block - one exported before anything was drawn -
 *   raises `IllegalStateException`.
 * - A short station line. `getOffsetForNamedStation` reads `tokens[2]` unguarded.
 * - The offset fallback. When the origin is not in the station list, the second attempt calls
 *   `offset.minus(...)` on a value that may be null, and looks up a projected position that may be
 *   null too.
 *
 * Everything else is the Java's, including its `¯\_(ツ)_/¯` fallback of drawing at the origin when
 * no anchor station can be found at all.
 */
object PocketTopoTxtImporter {

    /**
     * @param name what to call it. The file's TRIP block has no name field, so the caller's - which
     *   is the filename - is all there is.
     */
    fun read(text: String, name: String): Survey {
        val survey = Survey(name)

        // The Java's own FIXME: the TRIP block carries a date and a declination and neither is
        // read. Left alone rather than half-ported, because a declination applied here and nowhere
        // else would silently rotate an imported cave against every other survey in the library.
        parseCentreline(survey, text)

        survey.elevationSketch = sketchFrom(survey, text, "ELEVATION", Projection2D.EXTENDED_ELEVATION)
        survey.planSketch = sketchFrom(survey, text, "PLAN", Projection2D.PLAN)

        survey.isSaved = true
        return survey
    }

    // ---------------------------------------------------------------------------------------
    // The centreline
    // ---------------------------------------------------------------------------------------

    internal fun parseCentreline(survey: Survey, fullText: String) {
        val text = section(fullText, "DATA") ?: return

        var firstStation = true
        for (line in text.lines()) {
            val fields = line.split("\t")
            // Five, not the Java's three: the columns read are 0 to 4.
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

            // A row naming a station that does not exist yet is a file written out of order, or one
            // with a gap in it. The Java sets the active station to null and fails later somewhere
            // less obvious; skipping the row loses that shot and keeps the rest of the cave.
            val fromStation = survey.getStationByName(fromStationName) ?: continue
            survey.activeStation = fromStation

            if (toStationName.isEmpty()) {
                // No far end: a splay, shot into the dark to measure the passage.
                SurveyUpdater.update(survey, Leg(distance, azimuth, inclination))
            } else {
                val leg = Leg(distance, azimuth, inclination, Station(toStationName))
                SurveyBuilder.updateWithNewStation(survey, leg)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The drawings
    // ---------------------------------------------------------------------------------------

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
     * How far PocketTopo's drawing coordinates are from this app's.
     *
     * Ported from `extractOffset`, including its guesswork: try the origin station, then the far
     * end of its first connected leg - whose own position in *this* app's projection then has to
     * come off the offset as well - and if neither is in the file, draw where it lands.
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
        // Both of these are null-checked, which the Java does not do: it dereferences the offset it
        // has just failed to find, and a projected position that a station absent from the
        // projection would not have either.
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

    /**
     * Every `POLYLINE <colour>` block in a section, as a stroke.
     *
     * The y is negated on the way in: PocketTopo's drawing has y increasing upwards and this app's
     * increases downwards, the same flip [Projection2D] applies to the centreline.
     */
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

    /**
     * PocketTopo's colour names are this app's, with one exception it spells the American way.
     * Anything unrecognised draws black rather than refusing the file.
     */
    internal fun interpretColour(colourText: String): Colour {
        val name = if (colourText == "GRAY") "GREY" else colourText
        return BrushColour.entries.firstOrNull { it.name == name }?.colour ?: Colour.BLACK
    }

    // ---------------------------------------------------------------------------------------
    // The file's shape
    // ---------------------------------------------------------------------------------------

    /**
     * The body of a top-level section: a header line, the content, then a blank line.
     *
     * Null rather than the Java's exception when there is none — see the class comment.
     */
    internal fun section(text: String, header: String): String? {
        // The trailing pair is the Java's own hack, and it is load-bearing: the last section of the
        // file is not followed by a blank line, so without this it never matches.
        val terminated = "$text\n\n"
        val pattern = Regex("^${header.uppercase()}\n(.*?)\n^\n", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
        return pattern.find(terminated)?.groupValues?.get(1)
    }

    /**
     * The body of a sub-section: a header line, then everything up to the next line that is nothing
     * but capitals.
     *
     * Which is why `POLYLINE BROWN` does not end one and `SHOTS` does — the space disqualifies it.
     * Faithful to `getNamedSubSection`, whose `[A-Z]+` this is.
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
