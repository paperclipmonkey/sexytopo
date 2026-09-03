package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable

/**
 * Nothing to do here: the Screen Wake Lock API has to be requested from a user gesture, so the
 * browser build asks for it from `index.html` on the first tap instead.
 */
@Composable
actual fun KeepScreenAwake() = Unit
