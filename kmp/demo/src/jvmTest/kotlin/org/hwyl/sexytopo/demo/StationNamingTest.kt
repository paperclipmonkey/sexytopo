package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * A name that would break the file the survey is exported into.
     *
     * `Station` strips only newlines, faithfully to the Java, and the Android rename form checks
     * only blank, `-` and uniqueness — so *sump 2* is accepted there, and the Survex exporter puts
     * station names into whitespace-separated columns. That leg comes out as six fields where
     * `*data normal from to tape compass clino` wants five, and Survex will not read the file. A
     * semicolon is worse: it starts a comment, so the readings after it are thrown away in
     * silence.
     *
     * A deliberate divergence, and the reason it is worth it is that the alternative is losing a
     * trip's numbers with nothing said. What this does not do is rewrite an imported name — this
     * app cannot repair somebody else's file by refusing to open it — so it stops the problem being
     * made rather than pretending it cannot exist.
     */
    @Test
    fun aNameThatWouldBreakTheExportIsRefusedWithAReason() {
        val survey = Survey("Swildons")

        val spaced = assertNotNull(renameProblem(survey, survey.origin, "sump 2"))
        assertTrue("space" in spaced, spaced)
        assertTrue("Survex" in spaced, spaced)

        val tabbed = assertNotNull(renameProblem(survey, survey.origin, "sump\t2"))
        assertTrue("space" in tabbed, "a tab is whitespace too: $tabbed")

        val semicolon = assertNotNull(renameProblem(survey, survey.origin, "sump;2"))
        assertTrue("semicolon" in semicolon, semicolon)
        assertTrue("comment" in semicolon, semicolon)
    }

    /** And the ordinary names a surveyor actually types are still fine. */
    @Test
    fun theNamesPeopleActuallyUseAreStillAccepted() {
        val survey = Survey("Swildons")
        for (name in listOf("Sump", "sump-2", "sump_2", "Entrance", "42", "P.12", "aven'top")) {
            assertNull(renameProblem(survey, survey.origin, name), "$name was refused")
        }
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
