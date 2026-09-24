package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * `navigator.geolocation`, watched and polled, exactly as the compass next door is.
 *
 * The browser hands positions to a callback and this reads the last one back on a timer, which is
 * `DeviceHeading.wasmJs.kt`'s arrangement and for the same reasons: a value that has not changed
 * gets written back into the same state, which Compose treats as no change and does not redraw
 * for, and the polling rate is the app's business rather than the sensor's.
 *
 * `enableHighAccuracy` is asked for, which on a phone means the satellite receiver rather than a
 * position guessed from wifi. At a cave entrance there is no wifi to guess from, so without it the
 * browser would report the nearest thing it had heard of — which can be a town away and which
 * arrives looking exactly like a real answer.
 *
 * Nothing works over plain HTTP: geolocation is a secure-context feature in every browser. The
 * app is served over HTTPS and `localhost` counts as secure, so this only bites somebody serving a
 * build off a bare IP address — the same footnote the compass carries.
 */
@Composable
actual fun rememberPosition(enabled: Boolean): State<Positioning> {
    val positioning = remember { mutableStateOf(Positioning()) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            positioning.value = Positioning()
            return@LaunchedEffect
        }
        startWatchingPosition()
        try {
            while (true) {
                val latitude = positionLatitude()
                val problem = positionProblem()
                positioning.value =
                    when {
                        !latitude.isNaN() ->
                            Positioning(
                                reading =
                                    GpsReading(
                                        latitude = latitude,
                                        longitude = positionLongitude(),
                                        altitude = positionAltitude().takeIf { !it.isNaN() },
                                        horizontalAccuracy =
                                            positionAccuracy().takeIf { !it.isNaN() },
                                        verticalAccuracy =
                                            positionAltitudeAccuracy().takeIf { !it.isNaN() },
                                    ),
                            )
                        problem.isNotEmpty() -> Positioning(trouble = problem)
                        else -> Positioning()
                    }
                delay(POSITION_POLL_MS)
            }
        } finally {
            stopWatchingPosition()
            positioning.value = Positioning()
        }
    }

    return positioning
}

/**
 * Empty, because a browser that cannot do this says so itself.
 *
 * Every browser worth the name has had `navigator.geolocation` for fifteen years; what varies is
 * whether the page is allowed to use it, and that is a refusal with a reason rather than a missing
 * feature — [Positioning.trouble] carries it, since it can change while the screen is open.
 */
actual fun whyNoPosition(): String = ""

/** Twice a second: a receiver settles over minutes, and nothing here is being animated. */
private const val POSITION_POLL_MS = 500L

/**
 * Starts the watch, or notes another caller for the one already running.
 *
 * Reference counted for the compass's reason — two screens can want it at once — and a *watch*
 * rather than a single `getCurrentPosition`, because the whole method is to stand still while the
 * receiver improves on its first answer.
 */
private fun startWatchingPosition(): Unit =
    js(
        """(function () {
            if (window.__sexytopoPosition) {
                window.__sexytopoPosition.watchers++;
                return;
            }

            var state = {
                watchers: 1,
                id: null,
                latitude: NaN,
                longitude: NaN,
                altitude: NaN,
                accuracy: NaN,
                altitudeAccuracy: NaN,
                problem: '',
            };

            if (!navigator.geolocation) {
                state.problem = 'This browser will not say where it is, so type the position in.';
                window.__sexytopoPosition = state;
                return;
            }

            state.id = navigator.geolocation.watchPosition(
                function (position) {
                    var c = position.coords;
                    state.latitude = c.latitude;
                    state.longitude = c.longitude;
                    // Null and undefined both mean the browser has no height for you; NaN is how
                    // that crosses into Kotlin as "no answer" rather than as sea level.
                    state.altitude = (c.altitude === null || c.altitude === undefined)
                        ? NaN : c.altitude;
                    state.accuracy = (c.accuracy === null || c.accuracy === undefined)
                        ? NaN : c.accuracy;
                    state.altitudeAccuracy =
                        (c.altitudeAccuracy === null || c.altitudeAccuracy === undefined)
                            ? NaN : c.altitudeAccuracy;
                    state.problem = '';
                },
                function (error) {
                    state.problem = error && error.code === 1
                        ? 'This page has not been allowed your location. Allow it in the '
                            + 'browser, or type the position in.'
                        : 'No position yet. Step into the open, or type the position in.';
                },
                { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 },
            );

            window.__sexytopoPosition = state;
        })()""",
    )

/** Drops this caller's claim, and stops the receiver once nobody is left holding one. */
private fun stopWatchingPosition(): Unit =
    js(
        """(function () {
            var state = window.__sexytopoPosition;
            if (!state) return;
            state.watchers--;
            if (state.watchers > 0) return;
            if (state.id !== null && navigator.geolocation) {
                navigator.geolocation.clearWatch(state.id);
            }
            delete window.__sexytopoPosition;
        })()""",
    )

private fun positionLatitude(): Double =
    js("window.__sexytopoPosition ? window.__sexytopoPosition.latitude : NaN")

private fun positionLongitude(): Double =
    js("window.__sexytopoPosition ? window.__sexytopoPosition.longitude : NaN")

private fun positionAltitude(): Double =
    js("window.__sexytopoPosition ? window.__sexytopoPosition.altitude : NaN")

private fun positionAccuracy(): Double =
    js("window.__sexytopoPosition ? window.__sexytopoPosition.accuracy : NaN")

private fun positionAltitudeAccuracy(): Double =
    js("window.__sexytopoPosition ? window.__sexytopoPosition.altitudeAccuracy : NaN")

private fun positionProblem(): String =
    js("window.__sexytopoPosition ? window.__sexytopoPosition.problem : ''")
