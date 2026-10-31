package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scans made up from a known survey: walls round each leg, a phone path along the centerline a
 * little to one side, then the whole lot moved into a scan frame by a known placement. The fit
 * should find that placement again from a rough pin and a compass that is out.
 */
class FittedPlacementTest {

    private class Synthetic(val points: List<Coord3D>, val path: List<Coord3D>)

    /** Walls 1.5 m either side of each leg, floor 1 m down, roof 2 m up; path 0.3 m right of it. */
    private fun scanOf(survey: Survey, truth: ScanPlacement, fromMetres: Float = 0f, toMetres: Float = Float.MAX_VALUE): Synthetic {
        val frame = SurveyFrame(survey)
        val points = mutableListOf<Coord3D>()
        val path = mutableListOf<Coord3D>()
        var travelled = 0f
        for (leg in frame.legLines) {
            val dx = leg.end.x - leg.start.x
            val dy = leg.end.y - leg.start.y
            val length = sqrt(dx * dx + dy * dy)
            val rightX = dy / length
            val rightY = -dx / length
            var along = 0f
            while (along <= length) {
                val t = along / length
                val cx = leg.start.x + t * dx
                val cy = leg.start.y + t * dy
                val cz = leg.start.z + t * (leg.end.z - leg.start.z)
                if (travelled + along in fromMetres..toMetres) {
                    var z = -1f
                    while (z <= 2f) {
                        points.add(Coord3D(cx + 1.5f * rightX, cy + 1.5f * rightY, cz + z))
                        points.add(Coord3D(cx - 1.5f * rightX, cy - 1.5f * rightY, cz + z))
                        z += 0.2f
                    }
                    var side = -1.5f
                    while (side <= 1.5f) {
                        points.add(Coord3D(cx + side * rightX, cy + side * rightY, cz - 1f))
                        points.add(Coord3D(cx + side * rightX, cy + side * rightY, cz + 2f))
                        side += 0.2f
                    }
                    path.add(Coord3D(cx + 0.3f * rightX, cy + 0.3f * rightY, cz + 0.2f))
                }
                along += 0.2f
            }
            travelled += length
        }
        // Walls are drawn per leg, so at a bend one leg's wall cuts across the next leg's passage.
        // A real cave has no rock there; keep only wall points clear of every leg.
        val legs = frame.legLines
        val walls =
            points.filter { point ->
                legs.all { leg ->
                    val flat = Coord3D(point.x, point.y, 0f)
                    val flatLeg = org.hwyl.sexytopo.shared.model.graph.Line(
                        Coord3D(leg.start.x, leg.start.y, 0f),
                        Coord3D(leg.end.x, leg.end.y, 0f),
                    )
                    SurveyFrame.distanceToSegment(flat, flatLeg) >= 1.4f ||
                        abs(point.z - leg.start.z) >= 0.9f
                }
            }
        return Synthetic(walls.map { truth.unplace(it) }, path.map { truth.unplace(it) })
    }

    private fun Survey.position(name: String): Coord3D = SurveyFrame(this).stationPositions.getValue(name)

    private fun headingError(a: Float, b: Float): Float {
        val difference = abs(a - b) % 360
        return minOf(difference, 360 - difference)
    }

    @Test
    fun aScanRoundABendIsFoundFromARoughPinAndAWrongCompass() {
        val survey = TestSurveys.createRightRight()
        val truth = ScanPlacement(20f, Coord3D(3f, -2f, 0.5f))
        val scan = scanOf(survey, truth)
        val station = survey.position("1")
        val pin = Coord3D(station.x + 1.5f, station.y - 1f, station.z)

        val result = assertNotNull(FittedPlacement.fit(scan.path, scan.points, SurveyFrame(survey), pin, 0f))

        assertTrue(headingError(20f, result.placement.headingDegrees) < 2f, "heading ${result.placement.headingDegrees}")
        val placedStart = result.placement.place(scan.path.first())
        val trueStart = truth.place(scan.path.first())
        assertTrue(StationAnchoring.distance(placedStart, trueStart) < 0.5f, "start off by ${StationAnchoring.distance(placedStart, trueStart)}")
        assertTrue(result.legsThroughRock < 0.05f)
    }

    @Test
    fun aStraightFeaturelessPassageSaysItCouldSlide() {
        val survey = TestSurveys.createStraightNorth()
        val truth = ScanPlacement(0f, Coord3D.ORIGIN)
        val scan = scanOf(survey, truth, fromMetres = 4f, toMetres = 9f)
        val pin = survey.position("2")

        val result = assertNotNull(FittedPlacement.fit(scan.path, scan.points, SurveyFrame(survey), pin, 0f))

        assertTrue(result.slideMetres >= 2f, "slide ${result.slideMetres}")
    }

    @Test
    fun noCenterlineNearThePinIsNoPlacement() {
        val survey = TestSurveys.createStraightNorth()
        val scan = scanOf(survey, ScanPlacement.IDENTITY)

        assertNull(FittedPlacement.fit(scan.path, scan.points, SurveyFrame(survey), Coord3D(500f, 500f, 0f), 0f))
    }

    @Test
    fun applyingAFittedScanDrawsWallsAndAddsLruds() {
        val survey = TestSurveys.createStraightNorth()
        val scan = scanOf(survey, ScanPlacement(10f, Coord3D(1f, 1f, 0f)))
        val wallScan = WallScan(scan.points, scan.path, emptyList(), emptyList(), emptyList(), startStation = "1")

        val report = WallScanApplier.apply(survey, wallScan)

        assertEquals(PlacementMode.FITTED, report.mode)
        assertTrue(survey.getSketch(Projection2D.PLAN).pathDetails.size >= 6, report.message)
        assertTrue(report.lrudsAdded >= 3, report.message)
    }

    @Test
    fun applyingAnAnchoredScanUsesTheTouchesAndLeavesTheSurveyAlone() {
        val survey = TestSurveys.createStraightNorth()
        val truth = ScanPlacement(35f, Coord3D(-2f, 4f, 0f))
        val touches =
            listOf("1", "3").map { StationTouch(it, truth.unplace(survey.position(it))) }
        val stroke = PenStroke()
        for (i in 0..300) stroke.add(truth.unplace(Coord3D(1.5f, i * 0.02f, 0f)))
        val legsBefore = survey.getAllLegs().size

        val report =
            WallScanApplier.apply(
                survey,
                WallScan(emptyList(), emptyList(), touches, listOf(PenLine(stroke, Colour.BLACK)), emptyList(), null),
            )

        assertEquals(PlacementMode.ANCHORED, report.mode)
        assertEquals(35f, assertNotNull(report.placement).headingDegrees, 0.01f)
        assertEquals(legsBefore, survey.getAllLegs().size)
        val drawn = survey.getSketch(Projection2D.PLAN).pathDetails.single().path
        for (point in drawn) assertEquals(1.5f, point.x, 0.05f)
    }

    @Test
    fun aScanWithNoTouchesAndNoPinSaysWhatIsMissing() {
        val survey = TestSurveys.createStraightNorth()

        val report = WallScanApplier.apply(survey, WallScan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null))

        assertNull(report.mode)
        assertEquals(WallScanApplier.NOTHING_TO_PLACE_BY, report.message)
    }
}
