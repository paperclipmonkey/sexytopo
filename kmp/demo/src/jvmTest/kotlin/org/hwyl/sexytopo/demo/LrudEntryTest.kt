package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.InputMode
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Passage size from a tape rather than an instrument: four numbers per station, and the app works
 * out the directions. This is how a survey gets booked when there is no DistoX in the party, and
 * it is what makes cross-sections work on a hand-booked survey.
 */
class LrudEntryTest {

    /** Station 2 mid-passage, so left and right have a bearing to be square to. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        return survey
    }

    @Test
    fun fourNumbersMakeFourSplays() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(4, addLruds(survey, middle, listOf("1.5", "2", "3", "0.5")))
        assertEquals(4, middle.getUnconnectedOnwardLegs().size)
    }

    /**
     * Left and right go square to the passage; up and down are vertical. A survey running due
     * north therefore has its walls at 270 and 90.
     */
    @Test
    fun theDirectionsAreWorkedOutFromThePassage() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        addLruds(survey, middle, listOf("1.5", "2", "3", "0.5"))
        val splays = middle.getUnconnectedOnwardLegs()

        assertEquals(270f, splays[0].azimuth)
        assertEquals(90f, splays[1].azimuth)
        assertEquals(90f, splays[2].inclination)
        assertEquals(-90f, splays[3].inclination)
    }

    /** Only the fields that were filled in. */
    @Test
    fun blanksAddNothing() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(1, addLruds(survey, middle, listOf("", "2", "  ", "")))
        assertEquals(1, middle.getUnconnectedOnwardLegs().size)
    }

    /**
     * A zero is what somebody types for a wall they are standing against, and [Leg] rejects a
     * non-positive distance by throwing — so it has to be skipped rather than passed on.
     */
    @Test
    fun aZeroOrNonsenseIsSkippedRatherThanThrowing() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        assertEquals(0, addLruds(survey, middle, listOf("0", "-1", "wide", "")))
        assertTrue(middle.getUnconnectedOnwardLegs().isEmpty())
    }

    @Test
    fun aCommaIsAcceptedAsADecimalPoint() {
        val survey = passage()
        val middle = survey.getStationByName("2")!!

        addLruds(survey, middle, listOf("1,5", "", "", ""))

        assertEquals(1.5f, middle.getUnconnectedOnwardLegs().single().distance)
    }

    /**
     * The passage size goes on the station the surveyor is standing at, not the one they just made.
     *
     * This is the rule that would be wrong silently. A reading that promotes moves the active
     * station to the far end of the shot, so LRUDs attached after the leg would land on the *new*
     * station — putting the walls of this chamber around the next one. Nothing in the numbers
     * afterwards says so: they are ordinary splays either way, on a station that really exists, at
     * a bearing that really was measured. It would come out as a drawing that is subtly the wrong
     * shape, and nobody would know which trip it happened on.
     *
     * Upstream does the same thing more visibly, shuffling `survey.setActiveStation` back to the
     * from-station around its own LRUD calls and forward again afterwards.
     */
    @Test
    fun thePassageIsMeasuredWhereTheSurveyorIsStanding() {
        val survey = passage()
        val standingAt = survey.activeStation
        // One reading in a survey whose repeat count is one, so it promotes immediately and moves
        // the active station on: exactly the case that gets this wrong.
        val settings = SurveySettings.DEFAULT.copy(numberOfRepeatsForNewStation = 1)

        val added =
            addTypedReading(
                survey = survey,
                leg = Leg(8f, 90f, 0f),
                asSplay = false,
                lrud = listOf("1", "2", "3", "4"),
                inputMode = InputMode.FORWARD,
                settings = settings,
            )

        assertEquals(4, added, "four typed measurements should make four splays")
        assertTrue(
            survey.activeStation != standingAt,
            "the reading should have promoted, or this check proves nothing",
        )
        assertEquals(
            4,
            standingAt.getUnconnectedOnwardLegs().size,
            "the passage was measured at the station the surveyor was standing at",
        )
        assertEquals(
            0,
            survey.activeStation.getUnconnectedOnwardLegs().size,
            "and not at the one the reading created",
        )
    }

    /** A splay takes no passage size with it, as upstream's own splay dialog has no fields. */
    @Test
    fun aSplayBooksNoPassageSize() {
        val survey = passage()
        val standingAt = survey.activeStation

        val added =
            addTypedReading(
                survey = survey,
                leg = Leg(3f, 45f, 0f),
                asSplay = true,
                lrud = emptyList(),
                inputMode = InputMode.FORWARD,
                settings = SurveySettings.DEFAULT,
            )

        assertEquals(0, added)
        assertEquals(
            1,
            standingAt.getUnconnectedOnwardLegs().size,
            "the splay itself is still recorded, and only it",
        )
    }

    /** With the fields turned off nothing is passed, and nothing extra is recorded. */
    @Test
    fun noPassageSizeTypedIsNoPassageSizeRecorded() {
        val survey = passage()
        val standingAt = survey.activeStation

        addTypedReading(
            survey = survey,
            leg = Leg(8f, 90f, 0f),
            asSplay = false,
            lrud = emptyList(),
            inputMode = InputMode.FORWARD,
            settings = SurveySettings.DEFAULT.copy(numberOfRepeatsForNewStation = 1),
        )

        assertEquals(0, standingAt.getUnconnectedOnwardLegs().size)
    }
}
