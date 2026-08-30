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
) {
    companion object {
        /**
         * On, which is what `preferences_general.xml` says and what the Android settings screen
         * shows — see the note in [AppPreferencesStore] about the two disagreeing.
         */
        const val DEFAULT_BUZZ_ON_NEW_STATION = true

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
        buildString { appendLine("buzzOnNewStation=${preferences.buzzOnNewStation}") }

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
        )
    }

    /** Never throws: browser storage can be disabled, and a missing file means the defaults. */
    fun load(store: FileStore): AppPreferences =
        runCatching { store.readText(PATH)?.let(::parse) }.getOrNull() ?: AppPreferences.DEFAULT

    fun save(store: FileStore, preferences: AppPreferences): Boolean =
        runCatching { store.writeText(PATH, format(preferences)) }.isSuccess
}
