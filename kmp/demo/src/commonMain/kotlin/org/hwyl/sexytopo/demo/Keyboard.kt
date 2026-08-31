package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * The cursor already in the box when a dialog whose whole purpose is typing opens.
 *
 * Every text dialog in this app used to open with nothing focused, so writing "Sump" on the sketch
 * was: tap the label tool, tap the paper, tap the box, wait for the keyboard, type. The third tap
 * is the one worth removing - not because it is a tap, but because of what it costs on iOS.
 *
 * ## Why this matters more on a phone than it looks
 *
 * Bringing up the iOS keyboard is not free the first time: UIKit has to start the keyboard's own
 * process, load the layout and the predictive bar, and negotiate a window. On a debug build that
 * is measured in seconds, and the system's own log says so - `Took 1.53s to get the token`,
 * `System gesture gate timed out` - none of it from this app's code, and none of it avoidable.
 * What *is* avoidable is starting that work one tap later than necessary. Asking for focus as the
 * dialog appears begins the wait while the surveyor's finger is still moving, instead of after it
 * has landed a second time.
 *
 * Underground the same delay is worse than it sounds: a surveyor holding a phone in one hand with
 * a tape in the other, in the wet, does not know whether a tap registered, so a keyboard that
 * takes two seconds gets tapped again - which dismisses the field they just focused.
 *
 * ## Why the frame, and why the catch
 *
 * [FocusRequester.requestFocus] throws if the requester is not yet attached to a node, and on the
 * dialog's first composition it is not: the field is described but not yet laid out. Waiting one
 * frame is what makes it attached. The `runCatching` is the belt: a focus request that loses a
 * race must leave the surveyor tapping the box, which is what they did before, and not take the
 * app down - on Kotlin/Native an uncaught throw ends the process.
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
