package org.hwyl.sexytopo.shared.math

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A 4x4 transform, stored column-major, with the handful of operations the 3D view needs.
 *
 * Ported from `android.opengl.Matrix`, which the Java's `SurveyRenderer` uses and which is not
 * available anywhere else. Column-major means element `(row, column)` lives at `m[column * 4 + row]`
 * — the OpenGL convention, kept because every formula below is copied from the Android source and
 * changing the layout would mean re-deriving all of them.
 *
 * Float rather than Double throughout, again to match: the Java accumulates a camera in floats, and
 * a port that quietly used doubles would drift away from it in a way no test would catch.
 */
class Matrix4(internal val m: FloatArray) {

    init {
        require(m.size == 16) { "a 4x4 matrix has 16 elements, not ${m.size}" }
    }

    operator fun get(row: Int, column: Int): Float = m[column * 4 + row]

    /** `this * other`, in the sense `Matrix.multiplyMM(result, this, other)` does. */
    operator fun times(other: Matrix4): Matrix4 {
        val result = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += this.m[k * 4 + row] * other.m[column * 4 + k]
                }
                result[column * 4 + row] = sum
            }
        }
        return Matrix4(result)
    }

    /**
     * `this * (x, y, z, w)`, as a four-element array.
     *
     * `w` is 1 for a point and 0 for a direction; the difference is whether the matrix's
     * translation applies, which is exactly what `panBy` relies on.
     */
    fun transform(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val result = FloatArray(4)
        for (row in 0 until 4) {
            result[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w
        }
        return result
    }

    /**
     * `this` followed by a translation, in the sense `Matrix.translateM` means it: the translation
     * happens *first* in world terms, because it post-multiplies.
     */
    fun translated(x: Float, y: Float, z: Float): Matrix4 {
        val result = m.copyOf()
        for (i in 0 until 4) {
            result[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z
        }
        return Matrix4(result)
    }

    /**
     * The inverse, or null if there isn't one.
     *
     * `Matrix.invertM` does this with an unrolled cofactor expansion; this is the same expansion
     * written out through [get] instead, so the column-major layout is stated once rather than
     * baked into sixteen hand-indexed expressions. The view matrix it is used on is always
     * invertible — it is a rotation and a translation — but a general routine is easier to test
     * than a special-cased one: a matrix times its inverse is the identity, whatever the matrix.
     */
    fun inverted(): Matrix4? {
        var determinant = 0f
        for (column in 0 until 4) {
            determinant += this[0, column] * cofactor(0, column)
        }
        if (determinant == 0f) return null

        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                // Transposed on the way out: the inverse is the *adjugate* over the determinant,
                // and the adjugate is the transpose of the cofactor matrix.
                result[column * 4 + row] = cofactor(column, row) / determinant
            }
        }
        return Matrix4(result)
    }

    /** The signed determinant of the 3x3 left when [row] and [column] are struck out. */
    private fun cofactor(row: Int, column: Int): Float {
        val rows = (0 until 4).filter { it != row }
        val columns = (0 until 4).filter { it != column }
        val minor =
            this[rows[0], columns[0]] *
                (this[rows[1], columns[1]] * this[rows[2], columns[2]] -
                    this[rows[1], columns[2]] * this[rows[2], columns[1]]) -
                this[rows[0], columns[1]] *
                (this[rows[1], columns[0]] * this[rows[2], columns[2]] -
                    this[rows[1], columns[2]] * this[rows[2], columns[0]]) +
                this[rows[0], columns[2]] *
                (this[rows[1], columns[0]] * this[rows[2], columns[1]] -
                    this[rows[1], columns[1]] * this[rows[2], columns[0]])
        return if ((row + column) % 2 == 0) minor else -minor
    }

    companion object {

        fun identity(): Matrix4 =
            Matrix4(
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )

        /**
         * A perspective projection, from `Matrix.perspectiveM`.
         *
         * [fieldOfViewDegrees] is the *vertical* angle, and the 360 rather than 180 in the tangent
         * is not a slip: it halves the angle at the same time as converting to radians.
         */
        fun perspective(
            fieldOfViewDegrees: Float,
            aspect: Float,
            near: Float,
            far: Float,
        ): Matrix4 {
            val f = 1f / tan(fieldOfViewDegrees * (PI.toFloat() / 360f))
            val rangeReciprocal = 1f / (near - far)
            val m = FloatArray(16)
            m[0] = f / aspect
            m[5] = f
            m[10] = (far + near) * rangeReciprocal
            m[11] = -1f
            m[14] = 2f * far * near * rangeReciprocal
            return Matrix4(m)
        }

        /**
         * A camera at [eye] looking at [centre], from `Matrix.setLookAtM`.
         *
         * The survey's world is z-up, so the up vector the renderer passes is `(0, 0, 1)` rather
         * than OpenGL's usual `(0, 1, 0)`.
         */
        @Suppress("LongParameterList")
        fun lookAt(
            eyeX: Float,
            eyeY: Float,
            eyeZ: Float,
            centreX: Float,
            centreY: Float,
            centreZ: Float,
            upX: Float,
            upY: Float,
            upZ: Float,
        ): Matrix4 {
            var fx = centreX - eyeX
            var fy = centreY - eyeY
            var fz = centreZ - eyeZ
            val rlf = 1f / sqrt(fx * fx + fy * fy + fz * fz)
            fx *= rlf
            fy *= rlf
            fz *= rlf

            var sx = fy * upZ - fz * upY
            var sy = fz * upX - fx * upZ
            var sz = fx * upY - fy * upX
            val rls = 1f / sqrt(sx * sx + sy * sy + sz * sz)
            sx *= rls
            sy *= rls
            sz *= rls

            val ux = sy * fz - sz * fy
            val uy = sz * fx - sx * fz
            val uz = sx * fy - sy * fx

            val m =
                floatArrayOf(
                    sx, ux, -fx, 0f,
                    sy, uy, -fy, 0f,
                    sz, uz, -fz, 0f,
                    0f, 0f, 0f, 1f,
                )
            return Matrix4(m).translated(-eyeX, -eyeY, -eyeZ)
        }
    }
}
