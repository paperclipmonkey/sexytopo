package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scan's geometry against the fixture survey whose answer is easy to state: stations 1 to 4
 * five metres apart due north, at y = 0, 5, 10 and 15.
 */
class ScanGeometryTest {

    /**
     * A box passage round the straight-north survey: walls 1.5 m either side, floor 1 m down,
     * roof 2 m up, a point every 10 cm. [missingRight] leaves a stretch of the east wall unscanned.
     */
    private fun boxPassage(missingRight: ClosedFloatingPointRange<Float>? = null): List<Coord3D> {
        val points = mutableListOf<Coord3D>()
        var y = -1f
        while (y <= 16f) {
            var z = -1f
            while (z <= 2f) {
                points.add(Coord3D(-1.5f, y, z))
                if (missingRight == null || y !in missingRight) points.add(Coord3D(1.5f, y, z))
                z += 0.1f
            }
            var x = -1.5f
            while (x <= 1.5f) {
                points.add(Coord3D(x, y, -1f))
                points.add(Coord3D(x, y, 2f))
                x += 0.1f
            }
            y += 0.1f
        }
        return points
    }

    @Test
    fun stationPositionsAreTheSurveysOwn() {
        val frame = SurveyFrame(TestSurveys.createStraightNorth())

        assertEquals(Coord3D(0f, 15f, 0f), frame.stationPositions["4"])
    }

    @Test
    fun thePlanDropsTheHeightAndTurnsNorthUpThePage() {
        val frame = SurveyFrame(TestSurveys.createStraightNorth())

        assertEquals(Coord2D(1f, -2.5f), frame.toPlan(Coord3D(1f, 2.5f, 3f)))
    }

    @Test
    fun theElevationKeepsHeightAndDistanceAlongButNotTheSideways() {
        val frame = SurveyFrame(TestSurveys.createStraightNorth())

        // Halfway along leg 1-2, a metre up, half a metre to the east.
        val point = assertNotNull(frame.toExtendedElevation(Coord3D(0.5f, 2.5f, 1f)))

        assertEquals(2.5f, point.x, 0.001f)
        assertEquals(-1f, point.y, 0.001f)
    }

    @Test
    fun aSurveyWithNoLegsHasNoElevationToPutAnythingOn() {
        val frame = SurveyFrame(Survey())

        assertNull(frame.toExtendedElevation(Coord3D(1f, 1f, 1f)))
    }

    @Test
    fun aGapInALineBreaksItsStroke() {
        val frame = SurveyFrame(TestSurveys.createStraightNorth())
        val line = listOf(0f, 0.5f, 1f, 6f, 6.5f, 7f).map { Coord3D(1f, it, 0f) }

        val strokes = frame.toStrokes(line, Projection2D.PLAN)

        assertEquals(2, strokes.size)
    }

