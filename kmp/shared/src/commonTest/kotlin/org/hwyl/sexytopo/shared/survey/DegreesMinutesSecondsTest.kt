package org.hwyl.sexytopo.shared.survey

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Angles as a compass reads them. */
class DegreesMinutesSecondsTest {

    private fun assertNear(expected: Float, actual: Float?, message: String = "") {
        assertTrue(
            actual != null && abs(actual - expected) < 0.0001f,
            "$message expected $expected, was $actual",
        )
    }

    @Test
    fun theOrdinaryCase() {
        assertNear(123.5f, DegreesMinutesSeconds.toDecimal("123", "30", ""))
        assertNear(123.5125f, DegreesMinutesSeconds.toDecimal("123", "30", "45"))
        assertNear(90f, DegreesMinutesSeconds.toDecimal("90", "", ""))
    }

    /** Blank minutes and seconds are zero, as upstream has them; blank degrees is not a reading. */
    @Test
    fun whatCountsAsBlank() {
        assertNear(7f, DegreesMinutesSeconds.toDecimal("7", "", ""))
        assertNull(DegreesMinutesSeconds.toDecimal("", "30", "0"))
        assertNull(DegreesMinutesSeconds.toDecimal("-", "30", "0"))
        assertNull(DegreesMinutesSeconds.toDecimal("123", "half", ""))
    }

    /** A downward shot: the minutes are a magnitude and the degrees carry the direction. */
    @Test
    fun aDownwardShotGoesDownwards() {
        assertNear(-5.5f, DegreesMinutesSeconds.toDecimal("-5", "30", ""))
        assertNear(-5.5125f, DegreesMinutesSeconds.toDecimal("-5", "30", "45"))
    }

    /**
     * The one upstream gets wrong: a shot less than a degree below horizontal.
     *
     * `EditLegForm.getInclination` takes its sign from `degrees < 0`, and `-0.0f < 0` is false, so
     * 0° 30′ *down* is recorded as 0° 30′ *up*, with no other way to type that angle since minutes
     * and seconds are documented there as always positive. See finding 54.
     */
    @Test
    fun aShotJustBelowHorizontalGoesDownAndNotUp() {
        assertNear(
            -0.5f,
            DegreesMinutesSeconds.toDecimal("-0", "30", ""),
            "the sign was taken from the parsed number rather than the typed text:",
        )
        assertNear(-0.008333f, DegreesMinutesSeconds.toDecimal("-0", "0", "30"))
        assertNear(0.5f, DegreesMinutesSeconds.toDecimal("0", "30", ""))
    }

    /** A minus in the minutes is a typo, not a direction: refused rather than silently added. */
    @Test
    fun aNegativeMinuteIsRefused() {
        assertNull(DegreesMinutesSeconds.toDecimal("5", "-30", ""))
        assertNull(DegreesMinutesSeconds.toDecimal("5", "", "-30"))
    }

    /** A comma is a decimal point, as it is everywhere else a number is typed here. */
    @Test
    fun aCommaIsADecimalPoint() {
        assertNear(-5.5f, DegreesMinutesSeconds.toDecimal("-5", "30", ""))
        assertNear(0.0083f, DegreesMinutesSeconds.toDecimal("0", "0", "30"), "seconds")
        assertNear(123.5f, DegreesMinutesSeconds.toDecimal("123", "30", "0,0"))
    }

    @Test
    fun andBackAgain() {
        val parts = DegreesMinutesSeconds.of(123.5125f)
        assertEquals(123, parts.degrees)
        assertEquals(30, parts.minutes)
        assertEquals(45, parts.seconds)
        assertTrue(!parts.negative)
        assertEquals("123", parts.degreesText)
    }

    /** A downward angle keeps its sign on the degrees field, which is where a surveyor types it. */
    @Test
    fun aDownwardAngleComesBackDownward() {
        val parts = DegreesMinutesSeconds.of(-5.5f)
        assertEquals(5, parts.degrees)
        assertEquals(30, parts.minutes)
        assertTrue(parts.negative)
        assertEquals("-5", parts.degreesText)
    }

    /**
     * And the case that has no sign to keep: a shot half a degree down.
     *
     * The reason [DegreesMinutesSeconds.Parts] carries [DegreesMinutesSeconds.Parts.negative]
     * separately rather than signing the degrees — a signed zero cannot survive being written into
     * a text field, which is finding 54 from the display end.
     */
    @Test
    fun aShallowDownwardAngleStillReadsAsDownward() {
        val parts = DegreesMinutesSeconds.of(-0.5f)
        assertEquals(0, parts.degrees)
        assertEquals(30, parts.minutes)
        assertTrue(parts.negative)
        assertEquals("-0", parts.degreesText, "0 would read as half a degree up")
    }

    /** Rounding carries, so no field ever shows 60. */
    @Test
    fun roundingCarriesRatherThanShowingSixty() {
        val parts = DegreesMinutesSeconds.of(0.99999f)
        assertEquals(1, parts.degrees)
        assertEquals(0, parts.minutes)
        assertEquals(0, parts.seconds)
    }

    @Test
    fun whatIsShownIsWhatComesBack() {
        for (angle in listOf(0f, 0.5f, -0.5f, 123.5125f, -89.9f, 359.99f, -0.0083f)) {
            val parts = DegreesMinutesSeconds.of(angle)
            val back =
                DegreesMinutesSeconds.toDecimal(
                    parts.degreesText,
                    parts.minutesText,
                    parts.secondsText,
                )
            assertNear(angle, back, "$angle went out as ${parts.degreesText} ${parts.minutesText} ${parts.secondsText} and")
        }
    }
}
