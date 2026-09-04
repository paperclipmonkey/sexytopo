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
 *
 * ## Why the ask happens on the way up as well as on the way down
 *
 * A browser will only open its on-screen keyboard for a page that has *transient user activation*,
 * and the HTML specification is picky about which events grant it: a touch grants it on `pointerup`
 * and `touchend`, and **not** on `pointerdown` or `touchstart`. So the press-time ask above, and
 * the focus request the dialog makes when it opens, can never open a keyboard that is shut — they
 * can only keep one open that already is, which is exactly the symptom: the box has a cursor in
 * it, and nothing to type with.
 *
 * Asking again when the finger lifts costs a no-op call on the platforms that were already working
 * and is the only moment a browser will listen. [askForTheKeyboard] is the rest of it, on the one
 * platform that needs more than asking Compose nicely.
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
            // Every pass here is Initial, so this runs before the text field's own tap detector
            // and consumes nothing: the caret still lands where the finger did.
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            keyboardController?.show()
            askForTheKeyboard()
        }
    }
}

/**
 * Open the on-screen keyboard, from inside the gesture that asked for it.
 *
 * A no-op wherever [LocalSoftwareKeyboardController] is enough, which is everywhere except the
 * browser. Call it only from a touch or click handler: off one, the platforms that gate this on a
 * user gesture will ignore it, which is the whole reason it exists.
 */
internal expect fun askForTheKeyboard()
