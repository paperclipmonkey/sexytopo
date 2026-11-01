package org.hwyl.sexytopo.shared.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.SurveyFileType
import org.hwyl.sexytopo.shared.io.store.Zip
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Where a survey's wall scans live: beside its other files, two files a scan.
 *
 * A survey called Swildons with one scan holds `Swildons.scan-1.json` and `Swildons.scan-1.points`
 * next to `Swildons.data.json`, named the way [SurveyFileType] names everything else and for the
 * reason `PhotoStore` gives: copying or zipping the folder takes the scans with it.
 *
 * The JSON holds everything a person might want to read or a program might want to check — the
 * phone's track, the station marks, every raw pen hit, the stamps, what review has changed and how
 * the scan was last placed. The rock itself is the other file, because there is a lot of it: a
 * minute's scan is tens of thousands of points and a long one is millions, and as text that is ten
 * times the size and a hundred times slower to read. It is three little-endian floats a point
 * behind a short header; see [PointsCodec].
 *
 * Nothing in the Android app reads either file, and nothing needs to: what a scan drew is ordinary
 * strokes and symbols in the sketch files, which open there as they are.
 */
object WallScanStore {

    private const val SCAN_PREFIX = "scan-"
    private const val JSON_EXTENSION = "json"
    private const val POINTS_EXTENSION = "points"

    /** The version of the JSON layout. Read leniently; written so a later reader can tell. */
    const val FORMAT_VERSION = 1

    fun jsonFileNameFor(surveyName: String, scanId: String): String =
        SurveyFileType.withExtension(surveyName, "$SCAN_PREFIX$scanId.$JSON_EXTENSION")

    fun pointsFileNameFor(surveyName: String, scanId: String): String =
        SurveyFileType.withExtension(surveyName, "$SCAN_PREFIX$scanId.$POINTS_EXTENSION")

    /**
     * One past the highest id in use: among [records], and among the ids [survey]'s drawing and
     * splays still carry. The second matters when the newest scan has been deleted but something
     * it made survived, as a stroke the Android app re-saved or a splay somebody kept: a new scan
     * under the same id would take those for its own and remove them on its next rebuild.
     */
    fun nextScanId(records: List<WallScanRecord>, survey: Survey? = null): String {
        val inUse = records.map { it.id }.toMutableList()
        if (survey != null) {
            for (projection in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
                val sketch = survey.getSketch(projection)
                sketch.pathDetails.mapNotNullTo(inUse) { it.scanId }
                sketch.symbolDetails.mapNotNullTo(inUse) { it.scanId }
                sketch.crossSectionDetails.mapNotNullTo(inUse) { it.scanId }
            }
            val prefix = WallScanApplier.splayComment("")
            survey.getAllLegs().filter { it.comment.startsWith(prefix) }.mapTo(inUse) { it.comment.removePrefix(prefix) }
        }
        val highest = inUse.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
        return (highest + 1).toString()
    }

    /** The ids of the scans saved beside survey [surveyName] in [directory], in number order. */
    fun scanIdsIn(store: FileStore, directory: List<String>, surveyName: String): List<String> {
        val prefix = "$surveyName.$SCAN_PREFIX"
        val suffix = ".$JSON_EXTENSION"
        return store.list(directory)
            .filter { it.startsWith(prefix) && it.endsWith(suffix) }
            .map { it.removePrefix(prefix).removeSuffix(suffix) }
            .filter { it.isNotEmpty() && !it.contains('.') }
            .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }

    fun save(store: FileStore, directory: List<String>, surveyName: String, record: WallScanRecord) {
        store.writeBytes(directory + pointsFileNameFor(surveyName, record.id), PointsCodec.encode(record.scan.points))
        store.writeText(directory + jsonFileNameFor(surveyName, record.id), write(record))
    }

    /**
     * Writes only the JSON half: what review changes. The points never change after capture, so a
     * slider moved in review need not rewrite megabytes of rock.
     */
    fun saveReview(store: FileStore, directory: List<String>, surveyName: String, record: WallScanRecord) {
        store.writeText(directory + jsonFileNameFor(surveyName, record.id), write(record))
    }

