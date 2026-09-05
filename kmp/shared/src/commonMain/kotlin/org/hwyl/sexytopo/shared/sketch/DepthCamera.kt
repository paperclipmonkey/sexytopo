package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * Turning a lidar depth image into points on the rock, in the survey's own axes.
 *
 * A phone with lidar hands back a small depth picture — 256 by 192 on the iPhones that have one —
 * where every pixel is a distance in metres to whatever that pixel is looking at. That is a far
 * better source than the sparse cloud ARKit builds for tracking: it is dense, it is measured
 * rather than inferred from texture, and it does not care whether the rock has anything to look at.
 * What it is not is points. Turning a distance at a pixel into a place in the passage takes the
 * camera's own optics and its pose, and that arithmetic is what this is.
 *
 * ## Why it is here rather than in the file that reads the sensor
 *
 * Because getting it wrong produces a *plausible* wrong answer, which is the worst kind. A sign
 * flipped in the wrong place does not crash and does not come back empty: it draws a passage
 * mirrored, or upside-down, or turned ninety degrees, and every one of those looks like a scan
 * that nearly worked. The one thing that separates them from a correct scan is knowing what the
 * answer should have been, and a build server can know that where a phone in a cave cannot.
 *
 * So the whole conversion is a pure function of numbers, and `DepthCameraTest` walks a known
 * camera through known pixels. What those tests can pin is everything from a stated convention
 * onwards: the index arithmetic, the padding in the matrices, the order of the multiply, each
 * sign. What they cannot pin is the convention itself — that ARKit hands back the pose and the
 * optics meaning what the next section says they mean. That part is written down rather than
 * checked, and if a scan comes back mirrored or upside-down this paragraph is the first place to
 * look.
 *
 * ## The conventions this assumes
 *
 * **The depth picture.** Row zero at the top, column zero at the left, laid out the same way as
 * the camera's own captured image and therefore in the same frame as the optics below. The value
 * at a pixel is the distance from the camera plane, in metres, along the way the camera is looking.
 *
 * **The optics.** A focal length and a principal point in pixels, matching the *captured image*
 * rather than the depth picture — ARKit reports them that way, and the two differ by a factor of
 * seven or so, which `forDepthImage` is what exists to deal with.
 *
 * **The camera's own axes.** X to the right of the picture, Y up it, and Z *backwards* out of the
 * lens, so what the camera is looking at is at negative Z. That is ARKit's convention and OpenGL's
 * before it, and it is the one most likely to be argued with: a depth of three metres becomes a
 * point at z minus three, not plus three.
 *
 * **The pose.** Sixteen floats, column-major, taking a point in the camera's axes to a point in
 * the world's — which is how simd, ARKit and every graphics API this side of Direct3D write a
 * four-by-four. Column-major means the translation is the last four, at indices twelve to fourteen,
 * and *not* at three, seven and eleven. The two are indistinguishable in a camera that has not
 * moved, which is precisely the case somebody testing at their desk will try first.
 *
 * **The world's axes.** Gravity and true north, which is what the scanner asks ARKit for: X east,
 * Y up, and Z south — so north is negative Z. The survey's own axes, the ones `toCartesian`
 * builds, are X east, Y north, Z up. So the last thing done here is a swap and a negation, and
 * the fact that it is one line at the bottom of one method is the reason it is worth naming.
 */
