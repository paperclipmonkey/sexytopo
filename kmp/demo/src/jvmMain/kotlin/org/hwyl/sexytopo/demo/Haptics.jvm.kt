package org.hwyl.sexytopo.demo

/**
 * Desktops do not vibrate. Reported honestly rather than beeped at, so the settings screen can say
 * so instead of offering a switch that does nothing.
 */
actual fun canBuzz(): Boolean = false

actual fun buzz(milliseconds: Int): Boolean = false
