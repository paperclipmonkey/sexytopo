package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.store.PhotoStore
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
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * Every entry, as the bytes that came out of it.
     *
     * Reading each entry all the way to its end is not incidental. A stored entry's CRC is checked
     * by `ZipInputStream` as the last byte of it is handed over, so draining the stream is what
     * turns this from a copy into a check — see the tampering test below, which proves it does.
     */
    private fun readBackBytes(archive: ByteArray): Map<String, ByteArray> {
        val contents = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes()
            }
        }
        return contents
    }

    private fun readBack(archive: ByteArray): Map<String, String> =
        readBackBytes(archive).mapValues { (_, bytes) -> bytes.decodeToString() }

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

        /**
         * Padding that brings a sample photograph up to the size a phone really hands over.
         *
         * Wanted by one test only, and for one reason: with entries of a few hundred bytes every
         * offset in the central directory fits in two, and a writer that recorded them in two
         * would be indistinguishable from one that got it right. Every photograph a surveyor takes
         * is past that boundary, so one sample here has to be as well.
         */
        const val PHONE_SIZED = 70_000
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

    /**
     * A survey with pictures pinned to it hands the pictures over as well.
     *
     * The four JSON files are what a surveyor can write down. The photographs are the part they
     * cannot: the shape of an awkward squeeze, the colour of the water, the bit of the passage
     * they had no words for. A zip with the pins but not the images arrives as a page of markers
     * pointing at nothing, and the caver at the other end has no way of telling what is missing.
     */
    @Test
    fun aSurveyWithPhotographsZipsThemBesideTheFourFiles() {
        val photos = mapOf("1" to imageBytes(1), "2" to imageBytes(2))
        val survey = cave()
        survey.planSketch.addPhotoDetail(Coord2D(2f, -2f), "1", 1f, 0f)
        survey.planSketch.addPhotoDetail(Coord2D(3f, -5f), "2", 1f, 90f)

        val contents = readBackBytes(SurveyZip.archive(survey, "test", 1) { photos[it] })

        assertEquals(
            listOf(
                "Swildons.data.json",
                "Swildons.metadata.json",
                "Swildons.plan.json",
                "Swildons.ext-elevation.json",
                "Swildons.photo-1.jpg",
                "Swildons.photo-2.jpg",
            ),
            contents.keys.toList(),
            "the photographs should follow the four files, under the names a survey directory uses",
        )
        // The literal names above are what a caver sees when they unzip it; this says the same
        // names are the ones a pin looks under, so unzipping over a survey directory puts every
        // picture back where the sketch expects to find it rather than beside it under a new name.
        assertEquals(
            listOf("Swildons.photo-1.jpg", "Swildons.photo-2.jpg"),
            photos.keys.map { PhotoStore.fileNameFor(survey.name, it) },
            "the archive names the photographs something other than the store does",
        )
    }

    /**
     * A photograph comes back byte for byte, which is the check with real teeth.
     *
     * Everything else in a survey is text, and text survives being decoded and re-encoded by
     * accident. A JPEG does not: one byte out and the picture is a grey half-image or nothing at
     * all, and the surveyor finds out months later with the cave a day's drive away. So the sample
     * here is deliberately bytes that no text path could carry — a lone 0xFF, an embedded 0x00 —
     * and the first assertion is that the sample really is that, so this test cannot quietly rot
     * into one that a text round trip would pass.
     */
    @Test
    fun aPhotographArrivesByteForByteAsItLeft() {
        val photo = imageBytes(7)
        assertFalse(
            photo.contentEquals(photo.decodeToString().encodeToByteArray()),
            "the sample survives a text round trip, so it could not catch one",
        )
        val survey = cave()
        survey.planSketch.addPhotoDetail(Coord2D(1f, -1f), "1", 1f, 0f)

        val contents = readBackBytes(SurveyZip.archive(survey, "test", 1) { photo })

        assertTrue(
            photo.contentEquals(contents["Swildons.photo-1.jpg"]),
            "the photograph is not the one that went in",
        )
        // And the checksum written for it is the one the format asks for. The existing checksum
        // test feeds the routine text; a photograph is the case where the high bit is set in most
        // bytes, which is where a signed-vs-unsigned slip in the CRC loop would show up.
        assertEquals(
            CRC32().apply { update(photo) }.value.toInt(),
            Zip.crc32(photo),
            "the CRC of a photograph is not the JVM's",
        )
    }

    /**
     * A photograph altered inside the archive is refused rather than handed over.
     *
     * This test exists to give the one above its teeth. Byte-for-byte equality would still pass if
     * the reader never looked at the checksum, so the pass would say nothing about the CRC the
     * writer computes — and a wrong CRC is the failure that hurts most, because most tools notice
     * it long after the file has been copied somewhere and the original deleted. Changing one byte
     * of the picture and watching the JVM reject the whole entry — it reports an invalid entry CRC
     * — proves the checksum is being checked on the way out, and so that the earlier read was a
     * check and not a copy.
     */
    @Test
    fun aPhotographWhoseBytesHaveBeenAlteredIsRefused() {
        val photo = imageBytes(7)
        val survey = cave()
        survey.planSketch.addPhotoDetail(Coord2D(1f, -1f), "1", 1f, 0f)
        val archive = SurveyZip.archive(survey, "test", 1) { photo }
        // These same bytes read cleanly here, so whatever the read below objects to is the one
        // byte changed in between and not some other quarrel with the archive.
        readBackBytes(archive)

        val at = indexOf(archive, photo)
        assertTrue(
            at >= 0,
            "the photograph is not in the archive at all, so nothing was tampered with",
        )
        archive[at + 8] = (archive[at + 8] + 1).toByte()

        assertFailsWith<ZipException>("a tampered photograph read back without complaint") {
            readBackBytes(archive)
        }
    }

    /**
     * A pin whose picture is not on disc is left out, not written as an empty file.
     *
     * Photographs go missing for ordinary reasons: a survey passed on as JSON alone, a folder
     * copied without its images, a phone that ran out of room. A zero-byte JPEG in the archive
     * would turn "this picture was never sent" into "this picture is corrupt" — the same absence
     * dressed up as damage, which sends somebody looking for a fault that was never there.
     */
    @Test
    fun aPinWhosePhotographIsMissingIsLeftOutRatherThanWrittenEmpty() {
        val survey = cave()
        survey.planSketch.addPhotoDetail(Coord2D(1f, -1f), "1", 1f, 0f)
        survey.planSketch.addPhotoDetail(Coord2D(2f, -2f), "2", 1f, 0f)
        val present = imageBytes(1)

        val contents =
            readBackBytes(
                SurveyZip.archive(survey, "test", 1) { if (it == "1") present else null }
            )

        assertContains(contents.keys, "Swildons.photo-1.jpg")
        assertFalse(
            contents.containsKey("Swildons.photo-2.jpg"),
            "the missing photograph was written into the archive anyway",
        )
        assertEquals(
            ENTRIES + 1,
            contents.size,
            "the archive should hold the four files and the one picture that exists",
        )
    }

    /**
     * Asked for the way it always was, the archive is the one it always was.
     *
     * Photographs arrived as an extra argument with a default, and the whole point of the default
     * is that every caller written before them — the Android sharer, the demo's export, anything
     * that hands the object a survey and nothing else — keeps producing the four entries it did.
     * Failing that quietly would mean a version of the app that suddenly exports pictures it was
     * never given, or exports nothing at all.
     */
    @Test
    fun anArchiveAskedForWithoutPhotographsIsTheOneItAlwaysWas() {
        val pinned = cave()
        pinned.planSketch.addPhotoDetail(Coord2D(1f, -1f), "1", 1f, 0f)

        assertEquals(
            ENTRIES,
            readBackBytes(SurveyZip.archive(pinned, "test", 1)).size,
            "a caller that asked for no photographs was given some",
        )

        // And for a survey with no pins nothing is even asked for, so the bytes are unchanged: the
        // lambda below fails the test if it is ever called, and the archives are compared whole.
        val plain = SurveyZip.archive(cave(), "test", 1)
        val withReader =
            SurveyZip.archive(cave(), "test", 1) {
                fail("a survey with no pins asked for a picture")
            }
        assertTrue(plain.contentEquals(withReader), "the two archives are not the same bytes")
    }

    /**
     * The same survey and the same pictures still zip to the same bytes.
     *
     * Asserted for the four files already, and worth extending rather than assuming: photographs
     * are the part most likely to be gathered in whatever order a map or a directory listing hands
     * them over, and an archive that differs run to run is one nobody can diff, checksum or hand to
     * somebody with any claim about what is in it.
     */
    @Test
    fun theSameSurveyAndPhotographsProduceTheSameArchive() {
        val photos = mapOf("1" to imageBytes(1), "2" to imageBytes(2))

        val first = SurveyZip.archive(pinnedCave(), "test", 1) { photos[it] }
        val second = SurveyZip.archive(pinnedCave(), "test", 1) { photos[it] }

        assertTrue(
            first.contentEquals(second),
            "two archives of the same survey and the same pictures differ",
        )
    }

    /**
     * A picture pinned to the elevation travels too.
     *
     * Both sketches are drawn on and both take pins, and gathering ids from the plan alone is the
     * easy mistake to make. It would lose exactly the photographs of pitches and climbs, which are
     * the ones taken in elevation because that is where they make sense.
     */
    @Test
    fun aPhotographPinnedOnlyToTheElevationTravelsAsWell() {
        val survey = cave()
        survey.elevationSketch.addPhotoDetail(Coord2D(4f, 1f), "1", 1f, 180f)
        val photo = imageBytes(3)

        val contents = readBackBytes(SurveyZip.archive(survey, "test", 1) { photo })

        assertTrue(
            photo.contentEquals(contents["Swildons.photo-1.jpg"]),
            "a photograph pinned to the elevation did not reach the archive",
        )
    }

    /**
     * The directory and the record at the end account for the pictures as well.
     *
     * The reader used above streams from the front and never looks at either, so a directory whose
     * offsets stop making sense once a few hundred kilobytes of JPEG sit between the entries would
     * pass every test here and fail in Finder, Explorer and the iOS Files app. Photographs are what
     * makes that plausible: until now every entry was under a kilobyte.
     *
     * Which is why the two pictures here are the size a camera produces rather than the few
     * hundred bytes the rest of this file uses. That is the whole case: small entries leave every
     * offset inside two bytes, where a writer that truncated them would still be read correctly,
     * and the archive would only come apart in the field once someone pinned a real photograph.
     */
    @Test
    fun theDirectoryAndTheRecordAtTheEndAccountForThePhotographs() {
        val photos = mapOf("1" to imageBytes(1, PHONE_SIZED), "2" to imageBytes(2, PHONE_SIZED))
        val archive = SurveyZip.archive(pinnedCave(), "test", 1) { photos[it] }

        // Said out loud so that shrinking the samples later has to argue with a test: below the
        // 65,535 bytes a two-byte offset reaches, the seeks this test makes prove nothing.
        assertTrue(
            indexOf(archive, photos.getValue("2")) > 0xFFFF,
            "the samples are too small for the offsets in the directory to be worth reading",
        )

        val file = File.createTempFile("survey-with-photographs", ".zip")
        file.deleteOnExit()
        file.writeBytes(archive)
        ZipFile(file).use { zip ->
            assertEquals(
                ENTRIES + photos.size,
                zip.entries().toList().size,
                "the directory does not list the photographs",
            )
            for ((photoId, bytes) in photos) {
                val name = PhotoStore.fileNameFor("Swildons", photoId)
                val entry = zip.getEntry(name) ?: fail("$name is not in the directory")
                // Reached by seeking to the offset the directory records, so a wrong offset lands
                // in the middle of a neighbouring picture and this comes back as rubbish.
                assertTrue(
                    bytes.contentEquals(zip.getInputStream(entry).readBytes()),
                    "$name is not where the directory says it is",
                )
            }
        }

        val eocd = archive.size - 22
        val u16 = { at: Int ->
            (archive[at].toInt() and 0xFF) or ((archive[at + 1].toInt() and 0xFF) shl 8)
        }
        assertEquals(
            ENTRIES + photos.size,
            u16(eocd + 10),
            "the record at the end does not count the photographs",
        )
    }

    /** The cave above, with a picture pinned to each of its two sketches. */
    private fun pinnedCave(): Survey {
        val survey = cave()
        survey.planSketch.addPhotoDetail(Coord2D(1f, -1f), "1", 1f, 0f)
        survey.elevationSketch.addPhotoDetail(Coord2D(3f, 0f), "2", 1f, 45f)
        return survey
    }

    /**
     * Bytes that behave like a photograph and not like a piece of text.
     *
     * A JPEG's own markers to begin and end with, and every value from 0x00 to 0xFF in between, so
     * the sample carries the two that break things: 0x00, which ends a string in anything that
     * reaches for C, and 0xFF, which is not a byte any UTF-8 sequence can contain. The seed makes
     * two photographs in one archive tell each other apart.
     *
     * The padding is for the one test that needs a picture the size a real one is; everything else
     * wants the smallest sample that still cannot be mistaken for text, and leaves it out.
     */
    private fun imageBytes(seed: Int, padding: Int = 0): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), seed.toByte()) +
            ByteArray(256) { it.toByte() } +
            ByteArray(padding) { ((it + seed) and 0xFF).toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /**
     * Where the needle starts in the haystack, or -1. Used to find a picture to spoil, and to see
     * how far into an archive one of them lies.
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}