class DepthCamera(
    /** Focal length in depth-picture pixels, across and down. */
    private val fx: Float,
    private val fy: Float,
    /** Where the lens axis crosses the depth picture, in the same pixels. */
    private val cx: Float,
    private val cy: Float,
    /** Camera to world, sixteen floats, column-major. */
    private val transform: FloatArray,
) {

    init {
        require(transform.size == TRANSFORM_FLOATS) {
            "a camera pose is $TRANSFORM_FLOATS floats, not ${transform.size}"
        }
    }

    /**
     * Where the rock is that the pixel at this column and row is looking at, in survey axes and in
     * metres from wherever the scan started.
     *
     * Half a pixel is added to each because a pixel is a small square rather than a point, and its
     * centre is what the depth belongs to. It makes a difference of one part in five hundred, which
     * matters to nothing here and is done because leaving it out is the kind of thing that gets
     * copied into somewhere it does matter.
     */
    fun pointAt(column: Int, row: Int, metres: Float): Coord3D {
        // Out of the picture and into the camera's own axes. The depth picture counts rows
        // downwards and the camera counts Y upwards, so that one is negated; and the camera looks
        // along negative Z, so the distance is too.
        val acrossThePicture = (column + HALF_A_PIXEL - cx) * metres / fx
        val downThePicture = (row + HALF_A_PIXEL - cy) * metres / fy

        val cameraX = acrossThePicture
        val cameraY = -downThePicture
        val cameraZ = -metres

        // Column-major, so the columns are the strides of four and the translation is the last one.
        val east = transform[0] * cameraX + transform[4] * cameraY +
            transform[8] * cameraZ + transform[12]
        val up = transform[1] * cameraX + transform[5] * cameraY +
            transform[9] * cameraZ + transform[13]
        val south = transform[2] * cameraX + transform[6] * cameraY +
            transform[10] * cameraZ + transform[14]

        // ARKit's axes into the survey's: east stays east, north is the other way from south, and
        // what ARKit calls up is what the survey calls z.
        return Coord3D(east, -south, up)
    }

    companion object {

        /** Sixteen for a four-by-four, and the size the pose is asserted to be. */
        const val TRANSFORM_FLOATS = 16

        /**
         * Twelve, not nine, for a three-by-three of the kind ARKit reports the optics in.
         *
         * The same padding that catches everyone reading a point cloud: a three-wide vector
         * occupies four floats in memory, because that is the width the vector unit wants, and the
         * fourth is nothing at all. So the three columns sit at zero, four and eight rather than
         * at zero, three and six. Reading it as nine tightly-packed floats gives a focal length
         * where the principal point should be, and a scan that is wrong by a factor of hundreds
         * rather than obviously broken.
         */
        const val INTRINSICS_FLOATS = 12

        private const val HALF_A_PIXEL = 0.5f

        /**
         * A camera for reading a depth picture, from what the sensor reports about the full-size
         * one.
         *
         * The optics describe the captured image — a few million pixels — and the depth picture is
         * a great deal smaller, so every one of them is scaled by the ratio between the two. Both
         * axes are scaled separately even though the two pictures have the same shape and one
         * factor would do: they have the same shape *today*, on the phones that exist, and a scan
         * silently squashed along one axis is not something anybody would spot in a cave.
         */
        fun forDepthImage(
            intrinsics: FloatArray,
            imageWidth: Int,
            imageHeight: Int,
            depthWidth: Int,
            depthHeight: Int,
            transform: FloatArray,
        ): DepthCamera {
            require(intrinsics.size == INTRINSICS_FLOATS) {
                "camera optics are $INTRINSICS_FLOATS floats, not ${intrinsics.size}"
            }
            require(imageWidth > 0 && imageHeight > 0 && depthWidth > 0 && depthHeight > 0) {
                "a picture with no pixels: ${imageWidth}x$imageHeight and ${depthWidth}x$depthHeight"
            }

            val across = depthWidth.toFloat() / imageWidth
            val down = depthHeight.toFloat() / imageHeight

            // Column-major with four-float columns, so: the first column holds the focal length
            // across at nothing, the second holds it down at five, and the third holds the
            // principal point at eight and nine.
            return DepthCamera(
                fx = intrinsics[0] * across,
                fy = intrinsics[5] * down,
                cx = intrinsics[8] * across,
                cy = intrinsics[9] * down,
                transform = transform,
            )
        }
    }
}
