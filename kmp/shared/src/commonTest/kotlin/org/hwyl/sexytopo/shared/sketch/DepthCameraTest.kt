package org.hwyl.sexytopo.shared.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A depth picture becomes points in the right place, the right way up and the right way round.
 *
 * These exist because the failure they are looking for does not announce itself. A depth scan with
 * a sign wrong somewhere comes back full of points, in plausible numbers, at plausible distances —
 * and draws a passage mirrored, or upside-down, or turned. All three look like a scan that nearly
 * worked, and none of them can be told apart from a correct one without knowing the answer in
 * advance, which is what a test has and a surveyor in a cave has not.
 *
 * So each of these puts a camera somewhere known, points it somewhere known, and asks where a
 * particular pixel's rock ends up. What they pin is everything downstream of the conventions
 * `DepthCamera` states: the index arithmetic, the padding, the order of the multiply, every sign.
 * What no test here can pin is whether ARKit means by a pose and a set of optics what that class
 * says it means — that is written down rather than checked, and a mirrored scan on a phone would
 * be evidence against it rather than against anything here.
 */
class DepthCameraTest {

    /**
     * A four-by-four pose, given as what it actually is: three axes and a position.
     *
     * Written this way round on purpose. The bug this file is most afraid of is a column-major
     * matrix read as though it were row-major, and a test that lays sixteen floats out in a line
     * is a test that can make the same mistake as the code and agree with it. Naming the columns
     * means the test says "the camera's own X axis points *there*", which is a claim about the
     * world rather than about an index.
     *
     * The fourth float of each axis column is left NaN. Nothing should ever read it — those slots
     * are the bottom row of the matrix, which for a rigid pose is zero, zero, zero, one — so any
     * arithmetic that touches one comes back NaN and fails loudly instead of quietly.
     */
    private fun pose(
        xAxis: List<Float>,
        yAxis: List<Float>,
        zAxis: List<Float>,
        position: List<Float> = listOf(0f, 0f, 0f),
    ): FloatArray =
        floatArrayOf(
            xAxis[0], xAxis[1], xAxis[2], Float.NaN,
            yAxis[0], yAxis[1], yAxis[2], Float.NaN,
            zAxis[0], zAxis[1], zAxis[2], Float.NaN,
            position[0], position[1], position[2], Float.NaN,
        )

    /**
     * A camera that has not been moved or turned: its axes are the world's.
     *
     * The lens axis is put through the middle of a pixel rather than between two — the half in
     * 32.5 — so that "the middle of the picture" is an actual pixel these tests can name and the
     * arithmetic comes out exact. On a real camera it falls wherever it falls, and on a 64-wide
     * picture the geometric centre is in fact the crack between pixels 31 and 32; a test written
     * against that spends its time on a half-pixel that is not what any of this is about.
     */
    private fun stillCamera(position: List<Float> = listOf(0f, 0f, 0f)) =
        DepthCamera(
            fx = 10f,
            fy = 10f,
            cx = 32.5f,
            cy = 24.5f,
            transform =
                pose(
                    xAxis = listOf(1f, 0f, 0f),
                    yAxis = listOf(0f, 1f, 0f),
                    zAxis = listOf(0f, 0f, 1f),
                    position = position,
                ),
        )

