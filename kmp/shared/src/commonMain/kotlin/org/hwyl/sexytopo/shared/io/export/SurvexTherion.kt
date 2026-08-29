package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
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
 * The shared Survex/Therion emitter: the trip metadata block, the centreline itself, the
 * extended-elevation `extend` commands, and the station-comment block.
 */
object SurvexTherionWriter {

    // ---------------------------------------------------------------------------------------
    // Trip metadata
    // ---------------------------------------------------------------------------------------

    /**
     * The metadata block: date, instrument, team, exploration date and team, and trip comments.
     *
     * Ported from `SurvexTherionUtil.getMetadata`, whose one surprising habit is worth spelling
     * out: a field the surveyor left blank is written anyway, commented out, rather than omitted.
     * That is deliberate in the original and useful — the exported file doubles as a form, so
     * somebody editing it in Survex or Therion afterwards can see the slot and fill it in. It also
     * means the importer can round-trip a file it wrote.
     *
     * Returns the empty string when there is no trip at all, which is what makes the unconditional
     * newlines around the call sites correct rather than sloppy.
     */
    fun metadata(survey: Survey, format: SurveyFormat): String {
        val trip = survey.trip ?: return ""
        val marker = format.commandChar
        val comment = format.commentChar

        return buildString {
            append(marker).append("date ").append(formatTripDate(trip.surveyDate)).append('\n')

            if (trip.hasInstrument()) {
                append(marker).append("instrument insts \"").append(trip.instrument).append("\"\n")
            } else {
                append(comment).append(marker).append("instrument insts \"\"\n")
            }

            append(teamLines(trip, format))
            append('\n')

            // Three cases, as in the original: an exploration date linked to the survey date (the
            // usual "we surveyed it the day we found it"), an explicit earlier date, or nothing
            // recorded — in which case the keyword is still written, commented out.
            val exploredOn =
                when {
                    trip.explorationDateLinked -> trip.surveyDate
                    else -> trip.explorationDate
                }
            if (exploredOn != null) {
                append(marker)
                    .append(format.explorationDateKeyword)
                    .append(formatTripDate(exploredOn))
                    .append('\n')
            } else {
                append(comment).append(marker).append(format.explorationDateKeyword).append('\n')
            }

            append(exploTeamLines(trip, format))

            if (trip.comments.isNotEmpty()) {
                append('\n')
                append(comment).append("Comment from SexyTopo trip information\n")
                for (line in trip.comments.split("\n")) {
                    append(comment).append(line).append('\n')
                }
            }
        }
    }

    /**
     * The copyright and licence line, or the empty string when neither is set.
     *
     * `*copyright 2026 "Some Caving Club" ;"CC-BY-SA-4.0"` in Survex, the same without the leading
     * `*` and with `#` for Therion. The holder is always quoted even when blank, and the licence is
     * appended as a trailing *comment* — which looks odd until you notice neither format has a
     * licence field, so the only way to keep the information in the file is to write it where a
     * human will read it and a parser will not choke on it.
     *
     * The Java guards against an empty year and omits it; here a trip cannot have an absent
     * [SurveyDate], so the year is always written and that guard has nothing left to protect.
     */
    fun copyrightLine(survey: Survey, format: SurveyFormat): String {
        val trip = survey.trip ?: return ""
        if (!trip.hasCopyrightHolder() && !trip.hasLicence()) return ""

        return buildString {
            append(format.commandChar).append("copyright ")
            append(formatYear(trip.surveyDate)).append(' ')
            append('"').append(if (trip.hasCopyrightHolder()) trip.copyrightHolder else "")
            append('"')
            if (trip.hasLicence()) {
                append(' ').append(format.commentChar).append('"').append(trip.licence).append('"')
            }
            append('\n')
        }
    }

