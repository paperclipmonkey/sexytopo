package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.imports.ByteReader
import org.hwyl.sexytopo.shared.io.imports.PocketTopoFile
import org.hwyl.sexytopo.shared.io.imports.PocketTopoFormatException
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The primitives PocketTopo's binary `.top` format is made of.
 *
 * Every assertion here is one the Android app's own `PocketTopoFileTest` makes, run on three
 * targets rather than one — which matters more than usual for this file, because it is all bit
 * shifts and sign extension and Kotlin's `Short` is not Java's `short` in every expression.
 */
class PocketTopoFileTest {

    private fun reader(vararg bytes: Int) = ByteReader(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun aByteIsUnsigned() {
        assertEquals(0xAB, reader(0xAB).readByte())
    }

    @Test
    fun readingPastTheEndIsAnError() {
        assertFailsWith<PocketTopoFormatException> { ByteReader(ByteArray(0)).readByte() }
    }

    @Test
    fun sixteenBitNumbersAreLittleEndianAndSigned() {
        assertEquals(0, reader(0x00, 0x00).readInt16())
        assertEquals(0x0102, reader(0x02, 0x01).readInt16())
        assertEquals(-1, reader(0xFF, 0xFF).readInt16())
    }

    @Test
    fun thirtyTwoBitNumbersAreLittleEndianAndSigned() {
        assertEquals(0, reader(0, 0, 0, 0).readInt32())
        assertEquals(0x01020304, reader(0x04, 0x03, 0x02, 0x01).readInt32())
        assertEquals(-1, reader(0xFF, 0xFF, 0xFF, 0xFF).readInt32())
    }

    @Test
    fun sixtyFourBitNumbersAreLittleEndianToo() {
        assertEquals(0L, reader(0, 0, 0, 0, 0, 0, 0, 0).readInt64())
        assertEquals(
            0x0000000100000002L,
            reader(0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00).readInt64(),
        )
    }

    @Test
    fun anEmptyStringIsOneZeroByte() {
        assertEquals("", reader(0x00).readString())
    }

    @Test
    fun aShortStringIsALengthAndItsBytes() {
        assertEquals("abc", reader(0x03, 0x61, 0x62, 0x63).readString())
    }

    /** .NET writes the length seven bits at a time, so 128 takes two bytes rather than one. */
    @Test
    fun aLongStringHasAMultiByteLength() {
        val bytes = ByteArray(2 + 128)
        bytes[0] = 0x80.toByte()
        bytes[1] = 0x01
        for (i in 0 until 128) bytes[2 + i] = 'A'.code.toByte()

        val text = ByteReader(bytes).readString()

        assertEquals(128, text.length)
        assertTrue(text.all { it == 'A' })
    }

    @Test
    fun aStringIsUtf8() {
        // "café" is five bytes for four characters.
        val bytes = byteArrayOf(5, 0x63, 0x61, 0x66, 0xC3.toByte(), 0xA9.toByte())
        assertEquals("café", ByteReader(bytes).readString())
    }

    /**
     * Two ways a corrupt file can claim a string it has not got. The Java loops on the length
     * prefix with no width guard and hands whatever comes out to `new byte[length]`.
     */
    @Test
    fun aStringLongerThanTheFileIsAnErrorRatherThanARead() {
        assertFailsWith<PocketTopoFormatException> { reader(0x7F, 0x61).readString() }
        assertFailsWith<PocketTopoFormatException> {
            reader(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80).readString()
        }
    }

    @Test
    fun theUndefinedIdIsASplaysMissingFarEnd() {
        assertNull(PocketTopoFile.idToName(0x80000000.toInt()))
        assertNull(reader(0x00, 0x00, 0x00, 0x80).readId())
    }

    @Test
    fun aNegativeIdIsAPlainNumber() {
        assertEquals("0", PocketTopoFile.idToName(0x80000001L.toInt()))
        assertEquals("1", PocketTopoFile.idToName(0x80000002L.toInt()))
        assertEquals("10", PocketTopoFile.idToName(0x8000000BL.toInt()))
        assertEquals("0", reader(0x01, 0x00, 0x00, 0x80).readId())
    }

    @Test
    fun anythingElseIsAMajorAndAMinorPackedIntoTheTwoHalves() {
        assertEquals("0.0", PocketTopoFile.idToName(0x00000000))
        assertEquals("1.0", PocketTopoFile.idToName(0x00010000))
        assertEquals("1.2", PocketTopoFile.idToName(0x00010002))
        assertEquals("1.0", reader(0x00, 0x00, 0x01, 0x00).readId())
    }

    /** The same sixteen bits, read unsigned. North is 0, east is 0x4000. */
    @Test
    fun aBearingRunsAllTheWayRound() {
        assertClose(0f, PocketTopoFile.azimuthToDegrees(0))
        assertClose(90f, PocketTopoFile.azimuthToDegrees(0x4000))
        assertClose(180f, PocketTopoFile.azimuthToDegrees(0x8000.toShort()))
        assertClose(270f, PocketTopoFile.azimuthToDegrees(0xC000.toShort()))
    }

    /** The same sixteen bits, read signed. Up is 0x4000 and down is 0xC000. */
    @Test
    fun anInclinationGoesBothWays() {
        assertClose(0f, PocketTopoFile.inclinationToDegrees(0))
        assertClose(90f, PocketTopoFile.inclinationToDegrees(0x4000))
        assertClose(-90f, PocketTopoFile.inclinationToDegrees(0xC000.toShort()))
    }

    @Test
    fun distancesAreMillimetres() {
        assertClose(0f, PocketTopoFile.distanceToMetres(0), 0.001f)
        assertClose(1f, PocketTopoFile.distanceToMetres(1000), 0.001f)
        assertClose(3.5f, PocketTopoFile.distanceToMetres(3500), 0.001f)
    }

    @Test
    fun colourBytesAreTheSevenPocketTopoPens() {
        assertEquals(Colour.BLACK, PocketTopoFile.topoColourToColour(1))
        assertEquals(Colour.GREY, PocketTopoFile.topoColourToColour(2))
        assertEquals(Colour.BROWN, PocketTopoFile.topoColourToColour(3))
        assertEquals(Colour.BLUE, PocketTopoFile.topoColourToColour(4))
        assertEquals(Colour.RED, PocketTopoFile.topoColourToColour(5))
        assertEquals(Colour.GREEN, PocketTopoFile.topoColourToColour(6))
        assertEquals(Colour.ORANGE, PocketTopoFile.topoColourToColour(7))
        assertEquals(Colour.BLACK, PocketTopoFile.topoColourToColour(99))
    }

    /**
     * The Java hands the milliseconds to `new Date()` and lets the platform do the calendar. There
     * is no platform here, so the civil-from-days conversion is written out — and therefore has to
     * be tested rather than trusted.
     */
    @Test
    fun ticksAtTheEpochAreTheFirstOfJanuaryNineteenSeventy() {
        assertEquals(SurveyDate(1970, 1, 1), PocketTopoFile.ticksToDate(PocketTopoFile.TICKS_AT_EPOCH))
    }

    private fun ticksFor(daysAfterEpoch: Long): Long =
        PocketTopoFile.TICKS_AT_EPOCH +
            daysAfterEpoch * 86400000L * PocketTopoFile.TICKS_PER_MILLISECOND

    @Test
    fun aKnownDateComesOutRight() {
        // 1 July 2005 is 12965 days after 1 January 1970.
        assertEquals(SurveyDate(2005, 7, 1), PocketTopoFile.ticksToDate(ticksFor(12965)))
    }

    @Test
    fun leapYearsAndCenturiesAreHandled() {
        // 29 February 2000 - a leap year because 2000 is divisible by 400 - is day 11016.
        assertEquals(SurveyDate(2000, 2, 29), PocketTopoFile.ticksToDate(ticksFor(11016)))
        // 1 March 1900 would have been 29 February in a naive leap rule: 1900 is divisible by 100
        // and not by 400, so it is not a leap year.
        assertEquals(SurveyDate(1900, 3, 1), PocketTopoFile.ticksToDate(ticksFor(-25508)))
    }

    /** A survey from before 1970 is far-fetched; a date that lands a day out is not. */
    @Test
    fun aDateBeforeTheEpochDoesNotRoundTowardsZero() {
        assertEquals(SurveyDate(1969, 12, 31), PocketTopoFile.ticksToDate(ticksFor(-1)))
        assertEquals(SurveyDate(1969, 12, 30), PocketTopoFile.ticksToDate(ticksFor(-2)))
    }

    @Test
    fun aRunOfConsecutiveDaysStaysConsecutive() {
        // Across a year end, which is where an off-by-one shows up.
        assertEquals(SurveyDate(2023, 12, 31), PocketTopoFile.ticksToDate(ticksFor(19722)))
        assertEquals(SurveyDate(2024, 1, 1), PocketTopoFile.ticksToDate(ticksFor(19723)))
        assertEquals(SurveyDate(2024, 2, 29), PocketTopoFile.ticksToDate(ticksFor(19782)))
        assertEquals(SurveyDate(2024, 3, 1), PocketTopoFile.ticksToDate(ticksFor(19783)))
    }
}
