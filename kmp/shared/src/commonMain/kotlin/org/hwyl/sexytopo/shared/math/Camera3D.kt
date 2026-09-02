package org.hwyl.sexytopo.shared.math

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Space
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where you are standing, looking at the cave.
 *
 * Ported from the camera half of `SurveyRenderer`: an eye on a sphere around the survey's centre,
 * given by two angles and a distance, plus a pan that slides the whole thing sideways.
 */
data class Camera3D(
    /** Angle down from the z axis, in radians. Clamped away from the poles, where the view flips. */
    val angleX: Float = INITIAL_ANGLE,
    /** Angle around the z axis, in radians. */
    val angleY: Float = INITIAL_ANGLE,
    val distance: Float = INITIAL_DISTANCE,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val panZ: Float = 0f,
) {

    val eye: Coord3D
        get() =
            Coord3D(
                distance * (sin(angleX) * sin(angleY)),
                distance * (sin(angleX) * cos(angleY)),
                distance * cos(angleX),
            )

    /**
     * The view transform.
     *
     * Up is `(0, 0, 1)`: the survey's world is z-up, being metres east, north and above the
     * entrance, rather than OpenGL's y-up.
     */
    val view: Matrix4
        get() = Matrix4.lookAt(eye.x, eye.y, eye.z, 0f, 0f, 0f, 0f, 0f, 1f)

    /** Drag to spin. The 0.01 is the Java's, and is radians per pixel. */
    fun rotatedBy(dx: Float, dy: Float): Camera3D =
        copy(
            angleY = angleY + dx * ROTATE_PER_PIXEL,
            // Clamped, because at either pole the up vector and the view direction line up, the
            // cross product in `lookAt` is zero, and the whole matrix comes out as NaN.
            angleX = min(PI.toFloat() - POLE_GAP, max(POLE_GAP, angleX + dy * ROTATE_PER_PIXEL)),
        )

    fun zoomedBy(factor: Float): Camera3D =
        copy(distance = min(MAX_DISTANCE, max(MIN_DISTANCE, distance * factor)))

    /**
     * Drag to slide the cave about.
     *
     * The screen-space delta is turned into a world-space one through the inverse of the view
     * matrix, so that dragging right moves the cave right whichever way it is currently facing.
     */
    fun pannedBy(dx: Float, dy: Float): Camera3D {
        val inverseView = view.inverted() ?: return this
        val scale = distance * PAN_PER_PIXEL
        val world = inverseView.transform(dx * scale, -dy * scale, 0f, 0f)
        return copy(panX = panX + world[0], panY = panY + world[1], panZ = panZ + world[2])
    }

    /**
     * The full model-view-projection transform for a viewport of this shape.
     *
     * [centre] is the middle of the survey's bounding box, subtracted so that the cave orbits
     * around itself rather than around whichever station happened to be first.
     */
    fun transformFor(centre: Coord3D, aspect: Float): Matrix4 {
        val projection = Matrix4.perspective(FIELD_OF_VIEW, aspect, NEAR, FAR)
        val model =
            Matrix4.identity()
                .translated(-centre.x + panX, -centre.y + panY, -centre.z + panZ)
        return projection * (view * model)
    }

    companion object {
        /** 45 degrees, in radians: the Java's `Math.toRadians(45)`. */
        val INITIAL_ANGLE: Float = (PI / 4).toFloat()
        const val INITIAL_DISTANCE = 50f
        const val MIN_DISTANCE = 1f
        const val MAX_DISTANCE = 500f
        const val FIELD_OF_VIEW = 45f
        const val NEAR = 0.1f
        const val FAR = 1000f

        private const val ROTATE_PER_PIXEL = 0.01f
        private const val PAN_PER_PIXEL = 0.0005f
        private const val POLE_GAP = 0.01f

        /**
         * A camera far enough back to see all of [extent], the survey's largest dimension.
         *
         * The Java sets this in `buildGeometry`, so it is recomputed every time the geometry is
         * rebuilt and quietly throws away whatever zoom the surveyor had chosen. Here it is only
         * the starting point, and [zoomedBy] is what changes it afterwards.
         */
        fun fittingExtent(extent: Float): Float =
            if (extent > 0) {
                min(MAX_DISTANCE, max(MIN_DISTANCE, extent * 1.5f))
            } else {
                INITIAL_DISTANCE
            }

        internal val HALF_FIELD_OF_VIEW: Float = FIELD_OF_VIEW * (PI.toFloat() / 360f)

        /**
         * The tangents of the half-angles the frustum actually has, given the shape of the screen.
         *
         * The field of view is *vertical*: the horizontal one follows the aspect ratio, and on a
         * portrait phone it is much the narrower of the two. Getting this backwards is what makes a
         * cave that fits top to bottom hang off both sides.
         */
        internal fun halfAngleTangents(aspect: Float): Pair<Float, Float> {
            val vertical = tan(HALF_FIELD_OF_VIEW)
            val horizontal =
                if (aspect > 0f && aspect.isFinite()) vertical * aspect else vertical
            return horizontal to vertical
        }
    }
}

