package org.hwyl.sexytopo.demo

import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackTypeSuccess

/**
 * A haptic rather than a buzz of a given length, which is a deliberate divergence.
 *
 * iOS exposes no public API for "vibrate for 200 milliseconds". `AudioServicesPlaySystemSound`
 * with the vibrate identifier is the closest thing and it is a fixed, long, phone-call-style buzz
 * that cannot be shortened. What iOS *does* have is the feedback generator, and the success
 * notification is exactly the meaning wanted here: the thing you did worked. On a phone in a
 * pocket or a chest harness it is felt as clearly as the Android buzz, so the duration parameter is
 * ignored rather than approximated.
 *
 * `prepare()` before `notificationOccurred` because the Taptic Engine takes a moment to spin up;
 * without it the first buzz of a trip is late or dropped, which is the one that has to arrive.
 */
private val generator = UINotificationFeedbackGenerator()

actual fun canBuzz(): Boolean = true

actual fun buzz(milliseconds: Int): Boolean {
    generator.prepare()
    generator.notificationOccurred(UINotificationFeedbackTypeSuccess)
    return true
}
