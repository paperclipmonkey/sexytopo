package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Reads and writes SexyTopo's `<survey>.metadata.json`, the fourth file of a survey directory.
 *
 * Ported from `control/io/basic/MetadataTranslater`. It carries exactly two things: which station
 * the surveyor was working at, and this survey's links to other surveys.
 *
 * ## Why it is worth having at all
 *
 * The port already keeps the active station, but writes it as `activeStation` inside the *data*
 * file, which is this port's own extension and not somewhere the Android app looks. `Loader` reads
 * the active station from here and nowhere else, so a survey written by this port and opened on
 * Android came up at the origin - a cave with two hundred stations, opened at the entrance, with no
 * indication that anything had been lost. Nothing was corrupted and nothing warned, which is the
 * kind of divergence that is only ever noticed underground.
 *
 * The file is *optional* to the Android app: `Loader.loadMetadata` is guarded by `exists()` and
 * `IoUtils.isSurveyDirectory` asks only for the data file. So this closes a small loss rather than
 * a failure to open - which is also why the reader here declines to be as strict as the Java one,
 * which throws when the tag is missing. A survey whose metadata file has no active station in it is
 * still a survey; the port keeps whatever the data file said.
 *
 * ## Connections
 *
 * Written as an empty object and ignored on read. Cross-survey links are a documented gap in this
 * port for a reason that is in the model rather than the file format: a connection names the other
 * survey by Android `Uri`, which is a path into one device's document provider and means nothing on
 * another phone. Writing `{}` is honest - it is what the Android app itself writes for a survey
 * with no links - and it keeps the file the shape the other end expects.
 */
object MetadataJson {

    const val ACTIVE_STATION_TAG = "active-station"
    const val CONNECTIONS_TAG = "connections"

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
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Applies what is in [text] to [survey], and says whether the active station was one of them.
     *
     * Every failure is a no-op rather than a throw: unreadable JSON, no active station named, or a
     * name that is not a station in this survey. The last is the one worth being careful about - a
     * survey edited on one device and its metadata file edited on another can name a station that
     * has since been renamed or deleted, and the Java's `setActiveStation(null)` would then leave
     * the app pointing at nothing.
     */
    fun apply(survey: Survey, text: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        val name =
            runCatching { root[ACTIVE_STATION_TAG]?.jsonPrimitive?.content }.getOrNull()
                ?: return false
        val station = survey.getStationByName(name) ?: return false
        survey.activeStation = station
        return true
    }
}
