package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.store.SurveyZip
import org.hwyl.sexytopo.shared.io.store.Zip
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A survey handed over as one file, checked by a reader this project did not write.
 *
 * The writer is a hundred lines of hand-assembled headers, because Kotlin/Native and Kotlin/Wasm
 * have no `java.util.zip` and a survey is four small text files that do not need compressing. Hand-
 * assembled headers are exactly the kind of code that passes its author's own reader and fails
 * everybody else's — an off-by-one in an offset, a field written big-endian, a size counted before
 * the name rather than after.
 *
 * So the oracle here is deliberately **not** a matching reader written alongside it. It is
 * `java.util.zip.ZipInputStream`, which is the JVM's own, and the CRC is checked against
 * `java.util.zip.CRC32`. If the archive is malformed in a way this project cannot see, the JVM
 * says so. This is a JVM test rather than a common one for that reason and no other: the thing
 * being tested is common code, and it is the *independent* reader that is only available here.
 */
class SurveyZipTest {

    private fun cave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(12f, 95f, -4f))
        survey.planSketch.pathDetails =
            mutableListOf(PathDetail(listOf(Coord2D(1f, -1f), Coord2D(4f, -3f)), Colour.BLACK))
        return survey
    }

    private fun readBack(archive: ByteArray): Map<String, String> {
        val contents = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return contents
    }

    /** The whole point: what comes out is a zip, and it holds the survey. */
    @Test
    fun aSurveyZipsIntoTheFourFilesASurveyDirectoryHolds() {
        val archive = SurveyZip.archive(cave(), "test", 1)

        val contents = readBack(archive)

        assertEquals(
            listOf(
                "Swildons.data.json",
                "Swildons.metadata.json",
                "Swildons.plan.json",
                "Swildons.ext-elevation.json",
            ),
            contents.keys.toList(),
            "the entries should be named as the files in a survey directory are",
        )
        assertContains(contents["Swildons.data.json"]!!, "Swildons")
        assertTrue(
            contents["Swildons.plan.json"]!!.contains("path"),
            "the drawing should be in there",
        )
    }

    /**
     * The centreline and the drawing survive the trip, not merely the file names.
     *
     * Checked by parsing what came out of the archive with the app's own readers, which is what a
     * caver at the other end does when they unzip it and import.
     */
    @Test
    fun whatComesOutOfTheArchiveParsesBackIntoTheSurvey()  {
        val original = cave()

        val contents = readBack(SurveyZip.archive(original, "test", 1))
        val survey = SurveyJson.parse(contents["Swildons.data.json"]!!)
        val plan = SketchJson.read(contents["Swildons.plan.json"]!!, survey)

        assertEquals(
            original.getAllLegsInChronoOrder().size,
            survey.getAllLegsInChronoOrder().size,
            "every leg should come back",
        )
        assertEquals(1, plan.sketch.pathDetails.size, "and the stroke that was drawn")
    }

    /**
     * The checksum, against the JVM's.
     *
     * A wrong CRC is the failure that hurts most: many readers accept the archive, extract it, and
     * only complain — or silently hand over rubbish — later. `ZipInputStream` verifies it on
     * `closeEntry`, so the test above would already fail; this asserts the arithmetic directly so a
     * failure says *CRC* rather than "stream closed badly".
     */
    @Test
    fun theChecksumIsTheOneTheFormatAsksFor() {
        for (text in listOf("", "a", "Swildons Hole", "Šumava", "x".repeat(5000))) {
            val bytes = text.encodeToByteArray()
            val expected = CRC32().apply { update(bytes) }.value.toInt()

            assertEquals(expected, Zip.crc32(bytes), "CRC of ${text.take(12)}")
        }
    }

    /**
     * A name outside ASCII survives, which is what the UTF-8 flag is for.
     *
     * Without bit 11 set, an unzipper is entitled to read the name in an ancient code page, and a
     * Czech or Slovenian cave arrives as mojibake — or, worse, as a name that no longer matches the
     * one inside the data file, so importing it makes a survey called something else.
     */
    @Test
    fun aCaveWhoseNameIsNotAsciiKeepsIt() {
        val survey = cave()
        survey.name = "Šumava"

        val bytes = SurveyZip.archive(survey, "test", 1)
        val contents = readBack(bytes)

        assertContains(contents.keys, "Šumava.data.json")
        // And the flag that says so, which the reader above does not need and Windows does.
        //
        // `java.util.zip` decodes names as UTF-8 whatever the flag says, so the assertion above
        // passes with the flag left off entirely — which was the state of this test until the
        // writer was mutated to prove it. Explorer and the older tools read a name with the flag
        // clear as CP437, and `Šumava` arrives as `┼aumava`. So read bit 11 out of the header.
        assertTrue(utf8FlagSet(bytes, LOCAL_HEADER), "the local header does not flag UTF-8 names")
        assertTrue(
            utf8FlagSet(bytes, CENTRAL_HEADER),
            "the central directory does not flag UTF-8 names",
        )
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val CENTRAL_HEADER = 0x02014b50

        /**
         * How many files a survey is: data, metadata, plan, extended elevation.
         *
         * In one place because it was in two, and the second one was wrong within an hour of being
         * written - the metadata file was added to the archive and this test went on scanning for
         * exactly three headers, then reported the miscount as a missing UTF-8 flag.
         */
        const val ENTRIES = 4
    }

    /**
     * Whether every header of the given kind has bit 11 of its general-purpose flag set.
     *
     * The flag sits two bytes further into a central header than a local one, the central one
     * carrying an extra "version made by" field in front of it.
     */
    private fun utf8FlagSet(bytes: ByteArray, signature: Int): Boolean {
        val flagOffset = if (signature == CENTRAL_HEADER) 8 else 6
        var found = 0
        for (i in 0..bytes.size - 4) {
            val here =
                (bytes[i].toInt() and 0xFF) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 3].toInt() and 0xFF) shl 24)
            if (here != signature) continue
            val flag =
                (bytes[i + flagOffset].toInt() and 0xFF) or
                    ((bytes[i + flagOffset + 1].toInt() and 0xFF) shl 8)
            if (flag and (1 shl 11) == 0) return false
            found++
        }
        return found == ENTRIES
    }

    /**
     * The same survey zips to the same bytes.
     *
     * Every entry carries a fixed timestamp rather than the clock, for the reason this document
     * reports about the Android app's PocketTopo exporter: an archive that differs every time is
     * one nobody can diff, compare or test. Asserted so a later "improvement" that stamps the real
     * time has to argue with a test rather than slip through.
     */
    @Test
    fun theSameSurveyProducesTheSameArchive() {
        val first = SurveyZip.archive(cave(), "test", 1)
        val second = SurveyZip.archive(cave(), "test", 1)

        assertTrue(first.contentEquals(second), "two archives of the same survey differ")
    }

    /**
     * The central directory, which the reader above never looks at.
     *
     * `ZipInputStream` walks local headers from the front and ignores the central directory and the
     * end-of-central-directory record entirely — so every check above would pass with both of them
     * malformed, and the archive would still fail in Finder, in Windows Explorer and in the iOS
     * Files app, which all read the directory rather than streaming. That is a real hole in an
     * oracle and worth closing rather than noting.
     *
     * `ZipFile` is the reader that does read it. It needs a file on disk, which is the only reason
     * this test writes one.
     */
    @Test
    fun theDirectoryAtTheEndAgreesWithTheEntriesAtTheFront() {
        val file = File.createTempFile("survey", ".zip")
        file.deleteOnExit()
        file.writeBytes(SurveyZip.archive(cave(), "test", 1))

        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                listOf(
                    "Swildons.data.json",
                    "Swildons.metadata.json",
                    "Swildons.plan.json",
                    "Swildons.ext-elevation.json",
                ),
                names,
                "the directory should list the same four entries, in order",
            )
            for (entry in zip.entries()) {
                // Reading through the directory means seeking to the offset it records, so a wrong
                // offset lands in the middle of another entry and this throws or returns rubbish.
                val bytes = zip.getInputStream(entry).readBytes()
                assertEquals(
                    entry.size,
                    bytes.size.toLong(),
                    "${entry.name} is not the length the directory claims",
                )
                assertTrue(bytes.isNotEmpty(), "${entry.name} came back empty")
            }
        }
    }

    /**
     * The record at the end says how many files there are, and says four.
     *
     * Not covered by any of the readers above, which was found by mutating the writer to claim two:
     * `ZipInputStream` never reads the record at all and walks the local headers instead, and
     * `ZipFile` reads the central directory to its end rather than counting entries out of it. So
     * both of them recover every file from an archive whose own summary says there is one fewer —
     * and `unzip`, and Windows Explorer, and anything else that trusts the count, hand over two and
     * lose the drawing. Read the field itself.
     *
     * The record is the last twenty-two bytes, there being no archive comment to sit after it.
     */
    @Test
    fun theRecordAtTheEndCountsEveryFile() {
        val bytes = SurveyZip.archive(cave(), "test", 1)
        val eocd = bytes.size - 22
        val u16 = { at: Int ->
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        }
        val u32 = { at: Int -> u16(at) or (u16(at + 2) shl 16) }

        assertEquals(0x06054b50, u32(eocd), "the archive does not end with the record it should")
        assertEquals(
            ENTRIES,
            u16(eocd + 8),
            "the record does not say how many files are on this disk",
        )
        assertEquals(
            ENTRIES,
            u16(eocd + 10),
            "the record does not say how many files the archive holds",
        )
        // And where it says the directory is, is where the directory is.
        val start = u32(eocd + 16)
        assertEquals(0x02014b50, u32(start), "the record points somewhere that is not the directory")
        assertEquals(
            eocd - start,
            u32(eocd + 12),
            "the record's size for the directory does not reach the end of it",
        )
    }

    /** An empty file is a real case — a survey with no drawing — and a zero-length entry is legal. */
    @Test
    fun anEmptyEntryIsStillAValidArchive() {
        val archive = Zip.archive(listOf(Zip.Entry("empty.txt", ByteArray(0))))

        assertEquals(mapOf("empty.txt" to ""), readBack(archive))
    }
}
