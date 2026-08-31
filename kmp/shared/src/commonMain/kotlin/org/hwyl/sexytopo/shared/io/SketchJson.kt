package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.simplificationEpsilon
import org.hwyl.sexytopo.shared.sketch.simplify

/**
 * Reads and writes SexyTopo's `<survey>.plan.json` / `<survey>.ext-elevation.json` sketch files.
 *
 * Ported from `control/io/basic/SketchJsonTranslater`.
 *
 * Cross-sections live in the `x-sections` array. Each entry names the station it belongs to by
 * name (`station-id`) rather than by any id, so a sketch can only be read back against the survey
 * it belongs to — hence the [Survey] parameter on [parse]. Its own drawn content is a nested
 * sketch object under `sketch`, holding the same `paths`/`labels`/`symbols` arrays as the top
 * level (but never a further `x-sections`: cross-sections do not nest).
 */
@OptIn(ExperimentalSerializationApi::class)
/** A sketch as it was read, and how much of it could not be. */
class SketchRead(val sketch: Sketch, val dropped: Int)

object SketchJson {

    const val PATHS_TAG = "paths"
    const val POINTS_TAG = "points"
    const val COLOUR_TAG = "colour"
    const val SYMBOLS_TAG = "symbols"
    const val LABELS_TAG = "labels"
    const val CROSS_SECTIONS_TAG = "x-sections"
    const val SKETCH_TAG = "sketch"
    const val SYMBOL_ID_TAG = "symbol-id"
    const val TEXT_TAG = "text"
    const val SIZE_TAG = "size"
    const val STATION_ID_TAG = "station-id"
    const val POSITION_TAG = "location"
    const val ANGLE_TAG = "angle"
    const val SETTINGS_TAG = "settings"
    const val CROSS_SECTION_SCALE_TAG = "cross-section-scale"
    const val X_TAG = "x"
    const val Y_TAG = "y"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * @param survey the survey the sketch belongs to, needed to resolve `station-id` back to a
     *   station object. Cross-sections are skipped when it is absent (or names a station the survey
     *   does not have) — see [toCrossSectionDetail].
     */
    fun parse(text: String, survey: Survey? = null): Sketch = read(text, survey).sketch

    /**
     * The same read, and how much of the drawing it had to leave behind.
     *
     * Every detail is parsed inside its own `runCatching`, so one damaged stroke costs one stroke
     * rather than the drawing — which is a deliberate divergence from
     * `SketchJsonTranslater.populateSketch`, where the loop over paths sits *inside* a single try:
     * one bad stroke there throws out of the loop, `setPathDetails` is never reached, and the whole
     * plan is lost. (Its symbols loop has an inner try and does not behave that way; its paths,
     * labels and cross-sections do.)
     *
     * Being more forgiving is only an improvement if it is not also quieter. The Java logs each of
     * those failures; this said nothing at all, so a drawing that arrived three strokes short
     * looked exactly like a drawing that was drawn three strokes short. [dropped] is what the
     * importer needs to say so.
     */
    fun read(text: String, survey: Survey? = null): SketchRead {
        val root = json.parseToJsonElement(text).jsonObject
        val sketch = Sketch()

        var dropped = readDrawnDetails(root, sketch)

        for (element in root.arrayOrEmpty(CROSS_SECTIONS_TAG)) {
            val entry = runCatching { element.jsonObject }.getOrNull()
            val detail = entry?.let { runCatching { toCrossSectionDetail(it, survey) }.getOrNull() }
            when {
                detail != null -> sketch.crossSectionDetails.add(detail)
                // An *orphan* is not damage. A cross-section names the station it was cut at, and
                // deleting a station does not delete the drawing at it — neither here nor in the
                // Android app, and deliberately, because the drawing is the surveyor's work and
                // not a view of the graph. So a sketch file can legitimately hold a cross-section
                // whose station is gone, [toCrossSectionDetail] returns null for it by design, and
                // counting that as a mark that "could not be read" would warn about damage on
                // every single open of a perfectly good survey. A warning that cries wolf is worse
                // than no warning.
                entry != null && isOrphanedCrossSection(entry, survey) -> Unit
                else -> dropped++
            }
        }

        runCatching { root[SETTINGS_TAG]?.jsonObject?.floatOrNull(CROSS_SECTION_SCALE_TAG) }
            .getOrNull()
            ?.let { sketch.crossSectionScale = it }

        return SketchRead(sketch, dropped)
    }

