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
