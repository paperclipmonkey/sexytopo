package org.hwyl.sexytopo.shared.model.graph

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ported from `org.hwyl.sexytopo.model.graph` in the Android app.
 *
 * The sketch and survey geometry deliberately uses these types rather than any platform graphics
 * primitives, which is why this layer moves to Kotlin Multiplatform essentially unchanged.
 */

/** Matches SexyTopoConstants.ALLOWED_DOUBLE_DELTA. */
const val ALLOWED_DELTA: Double = 0.0001

internal fun isWithinDelta(first: Double, second: Double): Boolean =
    abs(first - second) < ALLOWED_DELTA

interface Coord<C : Coord<C>> {
    fun scale(scale: Float): C
}

class Coord2D(val x: Float, val y: Float) : Coord<Coord2D> {

    fun add(dx: Float, dy: Float): Coord2D = Coord2D(x + dx, y + dy)

    operator fun plus(other: Coord2D): Coord2D = Coord2D(x + other.x, y + other.y)

    operator fun minus(other: Coord2D): Coord2D = Coord2D(x - other.x, y - other.y)

    operator fun times(other: Coord2D): Coord2D = Coord2D(x * other.x, y * other.y)

    override fun scale(scale: Float): Coord2D = Coord2D(x * scale, y * scale)

    fun mag(): Float = sqrt(x * x + y * y)

    fun normalise(): Coord2D {
        val mag = mag()
        return if (mag > 0) scale(1 / mag) else this
    }

    /**
     * The projections invert y to go from maths space (origin bottom left) to screen space (origin
     * top left); exporters have to undo it. See Projection2D.
     */
    fun flipVertically(): Coord2D = Coord2D(x, -y)

    override fun equals(other: Any?): Boolean {
        if (other !is Coord2D) return false
        return isWithinDelta(other.x.toDouble(), x.toDouble()) &&
            isWithinDelta(other.y.toDouble(), y.toDouble())
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result.toInt()
    }

    override fun toString(): String = "($x, $y)"

    companion object {
        val ORIGIN = Coord2D(0f, 0f)
    }
}

class Coord3D(
    val x: Float,
    val y: Float,
    val z: Float,
) : Coord<Coord3D> {

    override fun scale(scale: Float): Coord3D = Coord3D(x * scale, y * scale, z * scale)

    override fun equals(other: Any?): Boolean {
        if (other !is Coord3D) return false
        return other.x == x && other.y == y && other.z == z
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + z
        return result.toInt()
    }

    override fun toString(): String = "($x, $y, $z)"

    companion object {
        val ORIGIN = Coord3D(0f, 0f, 0f)
    }
}

class Line<T : Coord<T>>(val start: T, val end: T) {
    fun scale(scale: Float): Line<T> = Line(start.scale(scale), end.scale(scale))

    override fun toString(): String = "$start -> $end"
}

/**
 * A projected survey: where each station sits, and where each leg runs.
 *
 * Station and Leg are used as keys with identity semantics (neither overrides equals), exactly as
 * in the Java original — two legs with identical readings are still different legs.
 */
class Space<T : Coord<T>> {

    val stationMap: MutableMap<Station, T> = HashMap()
    val legMap: MutableMap<Leg, Line<T>> = HashMap()

    fun addStation(station: Station, coord: T) {
        stationMap[station] = coord
    }

    fun addLeg(leg: Leg, line: Line<T>) {
        legMap[leg] = line
    }

    fun scale(scale: Float): Space<T> {
        val scaled = Space<T>()
        for ((station, coord) in stationMap) {
            scaled.addStation(station, coord.scale(scale))
        }
        for ((leg, line) in legMap) {
            scaled.addLeg(leg, line.scale(scale))
        }
        return scaled
    }
}

/**
 * Which way a station's subtree is drawn in the extended elevation.
 *
 * Left and right describe which way the survey continues and carry down the subtree; vertical
 * applies to a single leg, which is drawn using only its height change.
 */
enum class ExtendedElevationDirection(val propagates: Boolean) {
    LEFT(true),
    RIGHT(true),
    VERTICAL(false),
    ;

    companion object {
        val DEFAULT = RIGHT

        fun fromStringOrDefault(text: String?): ExtendedElevationDirection =
            entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Ported from `Space2DUtils.translate`: every station and leg moved by the same offset.
 *
 * Used to put a cross-section's own projection — which is drawn around the origin — where the
 * section sits on the main drawing.
 */
fun Space<Coord2D>.translate(translation: Coord2D): Space<Coord2D> {
    val translated = Space<Coord2D>()
    for ((station, coord) in stationMap) {
        translated.addStation(station, coord + translation)
    }
    for ((leg, line) in legMap) {
        translated.addLeg(leg, Line(line.start + translation, line.end + translation))
    }
    return translated
}
