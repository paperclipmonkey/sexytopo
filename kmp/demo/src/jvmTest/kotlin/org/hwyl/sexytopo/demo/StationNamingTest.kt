package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Naming a station is where the ported `SurveyUpdater.renameStation` would throw at a surveyor, so
 * the dialog has to decide before it calls.
 */
class StationNamingTest {

    private fun twoStations(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        return survey
    }

    @Test
    fun aFreshNameIsAccepted() {
        val survey = twoStations()
        assertNull(renameProblem(survey, survey.origin, "Sump"))
    }

    @Test
    fun keepingTheNameIsNotAClash() {
        val survey = twoStations()
        // renameStation rejects a rename to the station's own name, so the dialog must not read
        // "unchanged" as "taken" — that would make Save unpressable whenever only the comment
        // changed.
        assertNull(renameProblem(survey, survey.origin, survey.origin.name))
    }

    @Test
    fun aNameAlreadyInUseIsRefusedRatherThanThrown() {
        val survey = twoStations()
        val problem = renameProblem(survey, survey.origin, "2")
        assertNotNull(problem)
        assertEquals("There is already a station called 2", problem)
    }

    @Test
    fun anEmptyNameIsRefused() {
        val survey = twoStations()
        assertNotNull(renameProblem(survey, survey.origin, "   "))
    }

    /**
     * The ported `renameStation` checks the raw string while [org.hwyl.sexytopo.shared.model
     * .survey.Station] stores a newline-stripped one, so "2\n" passes its check and then collides.
     * The dialog checks what will actually be stored, which closes that without touching the port.
     */
    @Test
    fun aNameThatOnlyDiffersByANewlineIsStillAClash() {
        val survey = twoStations()
        assertNotNull(renameProblem(survey, survey.origin, "2\n"))
    }

    @Test
    fun savingAppliesTheNameCommentAndDirection() {
        val survey = twoStations()
        val station = survey.origin

        applyStationEdit(survey, station, " Sump ", "too tight", ExtendedElevationDirection.LEFT)

        assertEquals("Sump", station.name)
        assertEquals("too tight", station.comment)
        assertEquals(ExtendedElevationDirection.LEFT, station.extendedElevationDirection)
        assertEquals(station, survey.getStationByName("Sump"))
    }

    @Test
    fun savingWithoutRenamingStillTakesTheComment() {
        val survey = twoStations()
        val station = survey.origin

        applyStationEdit(survey, station, station.name, "draughting", station.extendedElevationDirection)

        assertEquals("draughting", station.comment)
    }
}
