package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A station the surveyor touched with the phone during a scan: its name, and where the phone's
 * corner was at that moment, in the scan's own frame.
 *
 * The scan's frame is survey-shaped (x east, y north, z up, metres) but only trusted for up. ARKit
 * levels it with gravity, which is reliable; it also tries to point it north, which underground
 * may be out by any amount, and it puts the origin wherever the phone was when the session began.
 * Touches are what take the scan from that frame into the survey's.
 */
class StationTouch(val stationName: String, val inScan: Coord3D)

/**
 * How to move a scan into the survey: turn it about the vertical, then shift it.
 *
 * Four numbers and not six, because gravity has already fixed the other two. [headingDegrees] is
 * clockwise, as a compass reads, so a bearing measured in the scan plus [headingDegrees] is the
 * same bearing in the survey.
 */
class ScanPlacement(val headingDegrees: Float, val translation: Coord3D) {

    private val radians = headingDegrees.toDouble() * PI / 180
    private val cosine = cos(radians).toFloat()
    private val sine = sin(radians).toFloat()

    fun place(point: Coord3D): Coord3D = place(point.x, point.y, point.z)

    /**
     * Takes three floats so a caller walking a raw buffer need not allocate a point to ask.
     */
    fun place(east: Float, north: Float, up: Float): Coord3D =
        Coord3D(
            east * cosine + north * sine + translation.x,
            -east * sine + north * cosine + translation.y,
            up + translation.z,
        )

    /** The inverse of [place]: where a point in the survey sits in the scan's own frame. */
    fun unplace(point: Coord3D): Coord3D {
        val east = point.x - translation.x
        val north = point.y - translation.y
        return Coord3D(east * cosine - north * sine, east * sine + north * cosine, point.z - translation.z)
    }

    /** The same placement shifted by [offset] in the survey: east, north and up. */
    fun moved(offset: Coord3D): ScanPlacement =
        ScanPlacement(
            headingDegrees,
            Coord3D(translation.x + offset.x, translation.y + offset.y, translation.z + offset.z),
        )

    /**
     * The same placement turned [degrees] clockwise about [pivot], a point in the survey that stays
     * where it is. Turning about the scan's own middle rather than the survey's origin is what makes
     * a nudge by hand turn the scan in place rather than swing it across the cave.
     */
    fun turnedAbout(pivot: Coord3D, degrees: Float): ScanPlacement {
        val inScan = unplace(pivot)
        val heading = ((headingDegrees + degrees) % 360 + 360) % 360
        val turned = ScanPlacement(heading, Coord3D.ORIGIN).place(inScan)
        return ScanPlacement(heading, Coord3D(pivot.x - turned.x, pivot.y - turned.y, pivot.z - turned.z))
    }

    companion object {
        val IDENTITY = ScanPlacement(0f, Coord3D.ORIGIN)
    }
}

/** How far one touch ended up from its station once the scan was placed. */
class TouchResidual(val touch: StationTouch, val metres: Float)

/**
 * The placement, and how well the touches agree with it.
 *
 * [isHeadingFromStations] is false when the heading came from the prior rather than the touches:
 * one touch, or touches too close together to say which way the scan was turned. A surveyor
 * should be told, because a heading from ARKit's compass underground is exactly the number this
 * whole approach exists to avoid trusting.
 */
class AnchorResult(
    val placement: ScanPlacement,
    val residuals: List<TouchResidual>,
    val isHeadingFromStations: Boolean,
    /** Touches whose station is not in the survey, which played no part. */
    val unmatched: List<StationTouch>,
) {
    val worstResidual: Float get() = residuals.maxOfOrNull { it.metres } ?: 0f
}

/**
 * Fitting a scan to the stations the surveyor touched during it.
 *
 * Least squares in the horizontal plane for heading and position, and a plain mean for height:
 * gravity makes those two problems independent, and each has a closed-form answer, so there is
 * nothing iterative here to fail to converge on a cold phone.
 *
 * Two touches fix a scan exactly. More than two over-determine it, and the residuals are then the
 * check: a touch a metre out is a station touched under the wrong name, a corner held a hand's
 * width off the mark, or a Disto leg that is wrong. Touching the same station twice, on the way
 * in and on the way out, is a free loop closure, and needs nothing special.
 */
object StationAnchoring {

    /**
     * How far apart, horizontally, the touches must be before their heading is believed.
     *
     * The heading error from touches is roughly the touch error over the baseline. Two
     * centimetres of touch error over one metre is just over a degree; over twenty centimetres it
     * is six, which ARKit's compass would often beat.
     */
    const val MINIMUM_BASELINE_METRES = 1f

