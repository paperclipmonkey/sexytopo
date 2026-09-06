package org.hwyl.sexytopo.shared.model.survey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A position, and the ways one can be typed wrong.
 *
 * The screen that collects this has two sources — a receiver, which cannot produce nonsense, and a
 * surveyor with a keyboard, who can. So everything here is about the second: what a typed
 * coordinate is allowed to be, what it is not, and what happens to the ones in between.
 */
class StationFixTest {

    /**
     * Decimal degrees, with a comma taken as a decimal point.
     *
     * The comma is not a nicety. Continental Europe writes `54,12345`, has a keyboard that offers
     * a comma, and is where a great many of the people using this app are; `toDoubleOrNull` reads
     * a dot and nothing else, so without this a French surveyor's perfectly good coordinate is
     * simply refused with no explanation of what it wanted instead.
     */
    @Test
    fun aCoordinateIsDecimalDegreesAndACommaIsADecimalPoint() {
        assertEquals(54.12345, StationFix.parseLatitude("54.12345"))
        assertEquals(54.12345, StationFix.parseLatitude("54,12345"))
        assertEquals(-2.5, StationFix.parseLongitude(" -2.5 "))
        assertEquals(312.0, StationFix.parseAltitude("312"))
        assertEquals(-3.5, StationFix.parseAltitude("-3,5"))
    }

    /**
     * A grouped number is refused rather than silently read as a different one.
     *
     * `1,234.5` is a thousands separator and not a coordinate anybody meant, so the comma rule
     * above deliberately does not apply where there is already a dot. Reading it as 1.2345 would
     * be a hundred kilometres of error introduced by punctuation.
     */
    @Test
    fun aNumberWithBothSeparatorsIsRefused() {
        assertNull(StationFix.parseLatitude("1,234.5"))
    }

    /**
     * Degrees and minutes are refused outright, which is the point.
     *
     * A paper map says 54° 07.4'. Half-supporting that — taking `54 07.4` and reading the digits
     * after the space as a decimal — gives 54.074 degrees, which is seven hundred metres from
     * where the surveyor is standing and looks entirely plausible. Refusing it sends them to
     * convert it properly.
     */
    @Test
    fun degreesAndMinutesAreRefusedRatherThanMisread() {
        assertNull(StationFix.parseLatitude("54 07.4"))
        assertNull(StationFix.parseLatitude("54° 07.4' N"))
        assertNull(StationFix.parseLatitude("N54.1234"))
    }

    /** Nothing typed, or nothing numeric, is nothing rather than zero. */
    @Test
    fun anEmptyOrUnreadableCoordinateIsNotZero() {
        assertNull(StationFix.parseLatitude(""))
        assertNull(StationFix.parseLatitude("   "))
        assertNull(StationFix.parseLatitude("north a bit"))
        assertNull(StationFix.parseAltitude(""))
    }

    /**
     * A coordinate off the ends of the earth is refused, and the two limits are different.
     *
     * Ninety and a hundred and eighty, not both one or both the other: a latitude of 100 is the
     * commonest way a longitude gets typed into the wrong box, and it is only catchable because
     * the two ranges differ.
     */
    @Test
    fun aCoordinateOffTheEndsOfTheEarthIsRefused() {
        assertNull(StationFix.parseLatitude("91"))
        assertNull(StationFix.parseLatitude("-90.1"))
        assertEquals(90.0, StationFix.parseLatitude("90"))

        assertNull(StationFix.parseLongitude("180.1"))
        assertEquals(180.0, StationFix.parseLongitude("180"))
        // The one a mixed-up pair produces, and the reason the limits are not shared.
        assertEquals(100.0, StationFix.parseLongitude("100"))
        assertNull(StationFix.parseLatitude("100"))
    }

    /** Infinities and not-a-numbers get in through the same door as any other text, and out again. */
    @Test
    fun aCoordinateHasToBeAFiniteNumber() {
        assertNull(StationFix.parseLatitude("NaN"))
        assertNull(StationFix.parseLatitude("Infinity"))
        assertNull(StationFix.parseAltitude("NaN"))
        assertNull(StationFix.parseAltitude("-Infinity"))
    }

    /** And the same rules hold when a fix is built rather than parsed. */
    @Test
    fun aFixCannotBeBuiltOutOfNonsense() {
        assertFailsWith<IllegalArgumentException> { fix(latitude = 91.0) }
        assertFailsWith<IllegalArgumentException> { fix(longitude = -181.0) }
        assertFailsWith<IllegalArgumentException> { fix(altitude = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { fix(station = " ") }
        assertFailsWith<IllegalArgumentException> { fix(horizontalAccuracy = -1.0) }
    }

    /**
     * The UTM zone a position falls in, which is what a survey program is told to compute in.
     *
     * Sixty zones of six degrees, counted from the antimeridian, so zone 31 starts at Greenwich
     * and Britain is mostly in 30. The hemisphere letter is the sign of the latitude and nothing
     * else — a zone is a band of longitude, and getting the letter from the longitude instead
     * would put every southern cave on the wrong grid by ten million metres.
     */
    @Test
    fun theUtmZoneIsTheBandOfLongitudeAndTheHemisphereIsTheLatitude() {
        assertEquals("30N", fix(latitude = 54.1, longitude = -2.3).utmZone)
        assertEquals("31N", fix(latitude = 51.5, longitude = 0.1).utmZone)
        // Just the other side of Greenwich, which is the boundary most likely to be off by one.
        assertEquals("30N", fix(latitude = 51.5, longitude = -0.1).utmZone)
        assertEquals("19S", fix(latitude = -33.4, longitude = -70.6).utmZone)
        assertEquals("1N", fix(latitude = 10.0, longitude = -180.0).utmZone)
        // The far end, where the arithmetic would otherwise give a sixty-first zone.
        assertEquals("60N", fix(latitude = 10.0, longitude = 180.0).utmZone)
        // The equator belongs to the northern zones by convention, and zero is not negative.
        assertEquals("31N", fix(latitude = 0.0, longitude = 3.0).utmZone)
    }

    private fun fix(
        station: String = "1",
        latitude: Double = 54.0,
        longitude: Double = -2.0,
        altitude: Double = 300.0,
        horizontalAccuracy: Double? = null,
    ) = StationFix(station, latitude, longitude, altitude, horizontalAccuracy)
}
