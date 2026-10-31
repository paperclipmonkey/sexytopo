package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationAnchoringTest {

    private val stations =
        mapOf(
            "1" to Coord3D(0f, 0f, 0f),
            "2" to Coord3D(0f, 5f, -1f),
            "3" to Coord3D(4f, 8f, -1.5f),
        )

    /**
     * Where each station would appear in a scan whose frame is turned [heading] degrees
     * anticlockwise from the survey's and offset by [origin]: the inverse of the placement the
     * solver should find.
     */
    private fun asScanned(heading: Float, origin: Coord3D, vararg names: String): List<StationTouch> {
        val undo = ScanPlacement(-heading, Coord3D.ORIGIN)
        return names.map { name ->
            val station = stations.getValue(name)
            val shifted = Coord3D(station.x - origin.x, station.y - origin.y, station.z - origin.z)
            StationTouch(name, undo.place(shifted))
        }
    }

    @Test
    fun twoTouchesFixHeadingAndPosition() {
        val origin = Coord3D(1f, 2f, 0.5f)
        val result = assertNotNull(StationAnchoring.solve(asScanned(30f, origin, "1", "2"), stations))

        assertTrue(result.isHeadingFromStations)
        assertEquals(30f, result.placement.headingDegrees, 0.01f)
        assertEquals(0f, result.worstResidual, 0.001f)
    }

    @Test
    fun aHeadingAndItsMirrorImageAreNotConfused() {
        // A sign slip in the cross product gives -100 here, which also "places" the scan, just
        // turned the wrong way. Only a turn far from zero tells the two apart.
        val result = assertNotNull(StationAnchoring.solve(asScanned(100f, Coord3D.ORIGIN, "1", "2", "3"), stations))

        assertEquals(100f, result.placement.headingDegrees, 0.01f)
    }

    @Test
    fun placingAScannedStationPutsItOnTheStation() {
        val touches = asScanned(250f, Coord3D(-3f, 7f, 2f), "1", "2", "3")
        val result = assertNotNull(StationAnchoring.solve(touches, stations))

        val placed = result.placement.place(touches[2].inScan)
        assertEquals(4f, placed.x, 0.001f)
        assertEquals(8f, placed.y, 0.001f)
        assertEquals(-1.5f, placed.z, 0.001f)
    }

    @Test
    fun oneTouchWithNoHeadingToFallBackOnGivesNothing() {
        assertNull(StationAnchoring.solve(asScanned(0f, Coord3D.ORIGIN, "2"), stations))
    }

    @Test
    fun oneTouchUsesThePriorHeadingAndSaysSo() {
        val result =
            assertNotNull(
                StationAnchoring.solve(asScanned(40f, Coord3D.ORIGIN, "2"), stations, priorHeadingDegrees = 40f),
            )

        assertFalse(result.isHeadingFromStations)
        assertEquals(40f, result.placement.headingDegrees)
        assertEquals(0f, result.worstResidual, 0.001f)
    }

    @Test
    fun touchesTooCloseTogetherDoNotSetTheHeading() {
        val near = mapOf("a" to Coord3D(0f, 0f, 0f), "b" to Coord3D(0.3f, 0f, 0f))
        val touches = listOf(StationTouch("a", Coord3D(0f, 0f, 0f)), StationTouch("b", Coord3D(0f, 0.3f, 0f)))

        val result = assertNotNull(StationAnchoring.solve(touches, near, priorHeadingDegrees = 12f))

        assertFalse(result.isHeadingFromStations)
        assertEquals(12f, result.placement.headingDegrees)
    }

    @Test
    fun aStationNotInTheSurveyIsSetAsideAndReported() {
        val touches = asScanned(0f, Coord3D.ORIGIN, "1", "2") + StationTouch("99", Coord3D(1f, 1f, 1f))

        val result = assertNotNull(StationAnchoring.solve(touches, stations))

        assertEquals(listOf("99"), result.unmatched.map { it.stationName })
        assertEquals(2, result.residuals.size)
    }

    @Test
    fun aTouchUnderTheWrongNameStandsOutInTheResiduals() {
        val good = asScanned(0f, Coord3D.ORIGIN, "1", "2", "3")
        // Station 3 touched, but recorded as 2: one touch disagrees with the other two.
        val touches = good.take(2) + StationTouch("2", good[2].inScan)

        val result = assertNotNull(StationAnchoring.solve(touches, stations))

        val worst = result.residuals.maxBy { it.metres }
        assertTrue(worst.metres > 1f)
    }

    @Test
    fun touchingAStationTwiceIsAnotherObservationOfIt() {
        val touches = asScanned(15f, Coord3D.ORIGIN, "1", "2", "1")

        val result = assertNotNull(StationAnchoring.solve(touches, stations))

        assertEquals(3, result.residuals.size)
        assertEquals(15f, result.placement.headingDegrees, 0.01f)
    }

    @Test
    fun theCornerOfAPhoneThatHasNotMovedIsItsOffsetTurnedIntoSurveyAxes() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        // Ten centimetres out of the back of the lens is minus z in camera axes, minus z in
        // ARKit's world (north), and so plus y in the survey's.
        val corner = touchPointInScan(identity, Coord3D(0f, 0f, -0.1f))

        assertEquals(Coord3D(0f, 0.1f, 0f), corner)
    }

    @Test
    fun theTranslationIsReadFromTheLastColumnNotTheLastRow() {
        // Column-major: translation at 12, 13, 14. Read row-major, these would be ignored and the
        // corner would come out at the camera's own origin.
        val moved = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 2f, 1.5f, -3f, 1f)

        val corner = touchPointInScan(moved, Coord3D.ORIGIN)

        // ARKit (2, 1.5, -3): 2 east, 1.5 up, 3 north.
        assertEquals(Coord3D(2f, 3f, 1.5f), corner)
    }
}
