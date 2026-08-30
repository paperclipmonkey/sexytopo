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
    /** Put the view back on the active station each time one is made. `AUTO_RECENTRE`. */
    val autoRecentre: Boolean = DEFAULT_AUTO_RECENTRE,
    /** Draw everything but the working end at a fifth alpha. `FADE_NON_ACTIVE`. */
    val fadeNonActive: Boolean = DEFAULT_FADE_NON_ACTIVE,
    /** Draw the leg just taken in magenta. `pref_highlight_latest_leg`. */
    val highlightLatestLeg: Boolean = DEFAULT_HIGHLIGHT_LATEST_LEG,
    /** Stamp the water symbol in blue whatever colour the brush is. `BLUE_WATER`. */
    val blueWater: Boolean = DEFAULT_BLUE_WATER,
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

        /**
         * Off, as `SketchPreferences.Toggle.AUTO_RECENTRE` is.
         *
         * Worth turning on for a long passage, though, and worth knowing why: without it this port
         * re-fits the *whole cave* as the survey grows, so by the fiftieth station the working end
         * is a few pixels across and the surveyor is pinching in after every leg. Auto-recentre
         * keeps the active station in the middle at the zoom they chose instead.
         */
        const val DEFAULT_AUTO_RECENTRE = false

        /**
         * Off, as `SketchPreferences.Toggle.FADE_NON_ACTIVE` is.
         *
         * What it is for: in a cave of any size the plan is a page of red lines that all look
         * alike, and the question a surveyor keeps asking is "where am I on this". Fading
         * everything that does not hang off the working station answers it without moving the
         * view, which matters when the sketch around you is the part you are drawing.
         */
        const val DEFAULT_FADE_NON_ACTIVE = false

        /** On, as `GeneralPreferences.isHighlightLatestLegModeOn` is. */
        const val DEFAULT_HIGHLIGHT_LATEST_LEG = true

        /**
         * On, as `SketchPreferences.Toggle.BLUE_WATER` is.
         *
         * Water is drawn blue by convention on every cave survey ever published, and a surveyor
         * who has the brush set to black for wall outlines should not have to remember to change
         * it and change it back to stamp a stream.
         */
        const val DEFAULT_BLUE_WATER = true

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
            appendLine("autoRecentre=${preferences.autoRecentre}")
            appendLine("fadeNonActive=${preferences.fadeNonActive}")
            appendLine("highlightLatestLeg=${preferences.highlightLatestLeg}")
            appendLine("blueWater=${preferences.blueWater}")
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
            autoRecentre =
                values["autoRecentre"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_AUTO_RECENTRE,
            fadeNonActive =
                values["fadeNonActive"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_FADE_NON_ACTIVE,
            highlightLatestLeg =
                values["highlightLatestLeg"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_HIGHLIGHT_LATEST_LEG,
            blueWater =
                values["blueWater"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_BLUE_WATER,
        )
    }

    /** Never throws: browser storage can be disabled, and a missing file means the defaults. */
    fun load(store: FileStore): AppPreferences =
        runCatching { store.readText(PATH)?.let(::parse) }.getOrNull() ?: AppPreferences.DEFAULT

    fun save(store: FileStore, preferences: AppPreferences): Boolean =
        runCatching { store.writeText(PATH, format(preferences)) }.isSuccess
}
