package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS half of the app, executed rather than merely compiled.
 *
 * Everything in `iosMain` had been through a compiler and nothing had been through a *runtime*.
 * That distinction is not academic. `DocumentsFileStore` is hand-written Objective-C interop
 * against `NSFileManager` and `NSString`, and the ways it can be wrong — a selector that silently
 * returns nil, a directory that is never created, a write with the wrong encoding — all compile
 * perfectly. If it is broken, a surveyor loses the trip and finds out on the way home.
 *
 * These run in the iOS simulator on the macOS runner, which costs nothing and answers it.
 *
 * Every test writes under [TEST_ROOT] and removes it afterwards, so a second run on the same
 * simulator does not inherit the first one's files.
 */
class IosPlatformTest {

    private val store = DocumentsFileStore()

    @AfterTest
    fun cleanUp() {
        store.delete(TEST_ROOT)
    }

    // -------------------------------------------------------------------------------------
    // The file store
    // -------------------------------------------------------------------------------------

    @Test
    fun whatIsWrittenCanBeReadBack() {
        val path = TEST_ROOT + "hello.txt"
        store.writeText(path, "one\ntwo\n")

        assertTrue(store.exists(path))
        assertEquals("one\ntwo\n", store.readText(path))
    }

    /** Accents and degree signs are in every real survey comment; UTF-8 has to survive. */
    @Test
    fun nonAsciiSurvivesTheRoundTrip() {
        val path = TEST_ROOT + "accents.txt"
        val text = "Grotte de l'Église — 12.5° — draughting"
        store.writeText(path, text)

        assertEquals(text, store.readText(path))
    }

    /** A survey is a directory of files, so writing one has to make its parents. */
    @Test
    fun writingCreatesTheDirectoriesAboveIt() {
        val path = TEST_ROOT + "deep" + "deeper" + "leaf.txt"
        store.writeText(path, "x")

        assertTrue(store.exists(path))
        assertTrue(store.isDirectory(TEST_ROOT + "deep"))
        assertTrue(store.isDirectory(TEST_ROOT + "deep" + "deeper"))
        assertFalse(store.isDirectory(path))
    }

    @Test
    fun listingNamesTheChildrenAndNothingElse() {
        store.writeText(TEST_ROOT + "listing" + "b.txt", "b")
        store.writeText(TEST_ROOT + "listing" + "a.txt", "a")
        store.createDirectory(TEST_ROOT + "listing" + "sub")

        assertEquals(listOf("a.txt", "b.txt", "sub"), store.list(TEST_ROOT + "listing"))
    }

    @Test
    fun aMissingFileReadsAsNullRatherThanThrowing() {
        assertNull(store.readText(TEST_ROOT + "nothing-here.txt"))
        assertFalse(store.exists(TEST_ROOT + "nothing-here.txt"))
        assertFalse(store.isDirectory(TEST_ROOT + "nothing-here.txt"))
        assertEquals(emptyList<String>(), store.list(TEST_ROOT + "nothing-here.txt"))
    }

    @Test
    fun deletingTakesTheSubtreeAndReportsWhetherThereWasOne() {
        store.writeText(TEST_ROOT + "doomed" + "a.txt", "a")

        assertTrue(store.delete(TEST_ROOT + "doomed"))
        assertFalse(store.exists(TEST_ROOT + "doomed" + "a.txt"))
        // Deleting what is not there is false, not an exception: SurveyLibrary relies on it.
        assertFalse(store.delete(TEST_ROOT + "doomed"))
    }

    @Test
    fun rewritingReplacesRatherThanAppends() {
        val path = TEST_ROOT + "twice.txt"
        store.writeText(path, "first version, which is longer")
        store.writeText(path, "second")

        assertEquals("second", store.readText(path))
    }

    // -------------------------------------------------------------------------------------
    // The thing all of that exists for
    // -------------------------------------------------------------------------------------

    /**
     * The trip. A survey is saved, listed and reopened through the same code the app runs, on the
     * same filesystem the phone uses — which is the only check here that would actually have
     * caught losing a weekend's work.
     */
    @Test
    fun aSurveySavesAndReopensWithItsLegsIntact() {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(5.42f, 12.5f, -3f))
        SurveyBuilder.updateWithNewStation(survey, Leg(7.1f, 30f, 2f))
        survey.origin.comment = "entrance, in the streamway"

        val directory = TEST_ROOT + "surveys" + survey.name
        SurveyStorage.save(store, survey, directory)

        assertContains(SurveyStorage.listSurveys(store, TEST_ROOT + "surveys"), "Swildons")

        val reopened = SurveyStorage.load(store, directory)
        assertEquals("Swildons", reopened.name)
        assertEquals(3, reopened.getAllStations().size)
        assertEquals(2, reopened.getAllLegsInChronoOrder().size)
        assertEquals("entrance, in the streamway", reopened.origin.comment)
        assertEquals(5.42f, reopened.origin.onwardLegs.single().distance)
    }

    // -------------------------------------------------------------------------------------
    // The other two actuals
    // -------------------------------------------------------------------------------------

    /**
     * `NSCalendar` is asked for numeric components precisely so a device set to a non-Gregorian
     * calendar cannot produce a year no survey tool can parse. The simulator is Gregorian, so this
     * checks the shape and the plausibility rather than the locale behaviour.
     */
    @Test
    fun todayIsAnIsoDate() {
        val today = todayIso()

        val parts = today.split("-")
        assertEquals(3, parts.size, "expected yyyy-MM-dd, got $today")
        assertEquals(4, parts[0].length, today)
        assertEquals(2, parts[1].length, today)
        assertEquals(2, parts[2].length, today)

        val year = parts[0].toInt()
        assertTrue(year in 2024..2100, "implausible year in $today")
        assertTrue(parts[1].toInt() in 1..12, today)
        assertTrue(parts[2].toInt() in 1..31, today)
    }

    /** The export path: a file the surveyor can find in the Files app afterwards. */
    @Test
    fun anExportLandsSomewhereItCanBeFound() {
        val name = "ios-test-export.svx"
        val where = saveTextFile(name, "*begin Test\n*end Test\n")

        assertNotNull(where, "saveTextFile reported failure")
        assertEquals("*begin Test\n*end Test\n", DocumentsFileStore().readText(listOf("exports", name)))

        DocumentsFileStore().delete(listOf("exports", name))
    }

    @Test
    fun theClipboardAccepts() {
        // UIPasteboard on a simulator is real, so this exercises the interop rather than mocking
        // it. It returns true unconditionally; what is being checked is that it does not crash.
        assertTrue(copyToClipboard("*begin Test"))
    }

    private companion object {
        /** Everything here is written under one directory so cleanup is one delete. */
        val TEST_ROOT = listOf("ios-platform-test")
    }
}
