package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * `DeviceOrientationEvent`, which is the browser's version of the phone's compass.
 *
 * The event is delivered as fast as the sensor produces it, which is faster than a compass rose
 * needs redrawing, so the listener parks the latest reading and this reads it back on a timer. Ten
 * times a second is smooth to look at and costs nothing; it also means a heading that has not
 * changed writes the same value back into the same state, which Compose treats as no change at
 * all and does not redraw for.
 *
 * Two event names because the browsers disagree. Chrome fires `deviceorientationabsolute` with an
 * `alpha` measured from north; Safari fires plain `deviceorientation` carrying its own
 * `webkitCompassHeading`, and its `alpha` is measured from wherever the page happened to start —
 * useless for a compass, which is why an `alpha` is only believed when the event says it is
 * absolute.
 *
 * None of this works over plain HTTP, on any browser: the orientation sensors are a secure
 * context feature. The app is served over HTTPS, and `localhost` counts as secure, so this only
 * bites somebody serving a build off a bare IP address.
 */
@Composable
actual fun rememberDeviceHeading(enabled: Boolean): State<Float?> {
    val heading = remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            heading.value = null
            return@LaunchedEffect
        }
        startWatchingOrientation()
        try {
            while (true) {
                val deviceTop = deviceTopHeading()
                heading.value =
                    if (deviceTop.isNaN()) null
                    else screenHeading(deviceTop.toFloat(), screenAngle().toFloat())
                delay(HEADING_POLL_MS)
            }
        } finally {
            stopWatchingOrientation()
            heading.value = null
        }
    }

    return heading
}

/** Ten readings a second: smoother than a compass needle settles, and far cheaper than a frame. */
private const val HEADING_POLL_MS = 100L

/**
 * Installs the listener, or notes another caller for the one already installed.
 *
 * Reference counted because two canvases can be composed at once — the plan behind a cross-section
 * editor over it — and without the count the second one closing would take the first one's compass
 * away with it.
 *
 * iOS Safari will not deliver these events at all until the page has asked for them, and will only
 * accept the asking from inside a user gesture. So the ask is hung off the next tap rather than
 * made here, where it would be refused; on every other browser `requestPermission` does not exist
 * and nothing is hung off anything.
 */
private fun startWatchingOrientation(): Unit =
    js(
        """(function () {
            if (window.__sexytopoCompass) {
                window.__sexytopoCompass.watchers++;
                return;
            }

            var state = { heading: NaN, watchers: 1, handler: null };

            state.handler = function (event) {
                if (typeof event.webkitCompassHeading === 'number' &&
                        !isNaN(event.webkitCompassHeading)) {
                    // Safari's own, already clockwise from magnetic north.
                    state.heading = event.webkitCompassHeading;
                } else if (event.absolute === true && typeof event.alpha === 'number') {
                    // alpha turns anticlockwise from north, so a heading is what is left of a
                    // full circle after it.
                    state.heading = 360 - event.alpha;
                }
            };

            window.addEventListener('deviceorientationabsolute', state.handler, true);
            window.addEventListener('deviceorientation', state.handler, true);
            window.__sexytopoCompass = state;

            if (typeof DeviceOrientationEvent !== 'undefined' &&
                    typeof DeviceOrientationEvent.requestPermission === 'function') {
                var ask = function () {
                    window.removeEventListener('click', ask);
                    window.removeEventListener('touchend', ask);
                    try {
                        DeviceOrientationEvent.requestPermission();
                    } catch (e) {
                        // Refused, or asked from somewhere the browser did not count as a
                        // gesture. The arrow keeps pointing north-up, which is what it did
                        // before there was a compass at all.
                    }
                };
                window.addEventListener('click', ask);
                window.addEventListener('touchend', ask);
            }
        })()""",
    )

/** Drops this caller's claim on the listener, and unhooks it once nobody is left holding one. */
private fun stopWatchingOrientation(): Unit =
    js(
        """(function () {
            var state = window.__sexytopoCompass;
            if (!state) { return; }
            state.watchers--;
            if (state.watchers > 0) { return; }
            window.removeEventListener('deviceorientationabsolute', state.handler, true);
            window.removeEventListener('deviceorientation', state.handler, true);
            window.__sexytopoCompass = null;
        })()""",
    )

/** The latest heading for the top of the device, or NaN if nothing has reported one. */
private fun deviceTopHeading(): Double =
    js("(window.__sexytopoCompass ? window.__sexytopoCompass.heading : NaN)")

/**
 * The quarter turn the page has been rotated through, in the same sense Android's display rotation
 * counts. `window.orientation` is the old name for it and reports -90 where the modern property
 * reports 270, so it is folded back into a full circle before being believed.
 */
private fun screenAngle(): Double =
    js(
        """(function () {
            if (typeof screen !== 'undefined' && screen.orientation &&
                    typeof screen.orientation.angle === 'number') {
                return screen.orientation.angle;
            }
            if (typeof window.orientation === 'number') {
                return ((window.orientation % 360) + 360) % 360;
            }
            return 0;
        })()""",
    )
