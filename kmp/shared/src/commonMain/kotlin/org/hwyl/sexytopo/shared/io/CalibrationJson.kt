package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hwyl.sexytopo.shared.calibration.CalibrationReading

/**
 * Calibration readings as JSON, in the Android app's own format.
 *
 * Ported from `control/io/basic/CalibrationJsonTranslater`: a flat array of objects with six
 * integer fields. The tag names are the app's, so a calibration saved by one and loaded by the
 * other is the same calibration — which matters because a calibration is a twenty-minute job
 * somebody does once and then reuses for every survey that instrument takes.
 *
 * The reason to persist it at all is that twenty minutes: fifty-six shots is long enough for a
 * phone to be dropped, a battery to die, or an app to be killed in a pocket, and losing the run
 * means doing all of it again.
 */
object CalibrationJson {

    const val GX_TAG = "gx"
    const val GY_TAG = "gy"
    const val GZ_TAG = "gz"
    const val MX_TAG = "mx"
    const val MY_TAG = "my"
    const val MZ_TAG = "mz"

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun write(readings: List<CalibrationReading>): String =
        pretty.encodeToString(JsonArray.serializer(), toJson(readings))

    /**
     * Reads a calibration file, or returns an empty list if it cannot be read.
     *
     * Deliberately forgiving where the Java throws: a saved calibration is a convenience, not the
     * survey, so a corrupt one should leave the screen empty rather than stop the app opening. A
     * reading missing any of its six fields is skipped rather than defaulted, because a zero
     * would be a plausible-looking sensor count that quietly spoils the fit.
     */
    fun read(text: String): List<CalibrationReading> {
        val array =
            runCatching { lenient.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return emptyList()
        return array.mapNotNull { toReading(it) }
    }

    fun toJson(readings: List<CalibrationReading>): JsonArray = buildJsonArray {
        for (reading in readings) {
            add(
                buildJsonObject {
                    put(GX_TAG, JsonPrimitive(reading.gx))
                    put(GY_TAG, JsonPrimitive(reading.gy))
                    put(GZ_TAG, JsonPrimitive(reading.gz))
                    put(MX_TAG, JsonPrimitive(reading.mx))
                    put(MY_TAG, JsonPrimitive(reading.my))
                    put(MZ_TAG, JsonPrimitive(reading.mz))
                },
            )
        }
    }

    private fun toReading(element: JsonElement): CalibrationReading? {
        val json = runCatching { element.jsonObject }.getOrNull() ?: return null
        return CalibrationReading(
            gx = json.intOrNull(GX_TAG) ?: return null,
            gy = json.intOrNull(GY_TAG) ?: return null,
            gz = json.intOrNull(GZ_TAG) ?: return null,
            mx = json.intOrNull(MX_TAG) ?: return null,
            my = json.intOrNull(MY_TAG) ?: return null,
            mz = json.intOrNull(MZ_TAG) ?: return null,
        )
    }

    private fun JsonObject.intOrNull(tag: String): Int? =
        runCatching { this[tag]?.jsonPrimitive?.intOrNull }.getOrNull()
}
