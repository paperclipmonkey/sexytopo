package org.hwyl.sexytopo.demo

import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * A haptic rather than a buzz of a given length, which is a deliberate divergence.
 *
 * iOS exposes no public API for "vibrate for 200 milliseconds". `AudioServicesPlaySystemSound` with
 * the vibrate identifier is the closest thing and it is a fixed, long, phone-call-style buzz that
 * cannot be shortened. What iOS *does* have is the feedback generator, and the success notification
 * is exactly the meaning wanted here: the thing you did worked. On a phone in a pocket or a chest
 * harness it is felt as clearly as the Android buzz, so the duration parameter is ignored rather
 * than approximated.
 *
 * `prepare()` before `notificationOccurred` because the Taptic Engine takes a moment to spin up;
 * without it the first buzz of a trip is late or dropped, which is the one that has to arrive.
 *
 * The feedback type is written out through its enum class rather than as a bare constant, which is
 * how Kotlin/Native exposes this particular `NS_ENUM`. Not every Objective-C enum is mapped that
 * way — `CBManagerStatePoweredOn` next door in `CoreBluetoothTransport` is a top-level constant —
 * and the difference is invisible until a macOS runner says so.
 */
private val generator = UINotificationFeedbackGenerator()

actual fun canBuzz(): Boolean = true

actual fun buzz(milliseconds: Int): Boolean {
    generator.prepare()
    generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    return true
}
