package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hwyl.sexytopo.shared.log.LogMessage

/**
 * The log as JSON, in the Android app's own format.
 *
 * Ported from `Log.marshal`: `isError` is written as the *string* `"true"`, not a JSON boolean,
 * because the Java builds a `Map<String, String>` and hands it to `new JSONObject(map)`.
 * Reproduced rather than corrected, since a boolean here would make the Android reader throw.
 * Reading also accepts a real boolean, so a more sensibly-written file still loads.
 */
object LogJson {

    const val TIMESTAMP_TAG = "timestamp"
    const val IS_ERROR_TAG = "isError"
    const val TEXT_TAG = "text"

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Indented by four, as `SaveLogTask` writes it with `toString(4)`. */
    fun write(messages: List<LogMessage>): String =
        pretty.encodeToString(JsonArray.serializer(), toJson(messages))

    fun toJson(messages: List<LogMessage>): JsonArray = buildJsonArray {
        for (message in messages) {
            add(
                buildJsonObject {
                    put(TIMESTAMP_TAG, JsonPrimitive(message.timestamp))
                    put(IS_ERROR_TAG, JsonPrimitive(message.isError.toString()))
                    put(TEXT_TAG, JsonPrimitive(message.text))
                },
            )
        }
    }

    /**
     * Reads a log file, or returns an empty list if it cannot be read.
     *
     * A line missing its text is dropped, but one missing its timestamp is kept with an empty
     * one, since the text is the part that matters.
     */
    fun read(text: String): List<LogMessage> {
        val array =
            runCatching { lenient.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return emptyList()
        return array.mapNotNull { toMessage(it) }
    }

    private fun toMessage(element: JsonElement): LogMessage? {
        val json = runCatching { element.jsonObject }.getOrNull() ?: return null
        val text = json.stringOrNull(TEXT_TAG) ?: return null
        return LogMessage(
            timestamp = json.stringOrNull(TIMESTAMP_TAG) ?: "",
            text = text,
            isError = json.stringOrNull(IS_ERROR_TAG)?.equals("true", ignoreCase = true) == true,
        )
    }

    private fun JsonObject.stringOrNull(tag: String): String? =
        runCatching { this[tag]?.jsonPrimitive?.content }.getOrNull()
}