    @Test
    fun theLrudAtAStationIsTheBoxAroundIt() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)
        val grid = PointGrid(boxPassage())

        val lrud = assertNotNull(ScanProfiles.lrudAt(survey, frame, grid, survey.getStationByName("2")!!))

        assertEquals(1.5f, assertNotNull(lrud.left), 0.05f)
        assertEquals(1.5f, assertNotNull(lrud.right), 0.05f)
        assertEquals(2f, assertNotNull(lrud.up), 0.05f)
        assertEquals(1f, assertNotNull(lrud.down), 0.05f)
    }

    @Test
    fun aDirectionNobodyScannedHasNoLrud() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)
        val grid = PointGrid(boxPassage(missingRight = 4f..6f))

        val lrud = assertNotNull(ScanProfiles.lrudAt(survey, frame, grid, survey.getStationByName("2")!!))

        assertNull(lrud.right)
        assertEquals(3, ScanProfiles.lrudSplays(survey, survey.getStationByName("2")!!, lrud).size)
    }

    @Test
    fun lrudSplaysPointTheWayTheirNamesSay() {
        val survey = TestSurveys.createStraightNorth()
        val station = survey.getStationByName("2")!!

        val splays = ScanProfiles.lrudSplays(survey, station, WallDistances(1f, 2f, 3f, 4f))

        // Passage runs north, so left is west and right is east.
        assertEquals(listOf(270f, 90f), splays.take(2).map { it.azimuth })
        assertEquals(listOf(1f, 2f, 3f, 4f), splays.map { it.distance })
    }

    @Test
    fun generatedWallsFollowTheWallsOfThePassage() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)

        val walls = ScanProfiles.wallsAlongLegs(frame, survey, PointGrid(boxPassage()))

        // Three legs in a line: a wall each side of each, joined over the stations into one wall
        // a side, as a surveyor would draw a straight passage.
        assertEquals(2, walls.size)
        for (wall in walls) {
            for (point in wall) assertEquals(1.5f, kotlin.math.abs(point.x), 0.05f)
        }
    }

    @Test
    fun anUnscannedStretchIsAGapInTheGeneratedWall() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)

        val walls = ScanProfiles.wallsAlongLegs(frame, survey, PointGrid(boxPassage(missingRight = 6f..9f)))

        val eastWalls = walls.filter { it.first().x > 0 }
        // The east wall broken by the gap and nowhere else: three metres is too far to join.
        assertEquals(2, eastWalls.size)
        assertTrue(eastWalls.flatten().none { it.y in 6.6f..8.4f })
    }

    @Test
    fun aScanDrawsIntoBothSketchesAndCanBeTakenOutAgain() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)
        val plan = survey.getSketch(Projection2D.PLAN)
        val handDrawn = PathDetail(listOf(Coord2D(1f, -1f), Coord2D(1f, -4f)), Colour.BLACK)
        plan.pathDetails.add(handDrawn)

        val stroke = PenStroke()
        for (i in 0..300) stroke.add(Coord3D(1.5f, i * 0.02f, 0f))
        val content =
            ScanSketchWriter.write(
                survey,
                frame,
                ScanPlacement.IDENTITY,
                listOf(PenLine(stroke, Colour.BLACK)),
                listOf(SymbolStamp(Symbol.STALAGMITE, Coord3D(1f, 3f, -1f))),
            )

        assertEquals(3, plan.pathDetails.size + plan.symbolDetails.size)
        assertTrue(survey.getSketch(Projection2D.EXTENDED_ELEVATION).pathDetails.isNotEmpty())

        ScanSketchWriter.remove(survey, content)

        assertEquals(listOf(handDrawn), plan.pathDetails)
        assertTrue(plan.symbolDetails.isEmpty())
        assertTrue(survey.getSketch(Projection2D.EXTENDED_ELEVATION).pathDetails.isEmpty())
    }

    @Test
    fun rewritingReplacesWhatWasDrawnRatherThanAddingToIt() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)
        val stamps = listOf(SymbolStamp(Symbol.STALAGMITE, Coord3D(1f, 3f, -1f)))

        val first = ScanSketchWriter.write(survey, frame, ScanPlacement.IDENTITY, emptyList(), stamps)
        ScanSketchWriter.write(survey, frame, ScanPlacement.IDENTITY, emptyList(), stamps, previous = first)

        assertEquals(1, survey.getSketch(Projection2D.PLAN).symbolDetails.size)
    }

    @Test
    fun aStampIsTurnedWithTheScan() {
        val survey = TestSurveys.createStraightNorth()
        val frame = SurveyFrame(survey)
        val placement = ScanPlacement(90f, Coord3D.ORIGIN)

        ScanSketchWriter.write(
            survey,
            frame,
            placement,
            emptyList(),
            listOf(SymbolStamp(Symbol.WATER_FLOW, Coord3D(0f, 3f, 0f), bearingInScan = 10f)),
        )

        val symbol = survey.getSketch(Projection2D.PLAN).symbolDetails.single()
        assertEquals(100f, symbol.angle)
        // Three metres north in the scan, turned a quarter clockwise, is three metres east.
        assertEquals(Coord2D(3f, 0f), symbol.position)
    }
}

class WallCornerTest {

    /** A passage 2 m wide round a right-angled bend at station 2: north 5 m, then east 5 m. */
    private fun bend(): Pair<Survey, List<Coord3D>> {
        val survey = Survey()
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        SurveyUpdater.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        val points = mutableListOf<Coord3D>()
        // The walls of an L: west and east of the first leg, north and south of the second.
        var t = -1f
        while (t <= 6f) {
            for (z in listOf(-0.8f, -0.4f, 0f, 0.4f, 0.8f)) {
                if (t <= 6f) points.add(Coord3D(-1f, t, z)) // outer west wall, all the way up
                if (t <= 4f) points.add(Coord3D(1f, t, z)) // inner east wall, stops at the bend
                if (t >= -1f) points.add(Coord3D(t, 6f, z)) // outer north wall
                if (t >= 1f) points.add(Coord3D(t, 4f, z)) // inner south wall, from the bend on
            }
            t += 0.05f
        }
        return survey to points
    }

    @Test
    fun theInsideOfABendIsNotDrawnAcrossThePassage() {
        val (survey, points) = bend()
        val walls = ScanProfiles.wallsAlongLegs(SurveyFrame(survey), survey, PointGrid(points))

        // Nothing drawn in the passage itself, which is everything within a metre of a leg.
        for (point in walls.flatten()) {
            val nearFirst = kotlin.math.abs(point.x) < 0.9f && point.y in 0f..5f
            val nearSecond = kotlin.math.abs(point.y - 5f) < 0.9f && point.x in 0f..5f
            assertTrue(!nearFirst && !nearSecond, "wall drawn inside the passage at ${point.x}, ${point.y}")
        }
    }

    @Test
    fun theOutsideOfABendIsOneWall() {
        val (survey, points) = bend()
        val walls = ScanProfiles.wallsAlongLegs(SurveyFrame(survey), survey, PointGrid(points))

        // The west wall of the first leg and the north wall of the second meet at the outside
        // corner, so they are one line.
        val outer = walls.filter { wall -> wall.any { it.x < -0.5f } }
        assertEquals(1, outer.size)
        assertTrue(outer.single().any { it.y > 5.5f && it.x > 3f })
    }
}
