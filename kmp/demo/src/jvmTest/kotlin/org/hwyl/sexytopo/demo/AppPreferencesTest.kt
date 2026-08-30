package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's own settings, and the one thing they currently control.
 */
class AppPreferencesTest {

    @Test
    fun theDefaultIsWhatTheAndroidSettingsScreenShows() {
        // `preferences_general.xml` declares defaultValue="true"; nothing calls setDefaultValues,
        // so the service's own getBoolean(key, false) wins on a fresh install and the screen and
        // the behaviour disagree. This port takes the screen at its word.
        assertTrue(AppPreferences.DEFAULT.buzzOnNewStation)
    }

    @Test
    fun aPreferenceSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(buzzOnNewStation = false))

        assertFalse(AppPreferencesStore.load(store).buzzOnNewStation)
    }

    /**
     * The three sketch-movement preferences keep the Android app's own defaults, which are not all
     * the same: the corners are on, a two-fingered drag is off, and following the survey is off.
     */
    @Test
    fun theSketchMovementDefaultsAreTheAndroidApps() {
        assertTrue(AppPreferences.DEFAULT.hotCorners, "pref_hot_corners defaults to true")
        assertFalse(
            AppPreferences.DEFAULT.twoFingerMove,
            "pref_two_finger_movement defaults to false",
        )
        assertFalse(AppPreferences.DEFAULT.autoRecentre, "AUTO_RECENTRE defaults to false")
    }

    @Test
    fun everyPreferenceSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        // Each one flipped away from its default, so a value that failed to round-trip and fell
        // back to the default would be a failure rather than an accident.
        val flipped =
            AppPreferences(
                buzzOnNewStation = false,
                hotCorners = false,
                twoFingerMove = true,
                autoRecentre = true,
            )
        AppPreferencesStore.save(store, flipped)

        assertEquals(flipped, AppPreferencesStore.load(store))
    }

    /**
     * The settings screen shows three preferences and the drawing menu sets a fourth. Building the
     * saved value from the three on screen resets the fourth to its default with nothing to say so
     * — which is what happened: turning *Follow the survey* on and then adjusting a tolerance
     * turned it off again.
     */
    @Test
    fun savingTheSettingsScreenLeavesThePreferencesItDoesNotShowAlone() {
        val current = AppPreferences(autoRecentre = true)
        val saved = preferencesFrom(current, buzzOnNewStation = false, hotCorners = false, twoFingerMove = true)

        assertTrue(saved.autoRecentre, "the drawing menu's preference is not this screen's to reset")
        assertFalse(saved.buzzOnNewStation)
        assertFalse(saved.hotCorners)
        assertTrue(saved.twoFingerMove)
    }

    @Test
    fun anAbsentFileMeansTheDefaults() {
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.load(InMemoryFileStore()))
    }

    /** A corrupt preferences file must not stop the app opening. */
    @Test
    fun rubbishReadsAsTheDefaults() {
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.parse("!!! not settings !!!"))
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.parse(""))
        // Not "true" and not "false": a typo, or something a later version wrote. `toBoolean`
        // would read this as false, which silently turns a setting off.
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.parse("buzzOnNewStation=yes"))
    }

    @Test
    fun aFileFromALaterVersionStillLoads() {
        val loaded = AppPreferencesStore.parse("buzzOnNewStation=false\nsomethingNew=42\n")
        assertFalse(loaded.buzzOnNewStation)
    }

    /**
     * The buzz has to happen when the *station* is made, not on every reading — a buzz per shot
     * would be three per leg and would stop meaning anything.
     */
    @Test
    fun theCallbackFiresOnceAStationIsMadeAndNotBefore() {
        val survey = Survey("Test")
        val session = SurveySession(survey)
        var buzzes = 0
        session.onStationCreated = { buzzes++ }

        // The simulator shoots each leg three times, as a surveyor does; only the third promotes.
        session.takeReading()
        assertEquals(0, buzzes, "a buzz on the first reading of a leg")
        session.takeReading()
        assertEquals(0, buzzes, "a buzz on the second reading of a leg")
        session.takeReading()

        assertEquals(1, buzzes, "expected one buzz for one station, not one per reading")
        assertEquals(2, survey.getAllStations().size)
    }
}
