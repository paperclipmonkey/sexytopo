package org.hwyl.sexytopo.shared.calibration

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The DistoX calibration solver: Beat Heeb's algorithm as written for PocketTopo, ported here from
 * the Android app's Java, which was itself translated from C#.
 *
 * ## Two deliberate departures from the Java
 *
 * **The results are returned, not stored.** The original keeps `aG`, `aM`, `bG`, `bM` and `nl` in
 * *static* fields, so `getCoefficients()` reads whatever the last `calculate()` anywhere in the
 * process happened to leave behind. Two calibrations in flight at once — two instruments, or a
 * retry started before the first finished — would silently write each other's coefficients to a
 * device. Returning a [CalibrationResult] makes that impossible rather than unlikely.
 *
 * **`useNonLinearity` travels with the result.** In the Java it is a field of the calculator while
 * the coefficients are static, so the flag used to *encode* the bytes need not be the flag the
 * numbers were *solved* with. Here the result carries it and [CalibrationResult.toBytes] takes no
 * argument, so the two cannot disagree.
 *
 * The arithmetic itself is untouched, deliberately: see [FloatMath] for the one place that took
 * care.
 */
object CalibrationAlgorithm {

    /** Divisor turning a raw 16-bit sensor reading into the algorithm's units. */
    const val FV = 24000f

    /** Scale for the matrix coefficients when packed for the instrument. */
    const val FM = 16384f

    /** Scale for the non-linearity coefficients: 2^26 / FV. */
    const val FN = 2796f

    /** Convergence threshold on the largest coefficient change between iterations. */
    const val EPS = 1.0E-6f

    /** Iteration ceiling. Reaching it means the fit never settled. */
    const val MAX_IT = 200

    private const val SHORT_MAX = 32767f

    /**
     * The number of readings that must be present before the grouped phase makes sense.
     *
     * The first sixteen readings are treated as four groups of four, so fewer than sixteen would
     * read past the end. The Java has no such guard and throws `ArrayIndexOutOfBoundsException`
     * from inside the solver; a real DistoX calibration is always 56 readings.
     */
    const val MINIMUM_READINGS = 16

    /**
     * Optimal `gx` and `mx` for a given pair of corrected vectors and the angle between the
     * sensors' planes.
     */
    fun optVectors(gr: Vector, mr: Vector, alpha: Float): Pair<Vector, Vector> {
        val no = (gr cross mr).normalised() // plane normal
        val s = sinF(alpha)
        val c = cosF(alpha)
        val gx = ((mr * c) + ((mr cross no) * s) + gr).normalised()
        val mx = (gx * c) + ((no cross gx) * s)
        return gx to mx
    }

    /** Rotates `gxp`/`mxp` about X to best match `gr`/`mr`. */
    fun turnVectors(gxp: Vector, mxp: Vector, gr: Vector, mr: Vector): Pair<Vector, Vector> {
        val s = gr.z * gxp.y - gr.y * gxp.z + mr.z * mxp.y - mr.y * mxp.z
        val c = gr.y * gxp.y + gr.z * gxp.z + mr.y * mxp.y + mr.z * mxp.z
        val a = atan2F(s, c)
        return gxp.turnX(a) to mxp.turnX(a)
    }

    /** Scales a matrix/offset pair down if packing it would overflow a signed 16-bit field. */
    private fun checkOverflow(m: Matrix, v: Vector): Pair<Matrix, Vector> {
        val largest =
            max(
                Matrix.maxDiff(m, Matrix.ZERO) * FM,
                Vector.maxDiff(v, Vector.ZERO) * FV,
            )
        if (largest <= SHORT_MAX) return m to v
        val scale = SHORT_MAX / largest
        return (m * scale) to (v * scale)
    }

    /** Clamps a non-linearity coefficient to what its single signed byte can hold. */
    private fun saturate(x: Float): Float =
        when {
            x > 127 / FN -> 127 / FN
            x < -127 / FN -> -127 / FN
            else -> x
        }

