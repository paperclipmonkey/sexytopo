package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * One reading from a satellite receiver: where it thinks it is, and how well it knows.
 *
 * Degrees and metres, whatever the platform's own units were, so that everything above this is
 * written once. The accuracies are metres at about one standard deviation — which is what both
 * iOS and Android report, and roughly what a receiver means by the circle it draws on a map.
 *
 * Altitude is nullable because a receiver can have a good position and no height at all: a browser
 * often omits it, and iOS says so with a negative vertical accuracy. Sea level rather than the
 * WGS84 ellipsoid, for the reason set out on `StationFix` — the two differ by about fifty metres
 * in Britain, which is deeper than most of the caves in it.
 */
class GpsReading(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val horizontalAccuracy: Double?,
    val verticalAccuracy: Double?,
)

/**
 * What the receiver is doing: the last reading, and why there is not one.
 *
 * Both together rather than a reading alone, which is what [rememberDeviceHeading] returns and
 * what this started as. A compass that says nothing is a tablet without a magnetometer and there
 * is nothing to be done about it; a receiver that says nothing is one of four things — no
 * receiver, permission refused, permission not yet answered, or a sky with no satellites in it —
 * and three of those are things the surveyor can act on if somebody tells them.
 */
class Positioning(
    val reading: GpsReading? = null,
    /** Said on screen when there is no reading. Null while things are merely still settling. */
    val trouble: String? = null,
)

/**
 * Watches the satellite receiver while [enabled], delivering readings as they arrive.
 *
 * Composable and keyed on [enabled] for [rememberDeviceHeading]'s reason: a receiver left running
 * is one of the most expensive things a phone can do, and a screen that has been closed should
 * stop it rather than leave it to a garbage collector. Nothing here is asked for until a surveyor
 * opens the screen that wants it.
 *
 * The readings arrive at whatever rate the platform produces them, and they get *better*: the
 * first one out of a cold receiver may be a hundred metres out, and two minutes later the same
 * spot reads to five. Which is why the screen keeps the best one it has seen rather than the last,
 * and why this hands over everything rather than filtering.
 */
@Composable
expect fun rememberPosition(enabled: Boolean): State<Positioning>

/**
 * Why this device cannot take a position at all. Empty where it can.
 *
 * [whyNoScanner]'s counterpart, and it is the *permanent* answer rather than the momentary one —
 * a desktop with no receiver, a browser build that does not ask. A phone that has one and has not
 * been given permission is not this: that is [Positioning.trouble], which can change while the
 * screen is open, and this cannot.
 */
expect fun whyNoPosition(): String
