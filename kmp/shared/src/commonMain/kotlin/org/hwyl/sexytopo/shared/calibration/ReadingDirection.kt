package org.hwyl.sexytopo.shared.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which way the instrument was pointing for a calibration reading, worked out from its gravity and
 * magnetic vectors. The laser is the sensors' x axis, and roll is about that axis.
 *
 * Ported from `control/calibration/ReadingDirection`. This is what lets a reading be described by
 * the way it was actually measured pointing rather than by its place in the guided sequence: the
 * instrument does not report where it was held, but its sensors say so well enough to tell the
 * fourteen directions of a calibration apart, even before there is a calibration to correct them.
 *
 * Doubles, as the Java's are. Unlike the solver, nothing here is iterated or compared against
 * PocketTopo, so the single-precision care [FloatMath] takes is not needed; the vectors going in
 * are still the solver's floats.
 */
class ReadingDirection(
    /** Degrees, up positive. */
    val inclination: Double,
    /** Degrees from magnetic north, 0 to 360. */
    val azimuth: Double,
    /** Degrees about the laser. */
    val roll: Double,
) {

    /**
     * Roughly where the laser was pointing, from its inclination: level, straight up or down, or
     * part way between, which is the corners of a calibration.
     */
    enum class Pointing {
        HORIZONTAL,
        UP,
        DOWN,
        CORNER_UP,
        CORNER_DOWN,
        ;

        /** Straight up or down, where roll cannot be told apart from which way it faces. */
        val isVertical: Boolean
            get() = this == UP || this == DOWN
    }

    /** Which way up the instrument was, from its roll about the laser. */
    enum class Face {
        UP,
        DOWN,
        SIDE,
    }

    /** A rough description of where the laser was pointing, enough to tell positions apart. */
    val pointing: Pointing
        get() = pointingOf(inclination)

    /** Which way up the instrument was. Meaningless when pointing straight up or down. */
    val face: Face
        get() {
            val absoluteRoll = abs(roll)
            return when {
                absoluteRoll <= 45 -> Face.UP
                absoluteRoll >= 135 -> Face.DOWN
                else -> Face.SIDE
            }
        }

    /** The laser as a unit vector in north, east, down coordinates. */
    fun toVector(): Vector {
        val inc = toRadians(inclination)
        val az = toRadians(azimuth)
        return Vector(
            (cos(inc) * cos(az)).toFloat(),
            (cos(inc) * sin(az)).toFloat(),
            (-sin(inc)).toFloat(),
        )
    }

    override fun toString(): String =
        "ReadingDirection(inclination=$inclination, azimuth=$azimuth, roll=$roll)"

    companion object {

        /** Degrees either side of level. */
        private const val HORIZONTAL_LIMIT = 20.0

        /** Degrees from level. */
        private const val VERTICAL_LIMIT = 65.0

        fun fromVectors(g: Vector, m: Vector): ReadingDirection {
            val down = g.normalised()
            val east = (down cross m).normalised()
            val north = east cross down
            val inclination = toDegrees(asin(-down.x.toDouble()))
            val azimuth = (toDegrees(atan2(east.x.toDouble(), north.x.toDouble())) + 360) % 360
            val roll = toDegrees(atan2(g.y.toDouble(), g.z.toDouble()))
            return ReadingDirection(inclination, azimuth, roll)
        }

        fun pointingOf(inclination: Double): Pointing {
            val steepness = abs(inclination)
            return when {
                steepness < HORIZONTAL_LIMIT -> Pointing.HORIZONTAL
                steepness > VERTICAL_LIMIT -> if (inclination > 0) Pointing.UP else Pointing.DOWN
                else -> if (inclination > 0) Pointing.CORNER_UP else Pointing.CORNER_DOWN
            }
        }
    }
}

/** The angle between two unit vectors, in degrees; clamped, since rounding can push past ±1. */
internal fun angleBetween(a: Vector, b: Vector): Double {
    val cosine = (a dot b).toDouble().coerceIn(-1.0, 1.0)
    return toDegrees(acos(cosine))
}

internal fun toDegrees(radians: Double): Double = radians * 180.0 / PI

internal fun toRadians(degrees: Double): Double = degrees * PI / 180.0
