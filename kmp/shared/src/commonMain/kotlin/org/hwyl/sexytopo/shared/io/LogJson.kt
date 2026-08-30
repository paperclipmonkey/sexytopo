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
 * Ported from `Log.marshal` and `Log.Message.marshal`. Note what that does: it builds a
 * `Map<String, String>` and hands it to `new JSONObject(map)`, so `isError` is written as the
 * *string* `"true"`, not as a JSON boolean, and read back with `getString`. Reproduced rather than
 * corrected, because a log file is meant to be interchangeable and a boolean here would make the
 * Android app's own reader throw.
 *
 * Reading is forgiving in the other direction: a real boolean is accepted too, so a file this port
 * had written more sensibly would still load.
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
     * Nothing here is worth failing over. A log is what you look at when something else has gone
     * wrong, so a corrupt one must not become the thing that goes wrong next; a line missing its
     * text is dropped, and one missing its timestamp is kept with an empty one, because the text is
     * the part somebody needs.
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
            // The Android app writes "true"; something more sensible would write true.
            isError = json.stringOrNull(IS_ERROR_TAG)?.equals("true", ignoreCase = true) == true,
        )
    }

    private fun JsonObject.stringOrNull(tag: String): String? =
        runCatching { this[tag]?.jsonPrimitive?.content }.getOrNull()
}
