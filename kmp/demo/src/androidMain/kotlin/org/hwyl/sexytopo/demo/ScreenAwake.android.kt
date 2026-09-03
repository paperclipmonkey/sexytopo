package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * The view's own flag rather than the window's, so this needs no Activity and no permission, and
 * unsets itself when the composition goes away.
 */
@Composable
actual fun KeepScreenAwake() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
