package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * Reading a Survex or Therion file back into a survey.
 *
 * Both formats share one parser, since SexyTopo writes them in the same shape, differing only
 * in comment character, command prefix, and the name a splay's absent destination takes.
 *
 * ## What the parser has to work out that the file does not say
 *
 * **Which end was shot from.** SexyTopo has no loop closures, so a leg naming a station not
 * seen before *in the from position* was shot backwards — positional, not by name.
 *
 * **Where the repeated shots went.** A station promoted from three agreeing readings has those
 * precursors inline in a `{from: d a i, d a i}` comment, on comment lines below, or — as this app
 * now writes them for Survex — as the readings themselves on consecutive lines naming the same two
 * stations, with no average at all. All three come back, the last averaged again on the way in,
 * since losing them discards the evidence a surveyor would use to check a suspicious leg.
 *
 * **Where a comment belongs.** Files from SexyTopo 1.11.3 and later put a trailing comment on
 * the *leg*; older ones meant it for the newer *station*. `useLegComments` chooses.
 */
object SurvexTherionImporter {

    /** The Java's `COMMENT_INSTRUCTION_REGEX`: the `{...}` holding inline promoted legs. */
    private val COMMENT_INSTRUCTION = Regex("([{].*?[}])")

    /** `copyright {year} "{holder}" [;#]"{licence}"`, matching `SurvexTherionWriter.copyrightLine`. */
    private val COPYRIGHT_LINE = Regex("""copyright\s+\S+\s+"([^"]*)"(?:\s*[;#]"([^"]*)")?""")

    private val WHITESPACE = Regex("\\s+")

    /**
     * The date given to a trip whose file states metadata but no date. The Java's `Trip()`
     * dates it today (unreproducible); this port's date is mandatory, so an obviously-unset
     * value is used instead.
     */
    val UNDATED = SurveyDate(1, 1, 1)

    /**
     * Read a centreline into [survey].
     *
     * @param useLegComments true for files written by SexyTopo 1.11.3 or later, and for files with
     *   no version header at all, which are assumed current; false for older ones.
     * @param settings whose amalgamation algorithm averages a run of repeated readings, as the
     *   Java uses whichever one is active.
     */
    fun parseCentreline(
        text: String,
        survey: Survey,
        useLegComments: Boolean = true,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ) {
        val stations = mutableMapOf<String, Station>()
        val extendCommands = mutableListOf<String>()
        val lines = text.split("\n")

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            index++
            if (trimmed.isEmpty()) continue

            // Survex marks this a command with a leading `*`; Therion writes it bare. Checked
            // before the general `*`-command skip below, or Survex's own `*extend ...` lines
            // would be thrown away with every other command — which is exactly what was happening.
            val withoutMarker = trimmed.removePrefix("*")
            if (withoutMarker.startsWith("extend ")) {
                extendCommands.add(withoutMarker)
                continue
            }

            if (trimmed.startsWith("*")) continue

            // A whole-line comment is never data. Precursor shots written on comment lines are
            // picked up from the leg above them instead, in commentedPrecursors.
            if (trimmed.startsWith(";") || trimmed.startsWith("#")) continue

            // `Leg`'s own range checks, or a reading that is not a number. Rethrown with the line,
            // so "could not read Cave.svx" becomes a message naming which line and the numbers as
            // written.
            try {
                val current = parseLegLine(trimmed) ?: continue

                // Splays are never grouped: three shots to ".." are three walls, not one leg.
                if (current.toName !in SPLAY_STATION_TOKENS) {
                    val group = repeatedLines(lines, index, current)
                    if (group.size > 1) {
                        addAveragedGroup(survey, stations, group, useLegComments, settings)
                        index += group.size - 1
                        continue
                    }
                }

                val precursors =
                    commentedPrecursors(
                        lines,
                        index - 1,
                        current.fromName,
                        current.toName,
                        useLegComments,
                    )
                addLeg(
                    survey,
                    stations,
                    current,
                    current.distance,
                    current.azimuth,
                    current.inclination,
                    precursors,
                    useLegComments,
                )
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("Error importing this line: $line", exception)
            }
        }