    /**
     * Every scan saved beside the survey. A scan whose JSON cannot be read is skipped and reported
     * to [onProblem] rather than stopping the survey opening; one whose points are missing opens
     * with none, so its marks and pen lines still draw.
     */
    fun loadAll(
        store: FileStore,
        directory: List<String>,
        surveyName: String,
        onProblem: (String) -> Unit = {},
    ): List<WallScanRecord> =
        scanIdsIn(store, directory, surveyName).mapNotNull { id ->
            val text = store.readText(directory + jsonFileNameFor(surveyName, id)) ?: return@mapNotNull null
            val points =
                store.readBytes(directory + pointsFileNameFor(surveyName, id))?.let {
                    runCatching { PointsCodec.decode(it) }.getOrNull()
                }
            runCatching { read(text, points.orEmpty()) }
                .onFailure { onProblem("Wall scan $id could not be read") }
                .getOrNull()
        }

    fun delete(store: FileStore, directory: List<String>, surveyName: String, scanId: String) {
        store.delete(directory + jsonFileNameFor(surveyName, scanId))
        store.delete(directory + pointsFileNameFor(surveyName, scanId))
    }

    /**
     * Copies every scan of survey [fromName] in [fromDirectory] to survey [toName] in
     * [toDirectory], file for file and renamed, without reading the scans: a scan whose points
     * will not read is copied as it is rather than saved again with none. The JSON goes as text
     * and the points as bytes, the way each was written.
     */
    fun copy(
        store: FileStore,
        fromDirectory: List<String>,
        fromName: String,
        toDirectory: List<String>,
        toName: String,
    ) {
        for (id in scanIdsIn(store, fromDirectory, fromName)) {
            store.readText(fromDirectory + jsonFileNameFor(fromName, id))?.let {
                store.writeText(toDirectory + jsonFileNameFor(toName, id), it)
            }
            store.readBytes(fromDirectory + pointsFileNameFor(fromName, id))?.let {
                store.writeBytes(toDirectory + pointsFileNameFor(toName, id), it)
            }
        }
    }

    /** The scans' files as zip entries, under the names they have in the survey directory. */
    fun zipEntries(store: FileStore, directory: List<String>, surveyName: String): List<Zip.Entry> =
        scanIdsIn(store, directory, surveyName).flatMap { id ->
            listOf(jsonFileNameFor(surveyName, id), pointsFileNameFor(surveyName, id)).mapNotNull { name ->
                store.readBytes(directory + name)?.let { Zip.Entry(name, it) }
            }
        }

