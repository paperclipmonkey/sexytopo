package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.model.graph.Coord2D
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
     *
     * `SurveyStorage` has read all four for as long as it has existed. It was only the loose-file
     * path that did not, and the fixture the browser check imports has no drawing in it, so
     * nothing here could see the loss.
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

    /** And a survey sent as its data file alone still imports, with nothing drawn. */
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
     * The other half of the same mistake: every part of a survey ends `.json`, so a rule of "any
     * `.json` is a survey" offered the drawing as something to import beside the survey. Picking
     * it would have parsed a sketch as a centreline.
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
     * beside a survey sitting right there. `action_file_import_directory` is the Android app's
     * name for this.
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

    /** A folder that is not a survey is not offered as one. */
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
        // A folder this app wrote is already in the library; offering it again would duplicate it.
        store.writeText(listOf("surveys", "Eastwater", "Eastwater.data.json"), "{}")

        assertEquals(listOf("Swildons.data.json"), SurveyImport.candidates(store))
    }

    @Test
    fun surveysFromOtherSoftwareAreOfferedToo() {
        val store = store()
        store.writeText(listOf("Eastwater.svx"), ";")
        store.writeText(listOf("Eastwater.th"), "#")
        store.writeText(listOf("Eastwater.txt"), POCKET_TOPO)
        // The drawing, not the centreline. This app writes it but cannot read it back, and
        // offering something that can only fail is worse than not offering it.
        store.writeText(listOf("Eastwater.th2"), "encoding utf-8")

        assertEquals(
            listOf("Eastwater.svx", "Eastwater.th", "Eastwater.txt"),
            SurveyImport.candidates(store).sorted(),
        )
    }

    /**
     * `.txt` belongs to everything, unlike every other extension here. On a phone whose Documents
     * folder is visible in the Files app, offering every text file as a survey would bury the one
     * that is.
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
     * its text export is something they have to know to produce.
     *
     * The format itself is tested in the shared module against a real `.top`; what is being checked
     * here is the plumbing, which is different from every other import: the bytes have to reach the
     * parser as bytes.
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
        // And the same bytes read as text and back are not the same bytes.
        assertFalse(
            MINIMAL_TOP_FILE.contentEquals(
                assertNotNull(store.readText(listOf("Ceiled Up.top")) ?: "").encodeToByteArray(),
            ),
        )
    }

    /** The only import that brings a drawing in as well as a centreline. */
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
     * this can get to that without a third-party file to hand.
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

    /**
     * A file that parses but yields nothing would otherwise arrive in the library as a survey with
     * no legs, which looks exactly like a successful import of an empty cave.
     */
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
     * surveying. Overwriting yours with theirs would be the worst possible outcome.
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

    /** `Swildons.data.json` is a survey called Swildons, not one called "Swildons.data". */
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

    /** A minimal PocketTopo text export: one splay and one stroke on the plan. */
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
}
