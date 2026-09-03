package org.hwyl.sexytopo.shared.calibration

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Float trigonometry computed the way Java computes it: in double precision, then narrowed.
 *
 * This is not fussiness. `java.lang.Math.sin` takes and returns a `double`, so the Java original's
 * `(float) Math.sin(a)` widens, computes in 53 bits of mantissa, and rounds once at the end.
 * Kotlin's `kotlin.math.sin(Float)` promises no such thing: on the JVM it happens to do exactly
 * that, but on Kotlin/Native and Kotlin/Wasm it is free to call a single-precision `sinf`, which
 * can differ in the last unit in the last place.
 *
 * One ulp would not normally matter. Here it does, because [CalibrationAlgorithm] iterates until
 * successive coefficient matrices differ by less than 1e-6 and *reports the iteration count* — and
 * the ported tests assert that count exactly, against numbers PocketTopo produced. A single ulp of
 * drift early on compounds into a different number of iterations, and the test that would catch a
 * real error would then be failing for a reason nobody could act on.
 *
 * So the conversions are written out. They cost nothing on the JVM, where this is what the
 * intrinsic does anyway, and they make the arithmetic identical on every target the port builds
 * for.
 */
internal fun sinF(a: Float): Float = sin(a.toDouble()).toFloat()

internal fun cosF(a: Float): Float = cos(a.toDouble()).toFloat()

internal fun atan2F(y: Float, x: Float): Float = atan2(y.toDouble(), x.toDouble()).toFloat()

internal fun sqrtF(a: Float): Float = sqrt(a.toDouble()).toFloat()
