package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch

/**
 * Reads and writes SexyTopo's `<survey>.plan.json` / `<survey>.ext-elevation.json` sketch files.
 *
 * Ported from `control/io/basic/SketchJsonTranslater`. Cross-sections are parsed past rather than
 * reconstructed in this proof of concept — see the module README for what is and is not covered.
 */
@OptIn(ExperimentalSerializationApi::class)
object SketchJson {

    const val PATHS_TAG = "paths"
    const val POINTS_TAG = "points"
    const val COLOUR_TAG = "colour"
    const val SYMBOLS_TAG = "symbols"
    const val LABELS_TAG = "labels"
    const val CROSS_SECTIONS_TAG = "x-sections"
    const val SYMBOL_ID_TAG = "symbol-id"
    const val TEXT_TAG = "text"
    const val SIZE_TAG = "size"
    const val POSITION_TAG = "location"
    const val ANGLE_TAG = "angle"
    const val X_TAG = "x"
    const val Y_TAG = "y"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun parse(text: String): Sketch {
        val root = json.parseToJsonElement(text).jsonObject
        val sketch = Sketch()

        root[PATHS_TAG]?.jsonArray?.forEach { element ->
            runCatching { toPathDetail(element.jsonObject) }.getOrNull()?.let {
                sketch.pathDetails.add(it)
            }
        }

        root[SYMBOLS_TAG]?.jsonArray?.forEach { element ->
            runCatching {
                val entry = element.jsonObject
                val position = toCoord2D(entry[POSITION_TAG]!!.jsonObject)
                sketch.addSymbolDetail(
                    position = position,
                    symbolName = entry.stringOrNull(SYMBOL_ID_TAG) ?: return@runCatching,
                    size = entry.floatOrNull(SIZE_TAG) ?: 1f,
                    angle = entry.floatOrNull(ANGLE_TAG) ?: 0f,
                    colour = colourOf(entry),
                )
            }
        }

        root[LABELS_TAG]?.jsonArray?.forEach { element ->
            runCatching {
                val entry = element.jsonObject
                val position = toCoord2D(entry[POSITION_TAG]!!.jsonObject)
                sketch.addTextDetail(
                    position = position,
                    text = entry.stringOrNull(TEXT_TAG) ?: return@runCatching,
                    size = entry.floatOrNull(SIZE_TAG) ?: 0f,
                    colour = colourOf(entry),
                )
            }
        }

        return sketch
    }

    fun write(sketch: Sketch, surveyName: String): String {
        val root = buildJsonObject {
            put(SurveyJson.SURVEY_NAME_TAG, surveyName)
            put(
                PATHS_TAG,
                buildJsonArray {
                    for (path in sketch.pathDetails) {
                        add(
                            buildJsonObject {
                                put(COLOUR_TAG, path.colour.name)
                                put(
                                    POINTS_TAG,
                                    buildJsonArray { for (p in path.path) add(toJson(p)) },
                                )
                            },
                        )
                    }
                },
            )
            put(
                LABELS_TAG,
                buildJsonArray {
                    for (label in sketch.textDetails) {
                        add(
                            buildJsonObject {
                                put(COLOUR_TAG, label.colour.name)
                                put(POSITION_TAG, toJson(label.position))
                                put(TEXT_TAG, label.text)
                                put(SIZE_TAG, label.size)
                            },
                        )
                    }
                },
            )
            put(
                SYMBOLS_TAG,
                buildJsonArray {
                    for (symbol in sketch.symbolDetails) {
                        add(
                            buildJsonObject {
                                put(COLOUR_TAG, symbol.colour.name)
                                put(POSITION_TAG, toJson(symbol.position))
                                put(SYMBOL_ID_TAG, symbol.symbolName)
                                put(SIZE_TAG, symbol.size)
                                put(ANGLE_TAG, symbol.angle)
                            },
                        )
                    }
                },
            )
            put(CROSS_SECTIONS_TAG, buildJsonArray {})
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    private fun toPathDetail(entry: JsonObject): PathDetail {
        val colour = colourOf(entry)
        val points = entry[POINTS_TAG]!!.jsonArray.map { toCoord2D(it.jsonObject) }
        return PathDetail(points, colour)
    }

    private fun colourOf(entry: JsonObject): Colour =
        entry.stringOrNull(COLOUR_TAG)?.let { Colour.fromNameOrNull(it) } ?: Colour.BLACK

    private fun toCoord2D(entry: JsonObject): Coord2D =
        Coord2D(entry.floatOrNull(X_TAG) ?: 0f, entry.floatOrNull(Y_TAG) ?: 0f)

    private fun toJson(coord: Coord2D): JsonObject = buildJsonObject {
        put(X_TAG, coord.x)
        put(Y_TAG, coord.y)
    }
}
