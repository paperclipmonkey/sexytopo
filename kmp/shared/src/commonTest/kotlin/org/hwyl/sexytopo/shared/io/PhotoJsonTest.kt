package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `photos` array of the native sketch format, and the price of having added it.
 *
 * The format is shared with the Android app, which knows nothing about photographs, so the promise
 * has to hold in both directions: a file written here still opens there, and a file written by
 * something that knows a key this port does not still opens here. A survey that opens on one of a
 * team's phones and not on another has effectively been lost, whichever end is the newer.
 *
 * The Android side of that is checked against what its reader actually does rather than against a
 * hope — see readAsAndroidWould, which makes the lookups `SketchJsonTranslater.toSketch` and its
 * `toSubSketch` make, at both levels, and touches nothing they do not.
 */
class PhotoJsonTest {

    private fun survey(): Survey =
        Survey("Swildons").also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 90f, 0f)) }

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 0.0001f, "expected $expected but was $actual")

    private fun roundTrip(sketch: Sketch, survey: Survey): Sketch =
        SketchJson.parse(SketchJson.write(sketch, survey.name), survey)

    private fun sketchWithOnePin(): Sketch =
        Sketch().apply {
            addPhotoDetail(
                position = Coord2D(12.5f, -3.25f),
                photoId = "7",
                size = 2.5f,
                angle = 137.5f,
                caption = "Boulder choke at the pitch head",
                colour = Colour.DEEP_PINK,
            )
        }

    @Test
    fun aPinSurvivesBeingSavedAndReadBack() {
        val survey = survey()

        val pin = roundTrip(sketchWithOnePin(), survey).photoDetails.single()

        assertEquals(Coord2D(12.5f, -3.25f), pin.position)
        assertEquals("7", pin.photoId)
        assertClose(2.5f, pin.size)
        assertClose(137.5f, pin.angle)
        assertEquals("Boulder choke at the pitch head", pin.caption)
        assertEquals(Colour.DEEP_PINK, pin.colour)
    }

    /**
     * A caption is free text a caver typed one-handed in the wet, and JSON escaping is exactly the
     * sort of thing that fails silently: the file still parses, the words are simply different, and
     * nobody notices until the survey is being drawn up months later.
     */
    @Test
    fun aCaptionKeepsItsQuotesBackslashesNewlinesAndAccents() {
        val survey = survey()
        val awkward = "Zoë said \"squeeze\"\nrigged off a thread \\ 12° down — Père Noël"
        val sketch = Sketch().apply {
            addPhotoDetail(Coord2D.ORIGIN, photoId = "1", size = 1f, angle = 0f, caption = awkward)
        }

        val written = SketchJson.write(sketch, survey.name)
        assertTrue(
            written.contains("\\n") && written.contains("\\\"") && written.contains("\\\\"),
            "the caption went into the file unescaped, so the file is no longer JSON: $written",
        )

        assertEquals(awkward, SketchJson.parse(written, survey).photoDetails.single().caption)
    }

    /**
     * Photographs are an addition, not a rearrangement: everything that could already be drawn has
     * to come back beside them, or a surveyor gains pictures and loses passage.
     */
    @Test
    fun aDrawingWithPhotographsInItKeepsAllTheRestOfItself() {
        val survey = survey()
        val station = survey.getStationByName("2")!!
        val sketch = Sketch().apply {
            pathDetails.add(PathDetail(listOf(Coord2D.ORIGIN, Coord2D(3f, 4f)), Colour.BLACK))
            addSymbolDetail(Coord2D(1f, 1f), "STALACTITE", size = 1.5f, angle = 45f, colour = Colour.BROWN)
            addTextDetail(Coord2D(2f, 2f), "Sump", size = 0.5f, colour = Colour.BLUE)
            addPhotoDetail(Coord2D(5f, 6f), photoId = "3", size = 1f, angle = 0f, colour = Colour.RED)
            crossSectionDetails.add(
                CrossSectionDetail(Coord2D(8f, 9f), CrossSection(station, 90f))
            )
            crossSectionScale = 3f
        }

        val restored = roundTrip(sketch, survey)

        assertEquals(2, restored.pathDetails.single().path.size, "the stroke lost its points")
        assertEquals("STALACTITE", restored.symbolDetails.single().symbolName)
        assertEquals("Sump", restored.textDetails.single().text)
        assertEquals("3", restored.photoDetails.single().photoId)
        assertEquals("2", restored.crossSectionDetails.single().station.name)
        assertClose(3f, restored.crossSectionScale)
    }

    /**
     * A pin dropped on a cross-section is written into its sub-sketch, and a section holding
     * nothing but a pin still counts as drawn — otherwise no `sketch` object is written at all and
     * the pin is gone by the next load.
     */
    @Test
    fun aPinOnACrossSectionHoldingNothingElseSurvivesTheRoundTrip() {
        val survey = survey()
        val station = survey.getStationByName("2")!!
        val subSketch = Sketch().apply {
            addPhotoDetail(Coord2D(0.5f, 0.5f), photoId = "4", size = 1f, angle = 0f, caption = "Rift")
        }
        val sketch = Sketch().apply {
            crossSectionDetails.add(
                CrossSectionDetail(Coord2D(8f, 9f), CrossSection(station, 0f), subSketch)
            )
        }

        val restored = roundTrip(sketch, survey)

        val pin = restored.crossSectionDetails.single().sketch.photoDetails.single()
        assertEquals("4", pin.photoId)
        assertEquals("Rift", pin.caption)
    }

    /** An empty array, not a missing key and not a null: the reader has nothing to trip over. */
    @Test
    fun aDrawingWithNoPhotographsWritesAnEmptyListRatherThanNothing() {
        val written = SketchJson.write(Sketch(), "Swildons")

        val photos = Json.parseToJsonElement(written).jsonObject[SketchJson.PHOTOS_TAG]
        assertNotNull(photos, "no photos key was written at all")
        assertTrue(photos.jsonArray.isEmpty())

        val read = SketchJson.read(written)
        assertTrue(read.sketch.photoDetails.isEmpty())
        assertEquals(0, read.dropped, "an empty photo list was reported as damage")
    }

    /**
     * The wire format of one pin, spelled out.
     *
     * Renaming any of these keys costs every photograph in every survey already written, and does
     * it quietly: the pins simply stop being read. Note `location` rather than `position` — the
     * name comes from the Android app's tag, which photographs share with symbols and labels.
     */
    @Test
    fun aPinIsWrittenWithTheKeysTheFormatUses() {
        val entry = Json.parseToJsonElement(SketchJson.write(sketchWithOnePin(), "Swildons"))
            .jsonObject["photos"]!!
            .jsonArray
            .single()
            .jsonObject

        assertEquals(
            setOf("colour", "location", "photo-id", "size", "angle", "caption"),
            entry.keys,
        )
        assertEquals("7", entry["photo-id"]!!.jsonPrimitive.content)
        assertEquals("DEEP_PINK", entry["colour"]!!.jsonPrimitive.content)
        assertClose(12.5f, entry["location"]!!.jsonObject["x"]!!.jsonPrimitive.float)
        assertClose(-3.25f, entry["location"]!!.jsonObject["y"]!!.jsonPrimitive.float)
    }

    /**
     * What the other end may send us: a pin with only the parts it thinks are essential.
     *
     * Every one of these defaults is a survey that opens rather than a survey with a hole in it,
     * so they are worth pinning separately from the round trip, which by construction never
     * exercises them.
     */
    @Test
    fun aPinWithOnlyTheEssentialsFillsInTheRest() {
        val text = buildJsonObject {
            put(
                SketchJson.PHOTOS_TAG,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                SketchJson.POSITION_TAG,
                                buildJsonObject {
                                    put("x", 1f)
                                    put("y", 2f)
                                },
                            )
                            put(SketchJson.PHOTO_ID_TAG, "4")
                        }
                    )
                },
            )
        }.toString()

        val read = SketchJson.read(text)

        assertEquals(0, read.dropped, "a pin with no caption is not a damaged pin")
        val pin = read.sketch.photoDetails.single()
        assertEquals("4", pin.photoId)
        assertEquals("", pin.caption)
        assertClose(1f, pin.size)
        assertClose(0f, pin.angle)
        assertEquals(Colour.BLACK, pin.colour)
    }

    /** With no id there is no picture to fetch, so this is damage and should be counted as such. */
    @Test
    fun aPinNamingNoPhotographIsDamage() {
        val damaged =
            SketchJson.write(sketchWithOnePin(), "Swildons")
                .replace("\"${SketchJson.PHOTO_ID_TAG}\"", "\"snapshot-id\"")

        val read = SketchJson.read(damaged)

        assertEquals(1, read.dropped, "the broken pin was not counted")
        assertTrue(read.sketch.photoDetails.isEmpty())
    }

    /**
     * Forward compatibility inbound: a file carrying something this port has never heard of is
     * still a file it can open, and opening it is not reported as damage.
     */
    @Test
    fun aKeyThisPortDoesNotKnowDoesNotStopADrawingLoading() {
        val survey = survey()
        val sketch = sketchWithOnePin().apply {
            pathDetails.add(PathDetail(listOf(Coord2D.ORIGIN, Coord2D(1f, 1f)), Colour.BLACK))
        }
        val written = Json.parseToJsonElement(SketchJson.write(sketch, survey.name)).jsonObject

        val fromTheFuture = JsonObject(
            written +
                mapOf(
                    "sexyTopoVersionName" to JsonPrimitive("99.0"),
                    "sound-recordings" to buildJsonArray {
                        add(buildJsonObject { put("recording-id", "1") })
                    },
                )
        ).toString()

        val read = SketchJson.read(fromTheFuture, survey)

        assertEquals(0, read.dropped, "an unknown key was mistaken for a damaged drawing")
        assertEquals("7", read.sketch.photoDetails.single().photoId)
        assertEquals(1, read.sketch.pathDetails.size)
    }

    /**
     * Forward compatibility outbound, checked against the Android reader rather than assumed.
     *
     * `SketchJsonTranslater.toSketch` asks for the keys it names and never enumerates the object,
     * so an extra array simply goes unread. What it does need is for everything it does name to be
     * there and to be the shape its getters demand — see readAsAndroidWould, which is those calls
     * and only those calls.
     */
    @Test
    fun aFileWithPhotographsInItStillHasEverythingTheAndroidReaderAsksFor() {
        val survey = survey()
        val station = survey.getStationByName("2")!!
        val subSketch = Sketch().apply {
            pathDetails.add(PathDetail(listOf(Coord2D.ORIGIN, Coord2D(0f, 1f)), Colour.BLACK))
            addSymbolDetail(
                Coord2D(0.2f, 0.2f), "STALAGMITE", size = 1f, angle = 0f, colour = Colour.BROWN
            )
            addTextDetail(Coord2D(0.3f, 0.3f), "Crawl", size = 0.5f, colour = Colour.BLUE)
            addPhotoDetail(Coord2D(0.5f, 0.5f), photoId = "9", size = 1f, angle = 0f)
        }
        val sketch = Sketch().apply {
            pathDetails.add(PathDetail(listOf(Coord2D.ORIGIN, Coord2D(3f, 4f)), Colour.BLACK))
            addSymbolDetail(Coord2D(1f, 1f), "STALACTITE", size = 1.5f, angle = 45f, colour = Colour.BROWN)
            addTextDetail(Coord2D(2f, 2f), "Sump", size = 0.5f, colour = Colour.BLUE)
            addPhotoDetail(Coord2D(5f, 6f), photoId = "3", size = 1f, angle = 0f, caption = "Choke")
            crossSectionDetails.add(
                CrossSectionDetail(Coord2D(8f, 9f), CrossSection(station, 90f), subSketch)
            )
            crossSectionScale = 2f
        }

        val reading = readAsAndroidWould(SketchJson.write(sketch, survey.name))

        assertEquals(listOf(2), reading.pointsPerStroke)
        assertEquals(listOf("STALACTITE"), reading.symbolIds)
        assertEquals(listOf("Sump"), reading.labels)
        assertEquals(listOf("2"), reading.stationIds)
        assertEquals(listOf(2), reading.pointsPerSubSketchStroke, "the section's own stroke")
        assertEquals(listOf("STALAGMITE"), reading.subSketchSymbolIds, "the section's own stamp")
        assertEquals(listOf("Crawl"), reading.subSketchLabels, "the section's own label")
        assertClose(2f, reading.crossSectionScale)
    }

    /**
     * And the other half of that: photographs are confined to their own array, so the one thing
     * Android meets that it does not understand is a key it never looks at.
     *
     * A photograph's parts leaking into `symbols` or `labels` would be read there, and the two
     * cost differently. `toSymbolDetail` throws on `Symbol.valueOf` of a name its enum has not
     * got, but `toSketch` catches that around each entry, so a leaked pin costs only itself.
     * `toTextDetail` throws on the `text` a pin does not carry and nothing catches it until the
     * whole block is abandoned before `setTextDetails` — so one leaked pin costs a surveyor every
     * label in the drawing.
     */
    @Test
    fun photographsAddExactlyOneKeyTheAndroidAppDoesNotKnow() {
        val survey = survey()
        val subSketch = Sketch().apply {
            addPhotoDetail(Coord2D(0.5f, 0.5f), photoId = "9", size = 1f, angle = 0f)
        }
        val sketch = sketchWithOnePin().apply {
            crossSectionDetails.add(
                CrossSectionDetail(
                    Coord2D(8f, 9f),
                    CrossSection(survey.getStationByName("2")!!, 0f),
                    subSketch,
                )
            )
        }

        val root = Json.parseToJsonElement(SketchJson.write(sketch, survey.name)).jsonObject

        assertEquals(setOf(SketchJson.PHOTOS_TAG), root.keys - androidTopLevelKeys)

        val nested = root[SketchJson.CROSS_SECTIONS_TAG]!!
            .jsonArray
            .single()
            .jsonObject[SketchJson.SKETCH_TAG]!!
            .jsonObject
        assertEquals(
            setOf(SketchJson.PHOTOS_TAG),
            nested.keys - androidSubSketchKeys,
            "a cross-section's sub-sketch grew a key beyond the one Android skips",
        )
    }

    /** Every top-level key `SketchJsonTranslater` writes or reads. */
    private val androidTopLevelKeys = setOf(
        "sexyTopoVersionName",
        "sexyTopoVersionCode",
        SurveyJson.SURVEY_NAME_TAG,
        SketchJson.PATHS_TAG,
        SketchJson.LABELS_TAG,
        SketchJson.SYMBOLS_TAG,
        SketchJson.CROSS_SECTIONS_TAG,
        SketchJson.SETTINGS_TAG,
    )

    /** And the three its `toSubSketchJson` writes inside a cross-section. */
    private val androidSubSketchKeys =
        setOf(SketchJson.PATHS_TAG, SketchJson.LABELS_TAG, SketchJson.SYMBOLS_TAG)

    /** What the Android reader would take away from the file, and nothing more. */
    private class AndroidReading(
        val pointsPerStroke: List<Int>,
        val symbolIds: List<String>,
        val labels: List<String>,
        val stationIds: List<String>,
        val pointsPerSubSketchStroke: List<Int>,
        val subSketchSymbolIds: List<String>,
        val subSketchLabels: List<String>,
        val crossSectionScale: Float,
    )

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.number(key: String): Float = this[key]!!.jsonPrimitive.float

    private fun JsonObject.array(key: String) = this[key]!!.jsonArray

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    private fun JsonObject.coord(key: String): Coord2D =
        obj(key).let { Coord2D(it.number("x"), it.number("y")) }

    private fun JsonObject.colourName(): String =
        text(SketchJson.COLOUR_TAG).also {
            // Android's Colour.valueOf throws on a name its enum has not got. In `symbols` that
            // throw is caught per entry and costs one stamp; in `paths` and `labels` nothing
            // catches it until the whole array has been abandoned, so a single unknown colour
            // takes every stroke or every label in the drawing with it.
            assertNotNull(Colour.fromNameOrNull(it), "no such colour as $it")
        }

    /**
     * The file read the way `SketchJsonTranslater.toSketch` and its `toSubSketch` read it: every
     * key they name, in the types their getJSONArray, getJSONObject, getString and getDouble calls
     * demand, and nothing else touched at all.
     *
     * The bang-bangs are the point rather than laziness. Each of those Java getters throws where
     * the file disagrees with it, and the reader answers a throw by logging and carrying on with an
     * empty list — so a shape it does not expect is not a loud failure on Android, it is a survey
     * that opens with the walls missing. Better it blows up here.
     */
    private fun readAsAndroidWould(text: String): AndroidReading {
        val root = Json.parseToJsonElement(text).jsonObject

        fun strokes(from: JsonObject): List<Int> =
            from.array(SketchJson.PATHS_TAG).map { element ->
                val entry = element.jsonObject
                entry.colourName()
                entry.array(SketchJson.POINTS_TAG).map { it.jsonObject.coord2D() }.size
            }

        fun symbolIdsIn(from: JsonObject): List<String> =
            from.array(SketchJson.SYMBOLS_TAG).map { element ->
                val entry = element.jsonObject
                entry.colourName()
                entry.coord(SketchJson.POSITION_TAG)
                entry.text(SketchJson.SYMBOL_ID_TAG)
            }

        fun labelsIn(from: JsonObject): List<String> =
            from.array(SketchJson.LABELS_TAG).map { element ->
                val entry = element.jsonObject
                entry.colourName()
                entry.coord(SketchJson.POSITION_TAG)
                entry.text(SketchJson.TEXT_TAG)
            }

        val subSketchStrokes = mutableListOf<Int>()
        val subSketchSymbolIds = mutableListOf<String>()
        val subSketchLabels = mutableListOf<String>()
        val stationIds = root.array(SketchJson.CROSS_SECTIONS_TAG).map { element ->
            val entry = element.jsonObject
            entry.coord(SketchJson.POSITION_TAG)
            entry.number(SketchJson.ANGLE_TAG)
            // A sub-sketch is read by toSubSketch, which takes the same three arrays as toSketch —
            // the photos go in beside them, so all three have to be checked here and not just the
            // strokes. Its has() guards are deliberately not mirrored: toSubSketchJson always
            // writes all three, so one going missing is a regression even though Android would
            // step over it.
            entry[SketchJson.SKETCH_TAG]?.jsonObject?.let { sub ->
                subSketchStrokes += strokes(sub)
                subSketchSymbolIds += symbolIdsIn(sub)
                subSketchLabels += labelsIn(sub)
            }
            entry.text(SketchJson.STATION_ID_TAG)
        }

        return AndroidReading(
            pointsPerStroke = strokes(root),
            symbolIds = symbolIdsIn(root),
            labels = labelsIn(root),
            stationIds = stationIds,
            pointsPerSubSketchStroke = subSketchStrokes,
            subSketchSymbolIds = subSketchSymbolIds,
            subSketchLabels = subSketchLabels,
            crossSectionScale =
                root.obj(SketchJson.SETTINGS_TAG).number(SketchJson.CROSS_SECTION_SCALE_TAG),
        )
    }

    private fun JsonObject.coord2D(): Coord2D = Coord2D(number("x"), number("y"))
}
