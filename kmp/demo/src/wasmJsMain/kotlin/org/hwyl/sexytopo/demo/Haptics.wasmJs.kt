package org.hwyl.sexytopo.demo

/**
 * `navigator.vibrate`, which Chrome on Android implements and Safari — on any platform — does not.
 *
 * So the browser build buzzes on the phone where Web Bluetooth also works, and quietly does not on
 * an iPhone, where the answer is the real iOS app rather than the web one.
 */
private fun vibrateFor(milliseconds: Int): Boolean =
    js(
        """{
          try {
            if (navigator.vibrate) { return navigator.vibrate(milliseconds) === true; }
            return false;
          } catch (e) {
            return false;
          }
        }""",
    )

private fun hasVibrate(): Boolean = js("(typeof navigator !== 'undefined' && !!navigator.vibrate)")

actual fun canBuzz(): Boolean = hasVibrate()

actual fun buzz(milliseconds: Int): Boolean = vibrateFor(milliseconds)
