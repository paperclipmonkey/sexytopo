package org.hwyl.sexytopo.demo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.scan.PlacementMode
import org.hwyl.sexytopo.shared.scan.ScanRecovery
import org.hwyl.sexytopo.shared.scan.ScanSketchWriter
import org.hwyl.sexytopo.shared.scan.SimulatedWallScan
import org.hwyl.sexytopo.shared.scan.WallScan
import org.hwyl.sexytopo.shared.scan.WallScanApplier
import org.hwyl.sexytopo.shared.scan.WallScanRecord
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tools → Wall scans, driven on the JVM with simulated scans: the desktop has no lidar, which is
 * what puts the simulate buttons on the panel and makes these tests possible at all.
 *
 * What is checked is the join between the panel, the state and the store: that a button press
 * reaches the scan's review, that the drawing follows, and that what review changed is still
 * there when the survey is opened again from storage.
 */
@OptIn(ExperimentalTestApi::class)
class WallScanPanelUiTest {

    private val store = InMemoryFileStore()

    private fun state(): DemoState =
        DemoState(
            exampleSurvey = ExampleSurvey.create(),
            initialProjection = Projection2D.PLAN,
            initialSystemDark = false,
            initialTool = null,
            initialMode = SurveyMode.LIVE,
            initialScreen = Screen.SKETCH,
            library = SurveyLibrary(store),
        )

    /** A passage that turns at every station, recorded into a new survey called Cave. */
    private fun withCave(state: DemoState): DemoState {
        state.newSurvey("Cave")
        val survey = state.liveSurvey
        for ((distance, azimuth) in listOf(6f to 20f, 6f to 80f, 5f to 30f, 7f to 120f, 6f to 60f, 6f to 10f)) {
            SurveyBuilder.updateWithNewStation(survey, Leg(distance, azimuth, 0f))
        }
        survey.activeStation = survey.getStationByName("1")!!
        state.saveLiveSurvey()
        return state
    }

    @Test
    fun aSimulatedScanIsPlacedDrawnAndSaved() = runComposeUiTest {
        val state = withCave(state())
        setContent { WallScanPanel(state) }

        onNodeWithTag("simulate-scan").performClick()

        val record = state.wallScans.single()
        assertEquals(PlacementMode.ANCHORED, record.result?.mode, record.result?.message)
        assertTrue(ScanSketchWriter.countDrawnBy(state.liveSurvey, record.id) > 0)
        assertTrue(store.exists(listOf("surveys", "Cave", "Cave.scan-1.json")))
        assertTrue(store.exists(listOf("surveys", "Cave", "Cave.scan-1.points")))
    }

    @Test
    fun aNudgePlacesByHandAndAutomaticPutsItBack() = runComposeUiTest {
        val state = withCave(state())
        setContent { WallScanPanel(state) }
        onNodeWithTag("simulate-scan").performClick()
        val record = state.wallScans.single()
        val before = assertNotNull(record.result?.placement)

        onNodeWithTag("nudge-east").performScrollTo().performClick()

        assertEquals(PlacementMode.MANUAL, record.result?.mode)
        assertEquals(before.translation.x + 0.1f, record.review.placement!!.translation.x, 1e-4f)
        assertTrue(record.result!!.residuals.all { it.second > 0.05f }, "the marks say it moved")

        onNodeWithTag("placement-automatic").performScrollTo().performClick()

        assertNull(record.review.placement)
        assertEquals(PlacementMode.ANCHORED, record.result?.mode)
    }

    @Test
    fun reviewChangesSurviveReopeningTheSurvey() = runComposeUiTest {
        val state = withCave(state())
        setContent { WallScanPanel(state) }
        onNodeWithTag("simulate-scan").performClick()
        val legs = state.liveSurvey.getAllLegs().size

        onNodeWithTag("wall-scan-fans").performScrollTo().performClick()

        val added = state.liveSurvey.getAllLegs().size - legs
        assertTrue(added > 0, "fans added no splays")

        val reopened = state()
        assertTrue(reopened.openSurvey("Cave"))
        val record = reopened.wallScans.single()
        assertTrue(record.review.splayFans)
        assertEquals(legs + added, reopened.liveSurvey.getAllLegs().size)

        // And the reopened copy can still take out what the first one drew.
        WallScanApplier.remove(reopened.liveSurvey, record)
        assertEquals(legs, reopened.liveSurvey.getAllLegs().size)
        assertEquals(0, ScanSketchWriter.countDrawnBy(reopened.liveSurvey, record.id))
    }

