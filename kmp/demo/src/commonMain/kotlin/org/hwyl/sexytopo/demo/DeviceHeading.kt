package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Which way the top of the screen is pointing, in degrees clockwise from magnetic north, or null
 * where nothing on this platform can say.
 *
 * This is what makes the north arrow on the plan a *compass* rather than a label. The Android app
 * has had it since `GraphActivity` grew a `TYPE_ROTATION_VECTOR` listener; the port drew the arrow
 * but had nothing to turn it with, so it always pointed at the top of the screen.
 *
 * Null rather than zero for "no compass here". Zero is a real heading — facing north — and drawing
 * it would tell somebody at a desk that the arrow was live.
 *
 * [enabled] is the register/unregister pair from `GraphActivity.onResume` and `onPause`, expressed
 * as state: it is false whenever the arrow is not on screen, and the sensor stops with it. A
 * magnetometer left running is a flat battery, and a flat battery is the worst bug this app can
 * have underground.
 *
 * A [State] rather than a plain value so the canvas can read it in the *draw* phase. Read during
 * composition, a heading arriving several times a second would recompose the whole survey canvas
 * at that rate, which on a large cave is the difference between a compass and a slideshow.
 */
@Composable expect fun rememberDeviceHeading(enabled: Boolean): State<Float?>

/** A bearing folded back into what a compass reads: 0 up to, but not including, 360. */
fun normaliseHeading(degrees: Float): Float {
    val folded = degrees % 360f
    return if (folded < 0f) folded + 360f else folded
}

/**
 * The heading of the top of the *screen*, given the heading of the top of the *device*.
 *
 * The two are only the same in portrait. A magnetometer reports where the hardware is pointing and
 * knows nothing about which way round the picture is being drawn, so turning a phone on its side
 * swings the arrow through ninety degrees with it unless this is applied.
 *
 * [screenAngleDegrees] is the quarter turn the platform itself reports — the browser's
 * `screen.orientation.angle`, iOS's device orientation — and they all count anticlockwise
 * rotations of the device, which is why the correction adds rather than subtracts. Android is the
 * exception and does not come through here: `SensorManager.remapCoordinateSystem` does the same
 * job on the whole rotation matrix, which stays correct when the phone is tilted rather than flat,
 * and the Java already did it that way.
 */
fun screenHeading(deviceHeading: Float, screenAngleDegrees: Float): Float =
    normaliseHeading(deviceHeading + screenAngleDegrees)
