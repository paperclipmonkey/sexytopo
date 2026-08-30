package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip

/**
 * Reading a Survex or Therion file back into a survey.
 *
 * Ported from `io/thirdparty/survextherion/SurvexTherionImporter`. Both formats share one parser
 * because SexyTopo writes them with the same shape: they differ in a comment character, a command
 * prefix, and the name a splay's absent destination takes.
 *
 * Two workflows depend on this, and neither is the drawing-up stage. A caver is handed a
 * colleague's `.svx` and wants to carry on surveying from the end of it; and a survey exported from
 * one phone has to open on another — which, since this port already exports both formats
 * byte-identically, is the round trip its tests assert.
 *
 * ## What the parser has to work out that the file does not say
 *
 * **Which end was shot from.** SexyTopo has no loop closures, so a leg naming a station not seen
 * before *in the from position* was shot backwards. That is positional, not by name: station
 * numbers say nothing about direction.
 *
 * **Where the repeated shots went.** A station is promoted from three agreeing readings, and the
 * exporter keeps those precursors either inline in a `{from: d a i, d a i}` comment or on comment
 * lines under the leg. Both come back, because losing them turns three readings into one and
 * discards the evidence a surveyor would use to check a suspicious leg.
 *
 * **Where a comment belongs.** Files written by SexyTopo 1.11.3 and later put a trailing comment on
 * the *leg*; older ones meant it for the newer *station*. `useLegComments` chooses.
 */
object SurvexTherionImporter {

    /** The Java's `COMMENT_INSTRUCTION_REGEX`: the `{...}` holding inline promoted legs. */
    private val COMMENT_INSTRUCTION = Regex("([{].*?[}])")

    /**
     * `copyright {year} "{holder}" [;#]"{licence}"`, the licence half optional — matching what
     * `SurvexTherionWriter.copyrightLine` emits.
     */
    private val COPYRIGHT_LINE = Regex("""copyright\s+\S+\s+"([^"]*)"(?:\s*[;#]"([^"]*)")?""")

    private val WHITESPACE = Regex("\\s+")

    /**
     * The date given to a trip whose file states metadata but no date.
     *
     * The Java's no-argument `Trip()` dates it today, which makes an import unreproducible; this
     * port's date is mandatory, so an obviously-unset value is used rather than a plausible wrong
     * one.
     */
    val UNDATED = SurveyDate(1, 1, 1)

    /**
     * Read a centreline into [survey].
     *
     * @param useLegComments true for files written by SexyTopo 1.11.3 or later, and for files with
     *   no version header at all, which are assumed current; false for older ones.
     */
    fun parseCentreline(text: String, survey: Survey, useLegComments: Boolean = true) {
        val stations = mutableMapOf<String, Station>()
        val lines = text.split("\n")

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("*")) continue

            // A whole-line comment is never data. Precursor shots written on comment lines are
            // picked up from the leg above them instead, in commentedPrecursors.
            if (trimmed.startsWith(";") || trimmed.startsWith("#")) continue

            val tokens = trimmed.split(WHITESPACE)
            // from to distance azimuth inclination, at least.
            if (tokens.size < 5) continue

            // The tail may or may not start with a comment character: the app writes both
            // "1 2 3.0 45.0 0.0 # My Chamber" and "1 2 3.0 45.0 0.0 My Chamber".
            val comment =
                if (tokens.size > 5) tokens.drop(5).joinToString(" ").withoutCommentChar() else ""

            val precursors = commentedPrecursors(lines, index, tokens[0], tokens[1], useLegComments)

            addLeg(survey, stations, tokens, comment, precursors, useLegComments)
        }
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
            // A deliberate fix rather than a faithful port. The Java splits on the first whitespace
            // run and keeps the rest, so the four LRUD placeholders come back as part of the
            // comment — "junction" reads back as "- - - - junction" — and it compounds: export and
            // import twice and there are eight of them. That is a round trip corrupting data, which
            // is the one class of divergence this port treats as worth breaking compatibility over.
            //
            // A row with only two fields is still read as `station comment`, so a hand-written
            // block that omits the dimensions works as it did.
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
     * Put passage comments onto their stations, joining rather than overwriting.
     *
     * A station can have a comment from the passage block *and* one from its leg line; the original
     * combines them as `passage :: leg` rather than losing either, because they came from different
     * places and mean different things.
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

    // ---------------------------------------------------------------------------------------
    // Legs
    // ---------------------------------------------------------------------------------------

    private fun addLeg(
        survey: Survey,
        stations: MutableMap<String, Station>,
        fields: List<String>,
        rawComment: String,
        commentedPrecursors: List<Leg>,
        useLegComments: Boolean,
    ) {
        val fromName = fields[0]
        val toName = fields[1]
        val distance = fields[2].toFloatOrNull() ?: return
        val azimuth = fields[3].toFloatOrNull() ?: return
        val inclination = fields[4].toFloatOrNull() ?: return

        // Both formats' splay names, so a Survex file read as Therion still works.
        val isSplay = toName == ".." || toName == "-"
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

        if (promoted.isEmpty() && commentedPrecursors.isNotEmpty()) {
            promoted = commentedPrecursors.toTypedArray()
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
     * Whether the shot was taken from the far end.
     *
     * By *position*, not by station name: a leg whose from-station has not been seen before is one
     * shot back towards the survey. Both new, or both known, means something this app cannot
     * represent — a disconnected leg or a loop closure — and the original assumes forward for
     * either rather than guessing.
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

    // ---------------------------------------------------------------------------------------
    // Metadata bits
    // ---------------------------------------------------------------------------------------

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
     * Survex and Therion role names, which are not this app's own.
     *
     * "notes" is the book, and both "assistant" and "dog" mean the role SexyTopo calls DOG — which
     * is a joke in the original, and preserved rather than tidied, because a file written by the
     * Android app uses those words.
     */
    private fun parseRole(word: String): Trip.Role? =
        when (word.lowercase()) {
            "notes" -> Trip.Role.BOOK
            "instruments" -> Trip.Role.INSTRUMENTS
            "explorer" -> Trip.Role.EXPLORATION
            "dog", "assistant" -> Trip.Role.DOG
            else -> null
        }

    /** `yyyy.MM.dd`, from `SurvexTherionUtil.TRIP_DATE_PATTERN`. */
    private fun parseDate(text: String): SurveyDate? {
        val parts = text.trim().split(".")
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
