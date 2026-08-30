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
 * Ported from `control/io/thirdparty/pockettopo/PocketTopoTxtExporter`, which the Android app marks
 * `Experimental`. Unlike the Survex, Therion and Compass exporters, this one is **not** pinned
 * byte-for-byte against the Java, and it cannot be. Two reasons, both worth reading before
 * comparing an exported file with one Android produced.
 *
 * ## 1. The Java's own output is not reproducible
 *
 * `Space` holds its stations and legs in a plain `HashMap`, and neither `Station` nor `Leg`
 * overrides `hashCode`. Iteration order therefore follows identity hash codes, which differ from
 * run to run. Exporting the *same survey twice* produces the STATIONS and SHOTS lines in different
 * orders - verified by building one survey twice in a single JVM and diffing the result, which came
 * out shuffled.
 *
 * Since no caller can depend on an order that is undefined, this port picks a defined one:
 * [Survey.getAllStationsInChronoOrder] and [Survey.getAllLegsInChronoOrder], so a station appears in
 * the order the surveyor created it. That makes exports diffable, reviewable and testable, which
 * the original's are not.
 *
 * This is a deliberate divergence and the only one. Where the Java's behaviour is *defined* it is
 * reproduced exactly, including the parts that look like mistakes - see [exportSketch].
 *
 * ## 2. Coordinates are written with a bare float-to-string
 *
 * The Java writes `coords.x` straight into the file, so a projected coordinate near zero comes out
 * as `1.7484555E-6`. Kotlin's `Float.toString` only agrees with Java's on the JVM; on Kotlin/Wasm
 * the same value is `0.0000017484555`. Real surveys hit this - a level passage gives y-coordinates
 * that are floating-point noise - so PocketTopo files written on iOS will differ textually from
 * Android's even where every number is identical. See `FloatRenderingTest` and the README.
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
            // The Java's own newline placement: this branch supplies one of its own *and* then
            // gets the shared one below, so a survey with no trip has a blank line here and a
            // survey with a trip does not. Reproduced rather than tidied.
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
     * Note the sign: the y of a *sketch* point is negated here and the y of a *station* is not, in
     * [exportStationCoords]. That asymmetry is the Java's, and it looks wrong - SexyTopo's 2D
     * projections put y downwards, so negating one and not the other should leave the drawing
     * mirrored against the centreline it belongs to. It is reproduced rather than corrected because
     * it is defined behaviour affecting a data format, and whether to change it is the maintainer's
     * call, not a porting decision.
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
     * Station positions and the lines between them.
     *
     * Ordered by the survey's own chronology rather than by hash, for the reason in the class
     * documentation. A station or leg the projection did not place is skipped rather than written
     * with a null coordinate.
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
     * A coordinate, exactly as the Java writes it: whatever `Float.toString` produces.
     *
     * Not [formatFixed], deliberately. Rounding to a fixed number of places would give tidier files
     * that no longer match Android's on the JVM at all, where today they do. The cost is the
     * cross-target difference documented on this class.
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
        // TableCol.INCLINATION is "%+.2f" — signed, unlike the plain "%.2f" the Survex and Therion
        // exporters use. This one goes through the *table* formatter, so the sign is written.
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
