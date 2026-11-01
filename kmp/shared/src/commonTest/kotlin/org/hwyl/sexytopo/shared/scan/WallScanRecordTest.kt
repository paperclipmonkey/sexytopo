package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.io.store.SurveyZip
import org.hwyl.sexytopo.shared.io.store.Zip
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scan kept with its survey: saved and read back, rebuilt as review changes it, and taken out
 * again leaving nothing behind — tested against scans made up by [SimulatedWallScan], whose true
 * placement is known.
 */
class WallScanRecordTest {

    /** A passage that turns at every station, so a fit has something to hold on to. */
    private fun zigzag(): Survey {
        val survey = Survey()
        for ((distance, azimuth, inclination) in listOf(
            Triple(6f, 20f, -5f),
            Triple(6f, 80f, 0f),
            Triple(5f, 30f, 8f),
            Triple(7f, 120f, -3f),
            Triple(6f, 60f, 0f),
            Triple(6f, 10f, 2f),
        )) {
            SurveyUpdater.updateWithNewStation(survey, Leg(distance, azimuth, inclination))
        }
        return survey
    }

    private fun simulate(survey: Survey, settings: SimulatedWallScan.Settings = SimulatedWallScan.Settings()) =
        assertNotNull(SimulatedWallScan.around(survey, survey.getStationByName("1")!!, settings))

    private fun headingError(a: Float, b: Float): Float {
        val difference = abs(a - b) % 360
        return minOf(difference, 360 - difference)
    }

