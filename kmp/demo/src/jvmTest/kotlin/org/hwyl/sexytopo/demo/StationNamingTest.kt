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

    /** 1 → 2 → 3 → 4, all heading east, so every station starts on the default direction. */
    private fun fourStations(): Survey {
        val survey = Survey("T")
        repeat(3) { SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f)) }
        return survey
    }

    /**
     * Sending a station left sends everything beyond it left too.
     *
     * This is what the direction *means*. An extended elevation unrolls the cave onto a line, and
     * at a junction the surveyor says which way the next passage is drawn — so the answer applies
     * to that passage, not to one leg of it. `SurveyUpdater.setExtendedElevationDirection` walks
     * the subtree for exactly this reason, and the dialog was setting the field on the one station
     * instead: mark a junction left and the leg into it flipped while everything past it carried
     * on to the right, which is a drawing that is wrong and does not look wrong.
     */
    @Test
    fun sendingAStationLeftSendsThePassageBeyondItLeftToo() {
        val survey = fourStations()
        val junction = survey.getStationByName("2")!!

        applyStationEdit(survey, junction, junction.name, "", ExtendedElevationDirection.LEFT)

        for (name in listOf("2", "3", "4")) {
            assertEquals(
                ExtendedElevationDirection.LEFT,
                survey.getStationByName(name)!!.extendedElevationDirection,
                "station $name should have gone left with the passage",
            )
        }
    }

    /** And not the passage before it: the surveyor marked a junction, not the whole cave. */
    @Test
    fun theStationsAboveTheJunctionAreLeftAlone() {
        val survey = fourStations()
        val junction = survey.getStationByName("2")!!

        applyStationEdit(survey, junction, junction.name, "", ExtendedElevationDirection.LEFT)

        assertEquals(
            ExtendedElevationDirection.RIGHT,
            survey.origin.extendedElevationDirection,
        )
    }

    /**
     * Vertical is the exception, and the model already says so: [ExtendedElevationDirection]
     * carries a `propagates` flag which is false for it. A pitch is drawn from its height change
     * alone and says nothing about which way the passage at the bottom goes, so the survey resumes
     * whatever it was doing.
     */
    @Test
    fun aPitchAppliesToItsOwnLegAndNoFurther() {
        val survey = fourStations()
        val head = survey.getStationByName("2")!!

        applyStationEdit(survey, head, head.name, "", ExtendedElevationDirection.VERTICAL)

        assertEquals(ExtendedElevationDirection.VERTICAL, head.extendedElevationDirection)
        for (name in listOf("3", "4")) {
            assertEquals(
                ExtendedElevationDirection.RIGHT,
                survey.getStationByName(name)!!.extendedElevationDirection,
                "station $name is below a pitch, not on it",
            )
        }
    }

    /** A direction that has not changed must not quietly re-flood the subtree under it. */
    @Test
    fun leavingTheDirectionAloneLeavesTheSubtreeAlone() {
        val survey = fourStations()
        val junction = survey.getStationByName("2")!!
        val below = survey.getStationByName("3")!!
        // Somebody sent this one branch the other way earlier in the trip.
        below.extendedElevationDirection = ExtendedElevationDirection.LEFT

        applyStationEdit(survey, junction, junction.name, "sump", junction.extendedElevationDirection)

        assertEquals("sump", junction.comment)
        assertEquals(ExtendedElevationDirection.LEFT, below.extendedElevationDirection)
    }
}
