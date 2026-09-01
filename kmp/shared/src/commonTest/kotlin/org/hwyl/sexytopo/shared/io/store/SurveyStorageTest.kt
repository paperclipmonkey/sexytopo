package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Saving and loading a survey, end to end, through an in-memory filesystem.
 *
 * The Android app cannot write this test. Its `MetadataTranslaterTest` has to Mockito-mock `Uri`
 * and `DocumentFile`, and its one real round-trip is `@Ignore`d with the note "To mock static
 * methods, need to use inline mocks, which breaks other tests". Behind [FileStore] with
 * [InMemoryFileStore] it is ordinary, and it runs on the JVM, on Kotlin/Wasm and on Kotlin/Native.
 *
 * That is the argument for this whole layer in one test: the storage code did not get harder to
 * port off Android, it got easier to *test* once it stopped being Android's.
 */
class SurveyStorageTest {

    private val home = listOf("Documents", "Caves")

    private fun survey(name: String = "Swildons"): Survey {
        val survey = Survey(name)
        val two = SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        SurveyBuilder.addSplay(survey, two, Leg(1.5f, 180f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(7.25f, 45f, -2f))
        survey.planSketch.startNewPath(Coord2D(0f, 0f), Colour.BLACK).apply {
            lineTo(Coord2D(3f, 4f))
            lineTo(Coord2D(9f, 1f))
        }
        return survey
    }

    private fun directory(name: String = "Swildons") = home + name

    // -------------------------------------------------------------------------------------
    // Naming
    // -------------------------------------------------------------------------------------

    @Test
    fun filesAreNamedAfterTheSurvey() {
        assertEquals("Swildons.data.json", SurveyFileType.DATA.filenameFor("Swildons"))
        assertEquals("Swildons.metadata.json", SurveyFileType.METADATA.filenameFor("Swildons"))
        assertEquals("Swildons.plan.json", SurveyFileType.PLAN_SKETCH.filenameFor("Swildons"))
        assertEquals(
            "Swildons.ext-elevation.json",
            SurveyFileType.EXTENDED_ELEVATION_SKETCH.filenameFor("Swildons"),
        )
    }

    @Test
    fun autosavesSitBesideTheirOriginals() {
        assertEquals(
            "Swildons.data.json.autosave",
            SurveyFileType.DATA.autosaveFilenameFor("Swildons"),
        )
    }

    @Test
    fun theLogHasNoAutosave() {
        assertEquals("Swildons.log", SurveyFileType.LOG.autosaveFilenameFor("Swildons"))
        assertFalse(SurveyFileType.LOG.autosaves)
        assertFalse(SurveyFileType.ALL_DATA_TYPES.contains(SurveyFileType.LOG))
    }

    /**
     * The two escape hatches in the Android app's `withExtension`.
     *
     * Unused by the four data types, and kept because the exporters' extensions do use them: a
     * leading `|` is how Therion gets `SwildonsP.th2` rather than `Swildons.P.th2`.
     */
    @Test
    fun extensionMarkersAreHonoured() {
        assertEquals("Swildons.th2", SurveyFileType.withExtension("Swildons", "th2"))
        assertEquals("Swildons.th2", SurveyFileType.withExtension("Swildons", ".th2"))
        assertEquals("SwildonsP", SurveyFileType.withExtension("Swildons", "|P"))
    }

    // -------------------------------------------------------------------------------------
    // Round trip
    // -------------------------------------------------------------------------------------

    @Test
    fun aSavedSurveyLoadsBackWithItsLegsAndSketch() {
        val store = InMemoryFileStore()
        val original = survey()
        SurveyStorage.save(store, original, directory())

        val loaded = SurveyStorage.load(store, directory())

        assertEquals(original.name, loaded.name)
        assertEquals(
            original.getAllLegsInChronoOrder().size,
            loaded.getAllLegsInChronoOrder().size,
        )
        assertEquals(
            original.getAllStationsInChronoOrder().map { it.name },
            loaded.getAllStationsInChronoOrder().map { it.name },
        )
        assertEquals(original.activeStation.name, loaded.activeStation.name)
        assertTrue(loaded.planSketch.pathDetails.isNotEmpty(), "the plan sketch came back")
    }

    @Test
    fun savingWritesTheExpectedFiles() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())

