package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore

/**
 * The settings that are about the app rather than about surveying.
 *
 * A separate file from the tolerances for the same reason the Android app has a separate
 * `preferences_general.xml`: these do not change what a reading means, and somebody adjusting their
 * instrument's tolerances is not looking for them.
 */
data class AppPreferences(
    /** Buzz when three readings promote to a station. `pref_vibrate_on_new_station`. */
    val buzzOnNewStation: Boolean = DEFAULT_BUZZ_ON_NEW_STATION,
    /** A touch in a corner of the sketch pans it, whatever tool is selected. `pref_hot_corners`. */
    val hotCorners: Boolean = DEFAULT_HOT_CORNERS,
    /** A two-fingered drag pans the sketch, likewise. `pref_two_finger_movement`. */
    val twoFingerMove: Boolean = DEFAULT_TWO_FINGER_MOVE,
) {
    companion object {
        /**
         * On, which is what `preferences_general.xml` says and what the Android settings screen
         * shows — see the note in [AppPreferencesStore] about the two disagreeing.
         */
        const val DEFAULT_BUZZ_ON_NEW_STATION = true

        /** `GeneralPreferences.isHotCornersModeActive` reads this key defaulting to true. */
        const val DEFAULT_HOT_CORNERS = true

        /**
         * Off, as in `GeneralPreferences.isTwoFingerModeActive`.
         *
         * A surveyor holding the phone in one hand rests a second finger on the glass more often
         * than they mean to pan with it, which is presumably why the Android app ships this off
         * while shipping the corners on. Pinch-to-zoom is not gated on it: that is always live, in
         * this port as in the original, where `ScaleGestureDetector` runs ahead of every tool.
         */
        const val DEFAULT_TWO_FINGER_MOVE = false

        val DEFAULT = AppPreferences()
    }
}

/**
 * Remembering them between runs, in the same plain `key=value` shape as [SurveySettingsStore].
 *
 * ## The default is the one the Android settings screen shows, not the one it uses
 *
 * `preferences_general.xml` declares `android:defaultValue="true"` for
 * `pref_vibrate_on_new_station`, so the checkbox appears ticked. But nothing in the app calls
 * `PreferenceManager.setDefaultValues`, so on a fresh install the key is simply absent, and
 * `NewStationNotificationService` reads it with `getBoolean(key, false)` — which returns false.
 * The settings screen therefore says vibration is on while it is off, until somebody toggles it
 * twice. This port takes the screen at its word.
 */
object AppPreferencesStore {

    val PATH = listOf("preferences.txt")

    fun format(preferences: AppPreferences): String =
        buildString {
            appendLine("buzzOnNewStation=${preferences.buzzOnNewStation}")
            appendLine("hotCorners=${preferences.hotCorners}")
            appendLine("twoFingerMove=${preferences.twoFingerMove}")
        }

    fun parse(text: String): AppPreferences {
        val values =
            text.lineSequence()
                .mapNotNull { line ->
                    val key = line.substringBefore('=', "").trim()
                    if (key.isEmpty() || '=' !in line) null else key to line.substringAfter('=').trim()
                }
                .toMap()

        return AppPreferences(
            // `toBooleanStrictOrNull` rather than `toBoolean`, which reads anything that is not
            // "true" as false - including a typo, and including a value a later version wrote.
            buzzOnNewStation =
                values["buzzOnNewStation"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_BUZZ_ON_NEW_STATION,
            hotCorners =
                values["hotCorners"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_HOT_CORNERS,
            twoFingerMove =
                values["twoFingerMove"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_TWO_FINGER_MOVE,
        )
    }

    /** Never throws: browser storage can be disabled, and a missing file means the defaults. */
    fun load(store: FileStore): AppPreferences =
        runCatching { store.readText(PATH)?.let(::parse) }.getOrNull() ?: AppPreferences.DEFAULT

    fun save(store: FileStore, preferences: AppPreferences): Boolean =
        runCatching { store.writeText(PATH, format(preferences)) }.isSuccess
}
