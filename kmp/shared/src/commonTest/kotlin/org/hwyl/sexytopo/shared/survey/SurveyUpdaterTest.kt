package org.hwyl.sexytopo.shared.survey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Ported from `SurveyUpdaterTest` and `SurveyUpdaterInheritedDirectionTest`, keeping the original
 * expectations exactly.
 */
class SurveyUpdaterTest {

    // -------------------------------------------------------------------------------------
    // Adding readings
    // -------------------------------------------------------------------------------------

    @Test
    fun updateWithOneLegAddsOneLegToSurvey() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 0f, 0f))
        assertEquals(1, survey.getAllLegs().size)
    }

    @Test
    fun updateWithThreeSimilarLegsLeadsToNewStation() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 0f, 0f))
        SurveyUpdater.update(survey, Leg(5f, 0.001f, 0f))
        SurveyUpdater.update(survey, Leg(5f, 0f, 0.001f))
        assertEquals(2, survey.getAllStations().size)
        assertEquals(1, survey.getAllLegs().size)
    }

    @Test
    fun promotedLegKeepsItsThreeConstituentReadings() {
        val survey = Survey()
        val first = Leg(5f, 0f, 0f)
        val second = Leg(5f, 0.001f, 0f)
        val third = Leg(5f, 0f, 0.001f)
        SurveyUpdater.update(survey, first)
        SurveyUpdater.update(survey, second)
        SurveyUpdater.update(survey, third)

        val leg = survey.origin.getConnectedOnwardLegs().single()
        assertTrue(leg.wasPromoted())
        assertEquals(listOf(first, second, third), leg.promotedFrom.toList())
        assertEquals(1, survey.getAllLegsInChronoOrder().size)
    }

    @Test
    fun editLegWorks() {
        val survey = Survey()
        val leg = Leg(5f, 0f, 0f)
        SurveyUpdater.update(survey, leg)

        SurveyUpdater.editLeg(survey, leg, Leg(6f, 0f, 0f))

        assertEquals(1, survey.getAllLegs().size)
        assertEquals(6f, survey.getAllLegs()[0].distance, DELTA)
    }

    @Test
    fun moveLegWorks() {
        val survey = TestSurveys.createStraightNorth()
        val toMove = survey.getStationByName("2")!!.onwardLegs[0]
        val originatingStation = survey.getOriginatingStation(toMove)!!
        val destinationStation = survey.getStationByName("1")!!
        assertNotSame(originatingStation, destinationStation)

        SurveyUpdater.moveLeg(survey, toMove, destinationStation)

        assertTrue(destinationStation.onwardLegs.contains(toMove))
        assertFalse(originatingStation.onwardLegs.contains(toMove))
    }

    @Test
    fun areLegsAboutTheSame() {
        assertTrue(
            SurveyUpdater.areLegsAboutTheSame(
                listOf(Leg(10f, 159.5f, 0f), Leg(10f, 160.0f, 0f), Leg(10f, 160.5f, 0f))
            )
        )
        assertFalse(
            SurveyUpdater.areLegsAboutTheSame(
                listOf(Leg(10f, 119.5f, 0f), Leg(10f, 110.0f, 0f), Leg(10f, 110.5f, 0f))
            )
        )
        assertFalse(
            SurveyUpdater.areLegsAboutTheSame(
                listOf(Leg(10f, 349.5f, 0f), Leg(10f, 10.0f, 0f), Leg(10f, 10.5f, 0f))
            )
        )
        // Readings straddling the 0/360 seam still compare correctly
        assertTrue(
            SurveyUpdater.areLegsAboutTheSame(
                listOf(Leg(10f, 359.5f, 0f), Leg(10f, 0.0f, 0f), Leg(10f, 0.5f, 0f))
            )
        )
        assertFalse(
            SurveyUpdater.areLegsAboutTheSame(
                listOf(
                    Leg(10.0f, 90.0f, 5.0f), // First: 90 degrees
                    Leg(10.1f, 270.0f, 4.0f), // Second: 270 degrees (opposite direction)
                    Leg(9.9f, 85.0f, 6.0f), // Third: 85 degrees (close to first)
                )
            )
        )
    }

    @Test
    fun areLegsAboutTheSameWithDistanceTolerance() {
        assertTrue(
            SurveyUpdater.areLegsAboutTheSame(listOf(Leg(10.0f, 90f, 0f), Leg(10.01f, 90f, 0f)))
        )
        assertFalse(
            SurveyUpdater.areLegsAboutTheSame(listOf(Leg(10.0f, 90f, 0f), Leg(15.0f, 90f, 0f)))
        )
    }

    @Test
    fun areLegsAboutTheSameWithInclinationTolerance() {
        assertTrue(
            SurveyUpdater.areLegsAboutTheSame(listOf(Leg(10f, 90f, 0f), Leg(10f, 90f, 0.5f)))
        )
        assertFalse(
            SurveyUpdater.areLegsAboutTheSame(listOf(Leg(10f, 90f, 0f), Leg(10f, 90f, 45f)))
        )
    }

    @Test
    fun fullLegsAreNeverAboutTheSame() {
        val leg = Leg(5f, 0f, 0f, Station("X"))
        assertFalse(SurveyUpdater.areLegsAboutTheSame(listOf(leg, Leg(5f, 0f, 0f))))
    }

    // -------------------------------------------------------------------------------------
    // Input modes
    // -------------------------------------------------------------------------------------

    @Test
    fun updateWithBackwardModeCreatesStationFromTripleShot() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 0f, 0f), InputMode.BACKWARD)
        SurveyUpdater.update(survey, Leg(5.001f, 0.001f, 0f), InputMode.BACKWARD)
        val stationCreated =
            SurveyUpdater.update(survey, Leg(5f, 0f, 0.001f), InputMode.BACKWARD)

        assertTrue(stationCreated)
        assertEquals(2, survey.getAllStations().size)
        val origin = survey.origin
        val newStation = survey.activeStation
        assertNotSame(origin, newStation)
        val createdLeg = origin.onwardLegs[0]
        assertTrue(createdLeg.wasShotBackwards)
        assertTrue(createdLeg.hasDestination())
        assertSame(newStation, createdLeg.destination)
    }

    @Test
    fun tripleShotInBackwardModeCreatesReversedLeg() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 45f, 10f), InputMode.BACKWARD)
        SurveyUpdater.update(survey, Leg(5.001f, 45.001f, 10f), InputMode.BACKWARD)
        val stationCreated =
            SurveyUpdater.update(survey, Leg(5f, 45f, 10.001f), InputMode.BACKWARD)

        assertTrue(stationCreated)
        assertEquals(2, survey.getAllStations().size)
        assertNotSame(survey.origin, survey.activeStation)
        assertEquals(1, survey.getAllLegs().size)
        val createdLeg = survey.getAllLegs()[0]
        assertTrue(createdLeg.hasDestination())
        // The reading averaged to 45 degrees up at 10; stored the other way round.
        assertEquals(225f, createdLeg.azimuth, 0.01f)
        assertEquals(-10f, createdLeg.inclination, 0.01f)
    }

    @Test
    fun updateWithComboModeCreatesStationFromBacksight() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 45f, 10f), InputMode.COMBO)
        val stationCreated = SurveyUpdater.update(survey, Leg(5f, 225f, -10f), InputMode.COMBO)

        assertTrue(stationCreated)
        assertEquals(2, survey.getAllStations().size)
        val leg = survey.origin.getConnectedOnwardLegs().single()
        assertEquals(45f, leg.azimuth, 0.01f)
        assertEquals(10f, leg.inclination, 0.01f)
        // Combo-mode legs keep no record of the pair they came from
        assertFalse(leg.wasPromoted())
        assertFalse(leg.wasShotBackwards)
    }

    @Test
    fun comboModeWithTripleShotAfterFailedBacksight() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 45f, 10f), InputMode.COMBO)
        SurveyUpdater.update(survey, Leg(5f, 90f, 10f), InputMode.COMBO)
        SurveyUpdater.update(survey, Leg(5f, 90f, 10f), InputMode.COMBO)
        val stationCreated = SurveyUpdater.update(survey, Leg(5f, 90f, 10f), InputMode.COMBO)

        assertTrue(stationCreated)
        assertEquals(2, survey.getAllStations().size)
        // The stray 45-degree reading is left behind as a splay
        assertEquals(1, survey.origin.getUnconnectedOnwardLegs().size)
    }

    @Test
    fun updateWithCalibrationCheckModeDoesNotCreateStation() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 0f, 0f), InputMode.CALIBRATION_CHECK)
        SurveyUpdater.update(survey, Leg(5f, 0f, 0f), InputMode.CALIBRATION_CHECK)
        val stationCreated =
            SurveyUpdater.update(survey, Leg(5f, 0f, 0f), InputMode.CALIBRATION_CHECK)

        assertFalse(stationCreated)
        assertEquals(1, survey.getAllStations().size)
        assertEquals(3, survey.getAllLegs().size)
    }

    @Test
    fun bulkUpdateWithList() {
        val survey = Survey()
        val legs = listOf(Leg(5f, 0f, 0f), Leg(5f, 0f, 0f), Leg(5f, 0f, 0f))

        val stationCreated = SurveyUpdater.update(survey, legs)

        assertTrue(stationCreated)
        assertEquals(2, survey.getAllStations().size)
        assertEquals(1, survey.getAllLegs().size)
    }

    @Test
    fun bulkUpdateDropsReadingsAfterAStationIsCreated() {
        // Bug-compatibility: the Java accumulates with `||`, which short-circuits, so readings
        // after the one that completes a station are never added at all.
        val survey = Survey()
        val legs =
            listOf(Leg(5f, 0f, 0f), Leg(5f, 0f, 0f), Leg(5f, 0f, 0f), Leg(3f, 180f, 0f))

        SurveyUpdater.update(survey, legs)

        assertEquals(1, survey.getAllLegs().size)
    }

    // -------------------------------------------------------------------------------------
    // updateWithNewStation
    // -------------------------------------------------------------------------------------

    @Test
    fun updateWithNewStationCreatesNewStation() {
        val survey = Survey()

        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))

        assertEquals(2, survey.getAllStations().size)
        assertEquals(1, survey.getAllLegs().size)
        val addedLeg = survey.getAllLegs()[0]
        assertTrue(addedLeg.hasDestination())
        assertSame(survey.activeStation, addedLeg.destination)
        assertEquals("2", addedLeg.destination.name)
    }

    @Test
    fun updateWithNewStationWithExistingDestination() {
        val survey = Survey()
        val customStation = Station("Custom")

        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f, customStation))

        assertEquals(2, survey.getAllStations().size)
        assertSame(customStation, survey.activeStation)
        assertEquals("Custom", survey.activeStation.name)
    }

    // -------------------------------------------------------------------------------------
    // upgradeSplay
    // -------------------------------------------------------------------------------------

    @Test
    fun upgradeSplayInForwardMode() {
        val survey = Survey()
        val splay = Leg(5f, 45f, 10f)
        SurveyUpdater.update(survey, splay)

        SurveyUpdater.upgradeSplay(survey, splay, InputMode.FORWARD)

        assertEquals(2, survey.getAllStations().size)
        val upgraded = survey.getAllLegs()[0]
        assertFalse(upgraded.wasShotBackwards)
        assertTrue(upgraded.hasDestination())
        assertSame(survey.activeStation, upgraded.destination)
    }

    @Test
    fun upgradeSplayInBackwardMode() {
        val survey = Survey()
        val splay = Leg(5f, 45f, 10f)
        SurveyUpdater.update(survey, splay)
        val origin = survey.origin
        val initialStationCount = survey.getAllStations().size

        SurveyUpdater.upgradeSplay(survey, splay, InputMode.BACKWARD)

        assertTrue(survey.getAllStations().size > initialStationCount)
        assertNotSame(origin, survey.activeStation)
        assertEquals(1, survey.getAllLegs().size)
        val upgraded = survey.getAllLegs()[0]
        assertTrue(upgraded.wasShotBackwards)
        assertTrue(upgraded.hasDestination())
        assertEquals(225f, upgraded.azimuth, 0.01f)
    }

    // -------------------------------------------------------------------------------------
    // Renaming
    // -------------------------------------------------------------------------------------

    @Test
    fun renameStationSuccess() {
        val survey = TestSurveys.createStraightNorth()
        val station = survey.getStationByName("1")!!

        SurveyUpdater.renameStation(survey, station, "A1")

        assertEquals("A1", station.name)
        assertNull(survey.getStationByName("1"))
        assertNotNull(survey.getStationByName("A1"))
        assertFalse(survey.isSaved)
    }

    @Test
    fun renameStationToDuplicateNameThrows() {
        val survey = TestSurveys.createStraightNorth()
        val station = survey.getStationByName("1")!!
        assertFailsWith<IllegalArgumentException> {
            SurveyUpdater.renameStation(survey, station, "2")
        }
    }

    @Test
    fun renameOriginStation() {
        val survey = Survey()
        val origin = survey.origin

        SurveyUpdater.renameOrigin(survey, "START")

        assertEquals("START", origin.name)
        assertSame(origin, survey.getStationByName("START"))
    }

    // -------------------------------------------------------------------------------------
    // Deletion
    // -------------------------------------------------------------------------------------

    @Test
    fun deleteStationRemovesStation() {
        val survey = TestSurveys.createStraightNorth()

        SurveyUpdater.deleteStation(survey, survey.getStationByName("2")!!)

        assertNull(survey.getStationByName("2"))
        assertFalse(survey.isSaved)
    }

    @Test
    fun deleteOriginStationDoesNothing() {
        val survey = TestSurveys.createStraightNorth()
        val origin = survey.origin
        val originalStationCount = survey.getAllStations().size

        SurveyUpdater.deleteStation(survey, origin)

        assertEquals(originalStationCount, survey.getAllStations().size)
        assertSame(origin, survey.origin)
    }

    @Test
    fun deleteStationWithSubtreeRemovesAll() {
        // The Java test this was ported from targets station "1" — the ORIGIN — which
        // deleteStation returns from immediately, and then asserts only `size <= initial`. It
        // therefore passes with deleteStation's body emptied. Targeting an interior station and
        // asserting the concrete outcome makes it test something.
        val survey = TestSurveys.createStraightNorthWith1EBranch()
        val toDelete = survey.getStationByName("2")!!

        SurveyUpdater.deleteStation(survey, toDelete)

        val remaining = survey.getAllStations().map { it.name }.toSet()
        assertEquals(setOf("1", "5"), remaining, "2 and its subtree 3, 4 should be gone")
        assertFalse(survey.isSaved)
    }

    @Test
    fun deletingTheOriginIsRefused() {
        // The behaviour the old assertion accidentally covered, stated on purpose.
        val survey = TestSurveys.createStraightNorthWith1EBranch()
        val before = survey.getAllStations().size

        SurveyUpdater.deleteStation(survey, survey.origin)

        assertEquals(before, survey.getAllStations().size, "the origin cannot be deleted")
    }

    @Test
    fun deleteLegRemovesLeg() {
        val survey = TestSurveys.createStraightNorth()
        val station2 = survey.getStationByName("2")!!
        val legCountBeforeDelete = survey.getAllLegs().size

        val leafLegToStation2 = survey.getReferringLeg(station2)!!
        val station1 = survey.getOriginatingStation(leafLegToStation2)!!
        SurveyUpdater.deleteLeg(survey, station1, leafLegToStation2)

        assertTrue(survey.getAllLegs().size < legCountBeforeDelete)
        assertNull(survey.getStationByName("2"))
        assertFalse(survey.isSaved)
    }

    @Test
    fun deleteLegWithSubtreeRemovesAllDescendants() {
        val survey = TestSurveys.createStraightNorthWith2EBranch()
        val station1 = survey.getStationByName("1")!!
        val toDelete = station1.onwardLegs[0]
        val originalStationCount = survey.getAllStations().size

        SurveyUpdater.deleteLeg(survey, station1, toDelete)

        assertTrue(survey.getAllStations().size < originalStationCount)
        // The record no longer refers to anything that has been detached
        assertTrue(
            survey.getAllLegsInChronoOrder().all { survey.getOriginatingStation(it) != null }
        )
    }

    // -------------------------------------------------------------------------------------
    // Downgrading
    // -------------------------------------------------------------------------------------

    @Test
    fun downgradeLegSuccess() {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))

        val origin = survey.origin
        val connectedLeg = origin.getConnectedOnwardLegs()[0]
        assertTrue(connectedLeg.destination.onwardLegs.isEmpty())

        SurveyUpdater.downgradeLeg(survey, connectedLeg)

        assertFalse(origin.onwardLegs[0].hasDestination())
        assertFalse(survey.isSaved)
    }

    @Test
    fun downgradeSplayDoesNothing() {
        val survey = Survey()
        val splay = Leg(5f, 45f, 10f)
        SurveyUpdater.update(survey, splay)

        SurveyUpdater.downgradeLeg(survey, splay)

        assertFalse(splay.hasDestination())
        assertEquals(1, survey.getAllLegs().size)
    }

    @Test
    fun downgradeLegWithOnwardLegsThrows() {
        val survey = TestSurveys.createStraightNorth()
        val legToStation1 = survey.origin.getConnectedOnwardLegs()[0]
        assertTrue(legToStation1.destination.onwardLegs.isNotEmpty())

        assertFailsWith<IllegalStateException> { SurveyUpdater.downgradeLeg(survey, legToStation1) }
    }

    @Test
    fun canDowngradeLegAnswersTheQuestionDowngradeLegThrowsOver() {
        val survey = TestSurveys.createStraightNorth()
        val legToStation1 = survey.origin.getConnectedOnwardLegs()[0]
        val lastLeg = survey.getAllLegsInChronoOrder().last { it.hasDestination() }

        assertFalse(SurveyUpdater.canDowngradeLeg(legToStation1), "a cave hangs off the far end")
        assertTrue(SurveyUpdater.canDowngradeLeg(lastLeg), "nothing was surveyed past the last one")
        assertFalse(
            SurveyUpdater.canDowngradeLeg(Leg(1f, 0f, 0f)),
            "a splay is already what a downgrade would make it",
        )
    }

    @Test
    fun downgradeNonPromotedLegProducesOneSplay() {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))

        val origin = survey.origin
        val connectedLeg = origin.getConnectedOnwardLegs()[0]
        assertFalse(connectedLeg.wasPromoted())

        SurveyUpdater.downgradeLeg(survey, connectedLeg)

        assertEquals(1, origin.onwardLegs.size)
        assertFalse(origin.onwardLegs[0].hasDestination())
    }

    @Test
    fun downgradePromotedLegRestoresAllSplays() {
        val survey = surveyWithLegPromotedFromThreeReadings()
        val origin = survey.origin
        val promotedLeg = origin.getConnectedOnwardLegs()[0]
        assertTrue(promotedLeg.wasPromoted())
        assertEquals(3, promotedLeg.promotedFrom.size)

        SurveyUpdater.downgradeLeg(survey, promotedLeg)

        assertEquals(3, origin.onwardLegs.size)
        assertTrue(origin.onwardLegs.none { it.hasDestination() })
    }

    @Test
    fun downgradePromotedLegPreservesOriginalReadings() {
        val survey = surveyWithLegPromotedFromThreeReadings()
        val origin = survey.origin
        val promotedFrom = origin.getConnectedOnwardLegs()[0].promotedFrom

        SurveyUpdater.downgradeLeg(survey, origin.getConnectedOnwardLegs()[0])

        val onwardLegs = origin.onwardLegs
        assertEquals(promotedFrom.size, onwardLegs.size)
        for (i in promotedFrom.indices) {
            assertEquals(promotedFrom[i].distance, onwardLegs[i].distance, DELTA)
            assertEquals(promotedFrom[i].azimuth, onwardLegs[i].azimuth, DELTA)
            assertEquals(promotedFrom[i].inclination, onwardLegs[i].inclination, DELTA)
        }
    }

    @Test
    fun downgradePromotedLegKeepsSurveyIntegrity() {
        val survey = surveyWithLegPromotedFromThreeReadings()

        SurveyUpdater.downgradeLeg(survey, survey.origin.getConnectedOnwardLegs()[0])
        survey.checkSurveyIntegrity()

        assertEquals(1, survey.getAllStations().size)
        assertSame(survey.origin, survey.activeStation)
        assertEquals(3, survey.getAllLegsInChronoOrder().size)
    }

    /** A leg to station "2" that has had two extra readings promoted into it. */
    private fun surveyWithLegPromotedFromThreeReadings(): Survey {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))

        val origin = survey.origin
        val splay2 = Leg(6f, 91f, 11f)
        SurveyBuilder.addSplay(survey, origin, splay2)
        SurveyUpdater.promoteToAboveLeg(survey, splay2)

        val splay3 = Leg(7f, 92f, 12f)
        SurveyBuilder.addSplay(survey, origin, splay3)
        SurveyUpdater.promoteToAboveLeg(survey, splay3)

        return survey
    }

    // -------------------------------------------------------------------------------------
    // promoteToAboveLeg
    // -------------------------------------------------------------------------------------

    @Test
    fun canPromoteToAboveLegIsFalseWhenNoLegHasBeenTakenYet() {
        val survey = Survey()
        val splay = Leg(2f, 10f, 5f)
        SurveyBuilder.addSplay(survey, survey.origin, splay)

        assertFalse(
            SurveyUpdater.canPromoteToAboveLeg(survey, splay),
            "the first reading of a survey has nothing above it to join",
        )
        assertFalse(SurveyUpdater.promoteToAboveLeg(survey, splay), "and the promotion agrees")
    }

    @Test
    fun canPromoteToAboveLegIsFalseForALegTheSurveyNoLongerHolds() {
        // editLeg replaces the Leg object, so anything holding the old one - a dialog left open,
        // say - is asking about a leg that has gone. indexOf answers -1 for it, and subList(0, -1)
        // throws rather than returning nothing.
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        val splay = Leg(2f, 10f, 5f)
        SurveyBuilder.addSplay(survey, survey.origin, splay)
        assertTrue(SurveyUpdater.canPromoteToAboveLeg(survey, splay))

        SurveyUpdater.editLeg(survey, splay, Leg(3f, 10f, 5f))

        assertFalse(SurveyUpdater.canPromoteToAboveLeg(survey, splay))
    }

    @Test
    fun promoteToAboveLegInheritsBackwardsFlag() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 45f, 10f), InputMode.BACKWARD)
        SurveyUpdater.update(survey, Leg(5.001f, 45.001f, 10f), InputMode.BACKWARD)
        SurveyUpdater.update(survey, Leg(5f, 45f, 10.001f), InputMode.BACKWARD)

        val origin = survey.origin
        assertTrue(origin.getConnectedOnwardLegs()[0].wasShotBackwards)

        val splay = Leg(3f, 50f, 8f)
        SurveyBuilder.addSplay(survey, origin, splay)

        assertTrue(SurveyUpdater.promoteToAboveLeg(survey, splay))

        val updatedLeg = origin.getConnectedOnwardLegs()[0]
        assertTrue(updatedLeg.wasPromoted())
        assertTrue(updatedLeg.promotedFrom.last().wasShotBackwards)
    }

    @Test
    fun promoteToAboveLegForwardLegDoesNotSetBackwards() {
        val survey = Survey()
        SurveyUpdater.update(survey, Leg(5f, 45f, 10f))
        SurveyUpdater.update(survey, Leg(5.001f, 45.001f, 10f))
        SurveyUpdater.update(survey, Leg(5f, 45f, 10.001f))

        val origin = survey.origin
        assertFalse(origin.getConnectedOnwardLegs()[0].wasShotBackwards)

        val splay = Leg(3f, 50f, 8f)
        SurveyBuilder.addSplay(survey, origin, splay)

        assertTrue(SurveyUpdater.promoteToAboveLeg(survey, splay))

        val updatedLeg = origin.getConnectedOnwardLegs()[0]
        assertTrue(updatedLeg.wasPromoted())
        assertFalse(updatedLeg.promotedFrom.last().wasShotBackwards)
    }

    @Test
    fun promoteToAboveLegRejectsFullLegsAndOrphanedSplays() {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        val fullLeg = survey.origin.getConnectedOnwardLegs()[0]
        assertFalse(SurveyUpdater.promoteToAboveLeg(survey, fullLeg))

        val loneSurvey = Survey()
        val splay = Leg(5f, 90f, 10f)
        SurveyBuilder.addSplay(loneSurvey, loneSurvey.origin, splay)
        assertFalse(SurveyUpdater.promoteToAboveLeg(loneSurvey, splay))
    }

    // -------------------------------------------------------------------------------------
    // Reversal and backsights
    // -------------------------------------------------------------------------------------

    @Test
    fun reverseLegChangesDirection() {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        val station1 = survey.activeStation
        val originalLegCount = survey.getAllLegs().size

        SurveyUpdater.reverseLeg(survey, station1)

        assertEquals(originalLegCount, survey.getAllLegs().size)
        val reversed = survey.origin.getConnectedOnwardLegs()[0]
        assertEquals(270f, reversed.azimuth, DELTA)
        assertEquals(-10f, reversed.inclination, DELTA)
        assertTrue(reversed.wasShotBackwards)
        assertSame(station1, reversed.destination)
        assertFalse(survey.isSaved)
    }

    @Test
    fun reverseLegMaintainsSurveyIntegrity() {
        val survey = TestSurveys.createStraightNorth()
        val station2 = survey.getStationByName("2")!!
        val originalLegCount = survey.getAllLegs().size
        val originalStationCount = survey.getAllStations().size

        SurveyUpdater.reverseLeg(survey, station2)

        assertEquals(originalLegCount, survey.getAllLegs().size)
        assertEquals(originalStationCount, survey.getAllStations().size)
    }

    @Test
    fun areLegsBacksightsWithMatchingPair() {
        assertTrue(SurveyUpdater.areLegsBacksights(Leg(10f, 45f, 15f), Leg(10f, 225f, -15f)))
    }

    @Test
    fun areLegsBacksightsWithNonMatchingPair() {
        assertFalse(SurveyUpdater.areLegsBacksights(Leg(10f, 45f, 15f), Leg(10f, 90f, -15f)))
    }

    @Test
    fun areLegsBacksightsNearBoundary() {
        assertTrue(SurveyUpdater.areLegsBacksights(Leg(10f, 5f, 10f), Leg(10f, 185f, -10f)))
    }

    @Test
    fun averageLegsSimple() {
        val averaged =
            SurveyUpdater.averageLegs(
                listOf(Leg(10f, 90f, 10f), Leg(11f, 90f, 12f), Leg(9f, 90f, 8f))
            )
        assertEquals(10f, averaged.distance, DELTA)
        assertEquals(90f, averaged.azimuth, DELTA)
        assertEquals(10f, averaged.inclination, DELTA)
    }

    @Test
    fun averageLegsAcrossAzimuthBoundary() {
        val averaged =
            SurveyUpdater.averageLegs(listOf(Leg(10f, 359f, 0f), Leg(10f, 1f, 0f), Leg(10f, 0f, 0f)))
        assertEquals(10f, averaged.distance, DELTA)
        assertEquals(0f, averaged.azimuth, DELTA)
    }

    @Test
    fun averageBacksightsWithAgreement() {
        val averaged = SurveyUpdater.averageBacksights(Leg(10f, 45f, 10f), Leg(10f, 225f, -10f))
        assertEquals(10f, averaged.distance, DELTA)
        assertEquals(45f, averaged.azimuth, DELTA)
        assertEquals(10f, averaged.inclination, DELTA)
    }

    @Test
    fun averageBacksightsWithDisagreement() {
        val averaged = SurveyUpdater.averageBacksights(Leg(10f, 45f, 10f), Leg(10.5f, 226f, -11f))
        assertEquals(10.25f, averaged.distance, DELTA)
        assertTrue(averaged.azimuth >= 44.5f && averaged.azimuth <= 46f)
        assertTrue(averaged.inclination >= 9.5f && averaged.inclination <= 10.5f)
    }

    // -------------------------------------------------------------------------------------
    // Extended elevation direction
    // -------------------------------------------------------------------------------------

    @Test
    fun setDirectionOfSubtreeOnSingleStation() {
        val survey = Survey()
        val origin = survey.origin
        origin.extendedElevationDirection = ExtendedElevationDirection.LEFT

        SurveyUpdater.setExtendedElevationDirectionOfSubtree(
            origin,
            ExtendedElevationDirection.RIGHT,
        )

        assertEquals(ExtendedElevationDirection.RIGHT, origin.extendedElevationDirection)
    }

    @Test
    fun setDirectionOfSubtreeRecursively() {
        val survey = TestSurveys.createStraightNorth()

        SurveyUpdater.setExtendedElevationDirectionOfSubtree(
            survey.origin,
            ExtendedElevationDirection.RIGHT,
        )

        for (name in listOf("1", "2", "3", "4")) {
            assertEquals(
                ExtendedElevationDirection.RIGHT,
                survey.getStationByName(name)!!.extendedElevationDirection,
            )
        }
    }

    @Test
    fun nonPropagatingDirectionAppliesToOneStationOnly() {
        val survey = TestSurveys.createStraightNorth()
        val station2 = survey.getStationByName("2")!!

        SurveyUpdater.setExtendedElevationDirection(
            survey,
            station2,
            ExtendedElevationDirection.VERTICAL,
        )

        assertEquals(ExtendedElevationDirection.VERTICAL, station2.extendedElevationDirection)
        assertEquals(
            ExtendedElevationDirection.DEFAULT,
            survey.getStationByName("3")!!.extendedElevationDirection,
        )
    }

    // Three identical splays are required to trigger the triple-shot rule, which is the only path
    // that resolves an inherited extended elevation direction.
    private fun addStationViaTripleShot(survey: Survey): Station {
        val before = survey.activeStation
        repeat(3) { SurveyUpdater.update(survey, Leg(5f, 0f, 0f)) }
        val after = survey.activeStation
        assertNotSame(before, after, "Triple shot should have created a new station")
        return after
    }

    @Test
    fun nonVerticalParentInheritsOwnDirection() {
        val survey = Survey()
        survey.origin.extendedElevationDirection = ExtendedElevationDirection.LEFT

        val newStation = addStationViaTripleShot(survey)

        assertEquals(ExtendedElevationDirection.LEFT, newStation.extendedElevationDirection)
    }

    @Test
    fun rightParentInheritsRight() {
        val survey = Survey()
        survey.origin.extendedElevationDirection = ExtendedElevationDirection.RIGHT

        val newStation = addStationViaTripleShot(survey)

        assertEquals(ExtendedElevationDirection.RIGHT, newStation.extendedElevationDirection)
    }

    @Test
    fun verticalParentInheritsFromGrandparent() {
        for (direction in
            listOf(ExtendedElevationDirection.RIGHT, ExtendedElevationDirection.LEFT)) {
            val survey = Survey()
            survey.origin.extendedElevationDirection = direction

            val firstStation = addStationViaTripleShot(survey)
            firstStation.extendedElevationDirection = ExtendedElevationDirection.VERTICAL

            val secondStation = addStationViaTripleShot(survey)

            assertEquals(direction, secondStation.extendedElevationDirection)
        }
    }

    @Test
    fun twoConsecutiveVerticalsInheritFromGreatGrandparent() {
        for (direction in
            listOf(ExtendedElevationDirection.RIGHT, ExtendedElevationDirection.LEFT)) {
            val survey = Survey()
            survey.origin.extendedElevationDirection = direction

            val firstStation = addStationViaTripleShot(survey)
            firstStation.extendedElevationDirection = ExtendedElevationDirection.VERTICAL

            val secondStation = addStationViaTripleShot(survey)
            secondStation.extendedElevationDirection = ExtendedElevationDirection.VERTICAL

            val thirdStation = addStationViaTripleShot(survey)

            assertEquals(direction, thirdStation.extendedElevationDirection)
        }
    }

    @Test
    fun verticalOriginFallsBackToRight() {
        val survey = Survey()
        survey.origin.extendedElevationDirection = ExtendedElevationDirection.VERTICAL

        val newStation = addStationViaTripleShot(survey)

        assertEquals(ExtendedElevationDirection.RIGHT, newStation.extendedElevationDirection)
    }

    companion object {
        private const val DELTA = 0.0001f
    }
}
