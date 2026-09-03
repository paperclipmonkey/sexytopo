package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Station
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Selection and hit-testing, from `Sketch.findNearest*` and the `GraphView` tolerances. */
class SketchHitTestingTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    @Test
    fun theNearestDetailWins() {
        val sketch = Sketch()
        val near = PathDetail(listOf(Coord2D(0f, 0f), Coord2D(0f, 10f)), Colour.BLACK)
        val far = PathDetail(listOf(Coord2D(3f, 0f), Coord2D(3f, 10f)), Colour.BLACK)
        sketch.pathDetails.add(near)
        sketch.pathDetails.add(far)

        val hit = findNearestVisibleItemWithin(sketch, Coord2D(1f, 5f), delta = 5f, pixelsPerMetre = 60f)
        assertSame(near, (hit as SketchItem.Drawn).detail)
    }

    @Test
    fun aDetailExactlyAtTheToleranceIsOutOfReach() {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(listOf(Coord2D(0f, 0f), Coord2D(0f, 10f)), Colour.BLACK))

        // The comparison is strictly less-than in the original.
        assertNull(findNearestVisibleItemWithin(sketch, Coord2D(1f, 5f), delta = 1f, pixelsPerMetre = 60f))
        assertTrue(
            findNearestVisibleItemWithin(sketch, Coord2D(1f, 5f), delta = 1.001f, pixelsPerMetre = 60f) != null,
        )
    }

    @Test
    fun symbolsAreEasierToHitThanLinesInsideTheirOwnBody() {
        val sketch = Sketch()
        val symbol = sketch.addSymbolDetail(Coord2D(0f, 0f), "SAND", size = 2f, angle = 0f)

        // Inside the radius (1m) the distance is halved, so 0.8m away reports 0.4m.
        assertClose(0.4f, distanceFrom(symbol, Coord2D(0.8f, 0f)))
        // Outside, it is measured from the symbol's edge rather than its centre.
        assertClose(0.5f, distanceFrom(symbol, Coord2D(1.5f, 0f)))
    }

    @Test
    fun textIsHitTestedAgainstItsEstimatedBox() {
        val sketch = Sketch()
        // Four characters at 1m: box is 2.4m wide, extending up 1m from the baseline.
        val text = sketch.addTextDetail(Coord2D(0f, 0f), "sump", size = 1f)

        val bounds = boundsOf(text)
        assertClose(0f, bounds.left)
        assertClose(2.4f, bounds.right)
        assertClose(-1f, bounds.top)
        assertClose(0f, bounds.bottom)

        // Inside the box: half the distance to the box centre (1.2, -0.5).
        assertClose(0.5f * 0.5f, distanceFrom(text, Coord2D(1.2f, 0f)))
        // Outside: distance to the nearest edge.
        assertClose(0.6f, distanceFrom(text, Coord2D(3f, -0.5f)))
    }

    @Test
    fun snappingPrefersTheClosestStrokeEnd() {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(listOf(Coord2D(0f, 0f), Coord2D(5f, 0f)), Colour.BLACK))
        sketch.pathDetails.add(PathDetail(listOf(Coord2D(5.2f, 0f), Coord2D(9f, 0f)), Colour.BLACK))

        assertEquals(Coord2D(5.2f, 0f), findEligibleSnapPointWithin(sketch, Coord2D(5.15f, 0f), 0.5f))
        assertNull(findEligibleSnapPointWithin(sketch, Coord2D(7f, 0f), 0.5f), "midpoints never snap")
    }

    @Test
    fun stationPickingIsInclusiveAtTheTolerance() {
        val space = Space<Coord2D>()
        val a = Station("A1")
        val b = Station("A2")
        space.addStation(a, Coord2D(0f, 0f))
        space.addStation(b, Coord2D(10f, 0f))

        assertSame(a, findNearestStationWithin(space, Coord2D(1f, 0f), 1f))
        assertSame(b, findNearestStationWithin(space, Coord2D(9f, 0f), 5f))
        assertNull(findNearestStationWithin(space, Coord2D(5f, 0f), 1f))
    }

    @Test
    fun theViewportConvertsTouchesToMetresAndBack() {
        val viewport = SketchViewport()
        assertEquals(60f, viewport.pixelsPerMetre)

        val surveyPoint = viewport.toSurvey(Coord2D(120f, 60f))
        assertEquals(Coord2D(2f, 1f), surveyPoint)
        assertEquals(Coord2D(120f, 60f), viewport.toView(surveyPoint))

        // A 10dp eraser at 60px/m reaches a sixth of a metre.
        assertClose(0.1667f, viewport.toSurveyDistance(SketchDefaults.DELETE_DETAILS_WITHIN_DP))
    }

    @Test
    fun zoomingKeepsTheFocusPointStill() {
        val viewport = SketchViewport()
        val focus = Coord2D(300f, 400f)
        val before = viewport.toSurvey(focus)

        assertTrue(viewport.adjustZoomBy(SketchViewport.ZOOM_IN_INCREMENT, focus))

        assertClose(before.x, viewport.toSurvey(focus).x)
        assertClose(before.y, viewport.toSurvey(focus).y)
        assertClose(66f, viewport.pixelsPerMetre)
    }

    @Test
    fun zoomOutOfRangeIsRefusedRatherThanClamped() {
        val viewport = SketchViewport()
        assertTrue(!viewport.setZoom(1000f, Coord2D.ORIGIN))
        assertEquals(60f, viewport.pixelsPerMetre, "an out-of-range zoom leaves the view alone")
        assertTrue(!viewport.setZoom(0.05f, Coord2D.ORIGIN))
        assertEquals(60f, viewport.pixelsPerMetre)
    }

    @Test
    fun directionalSymbolsAimAlongTheDrag() {
        // Dragging straight up the screen from the stamp point.
        assertClose(0f, directionalSymbolAngle(Coord2D(100f, 100f), Coord2D(100f, 0f)))
        // Dragging right.
        assertClose(90f, directionalSymbolAngle(Coord2D(100f, 100f), Coord2D(200f, 100f)))
    }

    @Test
    fun planAzimuthMeasuresClockwiseFromNorth() {
        assertClose(0f, planAzimuth(0f, -1f)) // -y is North on the plan
        assertClose(90f, planAzimuth(1f, 0f))
        assertClose(180f, planAzimuth(0f, 1f))
        assertClose(270f, planAzimuth(-1f, 0f))
    }

    @Test
    fun hotCornersCoverAllFourCorners() {
        val width = 1000f
        val height = 500f // corner delta is 5% of 500 = 25px
        assertTrue(hitsHotCorner(10f, 10f, width, height))
        assertTrue(hitsHotCorner(995f, 10f, width, height))
        assertTrue(hitsHotCorner(10f, 495f, width, height))
        assertTrue(hitsHotCorner(995f, 495f, width, height))
        assertTrue(!hitsHotCorner(500f, 250f, width, height))
        assertTrue(!hitsHotCorner(10f, 250f, width, height), "an edge alone is not a corner")
    }
}