    fun write(sketch: Sketch, surveyName: String): String {
        val root = buildJsonObject {
            put(SurveyJson.SURVEY_NAME_TAG, surveyName)
            put(PATHS_TAG, pathsToJson(sketch))
            put(LABELS_TAG, labelsToJson(sketch))
            put(SYMBOLS_TAG, symbolsToJson(sketch))
            put(
                CROSS_SECTIONS_TAG,
                buildJsonArray {
                    for (detail in sketch.crossSectionDetails) add(toJson(detail))
                },
            )
            put(SETTINGS_TAG, buildJsonObject { put(CROSS_SECTION_SCALE_TAG, sketch.crossSectionScale) })
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    // -----------------------------------------------------------------------------------------
    // Cross-sections
    // -----------------------------------------------------------------------------------------

    /**
     * One `x-sections` entry: which station, where on the sketch, at what bearing, and — only if
     * anything has been drawn in it — the nested sub-sketch.
     *
     * The empty-sub-sketch check is the original's: paths, symbols and labels only. A sub-sketch
     * carrying nothing but (impossible) nested cross-sections still counts as empty.
     */
    fun toJson(detail: CrossSectionDetail): JsonObject = buildJsonObject {
        put(STATION_ID_TAG, detail.station.name)
        put(POSITION_TAG, toJson(detail.position))
        put(ANGLE_TAG, detail.crossSection.angle)
        val subSketch = detail.sketch
        if (subSketch.hasDrawnDetails()) {
            put(SKETCH_TAG, toSubSketchJson(subSketch))
        }
    }

    /**
     * Rebuilds a cross-section, or returns null if it cannot be attached to a real station.
     *
     * Deliberate divergence: the Java looks the station up and stores whatever comes back, so a
     * sketch referring to a since-deleted station yields a detail with a null station that throws
     * the moment anything draws or re-saves it. Skipping is the safer equivalent, and the only
     * cost is that a dangling section is dropped on load rather than on crash.
     */
    /**
     * Whether this cross-section was skipped because its station is gone rather than because the
     * entry is broken: it names a station, and the survey does not have one by that name.
     */
    private fun isOrphanedCrossSection(entry: JsonObject, survey: Survey?): Boolean {
        val stationName = entry.stringOrNull(STATION_ID_TAG) ?: return false
        return survey == null || survey.getStationByName(stationName) == null
    }

    fun toCrossSectionDetail(entry: JsonObject, survey: Survey?): CrossSectionDetail? {
        val stationName = entry.stringOrNull(STATION_ID_TAG) ?: return null
        val station = survey?.getStationByName(stationName) ?: return null
        val position = runCatching { toCoord2D(entry[POSITION_TAG]!!.jsonObject) }.getOrNull()
            ?: return null
        val angle = entry.floatOrNull(ANGLE_TAG) ?: return null

        val subSketch = Sketch()
        runCatching { entry[SKETCH_TAG]?.jsonObject }.getOrNull()?.let {
            readDrawnDetails(it, subSketch)
        }

        return CrossSectionDetail(position, CrossSection(station, angle), subSketch)
    }

    private fun toSubSketchJson(sketch: Sketch): JsonObject = buildJsonObject {
        put(PATHS_TAG, pathsToJson(sketch))
        put(LABELS_TAG, labelsToJson(sketch))
        put(SYMBOLS_TAG, symbolsToJson(sketch))
    }

    /** The original's `isSketchEmpty`, inverted: cross-section details deliberately don't count. */
    private fun Sketch.hasDrawnDetails(): Boolean =
        pathDetails.isNotEmpty() || symbolDetails.isNotEmpty() || textDetails.isNotEmpty()

    // -----------------------------------------------------------------------------------------
    // Paths, labels and symbols — shared by the top-level sketch and by every sub-sketch
    // -----------------------------------------------------------------------------------------

    /** Returns how many details could not be read. */
    private fun readDrawnDetails(root: JsonObject, sketch: Sketch): Int {
        var dropped = 0

        for (element in root.arrayOrEmpty(PATHS_TAG)) {
            val path = runCatching { toPathDetail(element.jsonObject) }.getOrNull()
            if (path == null) dropped++ else sketch.pathDetails.add(path)
        }

        for (element in root.arrayOrEmpty(SYMBOLS_TAG)) {
            val added = runCatching {
                val entry = element.jsonObject
                sketch.addSymbolDetail(
                    position = toCoord2D(entry[POSITION_TAG]!!.jsonObject),
                    symbolName = entry.stringOrNull(SYMBOL_ID_TAG) ?: return@runCatching false,
                    size = entry.floatOrNull(SIZE_TAG) ?: 1f,
                    angle = entry.floatOrNull(ANGLE_TAG) ?: 0f,
                    colour = colourOf(entry),
                )
                true
            }.getOrDefault(false)
            if (!added) dropped++
        }

        for (element in root.arrayOrEmpty(LABELS_TAG)) {
            val added = runCatching {
                val entry = element.jsonObject
                sketch.addTextDetail(
                    position = toCoord2D(entry[POSITION_TAG]!!.jsonObject),
                    text = entry.stringOrNull(TEXT_TAG) ?: return@runCatching false,
                    size = entry.floatOrNull(SIZE_TAG) ?: 0f,
                    colour = colourOf(entry),
                )
                true
            }.getOrDefault(false)
            if (!added) dropped++
        }
        return dropped
    }

    private fun pathsToJson(sketch: Sketch): JsonArray = buildJsonArray {
        for (path in sketch.pathDetails) {
            add(
                buildJsonObject {
                    put(COLOUR_TAG, path.colour.name)
                    put(POINTS_TAG, buildJsonArray { for (p in path.path) add(toJson(p)) })
                },
            )
        }
    }

    private fun labelsToJson(sketch: Sketch): JsonArray = buildJsonArray {
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
    }

    private fun symbolsToJson(sketch: Sketch): JsonArray = buildJsonArray {
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
    }

    /**
     * One stroke, thinned on the way in.
     *
     * The simplification is not an optimisation this port added — it is in the original's loader,
     * and leaving it out would be a fidelity bug in the other direction: an old file whose strokes
     * were saved raw (several hundred touch samples per wall) would render and export with far more
     * points here than on Android, and would grow rather than shrink each time it was re-saved. The
     * tolerance is relative to the stroke's own bounding box, so it is resolution-independent.
     */
    private fun toPathDetail(entry: JsonObject): PathDetail {
        val colour = colourOf(entry)
        val points = entry[POINTS_TAG]!!.jsonArray.map { toCoord2D(it.jsonObject) }
        return PathDetail(simplify(points, simplificationEpsilon(points)), colour)
    }

    private fun colourOf(entry: JsonObject): Colour =
        entry.stringOrNull(COLOUR_TAG)?.let { Colour.fromNameOrNull(it) } ?: Colour.BLACK

    private fun toCoord2D(entry: JsonObject): Coord2D =
        Coord2D(entry.floatOrNull(X_TAG) ?: 0f, entry.floatOrNull(Y_TAG) ?: 0f)

    private fun toJson(coord: Coord2D): JsonObject = buildJsonObject {
        put(X_TAG, coord.x)
        put(Y_TAG, coord.y)
    }

    /** A missing or malformed array reads as empty, matching the original's log-and-carry-on. */
    private fun JsonObject.arrayOrEmpty(key: String): JsonArray =
        runCatching { this[key]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
}
