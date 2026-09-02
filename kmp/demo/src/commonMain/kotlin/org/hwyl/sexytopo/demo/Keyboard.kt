package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * The cursor already in the box when a dialog whose whole purpose is typing opens.
 *
 * Bringing up the iOS keyboard is not free the first time: UIKit has to start the keyboard's own
 * process, load the layout and negotiate a window — on a debug build that is measured in seconds,
 * per the system's own log (`Took 1.53s to get the token`). Asking for focus as the dialog
 * appears starts that wait while the surveyor's finger is still moving, rather than after a
 * second tap.
 *
 * [FocusRequester.requestFocus] throws if the requester is not yet attached to a node, which on
 * the dialog's first composition it is not — waiting one frame is what attaches it. The
 * `runCatching` is the belt: a focus request that loses a race must leave the surveyor tapping
 * the box rather than take the app down, since an uncaught throw ends the process on Kotlin/Native.
 */
@Composable
internal fun rememberOpeningFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return requester
}