        assertEquals(
            listOf(
                "Swildons.data.json",
                "Swildons.ext-elevation.json",
                "Swildons.metadata.json",
                "Swildons.plan.json",
            ),
            store.list(directory()),
        )
    }

    @Test
    fun aSurveyDirectoryIsRecognisedByItsDataFile() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())

        assertTrue(SurveyStorage.isSurveyDirectory(store, directory()))
        assertFalse(SurveyStorage.isSurveyDirectory(store, home), "the parent is not a survey")

        store.createDirectory(home + "Not A Survey")
        assertFalse(SurveyStorage.isSurveyDirectory(store, home + "Not A Survey"))
    }

    @Test
    fun loadingSomethingThatIsNotASurveyIsRefused() {
        val store = InMemoryFileStore()
        store.createDirectory(home + "Photos")
        assertFailsWith<IllegalArgumentException> {
            SurveyStorage.load(store, home + "Photos")
        }
    }

    @Test
    fun surveysAreListedAndNonSurveysAreNot() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey("Swildons"), home + "Swildons")
        SurveyStorage.save(store, survey("Eastwater"), home + "Eastwater")
        store.createDirectory(home + "Photos")

        assertEquals(listOf("Eastwater", "Swildons"), SurveyStorage.listSurveys(store, home))
    }

    // -------------------------------------------------------------------------------------
    // Autosave
    // -------------------------------------------------------------------------------------

    @Test
    fun anAutosaveDoesNotDisturbTheSavedFiles() {
        val store = InMemoryFileStore()
        val original = survey()
        SurveyStorage.save(store, original, directory())
        val savedData = store.readText(directory() + "Swildons.data.json")

        SurveyBuilder.updateWithNewStation(original, Leg(3f, 10f, 0f))
        SurveyStorage.autosave(store, original, directory())

        assertEquals(savedData, store.readText(directory() + "Swildons.data.json"))
        assertTrue(store.exists(directory() + "Swildons.data.json.autosave"))
        // A plain load still sees the saved version, not the autosaved one.
        assertEquals(3, SurveyStorage.load(store, directory()).getAllStationsInChronoOrder().size)
    }

    @Test
    fun restoringAnAutosaveGetsTheAutosavedSurvey() {
        val store = InMemoryFileStore()
        val original = survey()
        SurveyStorage.save(store, original, directory())

        SurveyBuilder.updateWithNewStation(original, Leg(3f, 10f, 0f))
        SurveyStorage.autosave(store, original, directory())

        val restored = SurveyStorage.load(store, directory(), restoreAutosave = true)
        assertEquals(4, restored.getAllStationsInChronoOrder().size)
    }

    /**
     * The bug this port does not carry.
     *
     * `Loader.loadSketches` applies its autosave swap to the plan sketch and not to the extended
     * elevation, so restoring an autosave in the Android app hands back the autosaved data and plan
     * next to the last explicitly *saved* elevation - two points in time in one survey, with no
     * indication. Its log line even prints the plan's filename while reading the elevation, which
     * is presumably how it survived.
     */
    @Test
    fun restoringAnAutosaveRestoresTheElevationSketchToo() {
        val store = InMemoryFileStore()
        val original = survey()
        SurveyStorage.save(store, original, directory())

        original.elevationSketch.startNewPath(Coord2D(1f, 1f), Colour.RED).apply {
            lineTo(Coord2D(6f, 2f))
        }
        SurveyStorage.autosave(store, original, directory())

        val restored = SurveyStorage.load(store, directory(), restoreAutosave = true)
        assertTrue(
            restored.elevationSketch.pathDetails.isNotEmpty(),
            "the autosaved elevation sketch should come back, not the saved one",
        )
    }

    /**
     * An autosave that only got as far as some files still yields the saved rest.
     *
     * The swap is per file in the Java and per file here: a half-written autosave should degrade to
     * the saved version of whatever it did not reach, rather than losing everything.
     */
    @Test
    fun aPartialAutosaveFallsBackFileByFile() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())
        store.delete(directory() + "Swildons.plan.json.autosave")
        SurveyStorage.autosave(store, survey(), directory())
        store.delete(directory() + "Swildons.plan.json.autosave")

        val restored = SurveyStorage.load(store, directory(), restoreAutosave = true)
        assertTrue(restored.planSketch.pathDetails.isNotEmpty(), "fell back to the saved plan")
    }

    // -------------------------------------------------------------------------------------
    // The store itself
    // -------------------------------------------------------------------------------------

    @Test
    fun deletingADirectoryTakesItsContents() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())

        assertTrue(store.delete(directory()))
        assertFalse(store.exists(directory()))
        assertFalse(store.exists(directory() + "Swildons.data.json"))
        assertTrue(store.exists(home), "the parent survives")
    }

    // -------------------------------------------------------------------------------------
    // The metadata file, which is where the Android app keeps the active station
    // -------------------------------------------------------------------------------------

    /**
     * A survey saved here and opened by the Android app opens where the surveyor left off.
     *
     * `Loader.loadMetadata` reads the active station out of `<name>.metadata.json` and nowhere
     * else. This port kept it as `activeStation` inside the *data* file — its own extension, which
     * the Android app does not read — and wrote no metadata file at all. So a cave surveyed on a
     * phone here and continued on an Android one came up at the entrance, with nothing to say that
     * anything had been dropped.
     */
    @Test
    fun theActiveStationIsWrittenWhereTheAndroidAppLooksForIt() {
        val store = InMemoryFileStore()
        val survey = survey()
        val working = survey.getStationByName("3")
        assertTrue(working != null, "the fixture should have a third station")
        survey.activeStation = working

        SurveyStorage.save(store, survey, directory())

        val metadata = store.readText(directory() + "Swildons.metadata.json")
        assertTrue(metadata != null, "no metadata file was written")
        assertTrue(
            metadata.contains("\"active-station\": \"3\""),
            "the metadata file does not name the working station: $metadata",
        )
    }

    /** And a survey that only has the Android app's copy of it is read the same way. */
    @Test
    fun theActiveStationIsReadBackFromTheMetadataFileAlone() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())
        // What a survey written by the Android app looks like: no `activeStation` in the data
        // file, because that key is this port's own.
        val data = store.readText(directory() + "Swildons.data.json")!!
        val withoutIt =
            data.lines().filterNot { it.contains("activeStation") }.joinToString("\n")
        store.writeText(
            directory() + "Swildons.data.json",
            // `activeStation` is written last, so taking its line out leaves the comma that
            // separated it. Trailing commas are not JSON and the parser is right to refuse them.
            withoutIt.replace(Regex(",(\\s*})\\s*$"), "$1"),
        )
        store.writeText(
            directory() + "Swildons.metadata.json",
            """
            {
              "name": "Swildons",
              "active-station": "3",
              "connections": {}
            }
            """.trimIndent(),
        )

        val loaded = SurveyStorage.load(store, directory())

        assertEquals("3", loaded.activeStation.name)
    }

    /** A round trip keeps it, which is the case a surveyor actually meets. */
    @Test
    fun theWorkingEndSurvivesSavingAndLoading() {
        val store = InMemoryFileStore()
        val survey = survey()
        survey.activeStation = survey.getStationByName("2")!!

        SurveyStorage.save(store, survey, directory())

        assertEquals("2", SurveyStorage.load(store, directory()).activeStation.name)
    }

    /**
     * A metadata file naming a station this survey does not have leaves the survey alone.
     *
     * The Java sets the active station to whatever `getStationByName` returned, which for an
     * unknown name is null — so a metadata file that has drifted out of step with its survey (a
     * station renamed on one device, the file copied from another) leaves the Android app pointing
     * at nothing. Here it is a no-op: whatever the data file said still stands.
     */
    @Test
    fun aMetadataFileNamingAStationThatIsNotThereChangesNothing() {
        val store = InMemoryFileStore()
        val survey = survey()
        survey.activeStation = survey.getStationByName("2")!!
        SurveyStorage.save(store, survey, directory())
        store.writeText(
            directory() + "Swildons.metadata.json",
            """{ "active-station": "Sump", "connections": {} }""",
        )

        assertEquals("2", SurveyStorage.load(store, directory()).activeStation.name)
    }

    /** As does one that is not JSON at all, rather than refusing to open the survey. */
    @Test
    fun aDamagedMetadataFileDoesNotStopTheSurveyOpening() {
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())
        store.writeText(directory() + "Swildons.metadata.json", "{ this is not json")

        val loaded = SurveyStorage.load(store, directory())

        assertEquals(3, loaded.getAllStations().size)
    }

    @Test
    fun listingIsStableAndShallow() {
        val store = InMemoryFileStore()
        store.writeText(home + "b.txt", "b")
        store.writeText(home + "a.txt", "a")
        store.writeText(home + "deep" + "c.txt", "c")

        assertEquals(listOf("a.txt", "b.txt", "deep"), store.list(home))
        assertEquals(listOf("a.txt", "b.txt", "deep"), store.list(home), "same answer twice")
    }
}
