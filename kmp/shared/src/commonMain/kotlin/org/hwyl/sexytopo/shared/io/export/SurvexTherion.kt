package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.asBacksight

/**
 * Ported from `control/io/thirdparty/survextherion/`.
 *
 * Survex and Therion take the same survey data in two dialects that differ only in punctuation, so
 * the Android app emits both from one core and this port keeps that shape.
 */
enum class SurveyFormat(
    val commentChar: Char,
    /** Prefixed to commands: Survex uses `*`, Therion nothing. */
    val commandChar: String,
    /** What a splay's absent destination is called. */
    val splayStationName: String,
    val explorationDateKeyword: String,
    val fileExtension: String,
) {
    SURVEX(';', "*", "..", "date explored ", "svx"),
    THERION('#', "", "-", "explo-date ", "th"),
    ;

    /** Survex says "passage", Therion says "dimensions". */
    val dataPassagePrefix: String
        get() = if (this == SURVEX) "${commandChar}data passage" else "${commandChar}data dimensions"
}

/**
 * The shared Survex/Therion emitter: the centreline itself, the extended-elevation `extend`
 * commands, and the station-comment block.
 *
 * What is deliberately not here yet is the trip metadata block (date, instrument, team, licence),
 * because this port's [org.hwyl.sexytopo.shared.model.survey.Trip] is a stub. That block is
 * self-contained string assembly and would follow the same shape.
 */
object SurvexTherionWriter {

    /**
     * The centreline data block.
     *
     * Every field is followed by a tab, including the last one before the newline — so lines end
     * `...\t\n`. That looks like a bug and is not: `formatField` in the Java appends a tab after
     * every value, and a comment (when present) is written straight after it. Trimming it would be
     * a gratuitous difference from the Android app's output.
     */
    fun centrelineData(survey: Survey, format: SurveyFormat): String = buildString {
        append(format.commandChar)
        append("data normal from to tape compass clino ignoreall\n")

        for ((from, leg) in chronologicalEntries(survey)) {
            appendEntry(this, from, leg, format)
            append("\n")
        }

        append("\n")
    }

    /**
     * Entries in the order the instrument delivered them.
     *
     * Ported from `GraphToListTranslator.toChronoListOfSurveyListEntries`, including its quirk:
     * ordering is by `indexOf` in the chronological record, so a leg missing from that record
     * sorts to -1 and lands at the front rather than being dropped.
     */
    fun chronologicalEntries(survey: Survey): List<Pair<Station, Leg>> {
        val chrono = survey.getAllLegsInChronoOrder()
        val entries = mutableListOf<Pair<Station, Leg>>()

        for (station in survey.getAllStations()) {
            // Splays before full legs, as the Java's per-station collection does.
            for (leg in station.getUnconnectedOnwardLegs()) entries.add(station to leg)
            for (leg in station.getConnectedOnwardLegs()) entries.add(station to leg)
        }

        return entries.sortedBy { (_, leg) -> chrono.indexOfFirst { it === leg } }
    }

    private fun appendEntry(builder: StringBuilder, from: Station, leg: Leg, format: SurveyFormat) {
        // A backwards shot is stored pointing the other way; exports must show it as taken.
        val fromName: String
        val toName: String
        val reading: Leg
        if (leg.wasShotBackwards) {
            fromName = leg.destination.name
            toName = from.name
            reading = leg.asBacksight()
        } else {
            fromName = from.name
            toName = if (leg.hasDestination()) leg.destination.name else format.splayStationName
            reading = leg
        }

        builder.append(fromName).append('\t')
        builder.append(toName).append('\t')
        builder.append(formatDistance(reading.distance)).append('\t')
        builder.append(formatAzimuth(reading.azimuth)).append('\t')
        builder.append(formatInclination(reading.inclination)).append('\t')

        if (reading.hasComment()) {
            builder.append(flattenComment(reading.comment))
        }

        // A promoted leg carries the readings it was averaged from; they follow as comments so the
        // original observations survive in the exported file.
        if (leg.wasPromoted()) {
            for (precursor in leg.promotedFrom) {
                builder.append('\n')
                builder.append(format.commentChar)
                builder.append(fromName).append('\t')
                builder.append(toName).append('\t')
                builder.append(formatDistance(precursor.distance)).append('\t')
                builder.append(formatAzimuth(precursor.azimuth)).append('\t')
                builder.append(formatInclination(precursor.inclination))
                if (precursor.hasComment()) {
                    builder.append('\t').append(flattenComment(precursor.comment))
                }
            }
        }
    }

