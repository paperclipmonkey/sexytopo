package org.hwyl.sexytopo.demo

/**
 * A buzz to say a station has been made. Ported from `NewStationNotificationService`.
 *
 * Best-effort everywhere: a device with no vibrator, a browser that does not implement it, a
 * desktop. It reports whether it did anything so that the settings screen can say "this device
 * cannot" rather than offering a switch that does nothing.
 */
expect fun buzz(milliseconds: Int = NEW_STATION_BUZZ_MS): Boolean

/** Whether this platform can buzz at all, without buzzing to find out. */
expect fun canBuzz(): Boolean

/** `NewStationNotificationService.VIBRATE_FOR_MS`. */
const val NEW_STATION_BUZZ_MS = 200
