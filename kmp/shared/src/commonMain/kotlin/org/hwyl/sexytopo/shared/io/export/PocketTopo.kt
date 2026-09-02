package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Export to PocketTopo's text format.
 *
 * Ported from `control/io/thirdparty/pockettopo/PocketTopoTxtExporter`. Unlike the Survex,
 * Therion and Compass exporters, this one is **not** pinned byte-for-byte against the Java, for
 * two reasons.
 *
 * First, the Java's own output isn't reproducible: `Space` holds stations and legs in a plain
 * `HashMap`, and neither overrides `hashCode`, so iteration order follows identity hash codes
 * that differ run to run — verified by building the same survey twice in one JVM and diffing the
 * shuffled result. This port picks a defined order instead
 * ([Survey.getAllStationsInChronoOrder] / [Survey.getAllLegsInChronoOrder]), the only deliberate
 * divergence; everything *defined* in the Java, including its apparent mistakes, is reproduced
 * exactly — see [exportSketch].
 *
 * Second, coordinates are a bare float-to-string: the Java writes `coords.x` straight into the
 * file, and Kotlin's `Float.toString` only agrees with Java's on the JVM — on Kotlin/Wasm the same
 * small value renders differently. See `FloatRenderingTest` and the README.
 */
object PocketTopoExporter {

    const val FILE_EXTENSION = "txt"

    /** What the Java writes when a survey has no trip recorded. */
    const val NO_TRIP_DATE = "1970-01-01"

    fun export(survey: Survey): String {
        val builder = StringBuilder()
        builder.append("TRIP\n")

        builder.append("DATE ")
        val trip = survey.trip
        if (trip != null) {
            builder.append(trip.surveyDate.toString())
        } else {
            // The Java's own newline placement: this branch adds its own newline *and* the
            // shared one below, so a survey with no trip gets an extra blank line here.
            builder.append(NO_TRIP_DATE).append('\n')
        }
        builder.append('\n')

        builder.append("DECLINATION\t0.00\n")
        builder.append(exportData(survey)).append('\n')
        builder.append(exportPlan(survey)).append('\n')
        builder.append(exportExtendedElevation(survey))

        return builder.toString()
    }

    fun exportData(survey: Survey): String {
        val builder = StringBuilder()
        builder.append("DATA\n")
        for ((from, leg) in SurvexTherionWriter.chronologicalEntries(survey)) {
            formatEntry(builder, from, leg)
            builder.append('\n')
        }
        return builder.toString()
    }

    fun exportPlan(survey: Survey): String =
        "PLAN\n" +
            exportStationCoords(survey, Projection2D.PLAN.project(survey)) + "\n" +
            exportSketch(survey.planSketch) + "\n"

    fun exportExtendedElevation(survey: Survey): String =
        "ELEVATION\n" +
            exportStationCoords(survey, Projection2D.EXTENDED_ELEVATION.project(survey)) + "\n" +
            exportSketch(survey.elevationSketch) + "\n"

    /**
     * Sketch strokes, one `POLYLINE` per path followed by its points.
     *
     * The y of a *sketch* point is negated here but not in [exportStationCoords] — an asymmetry
     * that's the Java's own and looks like a bug, but is kept as defined format behaviour rather
     * than a porting decision to fix.
     */
    fun exportSketch(sketch: Sketch): String {
        val lines = mutableListOf<String>()
        for (pathDetail in sketch.pathDetails) {
            lines.add("POLYLINE " + pathDetail.colour.toString())
            for (coords in pathDetail.path) {
                lines.add(renderCoordinate(coords.x) + "\t" + renderCoordinate(-coords.y))
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Station positions and the lines between them, ordered by the survey's own chronology
     * rather than by hash (see the class doc). A station or leg the projection didn't place is
     * skipped.
     */
    fun exportStationCoords(survey: Survey, space: Space<Coord2D>): String {
        val lines = mutableListOf<String>()

        lines.add("STATIONS")
        for (station in survey.getAllStationsInChronoOrder()) {
            val coords = space.stationMap[station] ?: continue
            lines.add(
                renderCoordinate(coords.x) + "\t" + renderCoordinate(coords.y) + "\t" + station.name,
            )
        }

        lines.add("SHOTS")
        for (leg in survey.getAllLegsInChronoOrder()) {
            val line = space.legMap[leg] ?: continue
            lines.add(
                renderCoordinate(line.start.x) + "\t" + renderCoordinate(line.start.y) + "\t" +
                    renderCoordinate(line.end.x) + "\t" + renderCoordinate(line.end.y),
            )
        }

        return lines.joinToString("\n")
    }

    /**
     * A coordinate, exactly as the Java writes it: whatever `Float.toString` produces. Not
     * [formatFixed] — rounding would give tidier files that no longer match Android's on the JVM.
     */
    private fun renderCoordinate(value: Float): String = value.toString()

    private fun formatEntry(builder: StringBuilder, from: Station, leg: Leg) {
        var effectiveLeg = leg
        val to = leg.destination
        var fromName = from.name
        var toName = to.name

        if (leg.wasShotBackwards) {
            effectiveLeg = leg.reverse()
            fromName = to.name
            toName = from.name
        }

        // A splay's destination is the null station, whose name is "-"; PocketTopo wants it blank.
        if (toName == Station.NULL_STATION.name) {
            toName = ""
        }

        field(builder, fromName)
        field(builder, toName)
        field(builder, formatDistance(effectiveLeg.distance))
        field(builder, formatAzimuth(effectiveLeg.azimuth))
        // TableCol.INCLINATION is signed ("%+.2f"), unlike the plain format Survex/Therion use.
        field(builder, formatFixed(effectiveLeg.inclination, 2, alwaysSigned = true))

        if (effectiveLeg.wasPromoted() || to.hasComment()) {
            builder.append("\t; ")
            if (effectiveLeg.wasPromoted()) {
                builder.append(' ')
                formatPromotedFrom(builder, effectiveLeg.promotedFrom)
            }
            if (to.hasComment()) {
                builder.append(' ')
                builder.append(flattenComment(to.comment))
            }
        }
    }

    private fun formatPromotedFrom(builder: StringBuilder, precursors: Array<Leg>) {
        builder.append("{from: ")
        var first = true
        for (precursor in precursors) {
            if (first) first = false else builder.append(", ")
            builder.append(formatDistance(precursor.distance))
            builder.append(' ')
            builder.append(formatAzimuth(precursor.azimuth))
            builder.append(' ')
            builder.append(formatFixed(precursor.inclination, 2, alwaysSigned = true))
        }
        builder.append('}')
    }

    private fun field(builder: StringBuilder, value: String) {
        builder.append(value)
        builder.append('\t')
    }

    /** A comment is one line in this format, so newline runs collapse to a literal `\n`. */
    private fun flattenComment(comment: String): String =
        comment.replace(Regex("(\\r\\n|\\r|\\n)+"), "\\\\n")
}
