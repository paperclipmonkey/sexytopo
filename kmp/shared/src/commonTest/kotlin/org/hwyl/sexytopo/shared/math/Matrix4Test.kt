package org.hwyl.sexytopo.shared.math

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 4x4 maths the 3D view stands on, checked against `android.opengl.Matrix`'s definitions: a
 * sign error here does not crash, it draws a cave that is subtly wrong.
 */
class Matrix4Test {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-5f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    private fun assertIsIdentity(matrix: Matrix4) {
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                assertClose(if (row == column) 1f else 0f, matrix[row, column], 1e-4f)
            }
        }
    }

    @Test
    fun anIdentityLeavesAPointWhereItIs() {
        val point = Matrix4.identity().transform(3f, -4f, 5f, 1f)
        assertEquals(listOf(3f, -4f, 5f, 1f), point.toList())
    }

    @Test
    fun columnMajorIndexingMatchesOpenGl() {
        // Element (row 2, column 1) is at 1 * 4 + 2.
        val m = Matrix4(FloatArray(16) { it.toFloat() })
        assertEquals(6f, m[2, 1])
        assertEquals(3f, m[3, 0])
    }

    @Test
    fun translationMovesAPointAndLeavesADirectionAlone() {
        val translate = Matrix4.identity().translated(10f, 20f, 30f)

        val point = translate.transform(1f, 2f, 3f, 1f)
        assertEquals(listOf(11f, 22f, 33f, 1f), point.toList())

        // w of 0 is a direction, which a translation must not change.
        val direction = translate.transform(1f, 2f, 3f, 0f)
        assertEquals(listOf(1f, 2f, 3f, 0f), direction.toList())
    }

    @Test
    fun multiplyingAppliesTheRightHandMatrixFirst() {
        val translate = Matrix4.identity().translated(1f, 0f, 0f)
        val scaleByTwo =
            Matrix4(
                floatArrayOf(
                    2f, 0f, 0f, 0f,
                    0f, 2f, 0f, 0f,
                    0f, 0f, 2f, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )

        // Scale then translate: the translation is not scaled.
        assertEquals(listOf(3f, 0f, 0f, 1f), (translate * scaleByTwo).transform(1f, 0f, 0f, 1f).toList())
        // Translate then scale: it is.
        assertEquals(listOf(4f, 0f, 0f, 1f), (scaleByTwo * translate).transform(1f, 0f, 0f, 1f).toList())
    }

    @Test
    fun aMatrixTimesItsInverseIsTheIdentity() {
        val view = Matrix4.lookAt(30f, -12f, 40f, 1f, 2f, 3f, 0f, 0f, 1f)
        assertIsIdentity(view * assertNotNull(view.inverted()))
        assertIsIdentity(assertNotNull(view.inverted()) * view)

        val projection = Matrix4.perspective(45f, 1.7f, 0.1f, 1000f)
        assertIsIdentity(projection * assertNotNull(projection.inverted()))
    }

    @Test
    fun aFlatMatrixHasNoInverse() {
        assertNull(Matrix4(FloatArray(16)).inverted())
    }

    /** `Matrix.perspectiveM`: 45 degrees vertical, and the horizontal follows the aspect ratio. */
    @Test
    fun theProjectionMatchesTheAndroidOne() {
        val projection = Matrix4.perspective(90f, 2f, 1f, 101f)
        // f = 1 / tan(45 degrees) = 1.
        assertClose(0.5f, projection[0, 0])
        assertClose(1f, projection[1, 1])
        assertClose((101f + 1f) / (1f - 101f), projection[2, 2])
        assertClose(-1f, projection[3, 2])
        assertClose(2f * 101f * 1f / (1f - 101f), projection[2, 3])
        assertClose(0f, projection[3, 3])
    }

    /** At 90 degrees, a point one unit away and one unit up sits exactly on the top of the screen. */
    @Test
    fun theFieldOfViewIsVertical() {
        val projection = Matrix4.perspective(90f, 1f, 0.1f, 100f)
        // Looking down -z, as OpenGL does.
        val clip = projection.transform(0f, 1f, -1f, 1f)
        assertClose(1f, clip[1] / clip[3])
    }

    @Test
    fun lookAtPutsTheEyeAtTheOriginOfViewSpace() {
        val view = Matrix4.lookAt(10f, 20f, 30f, 0f, 0f, 0f, 0f, 0f, 1f)
        val eye = view.transform(10f, 20f, 30f, 1f)
        assertClose(0f, eye[0])
        assertClose(0f, eye[1])
        assertClose(0f, eye[2])
    }

    /** What the camera is looking at ends up straight ahead, which in OpenGL is down -z. */
    @Test
    fun lookAtPutsTheTargetStraightAhead() {
        val view = Matrix4.lookAt(0f, -10f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val target = view.transform(0f, 0f, 0f, 1f)
        assertClose(0f, target[0])
        assertClose(0f, target[1])
        assertClose(-10f, target[2])
    }

    /** Up is up: the survey's world is z-up, so a point above the target draws above the middle. */
    @Test
    fun theUpVectorDecidesWhichWayIsUp() {
        val view = Matrix4.lookAt(0f, -10f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val above = view.transform(0f, 0f, 1f, 1f)
        assertClose(1f, above[1])
        assertClose(0f, above[0])
    }

    @Test
    fun theViewMatrixKeepsLengths() {
        val view = Matrix4.lookAt(7f, -3f, 11f, 0f, 0f, 0f, 0f, 0f, 1f)
        val a = view.transform(1f, 2f, 3f, 1f)
        val b = view.transform(4f, -2f, 0f, 1f)
        val moved =
            sqrt(
                (a[0] - b[0]) * (a[0] - b[0]) +
                    (a[1] - b[1]) * (a[1] - b[1]) +
                    (a[2] - b[2]) * (a[2] - b[2]),
            )
        val original = sqrt(3f * 3f + 4f * 4f + 3f * 3f)
        assertClose(original, moved, 1e-4f)
    }
}
