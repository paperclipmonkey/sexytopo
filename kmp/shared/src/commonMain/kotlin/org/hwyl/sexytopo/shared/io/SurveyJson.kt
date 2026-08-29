package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip

/**
 * Reads and writes SexyTopo's native `<survey>.data.json` format.
 *
 * Ported from `control/io/basic/SurveyJsonTranslater`, keeping the tag names and the two-pass load
 * (create every station first, then wire the legs) so that files written by the Android app load
 * here unchanged — that byte-level compatibility is what would give Android and iOS lossless
 * survey interchange.
 *
 * Parsing is tolerant in the same shape as the original: a survey that is partly unreadable loads
 * as far as it can rather than failing outright. Where it differs is in *reporting* that. The Java
 * sets a static `errors` flag and pops a Toast from inside the parser, which is both a global and
 * a UI call buried in IO code; here [load] returns a [LoadResult] carrying the same fact plus the
 * specific reasons, and [parse] is the convenience wrapper for callers that do not care.
 *
 * The one thing that is deliberately *not* tolerated, exactly as in the original, is a leg whose
 * destination station is not in the file: see [MissingStationException]. Quietly rewriting it as a
 * splay would silently detach a whole branch of the cave and lose every station beyond it.
 */
@OptIn(ExperimentalSerializationApi::class)
object SurveyJson {

    const val VERSION_NAME_TAG = "versionName"
    const val VERSION_CODE_TAG = "versionCode"
    const val SURVEY_NAME_TAG = "surveyName"

    const val STATIONS_TAG = "stations"
    const val STATION_NAME_TAG = "name"
    const val DIRECTION_TAG = "eeDirection"
    const val COMMENT_TAG = "comment"

    const val ONWARD_LEGS_TAG = "legs"
    const val DISTANCE_TAG = "distance"
    const val AZIMUTH_TAG = "azimuth"
    const val INCLINATION_TAG = "inclination"
    const val PROMOTED_FROM_TAG = "promotedFrom"
    const val DESTINATION_TAG = "destination"
    const val WAS_SHOT_BACKWARDS_TAG = "wasShotBackwards"
    const val INDEX_TAG = "index"

    const val ACTIVE_STATION_TAG = "activeStation"

    const val TRIP_TAG = "trip"
    const val TRIP_DATE_TAG = "tripDate"
    const val SURVEY_DATE_TAG = "surveyDate"
    const val EXPLO_DATE_LINKED_TAG = "exploDateLinked"
    const val TEAM_TAG = "team"
    const val TEAM_MEMBER_NAME_TAG = "name"
    const val TEAM_MEMBER_ROLE_TAG = "role"
    const val INSTRUMENT_TAG = "instrument"
    const val COPYRIGHT_HOLDER_TAG = "copyrightHolder"
    const val LICENCE_TAG = "licence"

    /** The name the null (splay) destination is written as. */
    const val BLANK_STATION_NAME = "-"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    /**
     * A leg pointed at a station the file does not contain.
     *
     * The Java throws `JSONException("Survey file corrupted: station X missing or out of order")`
     * for this, and the surrounding per-leg catch turns it into "skip this leg, flag the load as
     * partial". Both halves matter: dropping the leg loses one shot, whereas the alternative the
     * port previously had — resolving the missing name to `Station.NULL_STATION` — turns a
     * connecting leg into a splay, so every station beyond it silently vanishes from the tree.
     */
    class MissingStationException(val stationName: String) :
        Exception("Survey file corrupted: station $stationName missing or out of order")

    /**
     * A survey and an honest account of what could not be read.
     *
     * [hadPartialErrors] is the Java's `errors` static, which it uses to decide whether to Toast
     * "Partial errors encountered; survey load was incomplete". [problems] is new: the Java only
     * writes the detail to logcat, which is no use to a user on a phone in a cave.
     */
    data class LoadResult(
        val survey: Survey,
        val hadPartialErrors: Boolean,
        val problems: List<String>,
    )

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    /** Loads a survey, discarding the record of anything that could not be read. */
    fun parse(text: String): Survey = load(text).survey

    /** Loads a survey, reporting what (if anything) was dropped along the way. */
    fun load(text: String): LoadResult {
        val problems = mutableListOf<String>()
        val root = json.parseToJsonElement(text).jsonObject
        val survey = Survey(root.stringOrNull(SURVEY_NAME_TAG) ?: Survey.DEFAULT_NAME)

        // Trips first, as in the original, so that stations could refer to one. A trip that will
        // not parse is dropped and the rest of the survey still loads — the Java lets a malformed
        // date escape as a ParseException and abort the whole load, which loses far more than it
        // protects.
        root[TRIP_TAG]?.let { element ->
            val trip = runCatching { toTrip(element.jsonObject) }.getOrNull()
            if (trip == null) {
                problems.add("Trip metadata could not be read")
            } else {
                survey.trip = trip
            }
        }

        val stationEntries = root[STATIONS_TAG]?.jsonArray ?: JsonArray(emptyList())
        loadSurveyData(survey, stationEntries, problems)

        val activeName = root.stringOrNull(ACTIVE_STATION_TAG)
        survey.activeStation = survey.getStationByName(activeName ?: "") ?: survey.origin

        return LoadResult(survey, problems.isNotEmpty(), problems)
    }

