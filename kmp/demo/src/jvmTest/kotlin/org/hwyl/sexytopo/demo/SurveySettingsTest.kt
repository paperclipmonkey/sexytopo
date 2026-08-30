package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tolerances decide whether a survey happens at all, so these check the behaviour they buy
 * rather than only the plumbing.
 */
class SurveySettingsTest {

    /**
     * The reason the dialog exists. Three readings a hand-held compass would call identical are
     * more than 1.7 degrees apart, so under the DistoX defaults they stay splays for ever and the
     * surveyor gets no station and no explanation.
     */
    @Test
    fun loosenedTolerancesPromoteReadingsTheDefaultsRefuse() {
        val sloppy = listOf(Leg(5.0f, 90f, 0f), Leg(5.1f, 94f, 1f), Leg(4.9f, 86f, -1f))

        val strict = Survey("T")
        for (leg in sloppy) SurveyUpdater.update(strict, leg, settings = SurveySettings.DEFAULT)
        assertEquals(1, strict.getAllStations().size, "the defaults should refuse these")
        assertEquals(3, strict.origin.getUnconnectedOnwardLegs().size)

        val loose = Survey("T")
        val settings = SurveySettings.DEFAULT.copy(maxAngleDelta = 10f, maxDistanceDelta = 0.5f)
        for (leg in sloppy) SurveyUpdater.update(loose, leg, settings = settings)
        assertEquals(2, loose.getAllStations().size, "loosened tolerances should promote them")
    }

    /** Two readings instead of three, which is what a solo surveyor in a hurry will want. */
    @Test
    fun theNumberOfRepeatsIsHonoured() {
        val survey = Survey("T")
        val settings = SurveySettings.DEFAULT.copy(numberOfRepeatsForNewStation = 2)

        SurveyUpdater.update(survey, Leg(5f, 90f, 0f), settings = settings)
        assertEquals(1, survey.getAllStations().size)
        SurveyUpdater.update(survey, Leg(5.01f, 90.1f, 0f), settings = settings)
        assertEquals(2, survey.getAllStations().size)
    }

    // ---------------------------------------------------------------------------------------
    // Persistence: a surveyor sets these once, at the entrance
    // ---------------------------------------------------------------------------------------

    @Test
    fun settingsSurviveARoundTrip() {
        val settings =
            SurveySettings(
                legAmalgamationAlgorithm = LegAmalgamationAlgorithm.PAIRWISE,
                maxDistanceDelta = 0.25f,
                maxAngleDelta = 8f,
                maxEndpointDelta = 0.4f,
                maxPairwiseError = 0.2f,
                numberOfRepeatsForNewStation = 2,
            )

        assertEquals(settings, SurveySettingsStore.parse(SurveySettingsStore.format(settings)))
    }

    @Test
    fun settingsReachTheStoreAndComeBack() {
        val store = InMemoryFileStore()
        val settings = SurveySettings.DEFAULT.copy(maxAngleDelta = 5f)

        assertTrue(SurveySettingsStore.save(store, settings))
        assertEquals(settings, SurveySettingsStore.load(store))
    }

    /** No file yet is the normal first-run case, not an error. */
    @Test
    fun anAbsentFileMeansTheDefaults() {
        assertEquals(SurveySettings.DEFAULT, SurveySettingsStore.load(InMemoryFileStore()))
    }

    /**
     * A file from a later version, or a half-written one, must not stop the app starting. Anything
     * unreadable falls back to that field's default rather than to nothing.
     */
    @Test
    fun aDamagedFileDegradesToTheDefaults() {
        val parsed =
            SurveySettingsStore.parse(
                """
                algorithm=SOMETHING_NEW
                maxAngleDelta=not a number
                maxDistanceDelta=0.3
                somethingFromTheFuture=42
                """.trimIndent(),
            )

        assertEquals(LegAmalgamationAlgorithm.ANGULAR, parsed.legAmalgamationAlgorithm)
        assertEquals(SurveySettings.DEFAULT.maxAngleDelta, parsed.maxAngleDelta)
        assertEquals(0.3f, parsed.maxDistanceDelta)
    }

    // ---------------------------------------------------------------------------------------
    // What the dialog will accept
    // ---------------------------------------------------------------------------------------

    private fun from(
        distance: String = "0.05",
        angle: String = "1.7",
        repeats: String = "3",
    ) = settingsFrom(
        algorithm = LegAmalgamationAlgorithm.ANGULAR,
        distance = distance,
        angle = angle,
        endpoint = "0.1",
        pairwise = "0.05",
        repeats = repeats,
        current = SurveySettings.DEFAULT,
    )

    @Test
    fun aCommaIsAcceptedAsADecimalPoint() {
        assertEquals(2.5f, assertNotNull(from(angle = "2,5")).maxAngleDelta)
    }

    /** A negative tolerance refuses every reading; neither is recoverable from without knowing why. */
    @Test
    fun nonsenseIsRefusedRatherThanStored() {
        assertNull(from(angle = "-1"))
        assertNull(from(repeats = "0"))
        assertNull(from(distance = "wide"))
    }

    /** Switching algorithm to look at it and switching back must not reset the other tolerances. */
    @Test
    fun tolerancesTheChosenAlgorithmIgnoresAreCarriedAcross() {
        val current = SurveySettings.DEFAULT.copy(maxEndpointDelta = 0.42f)
        val edited =
            settingsFrom(
                algorithm = LegAmalgamationAlgorithm.ANGULAR,
                distance = "0.05",
                angle = "1.7",
                endpoint = current.maxEndpointDelta.toString(),
                pairwise = "0.05",
                repeats = "3",
                current = current,
            )

        assertEquals(0.42f, assertNotNull(edited).maxEndpointDelta)
    }
}