    // ---------------------------------------------------------------------------------------
    // The JSON
    // ---------------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun write(record: WallScanRecord): String {
        val scan = record.scan
        val review = record.review
        val root = buildJsonObject {
            put("version", FORMAT_VERSION)
            put("id", record.id)
            scan.startStation?.let { put("start-station", it) }
            put("point-count", scan.points.size)
            put("path", points(scan.path))
            if (scan.poorTracking.isNotEmpty()) put("poor-tracking", ints(scan.poorTracking))
            putJsonArray("touches") {
                for (touch in scan.touches) {
                    add(buildJsonObject {
                        put("station", touch.stationName)
                        put("at", point(touch.inScan))
                    })
                }
            }
            putJsonArray("pen-lines") {
                for (line in scan.penLines) {
                    add(buildJsonObject {
                        put("colour", line.colour.name)
                        put("smoothing", line.stroke.smoothing)
                        put("live-length", line.stroke.liveLengthMetres)
                        put("raw", points(line.stroke.rawHits))
                        put("frozen", buildJsonArray { for (amount in line.stroke.frozenSmoothing) add(JsonPrimitive(amount)) })
                    })
                }
            }
            putJsonArray("stamps") {
                for (stamp in scan.stamps) {
                    add(buildJsonObject {
                        put("symbol", stamp.symbol.name)
                        put("at", point(stamp.inScan))
                        put("bearing", stamp.bearingInScan)
                    })
                }
            }
            putJsonObject("review") {
                review.placement?.let { put("placement", placement(it)) }
                put("removed-touches", ints(review.removedTouches))
                putJsonObject("renamed-touches") {
                    for ((index, name) in review.renamedTouches) put(index.toString(), name)
                }
                put("removed-lines", ints(review.removedLines))
                putJsonObject("line-smoothing") {
                    for ((index, amount) in review.lineSmoothing) put(index.toString(), amount)
                }
                put("removed-stamps", ints(review.removedStamps))
                review.generateWalls?.let { put("walls", it) }
                review.addLruds?.let { put("lruds", it) }
                put("splay-fans", review.splayFans)
                put("cross-sections", review.crossSections)
            }
            record.result?.let { result ->
                putJsonObject("result") {
                    result.mode?.let { put("mode", it.name) }
                    result.placement?.let { put("placement", placement(it)) }
                    put("message", result.message)
                    result.slideMetres?.let { put("slide", it) }
                    put("from-rock-by-default", result.fromRockByDefault)
                    result.legsThroughRock?.let { put("legs-through-rock", it) }
                    putJsonArray("residuals") {
                        for ((station, metres) in result.residuals) {
                            add(buildJsonObject {
                                put("station", station)
                                put("metres", metres)
                            })
                        }
                    }
                    putJsonArray("splays") {
                        for (splay in result.splays) {
                            add(buildJsonObject {
                                put("station", splay.station)
                                put("distance", splay.distance)
                                put("azimuth", splay.azimuth)
                                put("inclination", splay.inclination)
                            })
                        }
                    }
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * A record from its JSON and its points. Strict about what makes a scan a scan — the id and
     * its arrays — and lenient about review and result, which a hand-edited file may have lost:
     * missing review is no review, and missing result is a scan that has not been placed yet.
     */
    fun read(text: String, points: List<Coord3D>): WallScanRecord {
        val root = json.parseToJsonElement(text).jsonObject
        val id = root.string("id") ?: error("a scan with no id")
        val path = toPoints(root["path"])
        val touches =
            root.array("touches").map { entry ->
                val item = entry.jsonObject
                StationTouch(item.string("station") ?: error("a mark with no station"), toPoint(item["at"]))
            }
        val lines =
            root.array("pen-lines").map { entry ->
                val item = entry.jsonObject
                val stroke =
                    PenStroke.restore(
                        rawHits = toPoints(item["raw"]),
                        frozenSmoothing = item.array("frozen").map { it.jsonPrimitive.floatOrNull ?: PenStroke.DEFAULT_SMOOTHING },
                        smoothing = item.float("smoothing") ?: PenStroke.DEFAULT_SMOOTHING,
                        liveLengthMetres = item.float("live-length") ?: PenStroke.DEFAULT_LIVE_LENGTH_METRES,
                    )
                PenLine(stroke, item.string("colour")?.let { Colour.fromNameOrNull(it) } ?: Colour.BLACK)
            }
        val stamps =
            root.array("stamps").mapNotNull { entry ->
                val item = entry.jsonObject
                val symbol = item.string("symbol")?.let { name -> Symbol.entries.firstOrNull { it.name == name } }
                    ?: return@mapNotNull null
                SymbolStamp(symbol, toPoint(item["at"]), item.float("bearing") ?: 0f)
            }
        val scan =
            WallScan(
                points,
                path,
                touches,
                lines,
                stamps,
                root.string("start-station"),
                toInts(root["poor-tracking"]).filter { it in path.indices }.toSet(),
            )

        val review = ScanReview()
        runCatching { root["review"]?.jsonObject }.getOrNull()?.let { item ->
            review.placement = item["placement"]?.let { runCatching { toPlacement(it) }.getOrNull() }
            review.removedTouches += toInts(item["removed-touches"])
            review.renamedTouches += toIndexMap(item["renamed-touches"]) { it.jsonPrimitive.content }
            review.removedLines += toInts(item["removed-lines"])
            review.lineSmoothing += toIndexMap(item["line-smoothing"]) { it.jsonPrimitive.floatOrNull }
            review.removedStamps += toInts(item["removed-stamps"])
            review.generateWalls = item.boolean("walls")
            review.addLruds = item.boolean("lruds")
            review.splayFans = item.boolean("splay-fans") ?: false
            review.crossSections = item.boolean("cross-sections") ?: false
        }

        val result =
            runCatching { root["result"]?.jsonObject }.getOrNull()?.let { item ->
                ScanResult(
                    mode = item.string("mode")?.let { name -> PlacementMode.entries.firstOrNull { it.name == name } },
                    placement = item["placement"]?.let { runCatching { toPlacement(it) }.getOrNull() },
                    message = item.string("message") ?: "",
                    splays =
                        item.array("splays").mapNotNull { entry ->
                            runCatching {
                                val splay = entry.jsonObject
                                AddedSplay(
                                    splay.string("station")!!,
                                    splay.float("distance")!!,
                                    splay.float("azimuth")!!,
                                    splay.float("inclination")!!,
                                )
                            }.getOrNull()
                        },
                    residuals =
                        item.array("residuals").mapNotNull { entry ->
                            runCatching {
                                val residual = entry.jsonObject
                                residual.string("station")!! to residual.float("metres")!!
                            }.getOrNull()
                        },
                    slideMetres = item.float("slide"),
                    fromRockByDefault = item.boolean("from-rock-by-default") ?: false,
                    legsThroughRock = item.float("legs-through-rock"),
                )
            }
        return WallScanRecord(id, scan, review, result)
    }

    private fun point(point: Coord3D): JsonArray = buildJsonArray {
        add(JsonPrimitive(point.x))
        add(JsonPrimitive(point.y))
        add(JsonPrimitive(point.z))
    }

    private fun points(points: List<Coord3D>): JsonArray = buildJsonArray { for (p in points) add(point(p)) }

    private fun ints(values: Set<Int>): JsonArray = buildJsonArray { for (v in values.sorted()) add(JsonPrimitive(v)) }

    private fun placement(placement: ScanPlacement): JsonObject = buildJsonObject {
        put("heading", placement.headingDegrees)
        put("translation", point(placement.translation))
    }

    private fun toPoint(element: JsonElement?): Coord3D {
        val values = element!!.jsonArray.map { it.jsonPrimitive.floatOrNull ?: error("not a number") }
        require(values.size == 3) { "a point is three numbers" }
        return Coord3D(values[0], values[1], values[2])
    }

    private fun toPoints(element: JsonElement?): List<Coord3D> =
        runCatching { element?.jsonArray }.getOrNull()?.map { toPoint(it) } ?: emptyList()

    private fun toPlacement(element: JsonElement): ScanPlacement {
        val item = element.jsonObject
        return ScanPlacement(item.float("heading") ?: error("no heading"), toPoint(item["translation"]))
    }

    private fun toInts(element: JsonElement?): List<Int> =
        runCatching { element?.jsonArray }.getOrNull()?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()

    private fun <T : Any> toIndexMap(element: JsonElement?, value: (JsonElement) -> T?): Map<Int, T> =
        runCatching { element?.jsonObject }.getOrNull()
            ?.mapNotNull { (key, item) ->
                val index = key.toIntOrNull() ?: return@mapNotNull null
                runCatching { value(item) }.getOrNull()?.let { index to it }
            }
            ?.toMap()
            ?: emptyMap()

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.float(key: String): Float? =
        runCatching { this[key]?.jsonPrimitive?.floatOrNull }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()

    private fun JsonObject.array(key: String): JsonArray =
        runCatching { this[key]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
}

/**
 * A scan's rock as bytes: `STP1`, a version, a count, then three little-endian floats a point,
 * east, north and up in the scan's own frame.
 */
object PointsCodec {

    private const val MAGIC = 0x31505453 // "STP1", little-endian
    private const val VERSION = 1
    private const val HEADER_BYTES = 12
    private const val POINT_BYTES = 12

    fun encode(points: List<Coord3D>): ByteArray {
        val bytes = ByteArray(HEADER_BYTES + POINT_BYTES * points.size)
        var at = 0
        fun int(value: Int) {
            bytes[at] = value.toByte()
            bytes[at + 1] = (value shr 8).toByte()
            bytes[at + 2] = (value shr 16).toByte()
            bytes[at + 3] = (value shr 24).toByte()
            at += 4
        }
        int(MAGIC)
        int(VERSION)
        int(points.size)
        for (point in points) {
            int(point.x.toRawBits())
            int(point.y.toRawBits())
            int(point.z.toRawBits())
        }
        return bytes
    }

    fun decode(bytes: ByteArray): List<Coord3D> {
        require(bytes.size >= HEADER_BYTES) { "too short to be a scan's points" }
        var at = 0
        fun int(): Int {
            val value =
                (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return value
        }
        require(int() == MAGIC) { "not a scan's points" }
        require(int() <= VERSION) { "points written by a later version" }
        val count = int()
        require(count >= 0 && bytes.size == HEADER_BYTES + POINT_BYTES * count) { "points cut short" }
        return List(count) { Coord3D(Float.fromBits(int()), Float.fromBits(int()), Float.fromBits(int())) }
    }
}
