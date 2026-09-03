package org.hwyl.sexytopo.demo

expect fun platformName(): String

/**
 * What one unit of [androidx.compose.ui.input.pointer.PointerInputChange.scrollDelta] is worth, in
 * screen pixels.
 *
 * Compose does not normalise a wheel across its targets, and the difference is two orders of
 * magnitude, so a single number tuned on one host is silently useless on the others. Measured
 * rather than assumed: in Chromium a `wheel` of `deltaY: 1` moves the drawing by exactly one unit
 * of `scrollDelta`, so the browser reports *pixels* - the same pixels the event carries - while
 * the Swing desktop reports *notches*, about one per click of a wheel.
 */
expect val scrollUnitInPixels: Float
