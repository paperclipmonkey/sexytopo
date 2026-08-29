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

/**
 * Reads and writes SexyTopo's native `<survey>.data.json` format.
 *
 * Ported from `control/io/basic/SurveyJsonTranslater`, keeping the tag names and the two-pass load
 * (create every station first, then wire the legs) so that files written by the Android app load
 * here unchanged — that byte-level compatibility is what would give Android and iOS lossless
 * survey interchange.
 *
 * Parsing is deliberately tolerant, like the original: a survey that is partly unreadable still
 * loads as far as it can rather than failing outright.
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

    /** The name the null (splay) destination is written as. */
    const val BLANK_STATION_NAME = "-"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    fun parse(text: String): Survey {
        val root = json.parseToJsonElement(text).jsonObject
        val survey = Survey(root.stringOrNull(SURVEY_NAME_TAG) ?: Survey.DEFAULT_NAME)

        val stationEntries = root[STATIONS_TAG]?.jsonArray ?: JsonArray(emptyList())

        // First pass: every station exists before any leg points at one.
        val stationsByName = LinkedHashMap<String, Station>()
        for (element in stationEntries) {
            val entry = element.jsonObject
            val name = entry.stringOrNull(STATION_NAME_TAG) ?: continue
            val station = Station(name)
            station.comment = entry.stringOrNull(COMMENT_TAG) ?: ""
            station.extendedElevationDirection =
                ExtendedElevationDirection.fromStringOrDefault(entry.stringOrNull(DIRECTION_TAG))
            stationsByName[name] = station
        }

        val originName = stationEntries.firstOrNull()?.jsonObject?.stringOrNull(STATION_NAME_TAG)
        survey.origin = stationsByName[originName] ?: Station(Survey.ORIGIN_NAME)

        // Second pass: attach legs, recording chronological index so the record can be rebuilt.
        val indexedLegs = mutableListOf<Pair<Int, Leg>>()
        for (element in stationEntries) {
            val entry = element.jsonObject
            val name = entry.stringOrNull(STATION_NAME_TAG) ?: continue
            val station = stationsByName[name] ?: continue
            val legs = entry[ONWARD_LEGS_TAG]?.jsonArray ?: continue
            for (legElement in legs) {
                val legObject = legElement.jsonObject
                val leg = toLeg(legObject, stationsByName) ?: continue
                station.addOnwardLeg(leg)
                val index = legObject.intOrNull(INDEX_TAG)
                if (index != null) {
                    indexedLegs.add(index to leg)
                }
            }
        }

        indexedLegs.sortBy { it.first }
        for ((_, leg) in indexedLegs) {
            survey.addLegRecord(leg)
        }

        val activeName = root.stringOrNull(ACTIVE_STATION_TAG)
        survey.activeStation = stationsByName[activeName] ?: survey.origin

        return survey
    }

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

        val destinationName = legObject.stringOrNull(DESTINATION_TAG)
        val destination =
            if (destinationName == null || destinationName == BLANK_STATION_NAME) {
                Station.NULL_STATION
            } else {
                stationsByName[destinationName] ?: Station.NULL_STATION
            }

        val promotedFrom =
            legObject[PROMOTED_FROM_TAG]
                ?.jsonArray
                ?.mapNotNull { toLeg(it.jsonObject, stationsByName) }
                ?.toTypedArray()
                ?: Leg.NO_LEGS

        val wasShotBackwards = legObject.booleanOrNull(WAS_SHOT_BACKWARDS_TAG) ?: false

        val leg = Leg(distance, azimuth, inclination, destination, promotedFrom, wasShotBackwards)
        leg.comment = legObject.stringOrNull(COMMENT_TAG) ?: ""
        return leg
    }

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    fun write(survey: Survey, versionName: String = "kmp-port", versionCode: Int = 0): String {
        val chrono = survey.getAllLegsInChronoOrder()

        val root = buildJsonObject {
            put(VERSION_NAME_TAG, versionName)
            put(VERSION_CODE_TAG, versionCode)
            put(SURVEY_NAME_TAG, survey.name)
            put(
                STATIONS_TAG,
                buildJsonArray {
                    for (station in survey.getAllStations()) {
                        add(stationToJson(station, chrono))
                    }
                },
            )
            put(ACTIVE_STATION_TAG, survey.activeStation.name)
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    private fun stationToJson(station: Station, chrono: List<Leg>): JsonObject = buildJsonObject {
        put(STATION_NAME_TAG, station.name)
        put(DIRECTION_TAG, station.extendedElevationDirection.name.lowercase())
        put(COMMENT_TAG, station.comment)
        put(
            ONWARD_LEGS_TAG,
            buildJsonArray {
                for (leg in station.onwardLegs) {
                    val index = chrono.indexOfFirst { it === leg }
                    add(legToJson(leg, if (index >= 0) index else null))
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