    /**
     * Solves for the correction coefficients.
     *
     * [g] and [m] are the raw sensor triples already divided by [FV] — see [readingsToVectors].
     */
    fun optimise(
        g: List<Vector>,
        m: List<Vector>,
        useNonLinearity: Boolean,
    ): CalibrationResult {
        val num = g.size
        require(num >= MINIMUM_READINGS) {
            "calibration needs at least $MINIMUM_READINGS readings, got $num"
        }
        require(m.size == num) { "got $num gravity readings but ${m.size} magnetic ones" }

        val gr = arrayOfNulls<Vector>(num)
        val mr = arrayOfNulls<Vector>(num)
        val gx = arrayOfNulls<Vector>(num)
        val mx = arrayOfNulls<Vector>(num)
        val gl = arrayOfNulls<Vector>(num) // linearised g values
        val gs = arrayOfNulls<Matrix>(num) // Diag(g² - ½)

        val invNum = 1.0f / num
        var sumG = Vector.ZERO
        var sumM = Vector.ZERO
        var sumG2 = Matrix.ZERO
        var sumM2 = Matrix.ZERO
        var sa = 0f
        var ca = 0f

        for (i in 0 until num) {
            // sum up g x m for the initial alpha
            sa += (g[i] cross m[i]).magnitude
            ca += g[i] dot m[i]
            sumG += g[i]
            sumM += m[i]
            sumG2 += (g[i] outer g[i])
            sumM2 += (m[i] outer m[i])
            gl[i] = g[i]
            gs[i] =
                Matrix.diagonal(
                    g[i].x * g[i].x - 0.5f,
                    g[i].y * g[i].y - 0.5f,
                    g[i].z * g[i].z - 0.5f,
                )
        }

        var alpha = atan2F(sa, ca)
        var avG = sumG * invNum
        val avM = sumM * invNum
        var invG = Matrix.inverse(sumG2 - (sumG outer avG))
        val invM = Matrix.inverse(sumM2 - (sumM outer avM))

        var nl = Vector.ZERO
        var aG = Matrix.IDENTITY
        var aM = Matrix.IDENTITY
        // The negative average makes a serviceable initial offset.
        var bG = Vector.ZERO - avG
        var bM = Vector.ZERO - avM

        var it = 0
        do {
            for (i in 0 until num) {
                gr[i] = (aG * gl[i]!!) + bG // gl, not g
                mr[i] = (aM * m[i]) + bM
            }

            sa = 0f
            ca = 0f

            // Hand-indexed, because the Java reassigns its loop variable from two nested loops and
            // then decrements it. The effect is that readings 0-15 are processed as four
            // equidirectional groups of four and the rest individually; Kotlin's `for` variable is
            // immutable, so a direct transliteration is not possible and a careless one silently
            // regroups the samples. Nothing throws if you get it wrong: you get plausible
            // coefficients that are quietly incorrect.
            var i = 0
            while (i < num) {
                if (i < 16) {
                    val first = i
                    var grp = Vector.ZERO
                    var mrp = Vector.ZERO
                    for (j in first until first + 4) {
                        // match each gr/mr in the group to the group's first
                        val (gt, mt) = turnVectors(gr[j]!!, mr[j]!!, gr[first]!!, mr[first]!!)
                        grp += gt
                        mrp += mt
                    }
                    // optimal matched gx & mx from the sum of the matched gr & mr
                    val (gxp, mxp) = optVectors(grp, mrp, alpha)
                    sa += (mrp cross gxp).magnitude
                    ca += mrp dot gxp
                    for (j in first until first + 4) {
                        val (gt, mt) = turnVectors(gxp, mxp, gr[j]!!, mr[j]!!)
                        gx[j] = gt
                        mx[j] = mt
                    }
                    i = first + 4
                } else {
                    val (gxi, mxi) = optVectors(gr[i]!!, mr[i]!!, alpha)
                    gx[i] = gxi
                    mx[i] = mxi
                    sa += (mr[i]!! cross gxi).magnitude
                    ca += mr[i]!! dot gxi
                    i++
                }
            }

            alpha = atan2F(sa, ca)

            var avGx = Vector.ZERO
            var avMx = Vector.ZERO
            var sumGxG = Matrix.ZERO
            var sumMxM = Matrix.ZERO
            for (k in 0 until num) {
                avGx += gx[k]!!
                avMx += mx[k]!!
                sumGxG += (gx[k]!! outer gl[k]!!) // gl, not g
                sumMxM += (mx[k]!! outer m[k])
            }

            val aG0 = aG
            val aM0 = aM
            avGx *= invNum
            avMx *= invNum
            aG = (sumGxG - (avGx outer sumG)) * invG
            aM = (sumMxM - (avMx outer sumM)) * invM

            // enforce a symmetric aG(y,z)
            val symmetric = (aG.y.z + aG.z.y) * 0.5f
            aG =
                Matrix(
                    aG.x,
                    Vector(aG.y.x, aG.y.y, symmetric),
                    Vector(aG.z.x, symmetric, aG.z.z),
                )

            bG = avGx - (aG * avG)
            bM = avMx - (aM * avM)

            if (useNonLinearity) {
                var psum = Matrix.ZERO
                var qsum = Vector.ZERO
                for (k in 0 until num) {
                    val p = aG * gs[k]!!
                    val q = gx[k]!! - (aG * g[k]) - bG
                    val pt = Matrix.transposed(p)
                    psum += (pt * p)
                    qsum += (pt * q)
                }
                nl = Matrix.inverse(psum) * qsum
                nl = Vector(saturate(nl.x), saturate(nl.y), saturate(nl.z))

                // recalculate the linearised g values
                sumG = Vector.ZERO
                sumG2 = Matrix.ZERO
                for (k in 0 until num) {
                    gl[k] = g[k] + (gs[k]!! * nl)
                    sumG += gl[k]!!
                    sumG2 += (gl[k]!! outer gl[k]!!)
                }
                avG = sumG * invNum
                invG = Matrix.inverse(sumG2 - (sumG outer avG))
            }

            it++
            val moved = max(Matrix.maxDiff(aG, aG0), Matrix.maxDiff(aM, aM0))
        } while (it < MAX_IT && moved > EPS)

        val (aGFinal, bGFinal) = checkOverflow(aG, bG)
        val (aMFinal, bMFinal) = checkOverflow(aM, bM)

        var delta = 0f
        for (i in 0 until num) {
            val dg = gx[i]!! - gr[i]!!
            val dm = mx[i]!! - mr[i]!!
            delta += (dg dot dg) + (dm dot dm)
        }
        delta = sqrtF(delta / num) * 100

        return CalibrationResult(
            aG = aGFinal,
            bG = bGFinal,
            aM = aMFinal,
            bM = bMFinal,
            nl = nl,
            iterations = it,
            delta = delta,
            useNonLinearity = useNonLinearity,
        )
    }

