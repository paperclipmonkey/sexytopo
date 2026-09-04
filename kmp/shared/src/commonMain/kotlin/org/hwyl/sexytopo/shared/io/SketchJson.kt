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
 * Cross-sections live in the `x-sections` array, each naming its station by name (`station-id`)
 * rather than by id, so a sketch can only be read back against its survey — hence the [Survey]
 * parameter on [parse]. Its drawn content is a nested sketch under `sketch`, with the same
 * `paths`/`labels`/`symbols`/`photos` arrays as the top level (never a further `x-sections`).
 *
 * The `photos` array is ours alone — the Android app neither writes nor reads it. Each entry is a
 * pin that names its image by id rather than carrying it; see photosToJson for why, and for why a
 * file with photos in it still opens on Android.
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

    // Everything above is a verbatim copy of the tags in the Android app's SketchJsonTranslater,
    // so the two agree key for key. These three have no counterpart there; photos reuse
    // COLOUR_TAG, POSITION_TAG, SIZE_TAG and ANGLE_TAG for the parts symbols already name.
    const val PHOTOS_TAG = "photos"
    const val PHOTO_ID_TAG = "photo-id"
    const val CAPTION_TAG = "caption"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** @param survey needed to resolve `station-id`; cross-sections are skipped without it. */
    fun parse(text: String, survey: Survey? = null): Sketch = read(text, survey).sketch

    /**
     * The same read, and how much of the drawing it had to leave behind.
     *
     * Every detail is parsed inside its own `runCatching`, so one damaged stroke costs one stroke
     * rather than the whole drawing — unlike `SketchJsonTranslater.populateSketch`, where one bad
     * stroke throws out before `setPathDetails` is reached, losing the entire plan.
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
                // An orphan (station since deleted) is not damage: warning here would fire on
                // every open of a perfectly good survey.
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
            put(PHOTOS_TAG, photosToJson(sketch))
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

    /**
     * One `x-sections` entry: station, position, bearing, and the nested sub-sketch if anything
     * was drawn in it (paths, symbols and labels only — matching the original's empty check).
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

    /** Whether this was skipped for a deleted station rather than a broken entry. */
    private fun isOrphanedCrossSection(entry: JsonObject, survey: Survey?): Boolean {
        val stationName = entry.stringOrNull(STATION_ID_TAG) ?: return false
        return survey == null || survey.getStationByName(stationName) == null
    }

    /**
     * Deliberate divergence: the Java looks the station up and stores whatever comes back, so a
     * sketch referring to a since-deleted station yields a detail with a null station that throws
     * the moment anything draws or re-saves it. Returning null here is the safer equivalent — a
     * dangling section is dropped on load rather than on crash.
     */
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

    /**
     * Photos are written into a cross-section's sub-sketch as well as at the top level, so a pin
     * dropped on a section survives a save.
     *
     * Supporting them at the top level only would be the smaller change but not a coherent one:
     * readDrawnDetails is the single reader for both levels, so it would go on accepting a nested
     * photo that nothing here could ever have written. A sub-sketch is ordinary ink on an ordinary
     * sketch, and a photograph of the passage at that station is exactly what belongs on one.
     * Nothing extra is needed to make it work, because images are named per survey rather than per
     * sketch — but for the same reason PhotoStore has to count ids used inside cross-sections when
     * it picks the next one, or a new photo would overwrite a nested pin's image file.
     */
    private fun toSubSketchJson(sketch: Sketch): JsonObject = buildJsonObject {
        put(PATHS_TAG, pathsToJson(sketch))
        put(LABELS_TAG, labelsToJson(sketch))
        put(SYMBOLS_TAG, symbolsToJson(sketch))
        put(PHOTOS_TAG, photosToJson(sketch))
    }

    /**
     * The original's `isSketchEmpty`, inverted: cross-section details deliberately don't count.
     *
     * Photos do, even though the Java has no notion of them. Its check can only ever see paths,
     * symbols and labels, so counting photos here cannot make us disagree with it about any sketch
     * it could have written; leaving them out would mean a cross-section holding nothing but a
     * photo wrote no `sketch` object at all, and the pin would be gone by the next load.
     */
    private fun Sketch.hasDrawnDetails(): Boolean =
        pathDetails.isNotEmpty() ||
            symbolDetails.isNotEmpty() ||
            textDetails.isNotEmpty() ||
            photoDetails.isNotEmpty()

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

        for (element in root.arrayOrEmpty(PHOTOS_TAG)) {
            val added = runCatching {
                val entry = element.jsonObject
                sketch.addPhotoDetail(
                    position = toCoord2D(entry[POSITION_TAG]!!.jsonObject),
                    photoId = entry.stringOrNull(PHOTO_ID_TAG) ?: return@runCatching false,
                    size = entry.floatOrNull(SIZE_TAG) ?: 1f,
                    angle = entry.floatOrNull(ANGLE_TAG) ?: 0f,
                    // A pin nobody has captioned is a normal pin, not a damaged one.
                    caption = entry.stringOrNull(CAPTION_TAG) ?: "",
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
     * A photo pin: where it sits, how big and which way up it is drawn, its caption, and the id of
     * the image it stands for.
     *
     * Only the id is written. The image itself is a separate file beside the survey's other files,
     * named by PhotoStore.fileNameFor, which keeps the sketch small enough to go on loading and
     * re-saving quickly however many photographs a survey collects. It also means a survey copied
     * without its photos still opens: the pins read back as normal and simply have nothing behind
     * them. Nothing here treats that as damage — the reader never looks for the file, so a missing
     * image cannot count towards `dropped` — and the drawing layer should show such a pin as an
     * empty placeholder that can be seen and deleted, rather than quietly discarding it and
     * writing the loss back out on the next save.
     *
     * The Android app knows nothing about photos and must still be able to open a file written
     * here. It can: its SketchJsonTranslater.toSketch asks only for the keys it names — `paths`,
     * `symbols`, `labels`, `x-sections`, `settings` — and never enumerates the object, so an extra
     * `photos` array simply goes unread, and its toSubSketch does the same for the nested case.
     * readDrawnDetails above is key-driven in exactly that way too, taking each array it knows by
     * name and ignoring the rest; that is what has to stay true on both sides for this to keep
     * working. The one cost is that Android re-saving the file rewrites it from a model with no
     * photos in it, so the pins are lost — the survey itself is not.
     */
    private fun photosToJson(sketch: Sketch): JsonArray = buildJsonArray {
        for (photo in sketch.photoDetails) {
            add(
                buildJsonObject {
                    put(COLOUR_TAG, photo.colour.name)
                    put(POSITION_TAG, toJson(photo.position))
                    put(PHOTO_ID_TAG, photo.photoId)
                    put(SIZE_TAG, photo.size)
                    put(ANGLE_TAG, photo.angle)
                    put(CAPTION_TAG, photo.caption)
                },
            )
        }
    }

    /**
     * One stroke, thinned on the way in — mirrors the original loader rather than an optimisation
     * this port added, since an old file's raw strokes would otherwise render with far more
     * points here than on Android and grow on every re-save.
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