    /**
     * The `team` lines, which are the one place the two dialects genuinely disagree.
     *
     * Survex has a single team list and an `explorer` role, so an explorer is just another team
     * member. Therion separates the two: `team` is who surveyed it and `explo-team` is who found
     * it, so the exploration role is stripped out of the team line here and re-emitted by
     * [exploTeamLines] — and somebody whose *only* role was exploration is left off the team line
     * altogether rather than being written with a name and no roles.
     *
     * A team member with no roles at all is skipped in both dialects: neither format has a way to
     * say "was there, did nothing".
     */
    fun teamLines(trip: Trip, format: SurveyFormat): String = buildString {
        for (entry in trip.team) {
            if (!entry.hasRoles()) continue
            val roles =
                when (format) {
                    SurveyFormat.SURVEX -> entry.roles
                    SurveyFormat.THERION -> entry.roles.filter { it != Trip.Role.EXPLORATION }
                }
            if (roles.isEmpty()) continue

            append(format.commandChar).append("team \"").append(entry.name).append('"')
            for (role in roles) append(' ').append(roleDescription(role, format))
            append('\n')
        }
    }

    /** Therion's separate list of who explored the passage. Survex has no equivalent. */
    fun exploTeamLines(trip: Trip, format: SurveyFormat): String {
        if (format != SurveyFormat.THERION) return ""
        return buildString {
            for (entry in trip.team) {
                if (entry.hasRoles() && entry.roles.contains(Trip.Role.EXPLORATION)) {
                    append("explo-team \"").append(entry.name).append("\"\n")
                }
            }
        }
    }

    /**
     * Both dialects name the roles the same way, except that Therion has no `explorer` — it says
     * the same thing with `explo-team`, so [teamLines] has already filtered that role out and this
     * is never asked about it there.
     */
    private fun roleDescription(role: Trip.Role, format: SurveyFormat): String =
        when (role) {
            Trip.Role.BOOK -> "notes"
            Trip.Role.INSTRUMENTS -> "instruments"
            Trip.Role.EXPLORATION -> if (format == SurveyFormat.SURVEX) "explorer" else "assistant"
            Trip.Role.DOG -> "assistant"
        }

    /** Survex and Therion want `yyyy.MM.dd`, unlike the native format's `yyyy-MM-dd`. */
    private fun formatTripDate(date: SurveyDate): String =
        "${pad(date.year, 4)}.${pad(date.month, 2)}.${pad(date.day, 2)}"

    private fun formatYear(date: SurveyDate): String = pad(date.year, 4)

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

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
            // The newlines after the copyright and metadata blocks are unconditional in the
            // original, so a survey with no trip still gets the two blank lines. Faithful, and
            // harmless: both are whitespace to the parser.
            append(SurvexTherionWriter.copyrightLine(survey, SurveyFormat.SURVEX))
            append('\n')
            append(SurvexTherionWriter.metadata(survey, SurveyFormat.SURVEX))
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
 * The `input` lines referencing `.th2` sketch files are the one thing missing, since this port has
 * no `.th2` exporter yet — so a Therion project built from this file gets the centreline and the
 * metadata but not the drawn sketch.
 */
object TherionExporter {

    fun export(survey: Survey, versionInfo: String = "SexyTopo", createdOn: String = ""): String =
        buildString {
            append("encoding utf-8\n")
            append("survey ").append(survey.name).append('\n')
            append(SurvexTherionWriter.creationComment(SurveyFormat.THERION, versionInfo, createdOn))
            append("\n\n")
            // Where the `input "...th2"` lines would go. The original writes the (here empty) list
            // followed by two newlines regardless, so the blank line is kept rather than tidied
            // away: a .th2 exporter dropping in later should not shift every line of the file.
            append("\n\n")
            append("centreline\n")
            // Therion, unlike Survex, has no blank line after the copyright line.
            append(SurvexTherionWriter.copyrightLine(survey, SurveyFormat.THERION))
            append(SurvexTherionWriter.metadata(survey, SurveyFormat.THERION))
            append('\n')
            append(SurvexTherionWriter.stationCommentsData(survey, SurveyFormat.THERION))
            append(SurvexTherionWriter.centrelineData(survey, SurveyFormat.THERION))
            append(SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.THERION))
            append("endcentreline\n")
            append("endsurvey\n")
        }
}
