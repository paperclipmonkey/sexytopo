package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trip details are only worth entering if they come out the other end, so these check the exports
 * rather than the model: a `*team` line in Therion and Survex is the whole point of the dialog.
 */
class TripDetailsTest {

    private fun surveyWithALeg(): Survey =
        Survey("Swildons").also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 90f, 0f)) }

    @Test
    fun aTripIsBuiltFromWhatWasTyped() {
        val trip =
            tripFrom(
                date = "2026-09-05",
                team = listOf(Trip.TeamEntry("L. Waterworth", listOf(Trip.Role.BOOK))),
                instrument = " DistoX2 ",
                comments = " wet ",
                copyrightHolder = " BEC ",
                licence = " CC-BY-SA-4.0 ",
            )

        assertNotNull(trip)
        assertEquals(SurveyDate(2026, 9, 5), trip.surveyDate)
        assertEquals("DistoX2", trip.instrument)
        assertEquals("wet", trip.comments)
        assertEquals("BEC", trip.copyrightHolder)
        assertEquals("CC-BY-SA-4.0", trip.licence)
        assertEquals(listOf(Trip.Role.BOOK), trip.team.single().roles)
    }

    @Test
    fun anUnparseableDateMakesNoTrip() {
        assertNull(tripFrom("last Tuesday", emptyList(), "", "", "", ""))
    }

    /** An empty name would emit `*team ""`, which Therion accepts and no human can read. */
    @Test
    fun blankPeopleAreDropped() {
        val trip =
            tripFrom(
                date = "2026-09-05",
                team = listOf(Trip.TeamEntry("  "), Trip.TeamEntry("R. Smith")),
                instrument = "",
                comments = "",
                copyrightHolder = "",
                licence = "",
            )

        assertEquals(listOf("R. Smith"), assertNotNull(trip).team.map { it.name })
    }

    @Test
    fun theTeamReachesTherionAndSurvex() {
        val survey = surveyWithALeg()
        survey.trip =
            tripFrom(
                date = "2026-09-05",
                team =
                    listOf(
                        Trip.TeamEntry("L. Waterworth", listOf(Trip.Role.BOOK)),
                        Trip.TeamEntry("R. Smith", listOf(Trip.Role.INSTRUMENTS, Trip.Role.DOG)),
                    ),
                instrument = "DistoX2",
                comments = "",
                copyrightHolder = "",
                licence = "",
            )

        val therion = TherionExporter.export(survey, createdOn = "2026-09-05")
        assertContains(therion, "L. Waterworth")
        assertContains(therion, "R. Smith")
        assertContains(therion, "DistoX2")
        assertContains(therion, "2026.09.05")

        val survex = SurvexExporter.export(survey, createdOn = "2026-09-05")
        assertContains(survex, "L. Waterworth")
        assertContains(survex, "R. Smith")
    }

    /**
     * The exporters take the whole metadata block from `survey.trip`, and a survey with none at
     * all still has to export — which is what every survey did before this dialog existed.
     */
    @Test
    fun aSurveyWithNoTripStillExports() {
        val survex = SurvexExporter.export(surveyWithALeg(), createdOn = "2026-09-05")
        assertTrue(survex.startsWith("*begin Swildons"))
    }

    /** Sentence case in the UI, shouting in the file format. */
    @Test
    fun roleLabelsAreReadable() {
        assertEquals("Instruments", labelFor(Trip.Role.INSTRUMENTS))
        assertEquals("Explo", labelFor(Trip.Role.EXPLORATION))
    }
}