    /**
     * The placement that best puts [touches] on their stations, or null if there is nothing to
     * fix the scan with.
     *
     * [stationPositions] are survey coordinates by station name: east, north, up, in metres,
     * which is what `Projection2D.PLAN.transform` gives. [priorHeadingDegrees] is used when the
     * touches cannot give a heading themselves; with no prior, a single touch gives null rather
     * than a guess.
     */
    fun solve(
        touches: List<StationTouch>,
        stationPositions: Map<String, Coord3D>,
        priorHeadingDegrees: Float? = null,
    ): AnchorResult? {
        val matched = touches.filter { it.stationName in stationPositions }
        val unmatched = touches.filter { it.stationName !in stationPositions }
        if (matched.isEmpty()) return null

        val scanCentre = centroid(matched.map { it.inScan })
        val surveyCentre = centroid(matched.map { stationPositions.getValue(it.stationName) })

        val baseline = horizontalSpread(matched.map { it.inScan }, scanCentre)
        val isHeadingFromStations = matched.size >= 2 && baseline >= MINIMUM_BASELINE_METRES

        val heading =
            if (isHeadingFromStations) {
                bestHeading(matched, stationPositions, scanCentre, surveyCentre)
            } else {
                priorHeadingDegrees ?: return null
            }

        // Turn the scan's centre, then shift it onto the survey's: that is the translation which
        // leaves the summed error smallest once the heading is fixed.
        val turned = ScanPlacement(heading, Coord3D.ORIGIN).place(scanCentre)
        val translation =
            Coord3D(
                surveyCentre.x - turned.x,
                surveyCentre.y - turned.y,
                surveyCentre.z - turned.z,
            )
        val placement = ScanPlacement(heading, translation)

        val residuals =
            matched.map { touch ->
                TouchResidual(
                    touch,
                    distance(placement.place(touch.inScan), stationPositions.getValue(touch.stationName)),
                )
            }

        return AnchorResult(placement, residuals, isHeadingFromStations, unmatched)
    }

    /**
     * The clockwise turn that best lines the touches up with their stations.
     *
     * With both sets centred, turning a by d clockwise and comparing with b gives an error that is
     * smallest where cos(d) C + sin(d) S is largest, C being the sum of dot products and S the sum
     * of cross products: so d is atan2(S, C). Written out because the sign of S is the whole
     * difference between a heading and its mirror image, and both look like a placement.
     */
    private fun bestHeading(
        touches: List<StationTouch>,
        stationPositions: Map<String, Coord3D>,
        scanCentre: Coord3D,
        surveyCentre: Coord3D,
    ): Float {
        var dot = 0.0
        var cross = 0.0
        for (touch in touches) {
            val station = stationPositions.getValue(touch.stationName)
            val ax = (touch.inScan.x - scanCentre.x).toDouble()
            val ay = (touch.inScan.y - scanCentre.y).toDouble()
            val bx = (station.x - surveyCentre.x).toDouble()
            val by = (station.y - surveyCentre.y).toDouble()
            dot += ax * bx + ay * by
            cross += bx * ay - by * ax
        }
        val degrees = atan2(cross, dot) * 180 / PI
        return ((degrees + 360) % 360).toFloat()
    }

    private fun centroid(points: List<Coord3D>): Coord3D {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (point in points) {
            x += point.x
            y += point.y
            z += point.z
        }
        val n = points.size
        return Coord3D((x / n).toFloat(), (y / n).toFloat(), (z / n).toFloat())
    }

    /** The largest horizontal distance of any touch from their centre, doubled. */
    private fun horizontalSpread(points: List<Coord3D>, centre: Coord3D): Float {
        var furthest = 0f
        for (point in points) {
            val dx = point.x - centre.x
            val dy = point.y - centre.y
            furthest = maxOf(furthest, sqrt(dx * dx + dy * dy))
        }
        return 2 * furthest
    }

    internal fun distance(a: Coord3D, b: Coord3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * Where the phone's touching corner is, in the scan's survey-shaped frame, given ARKit's camera
 * pose.
 *
 * [cameraToWorld] is sixteen floats, column-major, as simd and ARKit write a four-by-four: the
 * translation is at indices twelve to fourteen. [cornerInCamera] is the corner's offset from the
 * camera in the camera's own axes (x right, y up, z out of the back of the lens), measured once
 * per phone model. ARKit's world is x east, y up, z south; the survey's is x east, y north, z up,
 * so the last step swaps and negates.
 */
fun touchPointInScan(cameraToWorld: FloatArray, cornerInCamera: Coord3D): Coord3D {
    require(cameraToWorld.size == 16) { "a camera pose is 16 floats, not ${cameraToWorld.size}" }
    val m = cameraToWorld
    val cx = cornerInCamera.x
    val cy = cornerInCamera.y
    val cz = cornerInCamera.z
    val worldX = m[0] * cx + m[4] * cy + m[8] * cz + m[12]
    val worldY = m[1] * cx + m[5] * cy + m[9] * cz + m[13]
    val worldZ = m[2] * cx + m[6] * cy + m[10] * cz + m[14]
    return Coord3D(worldX, -worldZ, worldY)
}