    @Test
    fun aScanWithNoMarksIsFittedAndDrawsWalls() = runComposeUiTest {
        val state = withCave(state())
        setContent { WallScanPanel(state) }

        onNodeWithTag("simulate-scan-unmarked").performClick()

        val record = state.wallScans.single()
        assertEquals(PlacementMode.FITTED, record.result?.mode, record.result?.message)
        assertTrue(record.result!!.message.contains("wall"), record.result!!.message)
    }

    @Test
    fun deletingAScanTakesOutItsDrawingAndItsFiles() = runComposeUiTest {
        val state = withCave(state())
        setContent { WallScanPanel(state) }
        onNodeWithTag("simulate-scan").performClick()
        val id = state.wallScans.single().id

        onNodeWithTag("wall-scan-delete").performScrollTo().performClick()
        onNodeWithTag("wall-scan-delete-confirm").performClick()

        assertTrue(state.wallScans.isEmpty())
        assertEquals(0, ScanSketchWriter.countDrawnBy(state.liveSurvey, id))
        assertTrue(!store.exists(listOf("surveys", "Cave", "Cave.scan-1.json")))
    }

    @Test
    fun renamingTheSurveyTakesItsScansAlong() {
        val state = withCave(state())
        state.addWallScan(
            assertNotNull(
                SimulatedWallScan.around(state.liveSurvey, state.liveSurvey.activeStation),
            ).scan,
        )

        state.renameLiveSurvey("Swildons")

        assertTrue(store.exists(listOf("surveys", "Swildons", "Swildons.scan-1.json")))
        val reopened = state()
        assertTrue(reopened.openSurvey("Swildons"))
        assertEquals(1, reopened.wallScans.size)
    }

    @Test
    fun aScanThatSawNothingIsNotKept() {
        val state = withCave(state())

        val report = state.addWallScan(WallScan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "1"))

        assertNull(report)
        assertTrue(state.wallScans.isEmpty())
        assertEquals(Strings.wallScanEmpty, state.notice)
    }

    @Test
    fun poorTrackingIsSaidAsAShareOfTheWalk() {
        val path = List(10) { Coord3D(it.toFloat(), 0f, 0f) }
        val good = WallScan(emptyList(), path, emptyList(), emptyList(), emptyList(), "1")
        val poor = WallScan(emptyList(), path, emptyList(), emptyList(), emptyList(), "1", setOf(0, 1, 2))

        assertNull(trackingSummary(WallScanRecord("1", good)))
        assertEquals(
            "Tracking was poor for 30% of the walk, which is left out when fitting.",
            trackingSummary(WallScanRecord("1", poor)),
        )
    }

    @Test
    fun anUnfinishedScanGoesOnlyIntoTheSurveyItWasTakenIn() {
        val state = withCave(state())
        val scan = assertNotNull(SimulatedWallScan.around(state.liveSurvey, state.liveSurvey.activeStation)).scan
        // A scan begun in Cave, checkpointed, and the app stopped.
        ScanRecovery.start(store, "Cave")
        ScanRecovery.checkpoint(store, scan.path, scan.touches, scan.penLines, scan.stamps, "1")
        state.newSurvey("Elsewhere")

        assertTrue(!state.recoverUnfinishedScan())
        assertTrue(!state.readyToScan(), "a new scan here would clear the one from Cave")
        assertTrue(state.wallScans.isEmpty())
        assertTrue(ScanRecovery.exists(store))

        assertTrue(state.openSurvey("Cave"))
        assertTrue(state.recoverUnfinishedScan())
        assertEquals(1, state.wallScans.size)
        assertTrue(!ScanRecovery.exists(store))
    }

    @Test
    fun aNudgedFittedScanKeepsItsSwitchesOn() = runComposeUiTest {
        val state = withCave(state())
        setContent { WallScanPanel(state) }
        onNodeWithTag("simulate-scan-unmarked").performClick()

        onNodeWithTag("nudge-east").performScrollTo().performClick()

        onNodeWithTag("wall-scan-walls").performScrollTo().assertIsOn()
        onNodeWithTag("wall-scan-lruds").performScrollTo().assertIsOn()
    }
}
