package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.hwyl.sexytopo.shared.model.survey.StationFix
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Reads and writes SexyTopo's `<survey>.metadata.json`, the fourth file of a survey directory.
 *
 * Carries the active station and this survey's links to other surveys.
 *
 * Android reads the active station only from here (this port also writes it into the data file,
 * as its own extension), so skipping this file leaves an Android-opened survey silently stuck at
 * the origin. The file is optional to Android, so the reader here is more lenient than the Java,
 * which throws when the tag is missing.
 *
 * Connections are always written as an empty object: they name the other survey by Android
 * `Uri`, which means nothing on another device, so cross-survey links are a documented gap here.
 *
 * It also carries this port's own addition: where in the world one of the survey's stations is,
 * under a key the Android app has never heard of. That is the same bargain the photograph pins
 * struck — a reader that does not know a key ignores it, so a survey with a position in it still
 * opens on Android with everything else intact, and this reader treats an absent position as the
 * ordinary case rather than as damage.
 */
object MetadataJson {

    const val ACTIVE_STATION_TAG = "active-station"
    const val CONNECTIONS_TAG = "connections"

    /** This port's own, and unknown to the Android app: see the note on the class. */
    const val FIX_TAG = "fix"

    const val FIX_STATION_TAG = "station"
    const val FIX_LATITUDE_TAG = "latitude"
    const val FIX_LONGITUDE_TAG = "longitude"
    const val FIX_ALTITUDE_TAG = "altitude"
    const val FIX_HORIZONTAL_ACCURACY_TAG = "horizontal-accuracy"
    const val FIX_VERTICAL_ACCURACY_TAG = "vertical-accuracy"
    const val FIX_SOURCE_TAG = "source"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun write(survey: Survey, versionName: String, versionCode: Int): String {
        val root = buildJsonObject {
            put(SurveyJson.VERSION_NAME_TAG, versionName)
            put(SurveyJson.VERSION_CODE_TAG, versionCode)
            put(SurveyJson.SURVEY_NAME_TAG, survey.name)
            put(ACTIVE_STATION_TAG, survey.activeStation.name)
            put(CONNECTIONS_TAG, JsonObject(emptyMap()))
            // Written as degrees and metres, the same numbers the exports carry, rather than as
            // whatever the receiver's own units were: this file is read by people as well as by
            // programs, and a coordinate is the one thing in it somebody might check by eye.
            survey.fix?.let { fix ->
                putJsonObject(FIX_TAG) {
                    put(FIX_STATION_TAG, fix.station)
                    put(FIX_LATITUDE_TAG, fix.latitude)
                    put(FIX_LONGITUDE_TAG, fix.longitude)
                    put(FIX_ALTITUDE_TAG, fix.altitude)
                    fix.horizontalAccuracy?.let { put(FIX_HORIZONTAL_ACCURACY_TAG, it) }
                    fix.verticalAccuracy?.let { put(FIX_VERTICAL_ACCURACY_TAG, it) }
                    put(FIX_SOURCE_TAG, fix.source.name.lowercase())
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Applies what is in [text] to [survey], and says whether the active station was applied.
     *
     * Every failure is a no-op *for the active station*: unreadable JSON, no active station named,
     * or a name not in this survey — which can happen when the metadata file was edited elsewhere
     * and the station renamed.
     *
     * The position is applied first and separately, because it is independent of all of that. A
     * file whose active station has been renamed away still carries a perfectly good position, and
     * losing it as well would be a second thing going wrong for the price of the first — the more
     * annoying of the two, since a working station is one tap to set again and a position is a
     * walk back to the entrance.
     */
    fun apply(survey: Survey, text: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        survey.fix = fixIn(root)

        val name =
            runCatching { root[ACTIVE_STATION_TAG]?.jsonPrimitive?.content }.getOrNull()
                ?: return false
        val station = survey.getStationByName(name) ?: return false
        survey.activeStation = station
        return true
    }

    /**
     * The position in this file, or null if there is none or it is not one.
     *
     * Lenient in the same way as everything else here, and for a sharper reason: a survey whose
     * metadata carries a half-written position should open without one rather than refuse to open.
     * A coordinate out of range or a missing altitude is a file that has been edited by hand or
     * written by something else, and the survey itself — the legs, the drawings — is not in doubt
     * because of it.
     *
     * The station is *not* checked against the survey here. A fix naming a station that has since
     * been renamed is worth keeping so that it can be repointed or rescued; the export is where
     * that is noticed, and it comments the fix out rather than dropping it.
     */
    private fun fixIn(root: JsonObject): StationFix? {
        val fix = runCatching { root[FIX_TAG]?.jsonObject }.getOrNull() ?: return null
        fun number(tag: String): Double? =
            runCatching { fix[tag]?.jsonPrimitive?.content?.toDoubleOrNull() }.getOrNull()

        val station = runCatching { fix[FIX_STATION_TAG]?.jsonPrimitive?.content }.getOrNull()
        val latitude = number(FIX_LATITUDE_TAG)
        val longitude = number(FIX_LONGITUDE_TAG)
        val altitude = number(FIX_ALTITUDE_TAG)
        if (station == null || latitude == null || longitude == null || altitude == null) {
            return null
        }

        val source =
            runCatching { fix[FIX_SOURCE_TAG]?.jsonPrimitive?.content }.getOrNull()
                ?.let { name ->
                    StationFix.Source.entries.firstOrNull { it.name.equals(name, true) }
                }
                ?: StationFix.Source.ENTERED

        // The constructor is what says whether these numbers are a position at all, so a bad one
        // throws here and is caught rather than checked twice in two places that could disagree.
        return runCatching {
            StationFix(
                station = station,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                horizontalAccuracy = number(FIX_HORIZONTAL_ACCURACY_TAG),
                verticalAccuracy = number(FIX_VERTICAL_ACCURACY_TAG),
                source = source,
            )
        }.getOrNull()
    }
}
