package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.imports.PocketTopoFile
import org.hwyl.sexytopo.shared.io.imports.PocketTopoFormatException
import org.hwyl.sexytopo.shared.io.imports.PocketTopoImporter
import org.hwyl.sexytopo.shared.model.sketch.Colour
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reading PocketTopo's own binary `.top` file.
 *
 * The synthetic files are the ones `PocketTopoImporterTest` builds byte for byte, so the Android
 * app's own assertions are made here too — on three targets rather than one. The real file at the
 * end is the same `CeiledUp.top` its integration test uses.
 */
class PocketTopoImportTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ---------------------------------------------------------------------------------------
    // Building files, exactly as the Android app's test does
    // ---------------------------------------------------------------------------------------

    private class TopFileBuilder {
        private val bytes = mutableListOf<Byte>()

        fun byte(value: Int) = apply { bytes.add(value.toByte()) }

        fun int16(value: Int) = apply {
            bytes.add((value and 0xFF).toByte())
            bytes.add(((value shr 8) and 0xFF).toByte())
        }

        fun int32(value: Int) = apply {
            for (shift in 0 until 4) bytes.add(((value shr (shift * 8)) and 0xFF).toByte())
        }

        fun int64(value: Long) = apply {
            int32((value and 0xFFFFFFFFL).toInt())
            int32(((value shr 32) and 0xFFFFFFFFL).toInt())
        }

        fun string(text: String) = apply {
            val encoded = text.encodeToByteArray()
            byte(encoded.size)
            encoded.forEach { bytes.add(it) }
        }

        fun header() = apply { byte('T'.code).byte('o'.code).byte('p'.code).byte(3) }

        fun mapping() = apply { int32(0).int32(0).int32(1000) }

        fun emptyDrawing() = apply { mapping().byte(0) }

        @Suppress("LongParameterList")
        fun shot(
            from: Int,
            to: Int,
            millimetres: Int,
            azimuth: Int,
            inclination: Int,
            flags: Int = 0,
            comment: String? = null,
        ) = apply {
            int32(from)
            int32(to)
            int32(millimetres)
            int16(azimuth)
            int16(inclination)
            byte(flags)
            byte(0) // roll
            int16(-1) // trip index
            comment?.let { string(it) }
        }

        fun build(): ByteArray = bytes.toByteArray()
    }

    /** One connected leg and one splay, with a two-point brown stroke on the plan. */
    private fun minimalFile(): ByteArray =
        TopFileBuilder()
            .header()
            .int32(1) // one trip
            .int64(PocketTopoFile.TICKS_AT_EPOCH)
            .byte(0) // no comment
            .int16(0) // no declination
            .int32(2) // two shots
            .shot(from = 0x00000000, to = 0x00000001, millimetres = 3500, azimuth = 0x4000, inclination = 0)
            .shot(from = 0x00000001, to = 0x80000000.toInt(), millimetres = 2000, azimuth = 0, inclination = 0x2000)
            .int32(0) // no references
            .mapping() // the overview's
            .mapping() // the plan's
            .byte(1) // a polygon
            .int32(2) // of two points
            .int32(1000).int32(-2000)
            .int32(3000).int32(-4000)
            .byte(3) // brown
            .byte(0) // end of the plan
            .emptyDrawing() // the elevation
            .build()

    private fun read(bytes: ByteArray) = PocketTopoImporter.read(bytes, "Test")

    // ---------------------------------------------------------------------------------------
    // The header
    // ---------------------------------------------------------------------------------------

    @Test
    fun somethingThatIsNotAPocketTopoFileIsRefused() {
        assertFailsWith<PocketTopoFormatException> {
            read(byteArrayOf('B'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 3))
        }
    }

    @Test
    fun aVersionThisDoesNotUnderstandIsRefused() {
        assertFailsWith<PocketTopoFormatException> {
            read(byteArrayOf('T'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 2))
        }
    }

    @Test
    fun aTruncatedFileIsRefusedRatherThanHalfRead() {
        assertFailsWith<PocketTopoFormatException> { read(minimalFile().copyOfRange(0, 20)) }
    }

    /**
     * Four bytes of a corrupt file read as a count. The Java sizes an `ArrayList` with it and then
     * loops that many times, so this is an `OutOfMemoryError` or a very long wait.
     */
    @Test
    fun anAbsurdCountIsRefusedRatherThanAllocated() {
        val file =
            TopFileBuilder().header().int32(0x7FFFFFFF).build()
        assertFailsWith<PocketTopoFormatException> { read(file) }
    }

    @Test
    fun aFileIsRecognisedByItsFirstFourBytes() {
        assertTrue(PocketTopoImporter.looksLikeTopFile(minimalFile()))
        assertFalse(PocketTopoImporter.looksLikeTopFile("not a top file at all".encodeToByteArray()))
        assertFalse(PocketTopoImporter.looksLikeTopFile(ByteArray(2)))
    }

    // ---------------------------------------------------------------------------------------
    // The centreline
    // ---------------------------------------------------------------------------------------

    @Test
    fun theOriginTakesTheNameOfTheFirstShotsNearEnd() {
        assertEquals("0.0", read(minimalFile()).origin.name)
    }

    @Test
    fun aConnectedLegKeepsItsReadings() {
        val survey = read(minimalFile())
        val connected = survey.origin.onwardLegs.filter { it.hasDestination() }

        assertEquals(1, connected.size)
        assertClose(3.5f, connected[0].distance)
        assertClose(90f, connected[0].azimuth)
        assertClose(0f, connected[0].inclination)
    }

    @Test
    fun aShotWithNoFarEndIsASplay() {
        val survey = read(minimalFile())
        val station = assertNotNull(survey.getStationByName("0.1"))
        val splays = station.onwardLegs.filter { !it.hasDestination() }

        assertEquals(1, splays.size)
        assertClose(2f, splays[0].distance)
        assertClose(45f, splays[0].inclination)
    }

    @Test
    fun aShotCommentBecomesTheStationsComment() {
        val file =
            TopFileBuilder()
                .header()
                .int32(0) // no trips
                .int32(1)
                .shot(
                    from = 0x00000000,
                    to = 0x00000001,
                    millimetres = 5000,
                    azimuth = 0,
                    inclination = 0,
                    flags = 2,
                    comment = "test shot",
                )
                .int32(0)
                .mapping()
                .emptyDrawing()
                .emptyDrawing()
                .build()

        assertEquals("test shot", assertNotNull(read(file).getStationByName("0.1")).comment)
    }

    @Test
    fun aTripBecomesTheSurveysTrip() {
        val trip = assertNotNull(read(minimalFile()).trip)
        assertEquals("1970-01-01", trip.surveyDate.toString())
    }

    // ---------------------------------------------------------------------------------------
    // Repeated shots
    // ---------------------------------------------------------------------------------------

    /** Three shots between the same pair, as a surveyor actually takes them. */
    private fun tripleShotFile(): ByteArray =
        TopFileBuilder()
            .header()
            .int32(0)
            .int32(3)
            .shot(0x00000000, 0x00000001, 5000, 0x4000, 0)
            .shot(0x00000000, 0x00000001, 5010, 0x4001, 0x0024)
            .shot(0x00000000, 0x00000001, 4990, 0x3FFF, 0xFFDC)
            .int32(0)
            .mapping()
            .emptyDrawing()
            .emptyDrawing()
            .build()

    @Test
    fun repeatedShotsBecomeOneLeg() {
        val survey = read(tripleShotFile())

        assertEquals(2, survey.getAllStations().size)
        assertEquals(1, survey.origin.onwardLegs.count { it.hasDestination() })
    }

    @Test
    fun theOriginalReadingsAreKeptOnTheLegTheyMade() {
        val leg = read(tripleShotFile()).origin.onwardLegs.first { it.hasDestination() }

        assertTrue(leg.wasPromoted())
        assertEquals(3, leg.promotedFrom.size)
        assertClose(5.00f, leg.promotedFrom[0].distance)
        assertClose(5.01f, leg.promotedFrom[1].distance)
        assertClose(4.99f, leg.promotedFrom[2].distance)
        // And the leg itself is their average.
        assertClose(5.0f, leg.distance)
    }

    @Test
    fun oneShotIsNotAPromotion() {
        val leg = read(minimalFile()).origin.onwardLegs.first { it.hasDestination() }
        assertFalse(leg.wasPromoted())
        assertEquals(0, leg.promotedFrom.size)
    }

    // ---------------------------------------------------------------------------------------
    // Shots that are not in tree order
    // ---------------------------------------------------------------------------------------

    /**
     * A `.top` file stores shots in the order they were recorded. A surveyor doubling back records
     * a leg whose near end has not been created yet, so the reader has to sweep repeatedly.
     */
    private fun outOfOrderFile(): ByteArray =
        TopFileBuilder()
            .header()
            .int32(0)
            .int32(3)
            .shot(0x00000000, 0x80000000.toInt(), 1500, 0x4000, 0) // a splay off 0.0
            .shot(0x00000001, 0x00000002, 4000, 0, 0) // 0.1 -> 0.2, before 0.1 exists
            .shot(0x00000000, 0x00000001, 3000, 0x8000, 0) // and here is 0.1
            .int32(0)
            .mapping()
            .emptyDrawing()
            .emptyDrawing()
            .build()

    @Test
    fun aShotWhoseNearEndArrivesLaterIsStillAttached() {
        val survey = read(outOfOrderFile())

        assertEquals(3, survey.getAllStations().size)
        assertNotNull(survey.getStationByName("0.0"))
        assertNotNull(survey.getStationByName("0.1"))
        assertNotNull(survey.getStationByName("0.2"))

        assertEquals(1, survey.origin.onwardLegs.count { it.hasDestination() })
        assertEquals(1, survey.origin.onwardLegs.count { !it.hasDestination() })

        val station = assertNotNull(survey.getStationByName("0.1"))
        val onward = station.onwardLegs.filter { it.hasDestination() }
        assertEquals(1, onward.size)
        assertClose(4f, onward[0].distance)
    }

    /**
     * A shot whose *far* end already exists is a backsight. It is stored pointing the other way,
     * as every leg in this app is, and flagged so the table can show it as the surveyor read it.
     */
    @Test
    fun aBacksightIsTurnedRoundAndFlagged() {
        val file =
            TopFileBuilder()
                .header()
                .int32(0)
                .int32(2)
                .shot(0x00000000, 0x00000001, 3000, 0, 0) // 0.0 -> 0.1, due north, level
                .shot(0x00000002, 0x00000001, 4000, 0, 0x2000) // 0.2 -> 0.1: shot back from 0.2
                .int32(0)
                .mapping()
                .emptyDrawing()
                .emptyDrawing()
                .build()

        val survey = read(file)

        val station = assertNotNull(survey.getStationByName("0.1"))
        val backsight = station.onwardLegs.first { it.hasDestination() }
        assertEquals("0.2", backsight.destination.name)
        assertTrue(backsight.wasShotBackwards)
        // Shot north and up from 0.2; stored as south and down from 0.1.
        assertClose(180f, backsight.azimuth)
        assertClose(-45f, backsight.inclination)
        assertClose(4f, backsight.distance)
    }

    @Test
    fun aLoopClosureIsDroppedRatherThanLoopingForever() {
        val file =
            TopFileBuilder()
                .header()
                .int32(0)
                .int32(3)
                .shot(0x00000000, 0x00000001, 3000, 0, 0)
                .shot(0x00000001, 0x00000002, 3000, 0x4000, 0)
                // Back to the origin: this app's tree cannot hold it.
                .shot(0x00000002, 0x00000000, 3000, 0x8000, 0)
                .int32(0)
                .mapping()
                .emptyDrawing()
                .emptyDrawing()
                .build()

        val survey = read(file)

        assertEquals(3, survey.getAllStations().size)
        assertEquals(2, survey.getAllLegs().size)
    }

    // ---------------------------------------------------------------------------------------
    // The drawings
    // ---------------------------------------------------------------------------------------

    @Test
    fun aPolygonBecomesAStrokeInTheRightColourAndPlace() {
        val survey = read(minimalFile())

        assertEquals(1, survey.planSketch.pathDetails.size)
        val path = survey.planSketch.pathDetails[0]
        assertEquals(Colour.BROWN, path.colour)
        // Millimetres to metres, and no y-flip: the binary drawing is already screen-oriented.
        assertClose(1f, path.path[0].x)
        assertClose(-2f, path.path[0].y)
        assertClose(3f, path.path[1].x)
        assertClose(-4f, path.path[1].y)

        assertTrue(survey.elevationSketch.pathDetails.isEmpty())
    }

    /** PocketTopo's cross-sections carry no outline, so there is nothing to put in one here. */
    @Test
    fun aCrossSectionMarkerIsSkippedRatherThanRefused() {
        val file =
            TopFileBuilder()
                .header()
                .int32(0)
                .int32(1)
                .shot(0x00000000, 0x00000001, 3000, 0, 0)
                .int32(0)
                .mapping()
                .mapping()
                .byte(3) // a cross-section
                .int32(1000).int32(2000).int32(0x00000001).int32(0)
                .byte(1) // then a polygon, to prove the reader is still in step
                .int32(1)
                .int32(500).int32(600)
                .byte(5)
                .byte(0)
                .emptyDrawing()
                .build()

        val survey = read(file)

        assertEquals(1, survey.planSketch.pathDetails.size)
        assertEquals(Colour.RED, survey.planSketch.pathDetails[0].colour)
    }

    @Test
    fun anUnknownDrawingElementIsRefusedRatherThanGuessedAt() {
        val file =
            TopFileBuilder()
                .header()
                .int32(0)
                .int32(0)
                .int32(0)
                .mapping()
                .mapping()
                .byte(9) // no such element
                .build()

        assertFailsWith<PocketTopoFormatException> { read(file) }
    }

    // ---------------------------------------------------------------------------------------
    // A real file
    // ---------------------------------------------------------------------------------------

    /**
     * The Android app's own integration fixture, byte for byte. A synthetic file proves the reader
     * agrees with the test that built it; only a real one proves it agrees with PocketTopo.
     */
    @Test
    fun aRealPocketTopoFileReadsIntoASurvey() {
        val survey = PocketTopoImporter.read(CeiledUpTopFile.BYTES, "Ceiled Up")

        // Twelve stations named 1.0 upwards, eleven legs between them, and fifty-seven splays.
        assertEquals(12, survey.getAllStations().size)
        assertEquals("1.0", survey.origin.name)
        assertEquals(11, survey.getAllLegs().count { it.hasDestination() })
        assertEquals(57, survey.getAllLegs().count { !it.hasDestination() })

        val trip = assertNotNull(survey.trip)
        assertEquals("2013-07-02", trip.surveyDate.toString())
        assertTrue(trip.comments.contains("DistoX"), "the trip comment was \"${trip.comments}\"")

        // Every reading it read is one a surveyor could have taken. Two of the splays are 0/0/0,
        // which is what PocketTopo writes for a shot that was started and not taken; they are kept
        // rather than dropped, because the Java keeps them and a splay is not load-bearing.
        for (leg in survey.getAllLegs()) {
            assertTrue(leg.distance >= 0f, "a leg of ${leg.distance} m")
            assertTrue(leg.azimuth in 0f..360f, "a bearing of ${leg.azimuth}")
            assertTrue(leg.inclination in -90f..90f, "an inclination of ${leg.inclination}")
        }
    }

    @Test
    fun aRealPocketTopoFileBringsItsDrawingsIn() {
        val survey = PocketTopoImporter.read(CeiledUpTopFile.BYTES, "Ceiled Up")

        // A real drawing: a hundred and sixty-two strokes on the plan and forty-one on the
        // elevation. This is the assertion that would notice the element loop losing its place.
        assertEquals(162, survey.planSketch.pathDetails.size)
        assertEquals(41, survey.elevationSketch.pathDetails.size)
        for (path in survey.planSketch.pathDetails + survey.elevationSketch.pathDetails) {
            assertTrue(path.path.isNotEmpty(), "an empty stroke was kept")
        }
    }
}
