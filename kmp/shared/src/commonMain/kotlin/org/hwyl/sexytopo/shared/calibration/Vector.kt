package org.hwyl.sexytopo.shared.calibration

import kotlin.math.abs
import kotlin.math.max

/**
 * A 3-vector of `Float`, ported from `control/calibration/Vector.java`.
 *
 * Immutable, where the Java's is not. The original needs mutability in exactly three places — the
 * diagonal of `gs[i]`, the symmetry fix on `aG`, and saturating `nl` — and each of them is a
 * construction dressed up as an assignment, so rebuilding gives identical results with none of the
 * aliasing hazard. `Matrix.Transposed` is the reason it matters: the Java version deep-copies its
 * argument and then swaps fields in place, which is correct but only because of that copy.
 *
 * `Float`, not `Double`, throughout. The instrument's coefficients are 16-bit fixed point and the
 * reference implementation is single precision; widening here would produce a subtly different
 * answer from the one PocketTopo and the Android app produce.
 */
data class Vector(val x: Float, val y: Float, val z: Float) {

    operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector) = Vector(x - other.x, y - other.y, z - other.z)

    operator fun times(a: Float) = Vector(x * a, y * a, z * a)

    /** Dot product. */
    infix fun dot(other: Vector): Float = x * other.x + y * other.y + z * other.z

    infix fun cross(other: Vector): Vector =
        Vector(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    /** Outer product: this ⊗ other. */
    infix fun outer(other: Vector): Matrix =
        Matrix(other * x, other * y, other * z)

    val magnitude: Float
        get() = sqrtF(x * x + y * y + z * z)

    fun normalised(): Vector = this * (1 / magnitude)

    /** Rotate about the X axis. */
    fun turnX(a: Float): Vector {
        val s = sinF(a)
        val c = cosF(a)
        return Vector(x, c * y - s * z, c * z + s * y)
    }

    /** Rotate about the Y axis. */
    fun turnY(a: Float): Vector {
        val s = sinF(a)
        val c = cosF(a)
        return Vector(c * x + s * z, y, c * z - s * x)
    }

    /** Rotate about the Z axis. */
    fun turnZ(a: Float): Vector {
        val s = sinF(a)
        val c = cosF(a)
        return Vector(c * x - s * y, c * y + s * x, z)
    }

    companion object {
        val ZERO = Vector(0f, 0f, 0f)

        /** The largest absolute difference between corresponding components. */
        fun maxDiff(a: Vector, b: Vector): Float =
            max(abs(a.x - b.x), max(abs(a.y - b.y), abs(a.z - b.z)))
    }
}
