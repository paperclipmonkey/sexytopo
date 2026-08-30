package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Everything that can be done to a reading once it is in the survey.
 *
 * Three of these — reverse, downgrade and promote — were ported into [SurveyUpdater] months before
 * anything could reach them, because the tests that came with the Java exercised them directly.
 * That is the failure mode being closed here: a survey engine that can do the right thing and a
 * surveyor with no way to ask for it.
 *
 * What is tested is mostly *which* actions are offered, since each wrong answer is a trap that only
 * springs underground: a downgrade offered on a leg with a cave hanging off it throws where the
 * Java greys the item out, and a promotion offered on the first splay of a survey has no leg above
 * to promote into.
 */
class LegActionsTest {

    /** 1 → 2 → 3, with a splay off 2, which is where most of these questions get interesting. */
    private fun passage(): Survey {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        SurveyBuilder.addSplay(survey, survey.getStationByName("2")!!, Leg(2f, 180f, 0f))
        return survey
    }

    private fun rowFor(survey: Survey, from: String, leg: Leg) =
        rowFor(survey.getStationByName(from)!!, leg)

    private fun legInto(survey: Survey, station: String): Leg =
        survey.getReferringLeg(survey.getStationByName(station)!!)!!

    private fun splayOff(survey: Survey, station: String): Leg =
        survey.getStationByName(station)!!.onwardLegs.first { !it.hasDestination() }

    // -------------------------------------------------------------------------------------
    // What is offered
    // -------------------------------------------------------------------------------------

    @Test
    fun aLegWithACaveBeyondItCannotBeMadeIntoASplay() {
        val survey = passage()
        val offered = legActionsFor(survey, rowFor(survey, "1", legInto(survey, "2")))

        assertFalse(
            LegAction.DOWNGRADE in offered,
            "station 3 hangs off the far end; downgradeLeg would fail its own check",
        )
        assertContains(offered, LegAction.REVERSE, "a full leg can always be turned round")
    }

    @Test
    fun theLastLegInTheSurveyCanBeMadeIntoASplayAgain() {
        val survey = passage()
        val offered = legActionsFor(survey, rowFor(survey, "2", legInto(survey, "3")))

        assertContains(offered, LegAction.DOWNGRADE, "nothing was surveyed beyond station 3")
    }

    @Test
    fun aSplayIsOfferedTheTwoWaysOfPromotingItAndNeitherOfTheLegOnes() {
        val survey = passage()
        val offered = legActionsFor(survey, rowFor(survey, "2", splayOff(survey, "2")))

        assertContains(offered, LegAction.UPGRADE)
        assertContains(offered, LegAction.PROMOTE)
        assertFalse(LegAction.REVERSE in offered, "reverseLeg is addressed by a destination station")
        assertFalse(LegAction.DOWNGRADE in offered, "it is already a splay")
    }

    @Test
    fun theFirstSplayOfASurveyHasNoLegAboveToJoin() {
        val survey = Survey("T")
        SurveyUpdater.update(survey, Leg(2f, 10f, 5f))
        val splay = survey.origin.onwardLegs.single()

        val offered = legActionsFor(survey, rowFor(survey, "1", splay))

        assertFalse(
            LegAction.PROMOTE in offered,
            "promoteToAboveLeg would return false; the Android app offers it and then toasts",
        )
        assertContains(offered, LegAction.UPGRADE, "it can still become a station of its own")
    }

    @Test
    fun everyRowCanBeEditedCommentedAndDeleted() {
        val survey = passage()
        val rows =
            listOf(
                rowFor(survey, "1", legInto(survey, "2")),
                rowFor(survey, "2", splayOff(survey, "2")),
            )
        for (row in rows) {
            val offered = legActionsFor(survey, row)
            assertContains(offered, LegAction.EDIT)
            assertContains(offered, LegAction.COMMENT)
            assertContains(offered, LegAction.DELETE)
        }
    }

    @Test
    fun aSplayAsksForASplayCommentAndALegForALegOne() {
        // The Android app switches the same menu item between two strings; getting them the wrong
        // way round would have a surveyor writing a note about a shot they did not take.
        assertEquals("Splay comment", LegAction.COMMENT.label(isSplay = true))
        assertEquals("Leg comment", LegAction.COMMENT.label(isSplay = false))
        assertEquals("Delete", LegAction.DELETE.label(isSplay = true))
    }

    // -------------------------------------------------------------------------------------
    // What they do
    // -------------------------------------------------------------------------------------

    @Test
    fun reversingALegPutsTheStationOnTheOtherSideOfItsParent() {
        val survey = passage()
        val before = legInto(survey, "2")
        assertEquals(0f, before.azimuth)

        SurveyUpdater.reverseLeg(survey, survey.getStationByName("2")!!)

        val after = legInto(survey, "2")
        assertEquals(180f, after.azimuth, "turned through 180")
        assertTrue(after.wasShotBackwards, "and recorded as having been shot from the far end")
        assertSame(
            survey.getStationByName("2"),
            after.destination,
            "the station it arrives at is unchanged - this is a correction, not a re-survey",
        )
    }

