package org.hwyl.sexytopo.shared.calibration

import kotlin.math.max

/**
 * A 3x3 matrix of `Float`, held as its three rows. Ported from `control/calibration/Matrix.java`.
 *
 * The row-vector representation is the Java's, and it is load-bearing rather than incidental:
 * [times] treats `a * b` as "transpose b, then dot each of a's rows against each of b's rows",
 * which is the same thing as ordinary matrix multiplication only because of how the rows are laid
 * out. Reordering any of it silently changes the answer.
 */
data class Matrix(val x: Vector, val y: Vector, val z: Vector) {

    operator fun plus(a: Matrix) = Matrix(x + a.x, y + a.y, z + a.z)

    operator fun minus(a: Matrix) = Matrix(x - a.x, y - a.y, z - a.z)

    operator fun times(a: Float) = Matrix(x * a, y * a, z * a)

    operator fun times(v: Vector): Vector = Vector(x dot v, y dot v, z dot v)

    operator fun times(a: Matrix): Matrix {
        val t = transposed(a)
        return Matrix(t * x, t * y, t * z)
    }

    companion object {
        val ZERO = Matrix(Vector.ZERO, Vector.ZERO, Vector.ZERO)

        val IDENTITY =
            Matrix(
                Vector(1f, 0f, 0f),
                Vector(0f, 1f, 0f),
                Vector(0f, 0f, 1f),
            )

        /** A diagonal matrix; the algorithm builds `Diag(g² - ½)` this way. */
        fun diagonal(a: Float, b: Float, c: Float): Matrix =
            Matrix(
                Vector(a, 0f, 0f),
                Vector(0f, b, 0f),
                Vector(0f, 0f, c),
            )

        fun transposed(m: Matrix): Matrix =
            Matrix(
                Vector(m.x.x, m.y.x, m.z.x),
                Vector(m.x.y, m.y.y, m.z.y),
                Vector(m.x.z, m.y.z, m.z.z),
            )

        /** Adjugate divided by determinant. Undefined for a singular matrix, as in the original. */
        fun inverse(m: Matrix): Matrix {
            val t = transposed(m)
            val adjugate =
                Matrix(
                    t.y cross t.z,
                    t.z cross t.x,
                    t.x cross t.y,
                )
            return adjugate * (1 / (t.x dot adjugate.x))
        }

        /** The largest absolute difference between corresponding elements. */
        fun maxDiff(a: Matrix, b: Matrix): Float =
            max(
                Vector.maxDiff(a.x, b.x),
                max(Vector.maxDiff(a.y, b.y), Vector.maxDiff(a.z, b.z)),
            )
    }
}
