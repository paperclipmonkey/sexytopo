package org.hwyl.sexytopo.demo

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arithmetic between a sensor and the arrow.
 *
 * Small, but the part that is wrong in every compass anybody has ever had to fix twice: an angle
 * that goes negative, and a phone turned on its side.
 */
class DeviceHeadingTest {

    @Test
    fun aBearingComesBackInTheRangeACompassReads() {
        assertEquals(0f, normaliseHeading(0f))
        assertEquals(359f, normaliseHeading(-1f))
        assertEquals(0f, normaliseHeading(360f))
        assertEquals(1f, normaliseHeading(361f))
        // Android's getOrientation reports the azimuth from -180 to 180, so half of every reading
        // it gives arrives here negative.
        assertEquals(270f, normaliseHeading(-90f))
        assertEquals(180f, normaliseHeading(-180f))
        // Several turns out, which nothing should produce but which must not produce nonsense.
        assertEquals(45f, normaliseHeading(-1035f))
    }

    @Test
    fun turningThePhoneOnItsSideTurnsTheHeadingWithIt() {
        // Upright: the top of the screen and the top of the device are the same thing.
        assertEquals(30f, screenHeading(deviceHeading = 30f, screenAngleDegrees = 0f))

        // Turned a quarter circle anticlockwise, the top of the screen is where the device's
        // right-hand edge was, which is a quarter circle further round the compass.
        assertEquals(120f, screenHeading(deviceHeading = 30f, screenAngleDegrees = 90f))

        assertEquals(210f, screenHeading(deviceHeading = 30f, screenAngleDegrees = 180f))
        assertEquals(300f, screenHeading(deviceHeading = 30f, screenAngleDegrees = 270f))
    }

    @Test
    fun theCorrectionWrapsRatherThanRunningOffTheEndOfTheCircle() {
        assertEquals(30f, screenHeading(deviceHeading = 300f, screenAngleDegrees = 90f))
        assertEquals(89f, screenHeading(deviceHeading = 179f, screenAngleDegrees = 270f))
    }
}