    private fun drawnCount(survey: Survey): Int =
        listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION).sumOf {
            val sketch = survey.getSketch(it)
            sketch.pathDetails.size + sketch.symbolDetails.size
        }

    // -------------------------------------------------------------------------------------------
    // The simulated scan itself
    // -------------------------------------------------------------------------------------------

    @Test
    fun theTruthPutsASimulatedScanBackOnTheSurvey() {
        val survey = zigzag()
        val simulated = simulate(survey)
        val positions = SurveyFrame(survey).stationPositions
        for (touch in simulated.scan.touches) {
            val placed = simulated.truth.place(touch.inScan)
            assertTrue(StationAnchoring.distance(placed, positions.getValue(touch.stationName)) < 0.05f)
        }
        assertTrue(simulated.scan.points.size > 10_000, "a passage's worth of rock")
        assertEquals(3, simulated.scan.touches.size)
    }

    @Test
    fun theSameSettingsMakeTheSameScan() {
        val survey = zigzag()
        val a = simulate(survey).scan
        val b = simulate(survey).scan
        assertEquals(a.points.size, b.points.size)
        assertEquals(a.points[1234].x, b.points[1234].x)
    }

    // -------------------------------------------------------------------------------------------
    // Rebuilding
    // -------------------------------------------------------------------------------------------

    @Test
    fun anAnchoredScanIsPlacedWhereItWasMade() {
        val survey = zigzag()
        val simulated = simulate(survey)
        val record = WallScanRecord("1", simulated.scan)

        val report = WallScanApplier.rebuild(survey, record)

        assertEquals(PlacementMode.ANCHORED, report.mode, report.message)
        val placement = assertNotNull(record.result?.placement)
        assertTrue(headingError(simulated.truth.headingDegrees, placement.headingDegrees) < 1f, report.message)
        assertTrue(record.result!!.residuals.all { it.second < 0.05f }, report.message)
        assertEquals(3, record.result!!.residuals.size)
        assertTrue(ScanSketchWriter.countDrawnBy(survey, "1") > 0)
    }

    @Test
    fun rebuildingAgainReplacesRatherThanAdds() {
        val survey = zigzag()
        val record = WallScanRecord("1", simulate(survey).scan)
        record.review.splayFans = true
        record.review.addLruds = true

        WallScanApplier.rebuild(survey, record)
        val drawn = drawnCount(survey)
        val legs = survey.getAllLegs().size
        WallScanApplier.rebuild(survey, record)
        WallScanApplier.rebuild(survey, record)

        assertEquals(drawn, drawnCount(survey))
        assertEquals(legs, survey.getAllLegs().size)
    }

    @Test
    fun handDrawingSurvivesRebuildAndRemoval() {
        val survey = zigzag()
        val plan = survey.getSketch(Projection2D.PLAN)
        val hand = PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.RED)
        plan.pathDetails.add(hand)
        val record = WallScanRecord("1", simulate(survey).scan)

        WallScanApplier.rebuild(survey, record)
        WallScanApplier.rebuild(survey, record)
        WallScanApplier.remove(survey, record)

        assertEquals(listOf(hand), plan.pathDetails.toList())
        assertEquals(0, ScanSketchWriter.countDrawnBy(survey, "1"))
    }

    @Test
    fun aFittedScanAddsLrudsMarkedAsTheScansAndRemovesThemAgain() {
        val survey = zigzag()
        val legsBefore = survey.getAllLegs().size
        val simulated = simulate(survey, SimulatedWallScan.Settings(markStations = false, headingErrorDegrees = 15f, offset = Coord3D(1f, 1f, 0f)))
        val record = WallScanRecord("7", simulated.scan)

        val report = WallScanApplier.rebuild(survey, record)

        assertEquals(PlacementMode.FITTED, report.mode, report.message)
        assertTrue(headingError(simulated.truth.headingDegrees, report.placement!!.headingDegrees) < 4f, report.message)
        assertTrue(report.lrudsAdded >= 3, report.message)
        val added = survey.getAllLegs().size - legsBefore
        assertEquals(record.result!!.splays.size, added)
        assertEquals(added, survey.getAllLegs().count { it.comment == WallScanApplier.splayComment("7") })

        WallScanApplier.remove(survey, record)

        assertEquals(legsBefore, survey.getAllLegs().size)
    }

    @Test
    fun theElevationGetsTheRoofAndFloorAndThePlanTheWalls() {
        // Level, so the centreline is at nought all along the elevation.
        val survey = Survey()
        for (azimuth in listOf(20f, 80f, 30f, 120f)) SurveyUpdater.updateWithNewStation(survey, Leg(6f, azimuth, 0f))
        val record = WallScanRecord("1", simulate(survey).scan)
        record.review.generateWalls = true
        record.review.removedLines += 0

        WallScanApplier.rebuild(survey, record)

        // Simulated passage: 2 m wide and 1.6 m high, so walls a metre either side in plan and the
        // roof and floor 0.8 m above and below the centreline in the elevation.
        val plan = survey.getSketch(Projection2D.PLAN).pathDetails
        val elevation = survey.getSketch(Projection2D.EXTENDED_ELEVATION).pathDetails
        assertTrue(plan.isNotEmpty())
        assertTrue(elevation.size >= 2, "a roof and a floor")
        val heights = elevation.flatMap { it.path }.map { abs(it.y) }
        assertTrue(heights.all { abs(it - 0.8f) < 0.1f }, "roof and floor at the passage's height: $heights")
    }

    @Test
    fun crossSectionsAreDrawnBesideStationsAndLeaveHandMadeOnesAlone() {
        val survey = zigzag()
        val plan = survey.getSketch(Projection2D.PLAN)
        // One station already has a section somebody drew.
        val byHand = CrossSectionDetail(Coord2D(50f, 50f), CrossSection(survey.getStationByName("3")!!, 0f))
        plan.crossSectionDetails.add(byHand)
        val record = WallScanRecord("1", simulate(survey).scan)
        record.review.crossSections = true

        WallScanApplier.rebuild(survey, record)

        val made = plan.crossSectionDetails.filter { it.scanId == "1" }
        assertTrue(made.size >= 4, record.result!!.message)
        assertTrue(made.none { it.station.name == "3" })
        // The simulated passage is 2 m wide and 1.6 m high: the outline reaches about a metre
        // either side and 0.8 m up and down.
        val outline = made.first().sketch.pathDetails.flatMap { it.path }
        assertEquals(1f, outline.maxOf { it.x }, 0.15f)
        assertEquals(-1f, outline.minOf { it.x }, 0.15f)

        WallScanApplier.remove(survey, record)

        assertEquals(listOf(byHand), plan.crossSectionDetails.toList())
    }

    @Test
    fun aSectionEditedByHandIsNoLongerTheScans() {
        val survey = zigzag()
        val record = WallScanRecord("1", simulate(survey).scan)
        record.review.crossSections = true
        WallScanApplier.rebuild(survey, record)
        val plan = survey.getSketch(Projection2D.PLAN)
        val edited = plan.crossSectionDetails.first()

        // What committing the section editor does.
        edited.sketch = edited.sketch.copy()
        WallScanApplier.rebuild(survey, record)

        assertTrue(edited in plan.crossSectionDetails, "a rebuild took out a section edited by hand")
        assertEquals(1, plan.crossSectionDetails.count { it.station === edited.station })
    }

    @Test
    fun aScansCrossSectionSurvivesTheSketchFile() {
        val survey = zigzag()
        val record = WallScanRecord("4", simulate(survey).scan)
        record.review.crossSections = true
        WallScanApplier.rebuild(survey, record)

        val read = SketchJson.parse(SketchJson.write(survey.planSketch, "Cave"), survey)

        assertTrue(read.crossSectionDetails.isNotEmpty())
        assertTrue(read.crossSectionDetails.all { it.scanId == "4" })
    }

    @Test
    fun trackWalkedWithPoorTrackingIsLeftOutOfTheFit() {
        val survey = zigzag()
        val simulated = simulate(survey, SimulatedWallScan.Settings(markStations = false))
        val scan = simulated.scan
        // The last third of the track thrown ten metres sideways, as a relocalisation might, and
        // marked as taken while tracking was lost.
        val from = scan.path.size * 2 / 3
        val path = scan.path.mapIndexed { i, p -> if (i >= from) Coord3D(p.x + 10f, p.y, p.z) else p }
        val poor = (from until path.size).toSet()
        val record = WallScanRecord("1", WallScan(scan.points, path, emptyList(), emptyList(), emptyList(), "1", poor))

        val report = WallScanApplier.rebuild(survey, record)

        assertEquals(PlacementMode.FITTED, report.mode, report.message)
        assertTrue(headingError(simulated.truth.headingDegrees, report.placement!!.headingDegrees) < 4f, report.message)
    }

    @Test
    fun aPhoneCarriedAtChestHeightIsFittedAtTheRightHeight() {
        // A walking-height passage with its stations low on the walls, and the phone carried well
        // above them: the passage 2.2 m high, its middle 0.5 m above the legs, the phone 1.2 m. The
        // fit used to put the phone's track at the legs' height, which dropped the whole scan
        // 1.2 m and ran every leg through the roof.
        //
        // Stations right on the floor are beyond this method: a leg running ten centimetres over
        // the rock cannot be told from one going through it, at any height.
        val survey = zigzag()
        val settings =
            SimulatedWallScan.Settings(
                markStations = false,
                heightMetres = 2.2f,
                phoneHeightMetres = 1.2f,
                passageAboveLegsMetres = 0.5f,
            )
        val simulated = simulate(survey, settings)
        val record = WallScanRecord("1", simulated.scan)

        val report = WallScanApplier.rebuild(survey, record)

        assertEquals(PlacementMode.FITTED, report.mode, report.message)
        val placement = report.placement!!
        assertTrue(
            headingError(simulated.truth.headingDegrees, placement.headingDegrees) < 3f,
            "heading ${placement.headingDegrees} against ${simulated.truth.headingDegrees}",
        )
        assertEquals(simulated.truth.translation.z, placement.translation.z, 0.3f)
        assertTrue(!report.message.contains("runs through scanned rock"), report.message)
    }

    @Test
    fun poorTrackingIsSavedWithTheScan() {
        val survey = zigzag()
        val scan = simulate(survey).scan
        val marked = WallScan(scan.points, scan.path, scan.touches, scan.penLines, scan.stamps, "1", setOf(3, 4, 5))

        val back = WallScanStore.read(WallScanStore.write(WallScanRecord("1", marked)), marked.points).scan

        assertEquals(setOf(3, 4, 5), back.poorTracking)
        assertEquals(scan.path.size - 3, back.trustedPath().size)
    }

    @Test
    fun nudgingAFittedScanByHandKeepsItsWallsAndLruds() {
        val survey = zigzag()
        val record = WallScanRecord("1", simulate(survey, SimulatedWallScan.Settings(markStations = false)).scan)
        WallScanApplier.rebuild(survey, record)
        val legs = survey.getAllLegs().size
        val drawn = drawnCount(survey)

        record.review.placement = record.result!!.placement!!.moved(Coord3D(0.1f, 0f, 0f))
        WallScanApplier.rebuild(survey, record)

        assertEquals(PlacementMode.MANUAL, record.result!!.mode)
        assertTrue(record.result!!.fromRockByDefault)
        assertEquals(legs, survey.getAllLegs().size, "the LRUDs went")
        assertEquals(drawn, drawnCount(survey), "the walls went")
    }

    @Test
    fun aRebuildKeepsTheLastFitUnlessAskedForANewOne() {
        val survey = zigzag()
        val record = WallScanRecord("1", simulate(survey, SimulatedWallScan.Settings(markStations = false)).scan)
        WallScanApplier.rebuild(survey, record)
        val fitted = record.result!!.placement!!
        val message = record.result!!.message
        // Something that would change the fit if it were done again.
        SurveyUpdater.updateWithNewStation(survey, Leg(30f, 200f, 0f))

        WallScanApplier.rebuild(survey, record)

        assertTrue(fitted === record.result!!.placement)
        assertEquals(message.substringBefore(" Drew"), record.result!!.message.substringBefore(" Drew"))
        WallScanApplier.rebuild(survey, record, refit = true)
        assertTrue(fitted !== record.result!!.placement)
    }

    @Test
    fun aStrokeErasedOrMovedByHandIsStillTheScansAndIsNotDrawnTwice() {
        val survey = zigzag()
        val record = WallScanRecord("1", simulate(survey).scan)
        WallScanApplier.rebuild(survey, record)
        val plan = survey.getSketch(Projection2D.PLAN)
        val line = plan.pathDetails.first { it.scanId == "1" }
        val fragments = line.getPathFragmentsOutsideRadius(line.path[line.path.size / 2], 0.5f)
        plan.pathDetails.remove(line)
        plan.pathDetails.addAll(fragments)
        val symbol = plan.symbolDetails.first()
        plan.symbolDetails.remove(symbol)
        plan.symbolDetails.add(symbol.translate(Coord2D(1f, 1f)))
        val before = zigzag().also { WallScanApplier.rebuild(it, WallScanRecord("1", record.scan)) }.let { drawnCount(it) }

        WallScanApplier.rebuild(survey, record)

        assertTrue(fragments.all { it.scanId == "1" })
        assertEquals(before, drawnCount(survey))
    }

    @Test
    fun splaysAreFoundByTheirCommentEvenWhenTheRecordForgotThem() {
        val survey = zigzag()
        val legs = survey.getAllLegs().size
        val record = WallScanRecord("1", simulate(survey).scan)
        record.review.addLruds = true
        WallScanApplier.rebuild(survey, record)
        // What a crash between saving the survey and saving the scan leaves: splays in the
        // survey that the scan's record does not list.
        val forgetful = WallScanRecord("1", record.scan, record.review, null)

        WallScanApplier.remove(survey, forgetful)

        assertEquals(legs, survey.getAllLegs().size)
    }

    @Test
    fun aNewScanDoesNotTakeAnIdItsSurveyStillCarries() {
        val survey = zigzag()
        val record = WallScanRecord("3", simulate(survey).scan)
        WallScanApplier.rebuild(survey, record)

        assertEquals("4", WallScanStore.nextScanId(emptyList(), survey))
    }

    @Test
    fun aSplaySomebodyShotIsNotTakenForTheScans() {
        val survey = zigzag()
        val record = WallScanRecord("1", simulate(survey).scan)
        record.review.addLruds = true
        WallScanApplier.rebuild(survey, record)
        val splay = record.result!!.splays.first()
        // The same reading, by hand, on a station the scan also gave LRUDs to.
        val station = survey.getStationByName(splay.station)!!
        val shot = Leg(splay.distance, splay.azimuth, splay.inclination)
        station.onwardLegs.add(shot)
        survey.addLegRecord(shot)

        WallScanApplier.remove(survey, record)

        assertTrue(shot in station.onwardLegs)
    }

    @Test
    fun aPlacementByHandIsKeptAndTheMarksStillChecked() {
        val survey = zigzag()
        val simulated = simulate(survey)
        val record = WallScanRecord("1", simulated.scan)
        val truth = simulated.truth
        record.review.placement = ScanPlacement(truth.headingDegrees, Coord3D(truth.translation.x + 1f, truth.translation.y, truth.translation.z))

        val report = WallScanApplier.rebuild(survey, record)

        assertEquals(PlacementMode.MANUAL, report.mode)
        assertTrue(record.result!!.residuals.all { abs(it.second - 1f) < 0.1f }, report.message)
    }

    @Test
    fun refittingFromNearlyRightFindsRight() {
        val survey = zigzag()
        val simulated = simulate(survey, SimulatedWallScan.Settings(markStations = false))
        val record = WallScanRecord("1", simulated.scan)
        val truth = simulated.truth
        record.review.placement =
            ScanPlacement(truth.headingDegrees + 6f, Coord3D(truth.translation.x + 0.8f, truth.translation.y - 0.6f, truth.translation.z))

        assertTrue(WallScanApplier.refit(survey, record))

        val refitted = assertNotNull(record.review.placement)
        assertTrue(headingError(truth.headingDegrees, refitted.headingDegrees) < 2f, "${refitted.headingDegrees}")
        assertTrue(abs(refitted.translation.x - truth.translation.x) < 0.5f)
        assertTrue(abs(refitted.translation.y - truth.translation.y) < 0.5f)
    }

    @Test
    fun reviewEditsChangeWhatIsDrawnButNotTheCapture() {
        val survey = zigzag()
        val simulated = simulate(survey)
        val record = WallScanRecord("1", simulated.scan)
        WallScanApplier.rebuild(survey, record)
        val symbols = survey.getSketch(Projection2D.PLAN).symbolDetails.size

        record.review.removedStamps += 0
        record.review.removedTouches += 2
        record.review.lineSmoothing[0] = 1f
        WallScanApplier.rebuild(survey, record)

        assertEquals(symbols - 1, survey.getSketch(Projection2D.PLAN).symbolDetails.size)
        assertEquals(2, record.result!!.residuals.size)
        assertEquals(2, record.scan.stamps.size, "the capture is never changed")
        assertEquals(3, record.scan.touches.size)
        assertTrue(record.lineFor(0).size < record.scan.penLines[0].stroke.rawHits.size, "fliers gone at full smoothing")
    }

    @Test
    fun aRenamedMarkAnchorsToItsNewStation() {
        val survey = zigzag()
        val simulated = simulate(survey)
        // Recorded against the wrong name, as a tired surveyor might.
        val wrong = simulated.scan.touches.mapIndexed { i, t -> if (i == 0) StationTouch("6", t.inScan) else t }
        val record = WallScanRecord("1", WallScan(simulated.scan.points, simulated.scan.path, wrong, simulated.scan.penLines, simulated.scan.stamps, "1"))
        WallScanApplier.rebuild(survey, record)
        val before = record.result!!.residuals.maxOf { it.second }

        record.review.renamedTouches[0] = simulated.scan.touches[0].stationName
        WallScanApplier.rebuild(survey, record)

        assertTrue(before > 1f, "a misnamed mark shows up in the residuals")
        assertTrue(record.result!!.residuals.maxOf { it.second } < 0.05f)
    }

    @Test
    fun aScanThatCannotBePlacedSaysWhyAndDrawsNothing() {
        val survey = zigzag()
        val scan = WallScan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        val record = WallScanRecord("1", scan)

        val report = WallScanApplier.rebuild(survey, record)

        assertNull(report.mode)
        assertEquals(WallScanApplier.NOTHING_TO_PLACE_BY, record.result!!.message)
        assertEquals(0, drawnCount(survey))
    }

    // -------------------------------------------------------------------------------------------
    // Splay fans
    // -------------------------------------------------------------------------------------------

    @Test
    fun aFanInARoundPassageReachesItsWalls() {
        val points = mutableListOf<Coord3D>()
        for (step in -10..10) {
            for (k in 0 until 72) {
                val angle = k * 5.0 * kotlin.math.PI / 180
                points.add(Coord3D((cos(angle) * 1.5).toFloat(), step * 0.05f, (sin(angle) * 1.5).toFloat()))
            }
        }

        val fan = ScanProfiles.fanAt(PointGrid(points), Coord3D.ORIGIN, 0f, 12)

        assertEquals(12, fan.size)
        for ((distance, _, _) in fan) assertEquals(1.5f, distance, 0.05f)
        // The first is straight to the right of a passage heading north: due east and level.
        assertEquals(90f, fan[0].second, 0.5f)
        assertEquals(0f, fan[0].third, 0.5f)
        // A quarter of the way round is straight up.
        assertEquals(90f, fan[3].third, 0.5f)
    }

    // -------------------------------------------------------------------------------------------
    // Saving and reading back
    // -------------------------------------------------------------------------------------------

    @Test
    fun pointsRoundTripExactly() {
        val points = listOf(Coord3D(1.25f, -3.5f, 1e-7f), Coord3D(Float.MAX_VALUE, -0f, 42f))
        val back = PointsCodec.decode(PointsCodec.encode(points))
        assertEquals(points.map { listOf(it.x, it.y, it.z) }, back.map { listOf(it.x, it.y, it.z) })
        assertEquals(0, PointsCodec.decode(PointsCodec.encode(emptyList())).size)
    }

    @Test
    fun pointsThatAreNotPointsAreRefused() {
        assertFailsWith<IllegalArgumentException> { PointsCodec.decode(ByteArray(5)) }
        val good = PointsCodec.encode(listOf(Coord3D(1f, 2f, 3f)))
        assertFailsWith<IllegalArgumentException> { PointsCodec.decode(good.copyOf(good.size - 1)) }
        assertFailsWith<IllegalArgumentException> { PointsCodec.decode(ByteArray(12) { 7 }) }
    }

    @Test
    fun aRecordRoundTripsThroughTheStore() {
        val survey = zigzag()
        val record = WallScanRecord("3", simulate(survey).scan)
        record.review.removedStamps += 1
        record.review.renamedTouches[1] = "4"
        record.review.lineSmoothing[0] = 0.8f
        record.review.splayFans = true
        record.review.generateWalls = false
        record.review.crossSections = true
        WallScanApplier.rebuild(survey, record)
        val store = InMemoryFileStore()

        WallScanStore.save(store, listOf("Cave"), "Cave", record)
        val back = WallScanStore.loadAll(store, listOf("Cave"), "Cave").single()

        assertEquals("3", back.id)
        assertEquals(record.scan.points.size, back.scan.points.size)
        assertEquals(record.scan.path.size, back.scan.path.size)
        assertEquals(record.scan.touches.map { it.stationName }, back.scan.touches.map { it.stationName })
        assertEquals("1", back.scan.startStation)
        assertEquals(setOf(1), back.review.removedStamps)
        assertEquals(mapOf(1 to "4"), back.review.renamedTouches)
        assertEquals(mapOf(0 to 0.8f), back.review.lineSmoothing)
        assertEquals(true, back.review.splayFans)
        assertEquals(false, back.review.generateWalls)
        assertEquals(true, back.review.crossSections)
        assertNull(back.review.addLruds)
        assertEquals(record.result!!.mode, back.result!!.mode)
        assertEquals(record.result!!.message, back.result!!.message)
        assertEquals(record.result!!.splays.size, back.result!!.splays.size)
        val line = record.scan.penLines[0].stroke.line()
        val lineBack = back.scan.penLines[0].stroke.line()
        assertEquals(line.size, lineBack.size)
        assertEquals(line.last().x, lineBack.last().x, 1e-5f)
    }

    @Test
    fun aRecordReadBackRemovesWhatTheOriginalDrewAfterTheSurveyIsReopened() {
        val survey = zigzag()
        survey.name = "Cave"
        val record = WallScanRecord("2", simulate(survey).scan)
        record.review.addLruds = true
        WallScanApplier.rebuild(survey, record)
        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey, listOf("Cave"))
        WallScanStore.save(store, listOf("Cave"), "Cave", record)

        val reopened = SurveyStorage.load(store, listOf("Cave"))
        val recordBack = WallScanStore.loadAll(store, listOf("Cave"), "Cave").single()
        assertTrue(ScanSketchWriter.countDrawnBy(reopened, "2") > 0, "the scan's ids survive the sketch file")
        val legs = zigzag().getAllLegs().size

        WallScanApplier.remove(reopened, recordBack)

        assertEquals(0, drawnCount(reopened))
        assertEquals(legs, reopened.getAllLegs().size)
    }

    @Test
    fun aSketchWithNoScanInItWritesNoScanKey() {
        val survey = zigzag()
        survey.getSketch(Projection2D.PLAN).pathDetails.add(PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 0f)), Colour.BLACK))
        val text = SketchJson.write(survey.planSketch, "Cave")
        assertFalse("\"scan\"" in text)
    }

    @Test
    fun scanIdsAreFoundBesideTheSurveyAndNothingElseIs() {
        val store = InMemoryFileStore()
        store.writeText(listOf("Cave", "Cave.scan-2.json"), "{}")
        store.writeText(listOf("Cave", "Cave.scan-10.json"), "{}")
        store.writeText(listOf("Cave", "Cave.scan-1.json.autosave"), "{}")
        store.writeText(listOf("Cave", "Other.scan-3.json"), "{}")
        store.writeText(listOf("Cave", "Cave.plan.json"), "{}")

        assertEquals(listOf("2", "10"), WallScanStore.scanIdsIn(store, listOf("Cave"), "Cave"))
    }

    @Test
    fun anUnreadableScanIsReportedAndSkipped() {
        val store = InMemoryFileStore()
        store.writeText(listOf("Cave", "Cave.scan-1.json"), "not json")
        val problems = mutableListOf<String>()

        val records = WallScanStore.loadAll(store, listOf("Cave"), "Cave") { problems += it }

        assertTrue(records.isEmpty())
        assertEquals(1, problems.size)
    }

    @Test
    fun theNextIdIsNeverOneInUse() {
        val scan = WallScan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertEquals("1", WallScanStore.nextScanId(emptyList()))
        assertEquals("5", WallScanStore.nextScanId(listOf(WallScanRecord("4", scan), WallScanRecord("2", scan))))
    }

    @Test
    fun theZipCarriesTheScansFiles() {
        val survey = zigzag()
        survey.name = "Cave"
        val record = WallScanRecord("1", simulate(survey).scan)
        val store = InMemoryFileStore()
        WallScanStore.save(store, listOf("Cave"), "Cave", record)

        val names =
            SurveyZip.entries(
                survey,
                SurveyStorage.DEFAULT_VERSION_NAME,
                0,
                extraFiles = WallScanStore.zipEntries(store, listOf("Cave"), "Cave"),
            ).map(Zip.Entry::name)

        assertTrue("Cave.scan-1.json" in names, "$names")
        assertTrue("Cave.scan-1.points" in names, "$names")
    }
}

