package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
