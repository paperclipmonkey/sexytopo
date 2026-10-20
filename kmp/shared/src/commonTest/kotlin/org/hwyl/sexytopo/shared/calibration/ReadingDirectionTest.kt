package org.hwyl.sexytopo.shared.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android app's `ReadingDirectionTest`, ported: which way the instrument was pointing, from
 * idealised sensor vectors under a magnetic field dipping at 66 degrees, as it does in Britain.
 */
class ReadingDirectionTest {

    private val dip = 66 * PI / 180

    private fun vector(x: Double, y: Double, z: Double) = Vector(x.toFloat(), y.toFloat(), z.toFloat())

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) <= DELTA, "expected $expected but was $actual")
    }

    @Test
    fun levelPointingNorth() {
        val direction =
            ReadingDirection.fromVectors(vector(0.0, 0.0, 1.0), vector(cos(dip), 0.0, sin(dip)))

        assertClose(0.0, direction.inclination)
        assertClose(0.0, direction.azimuth)
        assertClose(0.0, direction.roll)
    }

    @Test
    fun levelPointingEast() {
        val direction =
            ReadingDirection.fromVectors(vector(0.0, 0.0, 1.0), vector(0.0, -cos(dip), sin(dip)))

        assertClose(0.0, direction.inclination)
        assertClose(90.0, direction.azimuth)

        val laser = direction.toVector()
        assertClose(0.0, laser.x.toDouble())
        assertClose(1.0, laser.y.toDouble())
        assertClose(0.0, laser.z.toDouble())
    }

    @Test
    fun pointingUp() {
        val direction =
            ReadingDirection.fromVectors(vector(-1.0, 0.0, 0.0), vector(-sin(dip), 0.0, cos(dip)))

        assertClose(90.0, direction.inclination)
    }

    @Test
    fun rolledOntoItsSide() {
        val direction =
            ReadingDirection.fromVectors(vector(0.0, 1.0, 0.0), vector(cos(dip), sin(dip), 0.0))

        assertClose(0.0, direction.inclination)
        assertClose(0.0, direction.azimuth)
        assertClose(90.0, direction.roll)
    }

    @Test
    fun levelFaceUpIsHorizontalFaceUp() {
        val direction =
            ReadingDirection.fromVectors(vector(0.0, 0.0, 1.0), vector(cos(dip), 0.0, sin(dip)))

        assertEquals(ReadingDirection.Pointing.HORIZONTAL, direction.pointing)
        assertEquals(ReadingDirection.Face.UP, direction.face)
    }

    @Test
    fun cornerUpOnItsSide() {
        val inclination = 35 * PI / 180
        // Pointing north and up, rolled so gravity lies along y.
        val g = vector(-sin(inclination), cos(inclination), 0.0)
        val m = vector(cos(dip + inclination), sin(dip + inclination), 0.0)

        val direction = ReadingDirection.fromVectors(g, m)

        assertClose(35.0, direction.inclination)
        assertEquals(ReadingDirection.Pointing.CORNER_UP, direction.pointing)
        assertEquals(ReadingDirection.Face.SIDE, direction.face)
    }

    @Test
    fun pointingUpAndDown() {
        val m = vector(-sin(dip), 0.0, cos(dip))

        assertEquals(
            ReadingDirection.Pointing.UP,
            ReadingDirection.fromVectors(vector(-1.0, 0.0, 0.0), m).pointing,
        )
        assertEquals(
            ReadingDirection.Pointing.DOWN,
            ReadingDirection.fromVectors(vector(1.0, 0.0, 0.0), m).pointing,
        )
        assertTrue(ReadingDirection.Pointing.UP.isVertical)
        assertTrue(!ReadingDirection.Pointing.CORNER_DOWN.isVertical)
    }

    private companion object {
        const val DELTA = 0.01
    }
}