    @Test
    fun aReversedLegIsShownInTheTableTheWayItWasTaken() {
        val survey = passage()
        SurveyUpdater.reverseLeg(survey, survey.getStationByName("2")!!)

        val row = rowFor(survey, "1", legInto(survey, "2"))

        assertEquals("2", row.from, "the reading was taken standing at 2")
        assertEquals("1", row.to)
        assertEquals("0.00", row.azimuth, "and shown as the surveyor read it, not as it is stored")
    }

    @Test
    fun downgradingALegGivesBackTheSplayAndTakesTheStationAway() {
        val survey = passage()
        val leg = legInto(survey, "3")

        SurveyUpdater.downgradeLeg(survey, leg)

        assertEquals(listOf("1", "2"), survey.getAllStations().map { it.name })
        val splays = survey.getStationByName("2")!!.onwardLegs.filter { !it.hasDestination() }
        assertEquals(2, splays.size, "the leg's own reading, plus the splay that was already there")
        assertTrue(splays.any { it.distance == 10f && it.azimuth == 90f })
    }

    @Test
    fun promotingASplayFoldsItIntoTheLegBeforeIt() {
        val survey = passage()
        val splay = splayOff(survey, "2")

        assertTrue(SurveyUpdater.promoteToAboveLeg(survey, splay))

        assertFalse(
            splay in survey.getStationByName("2")!!.onwardLegs,
            "the splay is consumed, not left as a duplicate reading",
        )
        assertTrue(
            legInto(survey, "3").wasPromoted(),
            "and the leg it joined now carries both readings, so the promotion can be undone",
        )
    }

    // -------------------------------------------------------------------------------------
    // Comments
    // -------------------------------------------------------------------------------------

    @Test
    fun commentingALegMarksTheSurveyUnsaved() {
        val survey = passage()
        survey.isSaved = true

        applyLegComment(survey, legInto(survey, "2"), "sump; do not follow")

        assertEquals("sump; do not follow", legInto(survey, "2").comment)
        assertFalse(
            survey.isSaved,
            "the Android app's own comment dialogs skip this, so a comment typed with nothing " +
                "else changed is not written out",
        )
    }

    @Test
    fun aCommentedLegIsMarkedInTheTable() {
        val survey = passage()
        applyLegComment(survey, legInto(survey, "2"), "tight")

        val row = rowFor(survey, "1", legInto(survey, "2"))

        assertEquals("† 10.000", row.distanceShown, "the dagger leads the distance, as in the app")
        assertEquals("1", row.fromShown, "and nothing else on the row is touched")
        assertEquals("2", row.toShown)
    }

    @Test
    fun aCommentedStationIsMarkedOnBothTheRowsItAppearsOn() {
        val survey = passage()
        survey.getStationByName("2")!!.comment = "junction"

        assertEquals("2 †", rowFor(survey, "1", legInto(survey, "2")).toShown)
        assertEquals("2 †", rowFor(survey, "2", legInto(survey, "3")).fromShown)
    }

    @Test
    fun aSplayOffACommentedStationIsNotMarked() {
        // Faithful to TableRowAdapter, which tests isFullLeg before it looks at the station at all.
        val survey = passage()
        survey.getStationByName("2")!!.comment = "junction"

        val row = rowFor(survey, "2", splayOff(survey, "2"))

        assertEquals("2", row.fromShown)
    }

    @Test
    fun theMarkerFollowsTheStationTheTableShowsAndNotTheOneTheLegStartsAt() {
        // A backwards leg is stored 1 → 2 and shown 2 → 1, so a comment on station 1 has to come
        // out in the To column. Marking the stored from-station would put it in the wrong one.
        val survey = passage()
        SurveyUpdater.reverseLeg(survey, survey.getStationByName("2")!!)
        survey.origin.comment = "entrance"

        val row = rowFor(survey, "1", legInto(survey, "2"))

        assertEquals("2", row.fromShown)
        assertEquals("1 †", row.toShown)
    }

    @Test
    fun aStaleLegIsNotOfferedAPromotionRatherThanCrashing() {
        // editLeg swaps the Leg object, so a reference held from before an edit is no longer in
        // the survey. indexOf then answers -1, and subList(0, -1) throws.
        val survey = passage()
        val splay = splayOff(survey, "2")
        SurveyUpdater.editLeg(survey, splay, Leg(3f, 180f, 0f))

        assertFalse(SurveyUpdater.canPromoteToAboveLeg(survey, splay))
    }

    @Test
    fun aStationThatIsNotASplayIsNotOfferedAPromotionEither() {
        val survey = passage()
        assertFalse(SurveyUpdater.canPromoteToAboveLeg(survey, legInto(survey, "2")))
        assertFalse(SurveyUpdater.canDowngradeLeg(splayOff(survey, "2")))
    }

}
