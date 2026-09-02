package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importing is the other half of exporting, and the half that decides whether a survey can be
 * recovered after a phone dies or continued from somebody else's.
 */
class SurveyImportTest {

    private fun store() = InMemoryFileStore()

    private fun aSurvey(name: String): Survey =
        Survey(name).also {
            SurveyBuilder.updateWithNewStation(it, Leg(5.42f, 12.5f, -3f))
            it.origin.comment = "entrance"
        }

    /**
     * A survey is four files, and the drawing is three of a surveyor's four hours.
     *
     * This importer takes one file at a time, so a survey handed over as `Name.data.json` and its
     * siblings parsed the centreline and dropped both sketches without a word — which is the worst
     * shape a bug can have: it succeeds, it says so, and what is missing is the part nobody can
     * reconstruct from the numbers.
     */
    @Test
    fun aSurveySentWithItsDrawingsKeepsThem() {
        val store = store()
        val survey = aSurvey("Eastwater")
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK),
        )
        survey.elevationSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(2f, 2f), Coord2D(3f, 3f)), Colour.RED),
        )
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(survey))
        store.writeText(
            listOf("Eastwater.plan.json"),
            SketchJson.write(survey.planSketch, "Eastwater"),
        )
        store.writeText(
            listOf("Eastwater.ext-elevation.json"),
            SketchJson.write(survey.elevationSketch, "Eastwater"),
        )

        val imported = assertNotNull(
            SurveyImport.import(SurveyLibrary(store), store, "Eastwater.data.json"),
        )
        assertEquals(1, imported.planSketch.pathDetails.size, "the plan drawing was dropped")
        assertEquals(
            1,
            imported.elevationSketch.pathDetails.size,
            "the elevation drawing was dropped",
        )
    }

    /**
     * A survey from the Android app comes in at the station somebody was standing at.
     *
     * The Android app keeps the active station in `<name>.metadata.json` and nowhere else — the
     * `activeStation` key inside the data file is this port's own. The fixture is deliberately what
     * Android writes rather than what this port writes: a data file with no `activeStation` in it
     * at all, so the only copy of the answer is the metadata file, and the test fails if the
     * reading is dropped rather than being carried by the port's own key.
     */
    @Test
    fun aSurveyFromTheAndroidAppOpensAtTheStationItWasLeftAt() {
        val store = store()
        val survey = aSurvey("Eastwater")
        SurveyBuilder.updateWithNewStation(survey, Leg(3f, 90f, 0f))
        val android =
            SurveyJson.write(survey)
                .lines()
                .filterNot { it.contains("activeStation") }
                .joinToString("\n")
                .replace(Regex(",(\\s*})\\s*$"), "$1")
        store.writeText(listOf("Eastwater.data.json"), android)
        store.writeText(
            listOf("Eastwater.metadata.json"),
            """{ "name": "Eastwater", "active-station": "2", "connections": {} }""",
        )

        val imported = assertNotNull(
            SurveyImport.import(SurveyLibrary(store), store, "Eastwater.data.json"),
        )

        assertEquals("2", imported.activeStation.name, "the working end did not come with it")
    }

    @Test
    fun aSurveyWithNoMetadataFileStillImports() {
        val store = store()
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(aSurvey("Eastwater")))

        val imported = assertNotNull(
            SurveyImport.import(SurveyLibrary(store), store, "Eastwater.data.json"),
        )

        assertEquals("Eastwater", imported.name)
    }

    /**
     * A drawing that is *there* and will not parse is reported, not swallowed.
     *
     * The sketches beside the data file were read inside a `runCatching` that discarded the
     * failure, so a survey whose plan file was damaged imported with an empty plan and said
     * nothing — a caver would conclude the sender had not drawn anything. Absent is different from
     * unreadable, and only the second is worth a word.
     */
    @Test
    fun aDrawingThatWillNotParseIsReported() {
        val store = store()
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(aSurvey("Eastwater")))
        store.writeText(listOf("Eastwater.plan.json"), "this is not a sketch")

        val library = SurveyLibrary(store)
        val imported = assertNotNull(SurveyImport.import(library, store, "Eastwater.data.json"))
        assertTrue(imported.origin.onwardLegs.isNotEmpty(), "the centreline still came in")
        val warning = assertNotNull(library.lastWarning, "nothing was said about the broken plan")
        assertTrue("Eastwater.plan.json" in warning, warning)
    }

    @Test
    fun aSurveyWithNoDrawingIsNotAProblem() {
        val store = store()
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(aSurvey("Eastwater")))

        val library = SurveyLibrary(store)
        assertNotNull(SurveyImport.import(library, store, "Eastwater.data.json"))
        assertNull(library.lastWarning, "a survey sent without drawings is ordinary")
    }

    /**
     * The likelier damage: a drawing that arrives *short* rather than empty.
     *
     * Each detail is parsed inside its own guard, so one broken stroke costs one stroke and the
     * rest of the plan comes through. But being more forgiving only helps if it is not also
     * quieter, and it was: a drawing three strokes short looked exactly like a drawing that was
     * drawn three strokes short.
     */
    @Test
    fun aDrawingThatCameInShortSaysSo() {
        val store = store()
        val survey = aSurvey("Eastwater")
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK),
        )
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(survey))
        // One good stroke and one whose points are not points.
        val good = SketchJson.write(survey.planSketch, "Eastwater")
        val damaged = good.replace("\"paths\": [", "\"paths\": [ { \"colour\": \"BLACK\" },")
        store.writeText(listOf("Eastwater.plan.json"), damaged)

        val library = SurveyLibrary(store)
        val imported = assertNotNull(
            SurveyImport.import(library, store, "Eastwater.data.json"),
        )
        assertEquals(1, imported.planSketch.pathDetails.size, "the good stroke still came through")
        val warning = assertNotNull(library.lastWarning, "nothing was said about the lost stroke")
        assertTrue("short" in warning, warning)
    }

    /**
     * And the same on *open*, where the consequence is worse than on import: the app saves on
     * every change, so a survey opened three strokes short is written back without them the moment
     * anything is edited, making the damage permanent.
     */
    @Test
    fun aSurveyThatOpensShortSaysSoBeforeTheNextSaveMakesItPermanent() {
        val store = store()
        val survey = aSurvey("Swildons")
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK),
        )
        SurveyStorage.save(store, survey, SURVEYS_ROOT + "Swildons")
        val path = SURVEYS_ROOT + "Swildons" + "Swildons.plan.json"
        store.writeText(
            path,
            store.readText(path)!!.replace("\"paths\": [", "\"paths\": [ { \"colour\": \"BLACK\" },"),
        )

        val library = SurveyLibrary(store)
        val opened = assertNotNull(library.open("Swildons"))
        assertEquals(1, opened.planSketch.pathDetails.size, "the good stroke still came through")
        val warning = assertNotNull(library.lastWarning, "the survey opened short and said nothing")
        assertTrue("could not be read" in warning, warning)
    }

    @Test
    fun aDrawingThatReadsIsNotReported() {
        val store = store()
        val survey = aSurvey("Eastwater")
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK),
        )
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(survey))
        store.writeText(
            listOf("Eastwater.plan.json"),
            SketchJson.write(survey.planSketch, "Eastwater"),
        )

        val library = SurveyLibrary(store)
        val imported = assertNotNull(SurveyImport.import(library, store, "Eastwater.data.json"))
        assertEquals(1, imported.planSketch.pathDetails.size)
        assertNull(library.lastWarning)
    }

    /**
     * The warning belongs to the survey on screen, not to the session: set once and never cleared,
     * it would sit in the app bar long after the surveyor had opened a different cave — a true
     * sentence about the wrong survey, which is worse than no sentence.
     */
    @Test
    fun theWarningGoesWhenAnotherSurveyIsOpened() {
        val store = store()
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(aSurvey("Eastwater")))
        store.writeText(listOf("Eastwater.plan.json"), "this is not a sketch")

        val library = SurveyLibrary(store)
        assertNotNull(SurveyImport.import(library, store, "Eastwater.data.json"))
        assertNotNull(library.lastWarning)

        store.writeText(listOf("Swildons.data.json"), SurveyJson.write(aSurvey("Swildons")))
        assertNotNull(library.import("Swildons.data.json"))
        assertNull(library.lastWarning, "the warning outlived the survey it was about")
    }

    @Test
    fun aSurveySentOnItsOwnStillImports() {
        val store = store()
        store.writeText(listOf("Eastwater.data.json"), SurveyJson.write(aSurvey("Eastwater")))

        val imported = assertNotNull(
            SurveyImport.import(SurveyLibrary(store), store, "Eastwater.data.json"),
        )
        assertEquals(0, imported.planSketch.pathDetails.size)
        assertTrue(imported.origin.onwardLegs.isNotEmpty(), "the centreline came in")
    }

    /**
     * Every part of a survey ends `.json`, so a rule of "any `.json` is a survey" would offer the
     * drawing as something to import beside the survey — picking it would parse a sketch as a
     * centreline.
     */
    @Test
    fun thePartsOfASurveyAreNotOfferedAsSurveys() {
        val store = store()
        store.writeText(listOf("Eastwater.data.json"), "{}")
        store.writeText(listOf("Eastwater.plan.json"), "{}")
        store.writeText(listOf("Eastwater.ext-elevation.json"), "{}")
        store.writeText(listOf("Eastwater.metadata.json"), "{}")

        assertEquals(listOf("Eastwater.data.json"), SurveyImport.candidates(store))
    }

    /**
     * A survey does not usually arrive as a loose file. It arrives as a zip.
     *
     * Unzipping one in the Files app leaves a *folder* named after the cave with the survey's four
     * files inside, and the import list only looked at files — so the app showed an empty list
     * beside a survey sitting right there.
     */
    @Test
    fun aWholeSurveyFolderCanBeImported() {
        val store = store()
        val survey = aSurvey("Swildons")
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK),
        )
        SurveyStorage.save(store, survey, listOf("Swildons"))

        assertEquals(listOf("Swildons"), SurveyImport.candidates(store))

        val imported = assertNotNull(SurveyImport.import(SurveyLibrary(store), store, "Swildons"))
        assertTrue(imported.origin.onwardLegs.isNotEmpty(), "the centreline came in")
        assertEquals(1, imported.planSketch.pathDetails.size, "the drawing came in")
    }

    @Test
    fun aFolderThatIsNotASurveyIsNotOffered() {
        val store = store()
        store.writeText(listOf("Photos", "cave.jpg.txt"), "not a survey")

        assertEquals(emptyList(), SurveyImport.candidates(store))
    }

    @Test
    fun onlyFilesAtTheRootAreOffered() {
        val store = store()
        store.writeText(listOf("Swildons.data.json"), "{}")
        store.writeText(listOf("notes.txt"), "not a survey")
        store.writeText(listOf("surveys", "Eastwater", "Eastwater.data.json"), "{}")

        assertEquals(listOf("Swildons.data.json"), SurveyImport.candidates(store))
    }

    @Test
    fun surveysFromOtherSoftwareAreOfferedToo() {
        val store = store()
        store.writeText(listOf("Eastwater.svx"), ";")
        store.writeText(listOf("Eastwater.th"), "#")
        store.writeText(listOf("Eastwater.txt"), POCKET_TOPO)
        // .th2 is the drawing, not the centreline. This app writes it but cannot read it back, and
        // offering something that can only fail is worse than not offering it.
        store.writeText(listOf("Eastwater.th2"), "encoding utf-8")

        assertEquals(
            listOf("Eastwater.svx", "Eastwater.th", "Eastwater.txt"),
            SurveyImport.candidates(store).sorted(),
        )
    }

    /**
     * `.txt` belongs to everything, unlike every other extension here: offering every text file as
     * a survey would bury the one that is.
     */
    @Test
    fun aTextFileThatIsNotAPocketTopoExportIsNotOffered() {
        val store = store()
        store.writeText(listOf("shopping list.txt"), "milk\nrope\ncarbide")
        store.writeText(listOf("Eastwater.txt"), POCKET_TOPO)

        assertEquals(listOf("Eastwater.txt"), SurveyImport.candidates(store))
    }

    /**
     * PocketTopo's own binary file, which is what is actually on the phone somebody hands you —
     * its text export is something they have to know to produce. The format itself is tested in
     * the shared module against a real `.top`; what is checked here is the plumbing: the bytes have
     * to reach the parser as bytes.
     */
    @Test
    fun aPocketTopoBinaryFileImportsThroughTheSameFlow() {
        val store = store()
        store.writeBytes(listOf("Ceiled Up.top"), MINIMAL_TOP_FILE)
        val library = SurveyLibrary(store)

        assertEquals(listOf("Ceiled Up.top"), SurveyImport.candidates(store))

        val imported = assertNotNull(SurveyImport.import(library, store, "Ceiled Up.top"))

        assertEquals("Ceiled Up", imported.name)
        assertEquals(2, imported.getAllStations().size)
        assertTrue(library.list().contains("Ceiled Up"))
    }

    /**
     * Bytes are not text. A `.top` put through a text-only store comes back with every byte that is
     * not valid UTF-8 replaced, which for a binary format moves a length prefix and ruins
     * everything after it — silently.
     */
    @Test
    fun aBinaryFileSurvivesTheStoreIntact() {
        val store = store()
        store.writeBytes(listOf("Ceiled Up.top"), MINIMAL_TOP_FILE)

        val read = assertNotNull(store.readBytes(listOf("Ceiled Up.top")))

        assertTrue(MINIMAL_TOP_FILE.contentEquals(read))
        assertFalse(
            MINIMAL_TOP_FILE.contentEquals(
                assertNotNull(store.readText(listOf("Ceiled Up.top")) ?: "").encodeToByteArray(),
            ),
        )
    }

    @Test
    fun aPocketTopoExportBringsItsDrawingToo() {
        val store = store()
        store.writeText(listOf("Eastwater.txt"), POCKET_TOPO)
        val library = SurveyLibrary(store)

        val imported = assertNotNull(SurveyImport.import(library, store, "Eastwater.txt"))

        assertEquals("Eastwater", imported.name)
        assertEquals(1, imported.origin.onwardLegs.size)
        assertEquals(1, imported.planSketch.pathDetails.size)
        assertTrue(library.list().contains("Eastwater"))
    }

    /**
     * The whole point of reading Survex: a club's existing survey of the cave, exported by
     * whatever they used, opened here to be extended. Round-tripping our own export is the closest
     * this can get without a third-party file to hand.
     */
    @Test
    fun aSurvexFileFromAnotherToolBecomesASurvey() {
        val store = store()
        store.writeText(listOf("Eastwater.svx"), SurvexExporter.export(aSurvey("Eastwater")))

        val imported = assertNotNull(SurveyImport.import(SurveyLibrary(store), store, "Eastwater.svx"))

        assertEquals("Eastwater", imported.name)
        assertEquals(1, imported.getAllLegsInChronoOrder().size)
        val leg = imported.getAllLegsInChronoOrder().first()
        assertEquals(5.42f, leg.distance, 0.005f)
        assertEquals(12.5f, leg.azimuth, 0.05f)
        assertEquals(-3f, leg.inclination, 0.05f)
        assertEquals("entrance", imported.origin.comment)
    }

    @Test
    fun aTherionFileFromAnotherToolBecomesASurvey() {
        val store = store()
        store.writeText(listOf("Eastwater.th"), TherionExporter.export(aSurvey("Eastwater")))

        val imported = assertNotNull(SurveyImport.import(SurveyLibrary(store), store, "Eastwater.th"))

        assertEquals("Eastwater", imported.name)
        assertEquals(1, imported.getAllLegsInChronoOrder().size)
        assertEquals("entrance", imported.origin.comment)
    }

    @Test
    fun aFileWithNoCentrelineIsRefusedRatherThanImportedEmpty() {
        val store = store()
        store.writeText(listOf("Drawing.th"), "encoding utf-8\nscrap s1 -projection plan\nendscrap")

        assertNull(SurveyImport.import(SurveyLibrary(store), store, "Drawing.th"))
    }

    @Test
    fun anImportedSurveyKeepsItsLegs() {
        val store = store()
        store.writeText(listOf("Swildons.data.json"), SurveyJson.write(aSurvey("Swildons")))
        val library = SurveyLibrary(store)

        val imported = SurveyImport.import(library, store, "Swildons.data.json")

        assertNotNull(imported)
        assertEquals("Swildons", imported.name)
        assertEquals(1, imported.getAllLegsInChronoOrder().size)
        assertEquals("entrance", imported.origin.comment)
        assertTrue(library.list().contains("Swildons"))
    }

    /**
     * The case importing exists for: a colleague sends you their copy of a cave you are also
     * surveying, and overwriting yours with theirs would be the worst possible outcome.
     */
    @Test
    fun anImportNeverOverwritesASurveyAlreadyInTheLibrary() {
        val store = store()
        val library = SurveyLibrary(store)
        library.save(aSurvey("Swildons"))
        store.writeText(listOf("Swildons.data.json"), SurveyJson.write(aSurvey("Swildons")))

        val imported = SurveyImport.import(library, store, "Swildons.data.json")

        assertEquals("Swildons 2", assertNotNull(imported).name)
        assertEquals(listOf("Swildons", "Swildons 2"), library.list().sorted())
    }

    @Test
    fun somethingThatIsNotASurveyIsRefusedRatherThanThrown() {
        val store = store()
        store.writeText(listOf("shopping.json"), "[1, 2, 3]")

        assertNull(SurveyImport.import(SurveyLibrary(store), store, "shopping.json"))
    }

    @Test
    fun aMissingFileIsRefusedRatherThanThrown() {
        val store = store()
        assertNull(SurveyImport.import(SurveyLibrary(store), store, "gone.data.json"))
    }

    @Test
    fun theAppsOwnExtensionsAreStrippedFromTheName() {
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.data.json"))
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.data.autosave.json"))
        assertEquals("Eastwater Cavern", SurveyImport.nameFor("Eastwater Cavern.json"))
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.svx"))
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.th"))
        // A file off a Windows machine, or off a case-insensitive filesystem.
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.SVX"))
    }

    private val POCKET_TOPO =
        listOf(
            "TRIP",
            "DATE 2026-08-30 ",
            "DATA",
            "1.0\t\t90.00\t0.00\t10.000\t>",
            "",
            "PLAN",
            "STATIONS",
            "0.000\t0.000\t1.0",
            "POLYLINE RED",
            "1.000\t1.000",
            "2.000\t2.000",
        ).joinToString("\n")

    /**
     * The smallest valid `.top`: a header, no trips, one leg from 0.0 to 0.1, no references, and
     * two empty drawings. Little-endian throughout, because the format is a .NET `BinaryWriter`
     * dump.
     */
    private val MINIMAL_TOP_FILE: ByteArray =
        buildList {
            fun int16(value: Int) {
                add((value and 0xFF).toByte())
                add(((value shr 8) and 0xFF).toByte())
            }
            fun int32(value: Int) {
                for (shift in 0 until 4) add(((value shr (shift * 8)) and 0xFF).toByte())
            }
            fun mapping() {
                int32(0)
                int32(0)
                int32(1000)
            }

            add('T'.code.toByte())
            add('o'.code.toByte())
            add('p'.code.toByte())
            add(3)
            int32(0) // no trips
            int32(1) // one shot
            int32(0x00000000) // from 0.0
            int32(0x00000001) // to 0.1
            int32(3500) // 3.5 m
            int16(0x4000) // due east
            int16(0) // level
            add(0) // no flags
            add(0) // roll
            int16(-1) // no trip
            int32(0) // no references
            mapping() // the overview's
            mapping() // the plan's
            add(0) // and it is empty
            mapping() // the elevation's
            add(0) // and so is that
        }.toByteArray()

    private fun tracedCave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        survey.planSketch.pathDetails =
            mutableListOf(
                PathDetail(listOf(Coord2D(1f, -1f), Coord2D(4f, -3f)), Colour.BLACK),
                PathDetail(listOf(Coord2D(2f, -5f), Coord2D(6f, -7f)), Colour.BLUE),
            )
        survey.elevationSketch.pathDetails =
            mutableListOf(PathDetail(listOf(Coord2D(0f, 0f), Coord2D(3f, -2f)), Colour.BROWN))
        return survey
    }

    private fun tracingFor(survey: Survey, projection: Projection2D): String =
        XviExporter.export(
            sketch = survey.getSketch(projection),
            space = projection.project(survey),
            scale = 50f,
            gridFrame = Frame(-100f, 100f, -100f, 100f),
        )

    /**
     * A Therion project arrives with its drawings, not as a bare centreline.
     *
     * The Android app reads the `.th` for the numbers and then any `.xvi` beside it for the plan
     * and the extended elevation; this port read the `.th` and stopped, so importing somebody's
     * Therion project silently threw away the part that took the whole trip.
     */
    @Test
    fun aTherionProjectBringsItsDrawingsIn() {
        val store = store()
        val original = tracedCave()
        store.writeText(listOf("Swildons.th"), TherionExporter.export(original))
        store.writeText(listOf("Swildons.plan.xvi"), tracingFor(original, Projection2D.PLAN))
        store.writeText(
            listOf("Swildons.ee.xvi"),
            tracingFor(original, Projection2D.EXTENDED_ELEVATION),
        )
        val library = SurveyLibrary(store)

        val imported = assertNotNull(SurveyImport.import(library, store, "Swildons.th"))

        assertTrue(imported.getAllLegsInChronoOrder().isNotEmpty(), "the centreline came in")
        assertEquals(2, imported.planSketch.pathDetails.size, "and both strokes of the plan")
        assertEquals(
            1,
            imported.elevationSketch.pathDetails.size,
            "and the elevation, told apart by its name ending in ee",
        )
        assertNull(library.lastWarning, "nothing was unreadable, so nothing should be reported")
    }

    @Test
    fun aTherionFileWithNoTracingsBesideItStillImports() {
        val store = store()
        store.writeText(listOf("Swildons.th"), TherionExporter.export(tracedCave()))
        val library = SurveyLibrary(store)

        val imported = assertNotNull(SurveyImport.import(library, store, "Swildons.th"))

        assertTrue(imported.getAllLegsInChronoOrder().isNotEmpty())
        assertTrue(imported.planSketch.pathDetails.isEmpty())
        assertNull(library.lastWarning, "an absent drawing is ordinary and not worth a warning")
    }

    /**
     * A loose tracing image imports as a drawing with nothing under it.
     *
     * `import` refuses a survey with no legs, because a Therion file that is all `scrap` parses
     * into an empty survey and importing it looks like success. An `.xvi` has no centreline *by
     * definition*, so that guard would have thrown away every tracing image ever picked — it is
     * now let through when it brought a drawing.
     */
    @Test
    fun aLooseTracingImageImportsAsADrawing() {
        val store = store()
        store.writeText(listOf("Traced.xvi"), tracingFor(tracedCave(), Projection2D.PLAN))
        val library = SurveyLibrary(store)

        assertTrue(SurveyImport.candidates(store).contains("Traced.xvi"), "it should be offered")

        val imported = assertNotNull(SurveyImport.import(library, store, "Traced.xvi"))

        assertEquals("Traced", imported.name, "the .xvi comes off the name")
        assertTrue(imported.getAllLegsInChronoOrder().isEmpty(), "a tracing has no centreline")
        assertEquals(2, imported.planSketch.pathDetails.size, "but it does have the drawing")
    }

    @Test
    fun aTracingImageWithNothingDrawnInItIsStillRefused() {
        val store = store()
        store.writeText(listOf("Blank.xvi"), "set XVIgrid {0 0 1 0 0 1 10 10}\n")
        val library = SurveyLibrary(store)

        assertNull(SurveyImport.import(library, store, "Blank.xvi"))
    }

    /**
     * A Survex file with an illegal reading used to fail with the same "could not read Cave.svx"
     * as a file that is not a survey at all, since [SurveyImport.import]'s
     * `runCatching { }.getOrNull()` threw the exception's own message away. `library.lastError`
     * now carries it, so what a surveyor sees is the line that needs fixing.
     */
    @Test
    fun anIllegalReadingReportsWhichLine() {
        val store = store()
        store.writeText(
            listOf("Cave.svx"),
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            2 3 -5.00 45.00 0.00
            """.trimIndent(),
        )
        val library = SurveyLibrary(store)

        assertNull(SurveyImport.import(library, store, "Cave.svx"))
        assertTrue(
            library.lastError?.contains("2 3 -5.00 45.00 0.00") == true,
            "expected the offending line in lastError, got: ${library.lastError}",
        )
    }
}
