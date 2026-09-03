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
import kotlin.test.assertFalse
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

    @Test
    fun withNoExplorationDateGivenTheTripIsLinked() {
        val trip = tripFrom(date = "2026-09-05", team = emptyList(), instrument = "", comments = "", copyrightHolder = "", licence = "")

        assertTrue(assertNotNull(trip).explorationDateLinked)
        assertNull(trip.explorationDate)
    }

    /**
     * A dialog that always builds a fresh [Trip] on Save has to be told to carry an unlinked
     * exploration date forward, or it silently reverts to "same day" on every edit — which is
     * exactly what happened to a date read in from an imported file the moment anyone opened this
     * dialog and pressed Save without touching anything.
     */
    @Test
    fun anUnlinkedExplorationDateIsKept() {
        val trip =
            tripFrom(
                date = "2026-09-05",
                team = emptyList(),
                instrument = "",
                comments = "",
                copyrightHolder = "",
                licence = "",
                explorationDateLinked = false,
                explorationDate = "2025-04-12",
            )

        assertNotNull(trip)
        assertFalse(trip.explorationDateLinked)
        assertEquals(SurveyDate(2025, 4, 12), trip.explorationDate)
    }

    /**
     * Linked means "the field is not consulted at all" - [Trip.hasExplorationDate]'s own contract
     * - so whatever text happens to be left in the box, blank, garbage, *or a perfectly valid date
     * left over from before the surveyor re-linked the checkbox*, must not leak into the trip. A
     * guard that merely happened to rely on garbage failing to parse would not catch the stale but
     * well-formed case.
     */
    @Test
    fun aLinkedTripIgnoresAStaleButValidExplorationDate() {
        val trip =
            tripFrom(
                date = "2026-09-05",
                team = emptyList(),
                instrument = "",
                comments = "",
                copyrightHolder = "",
                licence = "",
                explorationDateLinked = true,
                explorationDate = "2020-01-01",
            )

        assertNotNull(trip)
        assertTrue(trip.explorationDateLinked)
        assertNull(trip.explorationDate)
    }

    @Test
    fun anUnlinkedExplorationDateReachesBothExporters() {
        val survey = surveyWithALeg()
        survey.trip =
            tripFrom(
                date = "2026-09-05",
                team = emptyList(),
                instrument = "",
                comments = "",
                copyrightHolder = "",
                licence = "",
                explorationDateLinked = false,
                explorationDate = "2025-04-12",
            )

        assertContains(TherionExporter.export(survey, createdOn = "2026-09-05"), "2025.04.12")
        assertContains(SurvexExporter.export(survey, createdOn = "2026-09-05"), "2025.04.12")
    }

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

    @Test
    fun aSurveyWithNoTripStillExports() {
        val survex = SurvexExporter.export(surveyWithALeg(), createdOn = "2026-09-05")
        assertTrue(survex.startsWith("*begin Swildons"))
    }

    @Test
    fun roleLabelsAreReadable() {
        assertEquals("Instruments", labelFor(Trip.Role.INSTRUMENTS))
        assertEquals("Explo", labelFor(Trip.Role.EXPLORATION))
    }
}