class ScanPlacementNudgeTest {

    @Test
    fun turningAboutAPointLeavesThatPointWhereItWas() {
        val placement = ScanPlacement(30f, Coord3D(4f, -2f, 1f))
        val pivot = Coord3D(10f, 7f, 0.5f)
        val turned = placement.turnedAbout(pivot, 15f)
        val inScan = placement.unplace(pivot)
        val after = turned.place(inScan)
        assertEquals(pivot.x, after.x, 1e-4f)
        assertEquals(pivot.y, after.y, 1e-4f)
        assertEquals(pivot.z, after.z, 1e-4f)
        assertEquals(45f, turned.headingDegrees, 1e-4f)
        assertEquals(355f, placement.turnedAbout(pivot, -35f).headingDegrees, 1e-3f)
    }

    @Test
    fun movingShiftsEveryPointByTheSameAmount() {
        val placement = ScanPlacement(70f, Coord3D(1f, 2f, 3f))
        val point = Coord3D(5f, -1f, 2f)
        val before = placement.place(point)
        val after = placement.moved(Coord3D(0.5f, -1f, 0.1f)).place(point)
        assertEquals(before.x + 0.5f, after.x, 1e-4f)
        assertEquals(before.y - 1f, after.y, 1e-4f)
        assertEquals(before.z + 0.1f, after.z, 1e-4f)
    }
}