/**
 * A survey reduced to what the 3D view draws: lines, points, and the box they sit in.
 *
 * Built once per survey rather than per frame — the Java's `geometryDirty` flag does the same job —
 * because a camera move changes only the transform, not the geometry.
 */
class Wireframe(
    /** Both ends of every leg that connects two stations. */
    val legs: List<Pair<Coord3D, Coord3D>>,
    /** Both ends of every splay: a leg shot into the dark with no station at the end. */
    val splays: List<Pair<Coord3D, Coord3D>>,
    val stations: List<Coord3D>,
    val centre: Coord3D,
    /** How far the survey runs in each direction: the bounding box, as a width, depth and height. */
    val size: Coord3D,
) {

    val hasNothingToDraw: Boolean get() = legs.isEmpty() && splays.isEmpty()

    val extent: Float get() = max(size.x, max(size.y, size.z))

    /**
     * Half the bounding box's diagonal: the radius of a sphere the whole survey fits inside,
     * whichever way it is turned.
     */
    val radius: Float
        get() = 0.5f * sqrt(size.x * size.x + size.y * size.y + size.z * size.z)

    /**
     * Three half-extents in the camera's own frame — across the screen, up the screen, and along
     * the view — and then the distance at which the first two fit inside the frustum. The third is
     * added rather than ignored because perspective is measured from the eye, not from the middle
     * of the cave: the near end is closer than the centre and so spreads wider.
     */
    fun distanceToFit(camera: Camera3D, aspect: Float, margin: Float = FIT_MARGIN): Float {
        if (stations.isEmpty()) return Camera3D.INITIAL_DISTANCE

        // The camera's axes, from `Matrix4.lookAt` with up = (0, 0, 1): forward towards the cave,
        // right across the screen, up the screen.
        val eye = camera.eye
        val length = sqrt(eye.x * eye.x + eye.y * eye.y + eye.z * eye.z)
        if (length <= 0f) return Camera3D.INITIAL_DISTANCE
        val forward = Coord3D(-eye.x / length, -eye.y / length, -eye.z / length)
        // forward x (0, 0, 1), normalised. Never zero: `rotatedBy` keeps the camera off the poles.
        val rightRaw = Coord3D(forward.y, -forward.x, 0f)
        val rightLength = sqrt(rightRaw.x * rightRaw.x + rightRaw.y * rightRaw.y)
        if (rightLength <= 0f) return Camera3D.INITIAL_DISTANCE
        val right = Coord3D(rightRaw.x / rightLength, rightRaw.y / rightLength, 0f)
        val up =
            Coord3D(
                right.y * forward.z - right.z * forward.y,
                right.z * forward.x - right.x * forward.z,
                right.x * forward.y - right.y * forward.x,
            )

        var acrossHalf = 0f
        var upHalf = 0f
        var alongHalf = 0f
        for (station in stations) {
            val dx = station.x - centre.x
            val dy = station.y - centre.y
            val dz = station.z - centre.z
            acrossHalf = max(acrossHalf, abs(dx * right.x + dy * right.y + dz * right.z))
            upHalf = max(upHalf, abs(dx * up.x + dy * up.y + dz * up.z))
            alongHalf = max(alongHalf, abs(dx * forward.x + dy * forward.y + dz * forward.z))
        }

        val (tanHorizontal, tanVertical) = Camera3D.halfAngleTangents(aspect)
        val needed =
            max(acrossHalf / tanHorizontal, upHalf / tanVertical) * margin + alongHalf
        if (needed <= NOTHING_TO_FIT) return Camera3D.INITIAL_DISTANCE
        return min(Camera3D.MAX_DISTANCE, max(Camera3D.MIN_DISTANCE, needed))
    }

    /**
     * Where a world point lands on a viewport of [width] by [height] pixels, or null if it is
     * behind the eye.
     *
     * The perspective divide is the whole of the projection: a point with a non-positive `w` is
     * behind the camera, where dividing by it would fold it back onto the screen upside down.
     * OpenGL clips those against the near plane; a canvas has to be told.
     */
    fun project(transform: Matrix4, point: Coord3D, width: Float, height: Float): Coord2D? {
        val clip = transform.transform(point.x, point.y, point.z, 1f)
        if (clip[3] < Camera3D.NEAR) return null
        return toScreen(clip, width, height)
    }

    /**
     * Rejecting a whole leg because one end is behind the camera makes passages vanish the moment
     * you zoom into the cave; keeping it and dividing by a negative `w` folds that end back onto
     * the screen mirrored, which is worse. This clips the segment at the near plane by hand.
     */
    fun projectSegment(
        transform: Matrix4,
        start: Coord3D,
        end: Coord3D,
        width: Float,
        height: Float,
    ): Pair<Coord2D, Coord2D>? {
        var a = transform.transform(start.x, start.y, start.z, 1f)
        var b = transform.transform(end.x, end.y, end.z, 1f)
        val aInFront = a[3] >= Camera3D.NEAR
        val bInFront = b[3] >= Camera3D.NEAR
        if (!aInFront && !bInFront) return null
        if (!aInFront) a = crossingNearPlane(a, b)
        if (!bInFront) b = crossingNearPlane(b, a)
        return toScreen(a, width, height) to toScreen(b, width, height)
    }

    private fun crossingNearPlane(behind: FloatArray, inFront: FloatArray): FloatArray {
        val t = (Camera3D.NEAR - behind[3]) / (inFront[3] - behind[3])
        return FloatArray(4) { behind[it] + (inFront[it] - behind[it]) * t }
    }

    /**
     * The perspective divide.
     *
     * Normalised device coordinates run -1..1 with y up; the canvas runs 0..height with y down.
     */
    private fun toScreen(clip: FloatArray, width: Float, height: Float): Coord2D {
        val w = clip[3]
        return Coord2D(
            (clip[0] / w + 1f) * 0.5f * width,
            (1f - clip[1] / w) * 0.5f * height,
        )
    }

    companion object {

        fun of(space: Space<Coord3D>): Wireframe {
            val legs = mutableListOf<Pair<Coord3D, Coord3D>>()
            val splays = mutableListOf<Pair<Coord3D, Coord3D>>()
            for ((leg, line) in space.legMap) {
                val ends = line.start to line.end
                if (leg.hasDestination()) legs.add(ends) else splays.add(ends)
            }

            val stations = space.stationMap.values.toList()
            if (stations.isEmpty()) {
                val nowhere = Coord3D(0f, 0f, 0f)
                return Wireframe(legs, splays, stations, nowhere, nowhere)
            }

            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (coord in stations) {
                minX = min(minX, coord.x)
                maxX = max(maxX, coord.x)
                minY = min(minY, coord.y)
                maxY = max(maxY, coord.y)
                minZ = min(minZ, coord.z)
                maxZ = max(maxZ, coord.z)
            }

            return Wireframe(
                // Sorted so that the same survey always draws in the same order. `Space` keys its
                // maps on `Station` and `Leg`, neither of which overrides `hashCode`, so iteration
                // follows identity hashes and differs between runs — the same defect that makes
                // the Java's PocketTopo export unreproducible. It does not matter to a screenful of
                // lines, but it matters to a test that renders one.
                legs.sortedWith(BY_POSITION),
                splays.sortedWith(BY_POSITION),
                stations.sortedWith(compareBy({ it.x }, { it.y }, { it.z })),
                Coord3D((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f),
                Coord3D(maxX - minX, maxY - minY, maxZ - minZ),
            )
        }

        const val FIT_MARGIN = 1.12f

        /** Below this the survey has no extent worth pointing a camera at. A millimetre. */
        private const val NOTHING_TO_FIT = 0.001f

        private val BY_POSITION =
            compareBy<Pair<Coord3D, Coord3D>>(
                { it.first.x }, { it.first.y }, { it.first.z },
                { it.second.x }, { it.second.y }, { it.second.z },
            )
    }
}
