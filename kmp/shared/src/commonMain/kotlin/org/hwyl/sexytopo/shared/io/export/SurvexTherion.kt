package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.asBacksight

/**
 * Ported from `control/io/thirdparty/survextherion/`. Survex and Therion take the same survey
 * data in two dialects differing only in punctuation, so one core emits both.
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

    /**
     * The metadata block: date, instrument, team, exploration date and team, and trip comments.
     *
     * A field the surveyor left blank is written anyway, commented out, rather than omitted —
     * the exported file doubles as a form that can be filled in later, and lets the importer
     * round-trip a file it wrote.
     *
     * Returns the empty string when there is no trip, which is what makes the unconditional
     * newlines around the call sites correct.
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

            // Three cases: an exploration date linked to the survey date, an explicit earlier
            // date, or nothing recorded (keyword still written, commented out).
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
                // dropLastWhile: Kotlin's split keeps trailing empty strings where Java's drops
                // them, so a comment ending in a newline would otherwise emit a bare marker.
                for (line in trip.comments.split("\n").dropLastWhile { it.isEmpty() }) {
                    append(comment).append(line).append('\n')
                }
            }
        }
    }

    /**
     * The copyright and licence line, or the empty string when neither is set:
     * `*copyright 2026 "Some Caving Club" ;"CC-BY-SA-4.0"` in Survex. The licence is a trailing
     * *comment*, since neither format has a licence field.
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
     * The `team` lines — the one place the two dialects disagree. Survex has a single team list
     * with an `explorer` role; Therion separates `team` (surveyed) from `explo-team` (found), so
     * the exploration role is stripped here and re-emitted by [exploTeamLines]. A member with no
     * roles at all is skipped in both, since neither format can say "was there, did nothing".
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
     * Both dialects name roles the same way, except Therion has no `explorer` (already filtered
     * out by [teamLines]).
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
     * The centreline data block. Every field is followed by a tab, including the last one before
     * the newline — looks like a bug but matches the Java's `formatField`, which always appends a
     * trailing tab.
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
     * Entries in the order the instrument delivered them, including the Java's quirk: ordering
     * is by `indexOf` in the chronological record, so a leg missing from it sorts to -1 (front)
     * rather than being dropped.
     */
    fun chronologicalEntries(survey: Survey): List<Pair<Station, Leg>> {
        // Looked up once via a HashMap rather than scanned per leg with `indexOfFirst` (which
        // made this quadratic). `Leg` has no equals/hashCode, so the map is identity-keyed — the
        // same `===` the scan was doing.
        val order = HashMap<Leg, Int>()
        survey.getAllLegsInChronoOrder().forEachIndexed { index, leg ->
            if (!order.containsKey(leg)) order[leg] = index
        }

        val entries = mutableListOf<Pair<Station, Leg>>()
        for (station in survey.getAllStations()) {
            // Splays before full legs, as the Java's per-station collection does.
            for (leg in station.getUnconnectedOnwardLegs()) entries.add(station to leg)
            for (leg in station.getConnectedOnwardLegs()) entries.add(station to leg)
        }

        return entries.sortedBy { (_, leg) -> order[leg] ?: -1 }
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

        // A promoted leg's averaged-from readings follow as comments, so the raw observations survive.
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

    /** Iterative for the reason set out in `Space3DTransformer`: a passage is a chain. */
    private fun collectStationsWithComments(station: Station, into: MutableList<Station>) {
        val pending = ArrayDeque<Station>()
        pending.addLast(station)
        while (pending.isNotEmpty()) {
            val at = pending.removeLast()
            if (at.hasComment()) into.add(at)
            for (leg in at.getConnectedOnwardLegs().asReversed()) {
                pending.addLast(leg.destination)
            }
        }
    }

    /**
     * The `extend` commands that tell Survex/Therion how to unroll the cave. A non-propagating
     * (vertical) direction applies to one leg only, so it names both stations rather than
     * changing what the rest of the subtree inherits.
     */
    fun extendedElevationExtensions(survey: Survey, format: SurveyFormat): String = buildString {
        appendExtendCommands(this, survey.origin, format.commandChar)
    }

    /**
     * Depth first from the origin, carrying the direction each station inherits. A loop rather
     * than recursion, since a passage is a chain and a large survey would overflow the stack.
     * Children are pushed reversed so they come off in recorded order, matching the
     * golden-tested output.
     */
    private fun appendExtendCommands(builder: StringBuilder, origin: Station, marker: String) {
        val pending = ArrayDeque<Triple<Station, Station?, ExtendedElevationDirection?>>()
        pending.addLast(Triple(origin, null, null))
        while (pending.isNotEmpty()) {
            val (station, fromStation, lastDirection) = pending.removeLast()
            val inherited = appendExtendCommand(builder, station, fromStation, lastDirection, marker)
            for (leg in station.getConnectedOnwardLegs().asReversed()) {
                pending.addLast(Triple(leg.destination, station, inherited))
            }
        }
    }

    /** @return the direction this station's own subtree inherits. */
    private fun appendExtendCommand(
        builder: StringBuilder,
        station: Station,
        fromStation: Station?,
        lastDirection: ExtendedElevationDirection?,
        marker: String,
    ): ExtendedElevationDirection? {
        val current = station.extendedElevationDirection
        val directionName = current.name.lowercase()

        val inherited: ExtendedElevationDirection?
        if (!current.propagates && fromStation != null) {
            builder.append(marker)
                .append("extend ")
                .append(directionName)
                .append(' ')
                .append(fromStation.name)
                .append(' ')
                .append(station.name)
                .append('\n')
            inherited = lastDirection
        } else if (!current.propagates) {
            // The origin with a non-propagating direction (a survey starting down a pitch) names
            // no leg, since the origin has none incoming. The Java dereferences a null station
            // here and throws; writing the start command instead keeps the file valid.
            builder.append(marker).append("extend start ").append(station.name).append('\n')
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

        return inherited
    }

    /**
     * The "Created with ..." line. [createdOn] is passed in rather than read from a clock, since
     * commonMain has none and a wall-clock-dependent export can't be golden-tested.
     */
    fun creationComment(format: SurveyFormat, versionInfo: String, createdOn: String): String =
        "${format.commentChar} Created with $versionInfo on $createdOn"

    /**
     * The `input` lines a `.th` uses to pull its scrap files in. Empty for an empty list rather
     * than a blank line — `ThExporter` appends two newlines after this regardless, so the two
     * cases differ by exactly these lines.
     */
    fun inputText(th2Files: List<String>): String =
        th2Files.joinToString("") { "input \"$it\"\n" }
}

/** Emits a Survex `.svx` file. Ported from `SurvexExporter`. */
object SurvexExporter {

    fun export(survey: Survey, versionInfo: String = "SexyTopo", createdOn: String = ""): String =
        buildString {
            append("*begin ").append(survey.name).append('\n')
            append(SurvexTherionWriter.creationComment(SurveyFormat.SURVEX, versionInfo, createdOn))
            append('\n')
            // The newlines after copyright and metadata are unconditional in the original, so a
            // survey with no trip still gets two blank lines (harmless whitespace to the parser).
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
 * Emits the `.thconfig` that makes the rest of the Therion export buildable.
 *
 * Therion compiles a *project*, not a `.th` directly — without this file, the centreline,
 * scraps and tracing images have nothing to build them.
 *
 * The four blocks are the original's, in order: encoding, a layout with everything commented
 * out, the source, and three exports (a Survex 3D model and a PDF of each projection).
 */
object ThconfigExporter {

    /** `symbol-hide group cave-centreline` stays commented out: with it on, Therion can fail to compile. */
    const val DEFAULT_LAYOUT =
        "layout local\n" +
            "  debug off\n" +
            "  # map-header 0 0 off\n" +
            "  # symbol-hide group cave-centreline\n" +
            "endlayout"

    fun export(survey: Survey): String {
        val name = survey.name
        return listOf(
                "encoding utf-8",
                DEFAULT_LAYOUT,
                "source \"$name.th\"",
                "export model -fmt survex -o \"$name-th.3d\"",
                "export map -proj plan -layout local -o \"$name-plan.pdf\"",
                "export map -proj extended -layout local -o \"$name-ee.pdf\"",
            )
            .joinToString("\n\n")
    }
}

/**
 * Emits a Therion `.th` file. [th2Files] are the scrap files to pull in — `Name.plan.th2` and
 * `Name.ee.th2`. Defaults to none, though a `.th` with no `input` lines compiles to a centreline
 * with no drawing.
 */
object TherionExporter {

    fun export(
        survey: Survey,
        versionInfo: String = "SexyTopo",
        createdOn: String = "",
        th2Files: List<String> = emptyList(),
    ): String =
        buildString {
            append("encoding utf-8\n")
            append("survey ").append(survey.name).append('\n')
            append(SurvexTherionWriter.creationComment(SurveyFormat.THERION, versionInfo, createdOn))
            append("\n\n")
            append(SurvexTherionWriter.inputText(th2Files))
            // Unconditional in the original, whether or not there were any input lines.
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
