package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable

/**
 * Stops the screen turning itself off while the app is open.
 *
 * Not a nicety. Sketching a passage is minutes of looking and seconds of drawing, and a phone that
 * blanks after thirty seconds has to be woken - with wet, muddy, gloved hands, in the dark - before
 * every single stroke. It is the difference between an app somebody uses underground and one they
 * put back in the tackle sack.
 *
 * The browser build does this in `index.html` with the Screen Wake Lock API, which has to be
 * requested from a user gesture and so does not fit here. This is the native half.
 */
@Composable
expect fun KeepScreenAwake()
