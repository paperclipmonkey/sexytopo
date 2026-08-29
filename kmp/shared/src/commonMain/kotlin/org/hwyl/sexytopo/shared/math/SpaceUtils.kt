package org.hwyl.sexytopo.shared.math

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ported from `control/util/Space2DUtils` and `control/util/Space3DUtils`.
 *
 * All of this is plain arithmetic in the Java original too — no Android imports — which is why it
 * moves across unchanged.
 */

// ---------------------------------------------------------------------------------------------
// 2D
// ---------------------------------------------------------------------------------------------

fun getDistanceFromLine(point: Coord2D, lineStart: Coord2D, lineEnd: Coord2D): Float {
    val a = point.x - lineStart.x
    val b = point.y - lineStart.y
    val c = lineEnd.x - lineStart.x
    val d = lineEnd.y - lineStart.y

    val dot = a * c + b * d
    val lenSq = c * c + d * d
    var param = -1f
    if (lenSq != 0f) {
        param = dot / lenSq
    }

    val xx: Float
    val yy: Float
    when {
        param < 0 -> {
            xx = lineStart.x
            yy = lineStart.y
        }
        param > 1 -> {
            xx = lineEnd.x
            yy = lineEnd.y
        }
        else -> {
            xx = lineStart.x + param * c
            yy = lineStart.y + param * d
        }
    }

    val dx = point.x - xx
    val dy = point.y - yy
    return sqrt(dx * dx + dy * dy)
}

fun getDistance(a: Coord2D, b: Coord2D): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** Wraps an angle into 0..<360 after adding [delta]. */
fun adjustAngle(angle: Float, delta: Float): Float {
    var newAngle = (angle + delta) % 360
    if (newAngle < 0) {
        newAngle += 360
    }
    return newAngle
}

/**
 * Averages azimuths across the 0/360 seam, so {359, 1} averages to 0 rather than 180.
 */
fun averageAzimuths(vararg azimuths: Float): Float {
    if (azimuths.isEmpty()) return 0f

    var minimum = Float.POSITIVE_INFINITY
    var maximum = Float.NEGATIVE_INFINITY
    for (azimuth in azimuths) {
        minimum = min(azimuth, minimum)
        maximum = max(azimuth, maximum)
    }
    val splitOverZero = maximum - minimum > 180

    var total = 0f
    for (azimuth in azimuths) {
        total += if (splitOverZero && azimuth < 180) azimuth + 360 else azimuth
    }
    return (total / azimuths.size) % 360
}

// ---------------------------------------------------------------------------------------------
// 3D
// ---------------------------------------------------------------------------------------------

/**
 * Spherical to cartesian, in the survey's frame: y is north, x is east, z is up.
 */
fun toCartesian(start: Coord3D, leg: Leg): Coord3D {
    val r = leg.distance
    val phi = leg.azimuth.toDouble() * PI_OVER_180
    val theta = leg.inclination.toDouble() * PI_OVER_180

    val y = (r * cos(theta) * cos(phi)).toFloat()
    val x = (r * cos(theta) * sin(phi)).toFloat()
    val z = (r * sin(theta)).toFloat()

    return Coord3D(x + start.x, y + start.y, z + start.z)
}

fun getDistance(a: Coord3D, b: Coord3D): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Cartesian back to a polar reading. */
fun toLeg(vector: Coord3D): Leg {
    val x = vector.x
    val y = vector.y
    val z = vector.z

    val distance = sqrt(x * x + y * y + z * z)

    var azimuth = (atan2(x.toDouble(), y.toDouble()) / PI_OVER_180).toFloat()
    if (azimuth < 0) {
        azimuth += 360
    }

    val horizontal = sqrt(x * x + y * y)
    val inclination = (atan2(z.toDouble(), horizontal.toDouble()) / PI_OVER_180).toFloat()

    return Leg(distance, azimuth, inclination)
}

internal const val PI_OVER_180: Double = 0.017453292519943295

internal fun approximatelyEqual(a: Float, b: Float, tolerance: Float = 0.001f): Boolean =
    abs(a - b) < tolerance
