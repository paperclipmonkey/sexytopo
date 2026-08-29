package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.math.averageAzimuths
import org.hwyl.sexytopo.shared.math.getDistanceFromLine
import org.hwyl.sexytopo.shared.math.toCartesian
import org.hwyl.sexytopo.shared.math.toLeg
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from Space3DUtilsTest / Space2DUtilsTest and the Leg validation rules. */
class GeometryTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "expected $expected but was $actual",
        )
    }

    @Test
    fun northIsPositiveY() {
        val end = toCartesian(Coord3D.ORIGIN, Leg(10f, 0f, 0f))
        assertClose(0f, end.x)
        assertClose(10f, end.y)
        assertClose(0f, end.z)
    }

    @Test
    fun eastIsPositiveX() {
        val end = toCartesian(Coord3D.ORIGIN, Leg(10f, 90f, 0f))
        assertClose(10f, end.x)
        assertClose(0f, end.y)
        assertClose(0f, end.z)
    }

    @Test
    fun upIsPositiveZ() {
        val end = toCartesian(Coord3D.ORIGIN, Leg(10f, 0f, 90f))
        assertClose(0f, end.x)
        assertClose(0f, end.y)
        assertClose(10f, end.z)
    }

    @Test
    fun cartesianRoundTripsBackToTheSameReading() {
        val original = Leg(12.5f, 137f, -23f)
        val recovered = toLeg(toCartesian(Coord3D.ORIGIN, original))
        assertClose(original.distance, recovered.distance)
        assertClose(original.azimuth, recovered.azimuth, 0.01f)
        assertClose(original.inclination, recovered.inclination, 0.01f)
    }

    @Test
    fun planProjectionFlipsY() {
        // Maths space has y up; the screen has y down. Losing this flip mirrors every sketch.
        val projected = Projection2D.PLAN.project(Coord3D(3f, 4f, 5f))
        assertClose(3f, projected.x)
        assertClose(-4f, projected.y)
    }

    @Test
    fun elevationProjectionsDropTheExpectedAxis() {
        val point = Coord3D(3f, 4f, 5f)
        assertClose(4f, Projection2D.ELEVATION_NS.project(point).x)
        assertClose(-5f, Projection2D.ELEVATION_NS.project(point).y)
        assertClose(3f, Projection2D.ELEVATION_EW.project(point).x)
        assertClose(-5f, Projection2D.ELEVATION_EW.project(point).y)
    }

    @Test
    fun azimuthsAverageAcrossTheZeroSeam() {
        // {359, 1} must average to 0, not 180.
        val average = averageAzimuths(359f, 1f)
        assertTrue(average < 1f || average > 359f, "expected ~0 but was $average")
    }

    @Test
    fun azimuthsAverageNormallyAwayFromTheSeam() {
        assertClose(90f, averageAzimuths(80f, 100f))
    }

    @Test
    fun anglesWrapIntoRange() {
        assertClose(10f, adjustAngle(350f, 20f))
        assertClose(350f, adjustAngle(10f, -20f))
    }

    @Test
    fun distanceFromLineClampsToSegmentEnds() {
        val start = Coord2D(0f, 0f)
        val end = Coord2D(10f, 0f)
        assertClose(5f, getDistanceFromLine(Coord2D(5f, 5f), start, end))
        // Beyond the end of the segment, distance is measured to the endpoint.
        assertClose(5f, getDistanceFromLine(Coord2D(15f, 0f), start, end))
    }

    @Test
    fun inclinationAcceptsTheTheodoliteBand() {
        // Easy to lose in a port: 270..360 is legal as well as -90..90.
        assertTrue(Leg.isInclinationLegal(45f))
        assertTrue(Leg.isInclinationLegal(-90f))
        assertTrue(Leg.isInclinationLegal(300f))
        assertFalse(Leg.isInclinationLegal(100f))
        assertFalse(Leg.isInclinationLegal(200f))
    }

    @Test
    fun azimuthMustBeInRange() {
        assertTrue(Leg.isAzimuthLegal(0f))
        assertTrue(Leg.isAzimuthLegal(359.9f))
        assertFalse(Leg.isAzimuthLegal(360f))
        assertFalse(Leg.isAzimuthLegal(-1f))
    }

    @Test
    fun reversingALegFlipsBearingAndInclination() {
        val leg = Leg(5f, 90f, 30f)
        val reversed = leg.reverse()
        assertClose(270f, reversed.azimuth)
        assertClose(-30f, reversed.inclination)
        assertClose(5f, reversed.distance)
        assertEquals(true, reversed.wasShotBackwards)
    }

    @Test
    fun splaysHaveNoDestination() {
        assertFalse(Leg(5f, 90f, 0f).hasDestination())
    }
}