    /** The pixel the lens axis goes through, so nothing is off to one side of anything. */
    private val centreColumn = 32
    private val centreRow = 24

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) < 0.001f,
            "$what should be $expected and is $actual",
        )
    }

    /**
     * Straight ahead is north, because that is where a camera that has not been turned is looking.
     *
     * The whole convention in one assertion. ARKit aligned to gravity and heading puts north at
     * negative Z, and a camera looks along its own negative Z, so an untouched camera looks north
     * — and three metres of depth at the middle of the picture is three metres north of where the
     * scan started. Get the sign of the depth wrong and this comes back three metres *south*,
     * which is a passage drawn behind the surveyor.
     */
    @Test
    fun theMiddleOfThePictureIsStraightAheadWhichIsNorth() {
        val point = stillCamera().pointAt(centreColumn, centreRow, 3f)

        assertClose(0f, point.x, "east")
        assertClose(3f, point.y, "north")
        assertClose(0f, point.z, "up")
    }

    /**
     * The right of the picture is to the right of the surveyor, and the top of it is above them.
     *
     * Two mirrors in one test, and they are separate mistakes: a flipped X draws the passage
     * reflected left to right, and a flipped Y draws it upside-down. Neither changes how *much*
     * passage there is, so neither shows up in anything that measures a width or counts a point.
     */
    @Test
    fun rightInThePictureIsEastAndUpInThePictureIsUp() {
        val camera = stillCamera()

        // Ten pixels right of centre at ten pixels of focal length and two metres out: a fifth of
        // the way across a right angle, so a fifth of two metres to the east, near enough.
        val right = camera.pointAt(centreColumn + 10, centreRow, 2f)
        assertTrue(right.x > 0f, "a pixel right of centre came back ${right.x} to the east")
        assertClose(2f, right.y, "north")

        // Rows count downwards in a picture and metres count upwards in a cave, which is the
        // negation this is here to hold on to.
        val high = camera.pointAt(centreColumn, centreRow - 10, 2f)
        assertTrue(high.z > 0f, "a pixel above centre came back ${high.z} up")

        val low = camera.pointAt(centreColumn, centreRow + 10, 2f)
        assertTrue(low.z < 0f, "a pixel below centre came back ${low.z} up")

        // Symmetrical about the lens axis, which a wrong principal point would not be.
        assertClose(-high.z, low.z, "the drop below centre against the rise above it")
    }

    /**
     * Where the camera is gets added to where the rock is.
     *
     * A surveyor sweeping a passage moves the phone about as they turn, and every point has to
     * come back in the same frame regardless. The translation living in the *last* column is the
     * part worth pinning: read a column-major matrix as row-major and the translation is picked up
     * from indices three, seven and eleven instead — which in this test are NaN, so getting it
     * wrong is not a near miss.
     */
    @Test
    fun theCamerasOwnPositionIsAddedToEveryPoint() {
        val point = stillCamera(position = listOf(1f, 2f, 3f)).pointAt(centreColumn, centreRow, 3f)

        assertClose(1f, point.x, "east")
        // Three metres ahead of a camera that is itself three metres south of the origin.
        assertClose(0f, point.y, "north")
        assertClose(2f, point.z, "up")
    }

    /**
     * A camera turned to face east finds the rock to the east, and its right hand is to the south.
     *
     * The one that catches a transposed matrix, which the tests above cannot: with the axes all
     * lined up on the world's, a matrix and its transpose are the same thing. Turned a quarter
     * turn they are opposite quarter turns, so a passage comes back reflected about the bearing it
     * was scanned on — which is a cross-section that looks entirely reasonable and is inside out.
     */
    @Test
    fun aCameraTurnedToFaceEastFindsTheRockToTheEast() {
        // Facing east: the camera's own negative Z has to land on east, so its Z axis points west.
        // Its right hand then points south, which is the second assertion below and is the half a
        // transpose would get wrong.
        val camera =
            DepthCamera(
                fx = 10f,
                fy = 10f,
                cx = 32.5f,
                cy = 24.5f,
                transform =
                    pose(
                        xAxis = listOf(0f, 0f, 1f),
                        yAxis = listOf(0f, 1f, 0f),
                        zAxis = listOf(-1f, 0f, 0f),
                    ),
            )

        val ahead = camera.pointAt(centreColumn, centreRow, 4f)
        assertClose(4f, ahead.x, "east")
        assertClose(0f, ahead.y, "north")
        assertClose(0f, ahead.z, "up")

        val right = camera.pointAt(centreColumn + 10, centreRow, 4f)
        assertTrue(
            right.y < 0f,
            "facing east, the right of the picture is south, and this came back ${right.y} north",
        )
    }

    /**
     * The optics are read out of the four slots that hold them and none of the eight that do not.
     *
     * Every other float is NaN, which makes this an exact statement rather than an approximate
     * one: the answer is a number if and only if the reads were from indices zero, five, eight and
     * nine. A three-wide vector occupies four floats in memory, so a three-by-three is twelve
     * floats with a hole in each column — and reading it as nine tightly-packed ones puts a focal
     * length where the principal point belongs, which is a scan wrong by a factor of hundreds.
     */
    @Test
    fun theOpticsAreReadOutOfTheirPaddedColumnsAndNowhereElse() {
        val n = Float.NaN
        val intrinsics =
            floatArrayOf(
                100f, n, n, n,
                n, 200f, n, n,
                300f, 400f, n, n,
            )

        val camera =
            DepthCamera.forDepthImage(
                intrinsics = intrinsics,
                imageWidth = 1000,
                imageHeight = 800,
                depthWidth = 100,
                depthHeight = 80,
                transform =
                    pose(
                        xAxis = listOf(1f, 0f, 0f),
                        yAxis = listOf(0f, 1f, 0f),
                        zAxis = listOf(0f, 0f, 1f),
                    ),
            )

        // Scaled by a tenth each way, so a focal length of ten and a principal point at (30, 40).
        val point = camera.pointAt(column = 40, row = 60, metres = 2f)

        assertClose(2.1f, point.x, "east")
        assertClose(2f, point.y, "north")
        assertClose(-2.05f, point.z, "up")
    }

    /**
     * The optics are scaled to the picture they are being used on, on both axes separately.
     *
     * ARKit describes the full-size camera image — a few million pixels — and the depth picture is
     * a fiftieth of it each way. Skip the scaling and every pixel comes out within a couple of
     * degrees of straight ahead, so a whole passage collapses into a spot: a scan that looks like
     * it found nothing rather than one that looks wrong.
     *
     * Asserted against worked-out numbers rather than against the same conversion run at another
     * size, which was the first shape of this test and a bad one — a pixel is a square, its centre
     * is half a pixel in, and half a pixel is not half a pixel once the picture has been shrunk, so
     * the two sizes disagree by an amount that has nothing to do with what is being tested.
     */
    @Test
    fun theOpticsAreScaledFromTheImageToTheDepthPicture() {
        val n = Float.NaN
        val intrinsics = floatArrayOf(100f, n, n, n, n, 100f, n, n, 320f, 240f, n, n)

        val camera =
            DepthCamera.forDepthImage(
                intrinsics = intrinsics,
                imageWidth = 640,
                imageHeight = 480,
                depthWidth = 64,
                depthHeight = 48,
                transform =
                    pose(
                        xAxis = listOf(1f, 0f, 0f),
                        yAxis = listOf(0f, 1f, 0f),
                        zAxis = listOf(0f, 0f, 1f),
                    ),
            )

        // A tenth the picture, so a focal length of ten and a principal point at (32, 24).
        val point = camera.pointAt(column = 40, row = 30, metres = 5f)

        assertClose(4.25f, point.x, "east")
        assertClose(5f, point.y, "north")
        assertClose(-3.25f, point.z, "up")

        // Unscaled, that same pixel would have come out fourteen metres to the *west*, being well
        // to the left of a principal point meant for a picture ten times the size. Nothing subtle
        // about the failure; it is simply invisible without a number to hold it to.
        assertTrue(point.x > 0f, "the scaling was not applied: ${point.x}")
    }

    /**
     * A depth is measured along the lens axis rather than along the ray, so a flat wall reads flat.
     *
     * The one convention here that is a genuine coin-toss rather than a documented certainty.
     * Apple's own words for a depth map are "the distance from the device to a point", which sounds
     * like the length of the ray; Apple's own sample code multiplies a ray whose Z is one by that
     * number, which makes it the distance along the lens axis. The sample code is what this
     * follows, on the grounds that working code beats a sentence.
     *
     * The two differ by the cosine of the angle off-axis, which is nothing in the middle of the
     * picture and about fifteen per cent at the edge of a phone's field of view. So the cost of
     * choosing wrong is not nonsense: it is a passage measured a little too wide, bulging where
     * each frame's edges fell, and the wall drawn slightly outside the splays already on the
     * section. That is what to look for on a phone, and it is why this is written down rather than
     * quietly assumed.
     *
     * What the test pins is the arithmetic that follows from the choice: every pixel of a wall
     * square-on reports the same depth and every point lands on the same plane, and a point off to
     * one side is genuinely further from the surveyor than the depth it was read from.
     */
    @Test
    fun aDepthIsMeasuredAlongTheLensAxisSoAFlatWallReadsFlat() {
        val camera = stillCamera()
        val wallAt = 4f

        val northings =
            buildList {
                for (row in 0 until 48 step 4) {
                    for (column in 0 until 64 step 4) {
                        add(camera.pointAt(column, row, wallAt).y)
                    }
                }
            }

        val worst = northings.maxOf { kotlin.math.abs(it - wallAt) }
        assertTrue(worst < 0.001f, "a flat wall came back bowed by ${worst}m")
        assertEquals(192, northings.size, "the sweep did not cover the picture it meant to")

        // And the half of the convention that is not about flatness: the corner of the picture is
        // four metres *along the lens axis* and further than that in a straight line. Were the
        // depth a ray length, this would be four metres exactly.
        val corner = camera.pointAt(0, 0, wallAt)
        val straightLine =
            kotlin.math.sqrt(corner.x * corner.x + corner.y * corner.y + corner.z * corner.z)
        assertTrue(
            straightLine > wallAt + 0.5f,
            "the corner of the picture is ${straightLine}m away, which is a ray length rather " +
                "than a depth along the axis",
        )
    }
}