    fun readingsToVectors(readings: List<CalibrationReading>): Pair<List<Vector>, List<Vector>> {
        val g = readings.map { Vector(it.gx / FV, it.gy / FV, it.gz / FV) }
        val m = readings.map { Vector(it.mx / FV, it.my / FV, it.mz / FV) }
        return g to m
    }

    fun calibrate(
        readings: List<CalibrationReading>,
        useNonLinearity: Boolean,
    ): CalibrationResult {
        val (g, m) = readingsToVectors(readings)
        return optimise(g, m, useNonLinearity)
    }

    internal fun putCoefficient(data: ByteArray, index: Int, value: Float) {
        val coefficient = value.roundToInt()
        data[index] = coefficient.toByte()
        data[index + 1] = (coefficient shr 8).toByte()
    }
}

/**
 * What a calibration produced: the correction for each sensor, and how well it fits.
 *
 * [delta] is the average error as a percentage — the number the surveyor is shown and judges the
 * calibration by. [iterations] is how many rounds the fit took; hitting
 * [CalibrationAlgorithm.MAX_IT] means it never settled.
 */
data class CalibrationResult(
    val aG: Matrix,
    val bG: Vector,
    val aM: Matrix,
    val bM: Vector,
    /** Non-linearity coefficients; [Vector.ZERO] unless [useNonLinearity]. */
    val nl: Vector,
    val iterations: Int,
    val delta: Float,
    val useNonLinearity: Boolean,
) {

    val converged: Boolean
        get() = iterations < CalibrationAlgorithm.MAX_IT

    /**
     * The byte sequence to write to the instrument at address 0x8010.
     *
     * 48 bytes, or 52 with non-linearity. Each coefficient is a little-endian signed 16-bit value;
     * the non-linearity bytes are single signed bytes, stored one less than their rounded value,
     * followed by a 0xFF terminator.
     */
    fun toBytes(): ByteArray {
        val data = ByteArray(if (useNonLinearity) 52 else 48)
        val put = CalibrationAlgorithm::putCoefficient
        val fv = CalibrationAlgorithm.FV
        val fm = CalibrationAlgorithm.FM

        put(data, 0, bG.x * fv)
        put(data, 2, aG.x.x * fm)
        put(data, 4, aG.x.y * fm)
        put(data, 6, aG.x.z * fm)
        put(data, 8, bG.y * fv)
        put(data, 10, aG.y.x * fm)
        put(data, 12, aG.y.y * fm)
        put(data, 14, aG.y.z * fm)
        put(data, 16, bG.z * fv)
        put(data, 18, aG.z.x * fm)
        put(data, 20, aG.z.y * fm)
        put(data, 22, aG.z.z * fm)
        put(data, 24, bM.x * fv)
        put(data, 26, aM.x.x * fm)
        put(data, 28, aM.x.y * fm)
        put(data, 30, aM.x.z * fm)
        put(data, 32, bM.y * fv)
        put(data, 34, aM.y.x * fm)
        put(data, 36, aM.y.y * fm)
        put(data, 38, aM.y.z * fm)
        put(data, 40, bM.z * fv)
        put(data, 42, aM.z.x * fm)
        put(data, 44, aM.z.y * fm)
        put(data, 46, aM.z.z * fm)

        if (useNonLinearity) {
            val fn = CalibrationAlgorithm.FN
            data[48] = ((nl.x * fn).roundToInt() - 1).toByte()
            data[49] = ((nl.y * fn).roundToInt() - 1).toByte()
            data[50] = ((nl.z * fn).roundToInt() - 1).toByte()
            data[51] = 0xFF.toByte()
        }
        return data
    }
}