    /** Newlines inside a comment would break the line-oriented format, so they become literal \n. */
    private fun flattenComment(comment: String): String =
        comment.replace(Regex("(\\r|\\n|\\r\\n)+"), "\\\\n")

    /** The station-comment block, written as a passage/dimensions table with empty LRUDs. */
    fun stationCommentsData(survey: Survey, format: SurveyFormat): String {
        val withComments = mutableListOf<Station>()
        collectStationsWithComments(survey.origin, withComments)
        if (withComments.isEmpty()) return ""

        return buildString {
            append(format.dataPassagePrefix)
            append(" station left right up down ignoreall\n")
            for (station in withComments) {
                append(station.name).append('\t')
                // LRUD placeholders, unused but required by the format.
                append("-\t-\t-\t-\t")
                append(flattenComment(station.comment))
                append('\n')
            }
            append('\n')
        }
    }

    private fun collectStationsWithComments(station: Station, into: MutableList<Station>) {
        if (station.hasComment()) into.add(station)
        for (leg in station.getConnectedOnwardLegs()) {
            collectStationsWithComments(leg.destination, into)
        }
    }

    /**
     * The `extend` commands that tell Survex or Therion how to unroll the cave, mirroring the
     * per-station directions the app's own extended elevation uses.
     *
     * A direction that does not propagate (vertical) applies to one leg only, so it is written with
     * both station names and does not change what the rest of the subtree inherits.
     */
    fun extendedElevationExtensions(survey: Survey, format: SurveyFormat): String = buildString {
        appendExtendCommands(this, survey.origin, null, null, format.commandChar)
    }

    private fun appendExtendCommands(
        builder: StringBuilder,
        station: Station,
        fromStation: Station?,
        lastDirection: ExtendedElevationDirection?,
        marker: String,
    ) {
        val current = station.extendedElevationDirection
        val directionName = current.name.lowercase()

        val inherited: ExtendedElevationDirection?
        if (!current.propagates) {
            builder.append(marker)
                .append("extend ")
                .append(directionName)
                .append(' ')
                .append(fromStation?.name)
                .append(' ')
                .append(station.name)
                .append('\n')
            inherited = lastDirection
        } else {
            if (lastDirection == null) {
                builder.append(marker).append("extend start ").append(station.name).append('\n')
            } else if (current != lastDirection) {
                builder.append(marker)
                    .append("extend ")
                    .append(directionName)
                    .append(' ')
                    .append(station.name)
                    .append('\n')
            }
            inherited = current
        }

        for (leg in station.getConnectedOnwardLegs()) {
            appendExtendCommands(builder, leg.destination, station, inherited, marker)
        }
    }

    /**
     * The "Created with ..." line.
     *
     * [createdOn] is passed in rather than read from a clock, because commonMain has none and
     * because an export whose bytes depend on the wall clock cannot be compared against a golden
     * file.
     */
    fun creationComment(format: SurveyFormat, versionInfo: String, createdOn: String): String =
        "${format.commentChar} Created with $versionInfo on $createdOn"
}

/** Emits a Survex `.svx` file. Ported from `SurvexExporter`. */
object SurvexExporter {

    fun export(survey: Survey, versionInfo: String = "SexyTopo", createdOn: String = ""): String =
        buildString {
            append("*begin ").append(survey.name).append('\n')
            append(SurvexTherionWriter.creationComment(SurveyFormat.SURVEX, versionInfo, createdOn))
            append('\n')
            append(SurvexTherionWriter.stationCommentsData(survey, SurveyFormat.SURVEX))
            append(SurvexTherionWriter.centrelineData(survey, SurveyFormat.SURVEX))
            append('\n')
            append(SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.SURVEX))
            append("*end ").append(survey.name).append('\n')
        }
}

/**
 * Emits a Therion `.th` file. Ported from `ThExporter`.
 *
 * The `input` lines referencing `.th2` sketch files are omitted, since this port has no `.th2`
 * exporter yet.
 */
object TherionExporter {

    fun export(survey: Survey, versionInfo: String = "SexyTopo", createdOn: String = ""): String =
        buildString {
            append("encoding utf-8\n")
            append("survey ").append(survey.name).append('\n')
            append(SurvexTherionWriter.creationComment(SurveyFormat.THERION, versionInfo, createdOn))
            append("\n\n")
            append("centreline\n")
            append(SurvexTherionWriter.stationCommentsData(survey, SurveyFormat.THERION))
            append(SurvexTherionWriter.centrelineData(survey, SurveyFormat.THERION))
            append(SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.THERION))
            append("endcentreline\n")
            append("endsurvey\n")
        }
}
