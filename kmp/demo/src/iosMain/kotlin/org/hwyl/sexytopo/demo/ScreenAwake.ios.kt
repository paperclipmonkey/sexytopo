package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/**
 * iOS calls this the idle timer, and it is a property of the whole application rather than of a
 * view, so it is put back on the way out: leaving it disabled would keep the *next* app's screen
 * awake too, and drain a battery a caver may be relying on.
 */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}