        // After the centreline rather than as each line comes, as `TherionImporter` does it: a
        // direction carries down the passage, and the passage below a station is not there yet
        // when the line naming it is read. Applied inline, `extend left 2` turned station 2 left
        // and left everything beyond it drawn to the right.
        for (command in extendCommands) applyExtendCommand(survey, command)
    }

    /**
     * Every name a third-party file might give a splay's absent far end, whichever format it is.
     *
     * Therion's anonymous station is `-` or `.`; Survex's is `.`, `..` (a wall point) or `...` (a
     * point with no implicit flags), from the "Anonymous stations" section of its manual. This app
     * only ever writes [SurveyFormat.splayStationName], but somebody else's file need not. Before
     * upstream widened this to all four the Java took only `-` and this port `-` and `..`, so a
     * splay to `.` came in as a station called ".".
     */
    private val SPLAY_STATION_TOKENS = setOf("-", ".", "..", "...")

    /** One data line's five fields and its comment, read but not yet added to the survey. */
    private class LegLine(
        val fromName: String,
        val toName: String,
        val distance: Float,
        val azimuth: Float,
        val inclination: Float,
        val comment: String,
    )

    /**
     * Null for a line with fewer than five fields, which is not a reading. Throws for one whose
     * numbers are not numbers, as `Float.parseFloat` does in the Java: a reading that cannot be
     * read is an error to report, not a line to skip quietly.
     */
    private fun parseLegLine(trimmed: String): LegLine? {
        val tokens = trimmed.split(WHITESPACE)
        if (tokens.size < 5) return null

        // The tail may or may not start with a comment character: the app writes both
        // "1 2 3.0 45.0 0.0 # My Chamber" and "1 2 3.0 45.0 0.0 My Chamber".
        val comment =
            if (tokens.size > 5) tokens.drop(5).joinToString(" ").withoutCommentChar() else ""
        return LegLine(
            tokens[0],
            tokens[1],
            tokens[2].toFloat(),
            tokens[3].toFloat(),
            tokens[4].toFloat(),
            comment,
        )
    }

    /**
     * The run of consecutive data lines, starting with [first], that name the same two stations —
     * how this app now writes a promoted leg for Survex, which averages the readings itself.
     *
     * The run stops at a blank, comment or command line, and at a line that will not parse: that
     * one is left for the main loop, so the error names the bad line rather than the good one above
     * it.
     */
    private fun repeatedLines(lines: List<String>, startIndex: Int, first: LegLine): List<LegLine> {
        val group = mutableListOf(first)
        for (index in startIndex until lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("*") ||
                trimmed.startsWith(";") ||
                trimmed.startsWith("#")
            ) {
                break
            }
            val candidate = runCatching { parseLegLine(trimmed) }.getOrNull() ?: break
            if (candidate.fromName != first.fromName || candidate.toName != first.toName) break
            group.add(candidate)
        }
        return group
    }

    /**
     * One leg averaged from its repeated readings, with the readings kept as what it was promoted
     * from — the same as a leg promoted live, and averaged with the same algorithm the surveyor
     * has chosen for that.
     *
     * The first line's comment is the leg's: that is where the exporter puts it. A later line's
     * comment stays on its own reading.
     */
    private fun addAveragedGroup(
        survey: Survey,
        stations: MutableMap<String, Station>,
        group: List<LegLine>,
        useLegComments: Boolean,
        settings: SurveySettings,
    ) {
        val readings =
            group.mapIndexed { index, line ->
                Leg(line.distance, line.azimuth, line.inclination).also {
                    if (useLegComments && index > 0 && line.comment.isNotEmpty()) {
                        it.comment = line.comment
                    }
                }
            }
        val averaged = SurveyUpdater.averageLegs(readings, settings)
        addLeg(
            survey,
            stations,
            group.first(),
            averaged.distance,
            averaged.azimuth,
            averaged.inclination,
            readings,
            useLegComments,
        )
    }

    /**
     * `extend <direction> [<fromStation>] <station>`, mirroring `TherionImporter`'s
     * `handleElevationDirectionData` — which the Java only ever runs for Therion. A Survex file
     * from this app now carries its directions in a `.espec` instead (see [EspecParser]), but one
     * written before that change has `*extend` lines, and they still mean what they say, so they
     * are read here for both formats.
     *
     * `extend start <station>` is a no-op marker and is skipped. A propagating direction
     * (LEFT/RIGHT) names one station and applies from there down, through
     * [SurveyUpdater.setExtendedElevationDirection]. A non-propagating one (VERTICAL) names a
     * from/to pair and applies only to the *destination* — the station whose incoming leg is the
     * pitch.
     *
     * A station named in the file that this survey does not have is skipped rather than
     * aborting the import.
     */
    private fun applyExtendCommand(survey: Survey, withoutMarker: String) {
        val tokens = withoutMarker.split(WHITESPACE)
        if (tokens.size < 3) return
        val direction =
            ExtendedElevationDirection.entries.firstOrNull {
                it.name.equals(tokens[1], ignoreCase = true)
            } ?: return

        val target = survey.getStationByName(tokens.last()) ?: return
        SurveyUpdater.setExtendedElevationDirection(survey, target, direction)
    }

    /**
     * Station comments from a passage-data block.
     *
     * Survex writes `*data passage station left right up down ignoreall`, Therion
     * `data dimensions ...`; either way the rows below are a station name and free text.
     */
    fun parsePassageData(text: String, format: SurveyFormat): Map<String, String> {
        val comments = LinkedHashMap<String, String>()
        val passagePrefix = format.dataPassagePrefix
        val dataPrefix = "${format.commandChar}data "
        var inBlock = false

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith(passagePrefix)) {
                inBlock = true
                continue
            }
            if (inBlock && trimmed.startsWith(dataPrefix) && !trimmed.startsWith(passagePrefix)) {
                inBlock = false
                continue
            }
            if (!inBlock) continue

            if (trimmed.startsWith(";") ||
                trimmed.startsWith("#") ||
                trimmed.startsWith("*") ||
                trimmed.startsWith("extend")
            ) {
                continue
            }

            // `station left right up down ignoreall`: the comment is the sixth column onwards,
            // not everything after the station name.
            //
            // A deliberate fix, not a faithful port: the Java splits on the first whitespace run
            // and keeps the rest, so "junction" reads back as "- - - - junction" and compounds on
            // every export/import cycle — a round trip corrupting data, the one divergence this
            // port treats as worth breaking compatibility over.
            //
            // A row with only two fields is still read as `station comment`, for a hand-written
            // block that omits the dimensions.
            val parts = trimmed.split(WHITESPACE)
            val comment =
                when {
                    parts.size >= 6 -> parts.drop(5).joinToString(" ")
                    parts.size == 2 -> parts[1]
                    else -> continue
                }.trim()
            if (comment.isNotEmpty()) comments[parts[0]] = comment
        }

        return comments
    }

    /**
     * Put passage comments onto their stations, joining rather than overwriting: a station
     * comment from the passage block and one from its leg line combine as `passage :: leg`.
     */
    fun mergePassageComments(survey: Survey, passageComments: Map<String, String>) {
        for ((name, passageComment) in passageComments) {
            val station = survey.getStationByName(name) ?: continue
            val existing = station.comment
            station.comment =
                if (existing.isBlank()) passageComment else "$passageComment :: $existing"
        }
    }

    /**
     * The trip block, or null if the file carries no metadata at all.
     *
     * Null rather than an empty trip, as in the original: a file with no metadata should leave the
     * survey's own trip alone rather than replace it with a blank one.
     */
    fun parseMetadata(text: String, format: SurveyFormat): Trip? {
        var surveyDate: SurveyDate? = null
        var explorationDate: SurveyDate? = null
        var instrument: String? = null
        var copyrightHolder: String? = null
        var licence: String? = null
        val team = LinkedHashMap<String, MutableList<Trip.Role>>()
        val tripComments = StringBuilder()
        var found = false
        var readingComments = false

        val commentChar = format.commentChar
        val exploDateKeyword = format.explorationDateKeyword
        val commentedInstrument = "$commentChar${format.commandChar}instrument insts "
        val commentedExploDate = "$commentChar${format.commandChar}$exploDateKeyword"

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                readingComments = false
                continue
            }

            val effective = trimmed.removePrefix(format.commandChar)

            if (effective.startsWith("date ") && !effective.startsWith("date explored")) {
                surveyDate = parseDate(effective.substring(5).trim())
                if (surveyDate != null) found = true
                continue
            }

            if (effective.startsWith("instrument insts ")) {
                instrument = quoted(effective, "instrument insts ")
                found = true
                continue
            }
            // A commented-out instrument line means the trip explicitly had none.
            if (trimmed.startsWith(commentedInstrument)) {
                instrument = null
                found = true
                continue
            }

            if (effective.startsWith("copyright ")) {
                COPYRIGHT_LINE.find(effective)?.let { match ->
                    match.groupValues[1].takeIf { it.isNotEmpty() }?.let { copyrightHolder = it }
                    match.groupValues[2].takeIf { it.isNotEmpty() }?.let { licence = it }
                }
                found = true
                continue
            }

            if (effective.startsWith("team ")) {
                parseTeamLine(effective, team)
                found = true
                continue
            }

            if (effective.startsWith(exploDateKeyword)) {
                explorationDate = parseDate(effective.substring(exploDateKeyword.length).trim())
                found = true
                continue
            }
            if (trimmed.startsWith(commentedExploDate)) {
                found = true
                continue
            }

            // Therion records explorers on their own line; Survex folds them into `team`.
            if (format == SurveyFormat.THERION && effective.startsWith("explo-team ")) {
                val name = quoted(effective, "explo-team ")
                if (name.isNotEmpty()) {
                    val roles = team.getOrPut(name) { mutableListOf() }
                    if (Trip.Role.EXPLORATION !in roles) roles.add(Trip.Role.EXPLORATION)
                }
                found = true
                continue
            }

            if (trimmed.startsWith("${commentChar}Comment from SexyTopo trip information")) {
                readingComments = true
                continue
            }
            if (readingComments && trimmed.startsWith(commentChar)) {
                if (tripComments.isNotEmpty()) tripComments.append("\n")
                tripComments.append(trimmed.substring(1))
                continue
            }

            readingComments = false
        }

        if (!found) return null

        val trip = Trip(surveyDate ?: UNDATED)
        if (explorationDate != null) {
            trip.explorationDate = explorationDate
            trip.explorationDateLinked = false
        } else {
            trip.explorationDateLinked = true
        }
        trip.instrument = instrument.orEmpty()
        copyrightHolder?.let { trip.copyrightHolder = it }
        licence?.let { trip.licence = it }
        trip.team = team.map { (name, roles) -> Trip.TeamEntry(name, roles.toList()) }
        if (tripComments.isNotEmpty()) trip.comments = tripComments.toString()
        return trip
    }

    /**
     * [line] names the stations and carries the comment; the reading is passed separately, since
     * for a run of repeated lines it is their average rather than the first line's own numbers.
     * [rawPrecursors] are the readings found on comment lines below, or the repeated lines
     * themselves.
     */
    private fun addLeg(
        survey: Survey,
        stations: MutableMap<String, Station>,
        line: LegLine,
        distance: Float,
        azimuth: Float,
        inclination: Float,
        rawPrecursors: List<Leg>,
        useLegComments: Boolean,
    ) {
        val fromName = line.fromName
        val toName = line.toName
        val rawComment = line.comment

        // Every format's splay names, so a Survex file read as Therion still works.
        val isSplay = toName in SPLAY_STATION_TOKENS
        // Worked out before either station is created, because creating them destroys the evidence.
        val backwards = isBackwards(fromName, toName, stations)

        val from = stations.getOrPut(fromName) { Station(fromName) }
        val to = if (isSplay) Station.NULL_STATION else stations.getOrPut(toName) { Station(toName) }

        // The first connecting leg establishes the origin. For a backward leg the logical root is
        // the station it was shot *to*.
        if (!isSplay && survey.origin !in stations.values) {
            survey.origin = if (backwards) to else from
        }

        val instructions = COMMENT_INSTRUCTION.find(rawComment)?.groupValues?.get(1).orEmpty()
        var promoted = inlinePrecursors(instructions)
        val comment =
            if (instructions.isEmpty()) rawComment else rawComment.replace(instructions, "").trim()

        if (promoted.isEmpty() && rawPrecursors.isNotEmpty()) {
            promoted = rawPrecursors.toTypedArray()
        }

        val legFrom: Station
        val leg: Leg

        if (backwards) {
            legFrom = to
            leg =
                if (from === Station.NULL_STATION) {
                    Leg(distance, azimuth, inclination, wasShotBackwards = true)
                } else {
                    Leg(distance, azimuth, inclination, from, promoted, true)
                }
            if (comment.isNotEmpty()) {
                if (useLegComments) leg.comment = comment else from.comment = comment
            }
        } else {
            legFrom = from
            leg =
                if (to === Station.NULL_STATION) {
                    Leg(distance, azimuth, inclination)
                } else {
                    Leg(distance, azimuth, inclination, to, promoted)
                }
            if (comment.isNotEmpty()) {
                if (useLegComments) {
                    leg.comment = comment
                } else if (to !== Station.NULL_STATION) {
                    to.comment = comment
                }
            }
        }

        legFrom.addOnwardLeg(leg)
        survey.addLegRecord(leg)
        survey.activeStation = legFrom
    }

    /**
     * Whether the shot was taken from the far end, by *position* not name: a from-station not
     * seen before means shot backwards. Both new or both known (a disconnected leg or loop
     * closure) is something this app can't represent, so forward is assumed.
     */
    private fun isBackwards(
        fromName: String,
        toName: String,
        seen: Map<String, Station>,
    ): Boolean {
        val fromIsNew = fromName !in seen
        val toIsNew = toName !in seen
        if (fromIsNew && toIsNew) return false
        if (!fromIsNew && !toIsNew) return false
        return fromIsNew
    }

    /**
     * The repeated shots written on comment lines below a leg.
     *
     * They stop at the first line that is not a comment, or that names a different leg — a comment
     * about something else immediately below a leg is not one of its precursors.
     */
    private fun commentedPrecursors(
        lines: List<String>,
        startIndex: Int,
        expectedFrom: String,
        expectedTo: String,
        useLegComments: Boolean,
    ): List<Leg> {
        val shots = mutableListOf<Leg>()

        for (index in (startIndex + 1) until lines.size) {
            val line = lines[index].trim()
            if (line.isEmpty() || (!line.startsWith("#") && !line.startsWith(";"))) break

            val fields = line.substring(1).trim().split(WHITESPACE)
            if (fields.size < 5 || fields[0] != expectedFrom || fields[1] != expectedTo) break

            val distance = fields[2].toFloatOrNull() ?: continue
            val azimuth = fields[3].toFloatOrNull() ?: continue
            val inclination = fields[4].toFloatOrNull() ?: continue

            val precursor = runCatching { Leg(distance, azimuth, inclination) }.getOrNull()
                ?: continue
            if (useLegComments && fields.size > 5) {
                val tail = fields.drop(5).joinToString(" ").withoutCommentChar()
                if (tail.isNotEmpty()) precursor.comment = tail
            }
            shots.add(precursor)
        }

        return shots
    }

    /**
     * `{from: 5.542 73.95 -4.64, 5.541 73.93 -4.69}` — an empty array if it will not parse.
     *
     * All or nothing, as in the original: a half-read set of precursors would claim a station was
     * promoted from readings that were not the ones taken.
     */
    private fun inlinePrecursors(instructions: String): Array<Leg> {
        if (instructions.isEmpty()) return emptyArray()

        val body = instructions.removePrefix("{").removeSuffix("}").trim()
        val parts = body.split(":", limit = 2)
        if (parts.size < 2) return emptyArray()
        // "from" and "backsight" are both lists of shots; anything else is not understood.
        if (parts[0].trim() !in setOf("from", "backsight")) return emptyArray()

        val legs = mutableListOf<Leg>()
        for (shot in parts[1].trim().split(",")) {
            val fields = shot.trim().split(WHITESPACE)
            if (fields.size != 3) return emptyArray()
            val distance = fields[0].toFloatOrNull() ?: return emptyArray()
            val azimuth = fields[1].toFloatOrNull() ?: return emptyArray()
            val inclination = fields[2].toFloatOrNull() ?: return emptyArray()
            val leg =
                runCatching { Leg(distance, azimuth, inclination) }.getOrNull()
                    ?: return emptyArray()
            legs.add(leg)
        }
        return legs.toTypedArray()
    }

    private fun parseTeamLine(line: String, team: MutableMap<String, MutableList<Trip.Role>>) {
        val afterTeam = line.substring(5).trim()
        if (!afterTeam.startsWith("\"")) return
        val closeQuote = afterTeam.indexOf('"', 1)
        if (closeQuote <= 0) return

        val name = afterTeam.substring(1, closeQuote)
        if (name.isEmpty()) return
        val roles = team.getOrPut(name) { mutableListOf() }

        val rolesText = afterTeam.substring(closeQuote + 1).trim()
        if (rolesText.isEmpty()) return
        for (word in rolesText.split(WHITESPACE)) {
            val role = parseRole(word) ?: continue
            if (role !in roles) roles.add(role)
        }
    }

    /**
     * Survex and Therion role names, not this app's own: "notes" is the book, and both
     * "assistant" and "dog" mean DOG — a joke in the original, preserved because Android-written
     * files use it.
     */
    private fun parseRole(word: String): Trip.Role? =
        when (word.lowercase()) {
            "notes" -> Trip.Role.BOOK
            "instruments" -> Trip.Role.INSTRUMENTS
            "explorer" -> Trip.Role.EXPLORATION
            "dog", "assistant" -> Trip.Role.DOG
            else -> null
        }

    /**
     * `yyyy.MM.dd`, from `SurvexTherionUtil.TRIP_DATE_PATTERN`, or the ISO `yyyy-MM-dd`.
     *
     * Export only ever writes the dots, which is the native Survex and Therion form. The hyphens
     * are accepted as upstream now accepts them, for a file written or edited by another tool.
     */
    private fun parseDate(text: String): SurveyDate? {
        val trimmed = text.trim()
        val parts = trimmed.split(".").takeIf { it.size == 3 } ?: trimmed.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return SurveyDate(year, month, day)
    }

    private fun quoted(line: String, prefix: String): String {
        val rest = line.substring(prefix.length).trim()
        return if (rest.length >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
            rest.substring(1, rest.length - 1)
        } else {
            rest
        }
    }

    private fun String.withoutCommentChar(): String =
        if (startsWith(";") || startsWith("#")) substring(1).trim() else this
}
