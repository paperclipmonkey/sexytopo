package org.hwyl.sexytopo.demo

import kotlinx.coroutines.runBlocking
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.io.store.SurveyZip
import org.hwyl.sexytopo.shared.manual.contentsOf
import org.hwyl.sexytopo.shared.manual.parseManual
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

    /**
     * Handing a survey over as one file, on the platform it was written for.
     *
     * The other direction of the interop above, and the one with nothing else to catch it.
     * `saveBinaryFile` builds an `NSData` from a pinned Kotlin `ByteArray` — a pointer, a length,
     * and a copy — and every way that can be wrong compiles perfectly: a length off by one, a
     * pointer taken of an array that moves, a `writeToFile` whose result is ignored. On Linux the
     * iOS compile is *skipped* rather than run, so until this test existed the whole path had been
     * near a compiler on a macOS runner and near a processor nowhere.
     *
     * A zip is the right fixture rather than a convenience: it is the thing this actually writes,
     * its bytes are not text, and the reader at the other end checks a CRC — so a copy that is
     * subtly wrong fails here rather than in somebody's Files app.
     */
    @Test
    fun aWholeSurveySavesAsAZipTheBytesOfWhichSurvive() {
        val survey = Survey("IosZip")
        SurveyBuilder.updateWithNewStation(survey, Leg(5.5f, 90f, -3f))
        val expected = SurveyZip.archive(survey, "test", 1)
        val name = SurveyZip.fileNameFor(survey)

        val where = saveBinaryFile(name, expected)

        try {
            assertNotNull(where, "saveBinaryFile reported failure")
            val read =
                assertNotNull(
                    DocumentsFileStore().readBytes(listOf("exports", name)),
                    "the zip is not where saveBinaryFile said it was",
                )
            assertEquals(expected.size, read.size, "the zip came back the wrong length")
            assertTrue(expected.contentEquals(read), "the zip's bytes came back changed")
            // And it is a zip rather than whatever happened to land: the local-header signature.
            assertEquals(listOf(0x50, 0x4b, 0x03, 0x04), read.take(4).map { it.toInt() and 0xFF })
            assertTrue(read.size > 200, "the fixture is too short to catch a length mistake")
        } finally {
            DocumentsFileStore().delete(listOf("exports", name))
        }
    }

    /** An empty archive is not something to write, and saying so beats writing a broken file. */
    @Test
    fun savingNoBytesAtAllIsRefusedRatherThanWritten() {
        assertNull(saveBinaryFile("ios-test-empty.zip", ByteArray(0)))
    }

    /**
     * The tolerances a surveyor sets at the entrance. Written through the same store as the
     * surveys, so this is really asking whether a small file at the storage root round-trips —
     * which is a different path from the nested survey directories above.
     */
    @Test
    fun theSurveyingTolerancesSurviveOnDisk() {
        val store = DocumentsFileStore()
        val original = store.readText(SurveySettingsStore.PATH)
        try {
            val settings =
                org.hwyl.sexytopo.shared.survey.SurveySettings.DEFAULT.copy(maxAngleDelta = 7.5f)

            assertTrue(SurveySettingsStore.save(store, settings))
            assertEquals(settings, SurveySettingsStore.load(store))
        } finally {
            // Leave the simulator as it was found, so a later run reads its own defaults.
            if (original == null) {
                store.delete(SurveySettingsStore.PATH)
            } else {
                store.writeText(SurveySettingsStore.PATH, original)
            }
        }
    }

    /**
     * The one thing this app reads as bytes rather than text: PocketTopo's binary `.top`.
     *
     * `readBytes` is hand-written interop — an `NSData`, a pointer reinterpreted as bytes, and a
     * copy into a Kotlin array — and every way that can be wrong compiles perfectly. An off-by-one
     * in the length, a pointer read as the wrong width, or a copy that aliases a buffer `NSData` has
     * already freed all give a file that is *nearly* right, which for a format with length prefixes
     * in it means everything after the first mistake is rubbish.
     *
     * The content is written as text and compared as bytes, so no second piece of interop has to be
     * correct for this to mean anything. It still spans the awkward range: multi-byte sequences put
     * high bytes and continuation bytes through the copy, and it is long enough that a length
     * mistake shows.
     */
    @Test
    fun aFilesExactBytesComeBack() {
        val path = TEST_ROOT + "binary.top"
        // Every UTF-8 length: ASCII, two bytes, three, and a four-byte astral character.
        val text = ("Grotte de l'Église — 12.5° — ø∞≈ 𝕊 draughting\n").repeat(8)
        val expected = text.encodeToByteArray()
        store.writeText(path, text)

        val read = assertNotNull(store.readBytes(path), "readBytes returned null for a file")

        assertEquals(expected.size, read.size, "the file came back the wrong length")
        assertTrue(expected.contentEquals(read), "the bytes came back changed")
        assertTrue(expected.size > 300, "the fixture is too short to catch a length mistake")
    }

    @Test
    fun readingBytesFromNothingIsNullRatherThanACrash() {
        assertNull(store.readBytes(TEST_ROOT + "not-a-file.top"))
    }

    @Test
    fun anEmptyFileReadsAsNoBytesRatherThanNull() {
        val path = TEST_ROOT + "empty.top"
        store.writeText(path, "")

        assertEquals(0, assertNotNull(store.readBytes(path)).size)
    }

    /**
     * The log's timestamps, which are `Log.Message.FORMAT`'s so that a log file moves between this
     * app and the Android one intact. `NSDateFormatter` is given `en_US_POSIX` precisely so a
     * device set to a non-Gregorian calendar cannot write a year nothing can parse; the simulator
     * is Gregorian, so this checks the shape.
     */
    @Test
    fun nowIsAnIsoTimestampWithAnOffset() {
        val now = nowIso()

        val pattern = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{4}$""")
        assertTrue(pattern.matches(now), "expected yyyy-MM-ddTHH:mm:ssZ, got $now")
        assertTrue(now.substring(0, 4).toInt() in 2024..2100, "implausible year in $now")
        assertTrue(now.substring(5, 7).toInt() in 1..12, now)
        assertTrue(now.substring(11, 13).toInt() in 0..23, now)
    }

    /**
     * The buzz that says a station has been made. A simulator has no Taptic Engine, so what is
     * being checked is that constructing and firing a `UINotificationFeedbackGenerator` does not
     * bring the app down — which is the only failure mode that would matter underground.
     */
    @Test
    fun theHapticDoesNotBringTheAppDown() {
        assertTrue(canBuzz())
        assertTrue(buzz())
        assertTrue(buzz(NEW_STATION_BUZZ_MS))
    }

    // -------------------------------------------------------------------------------------
    // The manual, which is the first thing this app ships as a Compose *file* resource
    // -------------------------------------------------------------------------------------

    /**
     * The guide is bundled as `composeResources/files/manual.html` and read at runtime with
     * `Res.readBytes`. The fonts prove that mechanism works on iOS — the app draws text in the
     * simulator screenshot, and it could not without them — but `files/` is a different directory
     * from `font/`, and "the same mechanism, one folder along" is inference rather than evidence.
     *
     * It is also the failure that would be invisible until somebody in a cave taps *Manual*: the
     * resource is looked up in the framework's bundle at runtime, so a packaging mistake compiles,
     * links, launches and draws a cave perfectly well before failing on the one screen that needs
     * it. Reading it here is the cheapest possible answer.
     */
    @Test
    fun theManualIsInTheAppsOwnBundle() = runBlocking {
        val bytes = Res.readBytes("files/manual.html")
        assertTrue(
            bytes.size > 20_000,
            "the bundled manual is ${bytes.size} bytes, not the 23 KB guide",
        )
        val blocks = parseManual(bytes.decodeToString())
        // Parsed rather than merely read: a resource that comes back as the wrong bytes — a
        // truncation, or a text file mangled by an encoding step — reads fine and parses to
        // nothing much.
        assertEquals(
            13,
            contentsOf(blocks).size,
            "the manual on iOS has ${contentsOf(blocks).size} sections rather than thirteen",
        )
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
