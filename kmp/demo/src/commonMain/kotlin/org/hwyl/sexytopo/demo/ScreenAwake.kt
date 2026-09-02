package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable

/**
 * Stops the screen turning itself off while the app is open.
 *
 * The browser build instead requests the Screen Wake Lock API in `index.html`, since that API must
 * be requested from a user gesture and doesn't fit here. This is the native half.
 */
@Composable
expect fun KeepScreenAwake()
