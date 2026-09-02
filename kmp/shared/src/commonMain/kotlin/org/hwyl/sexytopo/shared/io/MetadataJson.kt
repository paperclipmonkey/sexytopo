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
 * Carries the active station and this survey's links to other surveys.
 *
 * Android reads the active station only from here (this port also writes it into the data file,
 * as its own extension), so skipping this file leaves an Android-opened survey silently stuck at
 * the origin. The file is optional to Android, so the reader here is more lenient than the Java,
 * which throws when the tag is missing.
 *
 * Connections are always written as an empty object: they name the other survey by Android
 * `Uri`, which means nothing on another device, so cross-survey links are a documented gap here.
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
     * Applies what is in [text] to [survey], and says whether the active station was applied.
     *
     * Every failure is a no-op: unreadable JSON, no active station named, or a name not in this
     * survey — which can happen when the metadata file was edited elsewhere and the station renamed.
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
