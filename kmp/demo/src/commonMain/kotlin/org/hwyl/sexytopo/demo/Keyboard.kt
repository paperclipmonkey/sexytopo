package org.hwyl.sexytopo.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The cursor already in the box when a dialog whose whole purpose is typing opens, and a retry on
 * every tap after that in case the keyboard never turned up.
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
 *
 * iOS's own keyboard-hosting service can occasionally come up cold and silently fail to attach to
 * the very first field focused in a session, on any app, native or not - which leaves this box
 * exactly as focused as it should be, cursor blinking, with no keyboard underneath. A plain
 * `UITextField` recovers from that on a second tap because UIKit re-asks for the keyboard on
 * every touch down, whether or not the field was already first responder; a Compose field does
 * not, since a tap on an already-focused field is just a caret move as far as Compose's focus
 * state is concerned. The `pointerInput` below re-asks anyway, on every tap, without consuming
 * the event - so the caret still moves normally underneath it - because there is no other way for
 * a surveyor mid-survey to unstick that OS-side failure themselves.
 */
@Composable
internal fun rememberOpeningFocus(): Modifier {
    val requester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        keyboardController?.show()
    }
    return Modifier.focusRequester(requester).pointerInput(keyboardController) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            keyboardController?.show()
        }
    }
}