    /**
     * The two-pass load, ported from `loadSurveyData`.
     *
     * Pass one creates every station, so pass two can resolve a destination that appears later in
     * the file than the leg pointing at it. Pass two then hangs the legs off their stations while
     * enforcing the two structural invariants a survey tree has: no station is the destination of
     * more than one leg, and the origin is whichever station nothing leads to.
     */
    private fun loadSurveyData(
        survey: Survey,
        stationEntries: JsonArray,
        problems: MutableList<String>,
    ) {
        val stationsByName = LinkedHashMap<String, Station>()

        var isFirst = true
        for (element in stationEntries) {
            val entry = runCatching { element.jsonObject }.getOrNull()
            val station = entry?.let { toStationOrNull(it) }
            if (station == null) {
                problems.add("A station could not be read and was skipped")
                continue
            }
            if (stationsByName.containsKey(station.name)) {
                problems.add("Duplicate station ${station.name} was skipped")
                continue
            }
            stationsByName[station.name] = station
            if (isFirst) {
                isFirst = false
                survey.origin = station
            }
        }

        // Second pass: attach legs, recording the chronological index so the record can be rebuilt.
        val indexedLegs = mutableListOf<Pair<Int, Leg>>()
        val unindexedLegs = mutableListOf<Leg>()
        // Identity, not name: two stations can share a name only if the duplicate check above let
        // them through, which it does not — but the tree invariant is about objects either way.
        val connectedDestinations = mutableListOf<Station>()

        for (element in stationEntries) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val name = entry.stringOrNull(STATION_NAME_TAG) ?: continue
            val station = stationsByName[name] ?: continue
            val legs = runCatching { entry[ONWARD_LEGS_TAG]?.jsonArray }.getOrNull() ?: continue

            for (legElement in legs) {
                val legObject = runCatching { legElement.jsonObject }.getOrNull() ?: continue

                val leg =
                    try {
                        toLeg(legObject, stationsByName)
                    } catch (exception: MissingStationException) {
                        problems.add(
                            "A leg to missing station ${exception.stationName} was skipped",
                        )
                        continue
                    }
                if (leg == null) {
                    problems.add("A leg could not be read and was skipped")
                    continue
                }

                if (leg.hasDestination()) {
                    if (connectedDestinations.any { it === leg.destination }) {
                        problems.add(
                            "Duplicate connection to ${leg.destination.name} was skipped",
                        )
                        continue
                    }
                    connectedDestinations.add(leg.destination)

                    // Files are normally written origin-first, but a leg arriving at what we
                    // currently believe is the origin proves it is not: the station this leg
                    // starts from is further up the tree. Re-rooting here is what lets a survey
                    // written in some other order still load with the right origin, and without
                    // it the stations above the assumed origin would be unreachable.
                    if (leg.destination === survey.origin) {
                        survey.origin = station
                    }
                }

                station.addOnwardLeg(leg)

                val index = legObject.intOrNull(INDEX_TAG)
                if (index == null) {
                    unindexedLegs.add(leg)
                } else {
                    indexedLegs.add(index to leg)
                }
            }
        }

        // Unindexed legs first, then the indexed ones in index order — the Java's ordering, and
        // the only sane one: a leg with no index predates the version that started writing them.
        for (leg in unindexedLegs) {
            survey.addLegRecord(leg)
        }
        indexedLegs.sortBy { it.first }
        for ((_, leg) in indexedLegs) {
            survey.addLegRecord(leg)
        }

