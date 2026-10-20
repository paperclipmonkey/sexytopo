package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * integer fields and, from 1.14.0, a JSON null for a reading that was deleted and is waiting to be
 * retaken, so the readings after it keep their places. Tag names match the app's, so a calibration
 * saved by one loads correctly in the other. An Android app older than 1.14.0 cannot read a file
 * with a gap in it, but nor can it make one.
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

    fun write(readings: List<CalibrationReading?>): String =
        pretty.encodeToString(JsonArray.serializer(), toJson(readings))

    /**
     * Reads a calibration file, or returns an empty list if it cannot be read. A null in the file
     * is a gap, and stays one.
     *
     * Deliberately forgiving where the Java throws: a reading missing any of its six fields is
     * skipped rather than defaulted, since a zero would look like a plausible sensor value and
     * quietly spoil the fit.
     */
    fun read(text: String): List<CalibrationReading?> {
        val array =
            runCatching { lenient.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return emptyList()
        val readings = mutableListOf<CalibrationReading?>()
        for (element in array) {
            if (element is JsonNull) {
                readings.add(null)
            } else {
                toReading(element)?.let(readings::add)
            }
        }
        return readings
    }

    fun toJson(readings: List<CalibrationReading?>): JsonArray = buildJsonArray {
        for (reading in readings) {
            if (reading == null) {
                add(JsonNull)
                continue
            }
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
