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
 * *Add a leg* and *Add a splay* from the Tools menu — `LegDialogs.addStation` and `addSplay`.
 *
 * The pair these are contrasted with is [addTypedReading], and the first test here is the reason
 * both exist: one typed reading through the instrument's rules is a splay, and the same reading
 * through this is a station. Neither is wrong; they answer different questions, and the app had
 * only the first.
 */
class AddLegOutrightTest {

    private fun cave(): Survey =
        Survey("Swildons").also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 90f, 0f)) }

    /**
     * Typed through the field bar, one reading in the default mode is *kept as a splay* — three
     * agreeing ones make a station, which is the rule an instrument's readings are held to and
     * which the dialog says out loud. The Android app's Tools menu does not go through that at
     * all: `SurveyUpdater.addLegFromStation` with a destination, no repeats, no waiting.
     */
    @Test
    fun theSameReadingIsASplayThroughOnePathAndAStationThroughTheOther() {
        val typed = cave()
        addTypedReading(
            survey = typed,
            leg = Leg(7f, 45f, 0f),
            asSplay = false,
            lrud = emptyList(),
            inputMode = InputMode.FORWARD,
            settings = SurveySettings.DEFAULT,
        )
        assertEquals(2, typed.getAllStations().size, "a typed reading should still be a splay")

        val outright = cave()
        addLegOutright(outright, Leg(7f, 45f, 0f), asSplay = false)

        assertEquals(3, outright.getAllStations().size, "the leg should have made its station")
    }

    @Test
    fun theSurveyMovesOnToTheNewStation() {
        val survey = cave()
        val from = survey.activeStation

        addLegOutright(survey, Leg(7f, 45f, 0f), asSplay = false)

        assertTrue(survey.activeStation != from, "the working end did not move on")
    }

    @Test
    fun theFarStationTakesTheNameItIsGiven() {
        val survey = cave()

        addLegOutright(survey, Leg(7f, 45f, 0f), asSplay = false, toName = "AV12")

        assertEquals("AV12", survey.activeStation.name)
    }

    @Test
    fun noNameMeansTheNameTheAppWouldHavePicked() {
        val survey = cave()

        addLegOutright(survey, Leg(7f, 45f, 0f), asSplay = false, toName = "   ")

        assertEquals("3", survey.activeStation.name)
    }

    /**
     * `advanceNumberIfNotUnique`, which upstream applies to typed names too. Two stations sharing a
     * name is a survey whose Survex and Therion exports name the wrong end of a passage — and
     * refusing the leg instead would lose a reading somebody has just taken.
     */
    @Test
    fun aNameThatIsAlreadyTakenIsAdvanced() {
        val survey = cave()

        addLegOutright(survey, Leg(7f, 45f, 0f), asSplay = false, toName = "2")

        assertEquals("3", survey.activeStation.name)
        assertEquals(3, survey.getAllStations().size)
    }

    /**
     * The same rule as the rename dialog, and it was missing here: *Add a leg* offers to name the
     * far station, so without this a leg to `sump 2` is accepted and then breaks every Survex and
     * Therion export the survey ever produces. Survex separates its columns with whitespace, so
     * that leg comes out as six fields where five are expected; a semicolon is worse, because it
     * starts a comment and the readings on the line are thrown away silently.
     */
    @Test
    fun aNameThatWouldBreakTheExportsIsRefused() {
        assertTrue(newStationNameProblem("sump 2") != null, "a space should be refused")
        assertTrue(newStationNameProblem("AV12;") != null, "a semicolon should be refused")
        assertTrue(newStationNameProblem("AV12") == null, "an ordinary name should be accepted")
    }

    /**
     * A blank name is not a problem, unlike in the rename dialog.
     *
     * It means "call it whatever you would have called it" — the box is pre-filled with exactly
     * that — so refusing it would be refusing the default.
     */
    @Test
    fun leavingTheNameAloneIsNotAProblem() {
        assertTrue(newStationNameProblem("") == null)
        assertTrue(newStationNameProblem("   ") == null)
    }

    /**
     * A name that collides only after `Station` has stripped it is still made unique.
     *
     * The uniqueness check runs on the *typed* string and the survey holds *stored* ones, and
     * `Station` strips newlines on the way in — so `AV\n12` compared against a survey that already
     * holds `AV12` finds no collision, and then stores `AV12` on top of it.
     */
    @Test
    fun aNameThatCollidesOnlyOnceStrippedIsStillAdvanced() {
        val survey = cave()
        addLegOutright(survey, Leg(7f, 45f, 0f), asSplay = false, toName = "AV12")
        assertEquals("AV12", survey.activeStation.name)

        addLegOutright(survey, Leg(3f, 200f, 0f), asSplay = false, toName = "AV\n12")

        assertEquals("AV13", survey.activeStation.name, "the stripped name was not made unique")
        assertEquals(
            1,
            survey.getAllStations().count { it.name == "AV12" },
            "the survey holds two stations of the same name",
        )
    }

    @Test
    fun theNoteReachesTheNewStation() {
        val survey = cave()

        addLegOutright(
            survey,
            Leg(7f, 45f, 0f),
            asSplay = false,
            toComment = "draughting hard",
        )

        assertEquals("draughting hard", survey.activeStation.comment)
    }

    @Test
    fun aSplayIsJustASplay() {
        val survey = cave()
        val from = survey.activeStation

        addLegOutright(survey, Leg(2f, 180f, 0f), asSplay = true, toName = "ignored")

        assertEquals(2, survey.getAllStations().size, "a splay should make no station")
        assertEquals(from, survey.activeStation, "a splay should not move the working end")
        assertEquals(1, from.getUnconnectedOnwardLegs().size)
    }

    /**
     * The same rule as [addTypedReading] and for the same reason: a tape is read from where you
     * are. Worth its own test here because the leg *always* moves the active station on in this
     * path, so reading the LRUDs off the active station afterwards would always be wrong.
     */
    @Test
    fun thePassageIsMeasuredWhereTheSurveyorIsStanding() {
        val survey = cave()
        val standingAt = survey.activeStation

        val added =
            addLegOutright(
                survey,
                Leg(7f, 45f, 0f),
                asSplay = false,
                lrud = listOf("1", "2", "3", "4"),
            )

        assertEquals(4, added)
        assertEquals(4, standingAt.getUnconnectedOnwardLegs().size)
        assertEquals(0, survey.activeStation.getUnconnectedOnwardLegs().size)
    }
}