        // Prunes record entries for anything the re-rooting or the skips left unreachable, and
        // re-homes the active station if it went with them.
        survey.checkSurveyIntegrity()
    }

    private fun toStationOrNull(entry: JsonObject): Station? {
        val name = entry.stringOrNull(STATION_NAME_TAG) ?: return null
        val station = Station(name)
        station.comment = entry.stringOrNull(COMMENT_TAG) ?: ""
        station.extendedElevationDirection =
            ExtendedElevationDirection.fromStringOrDefault(entry.stringOrNull(DIRECTION_TAG))
        return station
    }

    /**
     * One leg, or null if its numbers are missing or out of range.
     *
     * @throws MissingStationException if it names a destination the file does not define, which is
     *   corruption rather than a merely unreadable field.
     */
    private fun toLeg(legObject: JsonObject, stationsByName: Map<String, Station>): Leg? {
        val distance = legObject.floatOrNull(DISTANCE_TAG) ?: return null
        val azimuth = legObject.floatOrNull(AZIMUTH_TAG) ?: return null
        val inclination = legObject.floatOrNull(INCLINATION_TAG) ?: return null

        if (!Leg.isDistanceLegal(distance) ||
            !Leg.isAzimuthLegal(azimuth) ||
            !Leg.isInclinationLegal(inclination)
        ) {
            return null
        }

        val wasShotBackwards = legObject.booleanOrNull(WAS_SHOT_BACKWARDS_TAG) ?: false
        val destinationName = legObject.stringOrNull(DESTINATION_TAG)

        // A splay: no destination, and — as in the original — no promotedFrom either, since only
        // a connecting leg is ever promoted from a set of splays.
        if (destinationName == null || destinationName == BLANK_STATION_NAME) {
            val splay = Leg(distance, azimuth, inclination, wasShotBackwards = wasShotBackwards)
            splay.comment = legObject.stringOrNull(COMMENT_TAG) ?: ""
            return splay
        }

        val destination =
            stationsByName[destinationName] ?: throw MissingStationException(destinationName)

        // The promotedFrom splays are the raw shots this leg was averaged from. They are nested
        // inside the leg rather than hung off a station, and losing them only costs the audit
        // trail, so a malformed one is dropped rather than failing the leg.
        val promotedFrom =
            runCatching {
                legObject[PROMOTED_FROM_TAG]
                    ?.jsonArray
                    ?.mapNotNull { element ->
                        runCatching { toLeg(element.jsonObject, stationsByName) }.getOrNull()
                    }
                    ?.toTypedArray()
            }.getOrNull() ?: Leg.NO_LEGS

        val leg = Leg(distance, azimuth, inclination, destination, promotedFrom, wasShotBackwards)
        leg.comment = legObject.stringOrNull(COMMENT_TAG) ?: ""
        return leg
    }

    /**
     * The trip block, or null if it has no usable survey date.
     *
     * Handles the format's one piece of backwards compatibility, exactly as the Java does: in old
     * files `tripDate` *is* the survey date, whereas in new ones `surveyDate` is the survey date
     * and `tripDate` has been repurposed as the (optional, earlier) exploration date.
     */
    fun toTrip(entry: JsonObject): Trip? {
        val hasNewFormat = entry[SURVEY_DATE_TAG] != null

        val surveyDate =
            SurveyDate.parseOrNull(
                entry.stringOrNull(if (hasNewFormat) SURVEY_DATE_TAG else TRIP_DATE_TAG),
            ) ?: return null

        val trip = Trip(surveyDate)
        if (hasNewFormat) {
            trip.explorationDate = SurveyDate.parseOrNull(entry.stringOrNull(TRIP_DATE_TAG))
            trip.explorationDateLinked = entry.booleanOrNull(EXPLO_DATE_LINKED_TAG) ?: true
        }

        trip.comments = entry.stringOrNull(COMMENT_TAG) ?: ""
        trip.instrument = entry.stringOrNull(INSTRUMENT_TAG) ?: ""
        trip.copyrightHolder = entry.stringOrNull(COPYRIGHT_HOLDER_TAG) ?: ""
        trip.licence = entry.stringOrNull(LICENCE_TAG) ?: ""

        trip.team =
            (runCatching { entry[TEAM_TAG]?.jsonArray }.getOrNull() ?: JsonArray(emptyList()))
                .mapNotNull { element ->
                    val memberEntry = runCatching { element.jsonObject }.getOrNull()
                        ?: return@mapNotNull null
                    val name = memberEntry.stringOrNull(TEAM_MEMBER_NAME_TAG)
                        ?: return@mapNotNull null
                    val roles =
                        (runCatching { memberEntry[TEAM_MEMBER_ROLE_TAG]?.jsonArray }.getOrNull()
                            ?: JsonArray(emptyList()))
                            .mapNotNull { role ->
                                Trip.Role.fromNameOrNull(
                                    (role as? JsonPrimitive)?.content,
                                )
                            }
                    Trip.TeamEntry(name, roles)
                }

        return trip
    }

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    fun write(survey: Survey, versionName: String = "kmp-port", versionCode: Int = 0): String {
        val chrono = survey.getAllLegsInChronoOrder()

        // The chronological index of each leg, precomputed. Neither Leg nor Station overrides
        // equals, so a HashMap keyed on them is identity-keyed — which is exactly the semantics
        // wanted (two identical readings are two different legs) and turns what the Java does with
        // a linear `chronoList.indexOf` per leg into one lookup. On a survey with ten thousand legs
        // that is the difference between a hundred million comparisons and ten thousand.
        val chronoIndices = HashMap<Leg, Int>(chrono.size)
        for ((index, leg) in chrono.withIndex()) {
            chronoIndices.putIfAbsentCompat(leg, index)
        }

        val root = buildJsonObject {
            put(VERSION_NAME_TAG, versionName)
            put(VERSION_CODE_TAG, versionCode)
            put(SURVEY_NAME_TAG, survey.name)
            put(
                STATIONS_TAG,
                buildJsonArray {
                    for (station in stationsToWrite(survey, chrono)) {
                        add(stationToJson(station, chronoIndices))
                    }
                },
            )
            survey.trip?.let { put(TRIP_TAG, tripToJson(it)) }
            put(ACTIVE_STATION_TAG, survey.activeStation.name)
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    /** First occurrence wins, matching `List.indexOf` when a leg somehow appears twice. */
    private fun <K, V> HashMap<K, V>.putIfAbsentCompat(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }

    /**
     * The stations to write, origin first.
     *
     * Origin-first is load-bearing, not cosmetic: the reader takes the first entry as the origin
     * (and only re-roots if a leg contradicts it), so writing them in any other order would move
     * the root of the cave.
     *
     * The Java writes exactly the origin plus each chronologically-recorded leg's destination.
     * This adds a third step — any remaining station reachable in the tree — which is normally
     * empty, because every leg that creates a station is also recorded. It is not always empty:
     * a leg whose record entry was lost still has its station hanging in the tree, and the Java
     * silently drops that station and everything beyond it on the next save. Writing the tail is
     * byte-identical in the normal case and lossless in the abnormal one.
     */
    private fun stationsToWrite(survey: Survey, chrono: List<Leg>): List<Station> {
        val ordered = mutableListOf(survey.origin)
        // Identity-keyed, as Station does not override equals; see the note in [write].
        val written = HashSet<Station>()
        written.add(survey.origin)

        for (leg in chrono) {
            if (leg.hasDestination() && written.add(leg.destination)) {
                ordered.add(leg.destination)
            }
        }
        for (station in survey.getAllStations()) {
            if (written.add(station)) {
                ordered.add(station)
            }
        }
        return ordered
    }

    private fun stationToJson(station: Station, chronoIndices: Map<Leg, Int>): JsonObject =
        buildJsonObject {
            put(STATION_NAME_TAG, station.name)
            put(DIRECTION_TAG, station.extendedElevationDirection.name.lowercase())
            put(COMMENT_TAG, station.comment)
            put(
                ONWARD_LEGS_TAG,
                buildJsonArray {
                    for (leg in station.onwardLegs) {
                        add(legToJson(leg, chronoIndices[leg]))
                    }
                },
            )
        }

    private fun legToJson(leg: Leg, index: Int?): JsonObject = buildJsonObject {
        put(DISTANCE_TAG, leg.distance)
        put(AZIMUTH_TAG, leg.azimuth)
        put(INCLINATION_TAG, leg.inclination)
        put(DESTINATION_TAG, leg.destination.name)
        put(WAS_SHOT_BACKWARDS_TAG, leg.wasShotBackwards)
        if (leg.hasComment()) {
            put(COMMENT_TAG, leg.comment)
        }
        if (index != null) {
            put(INDEX_TAG, index)
        }
        put(
            PROMOTED_FROM_TAG,
            buildJsonArray { for (promoted in leg.promotedFrom) add(legToJson(promoted, null)) },
        )
    }

    fun tripToJson(trip: Trip): JsonObject = buildJsonObject {
        put(SURVEY_DATE_TAG, trip.surveyDate.toString())
        trip.explorationDate?.let { put(TRIP_DATE_TAG, it.toString()) }
        put(EXPLO_DATE_LINKED_TAG, trip.explorationDateLinked)
        put(COMMENT_TAG, trip.comments)
        put(INSTRUMENT_TAG, trip.instrument)
        put(COPYRIGHT_HOLDER_TAG, trip.copyrightHolder)
        put(LICENCE_TAG, trip.licence)
        put(
            TEAM_TAG,
            buildJsonArray {
                for (member in trip.team) {
                    add(
                        buildJsonObject {
                            put(TEAM_MEMBER_NAME_TAG, member.name)
                            put(
                                TEAM_MEMBER_ROLE_TAG,
                                buildJsonArray {
                                    for (role in member.roles) add(JsonPrimitive(role.name))
                                },
                            )
                        },
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Tolerant accessors, mirroring the original's "load what you can" behaviour
// ---------------------------------------------------------------------------------------------

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

internal fun JsonObject.floatOrNull(key: String): Float? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.float }.getOrNull() }

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this.content == "null") null else content
