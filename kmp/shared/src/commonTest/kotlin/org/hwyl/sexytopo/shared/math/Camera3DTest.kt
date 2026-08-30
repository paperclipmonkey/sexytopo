package org.hwyl.sexytopo.shared.math

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Looking at a cave from the outside.
 *
 * The Java's camera lives on the OpenGL renderer, where nothing about it can be tested without a
 * GL context and a screenshot. Split out as a value, every one of these is a plain assertion.
 */
class Camera3DTest {

    private val viewport = 800f to 600f

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-3f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    /** A short passage heading north-east and down, three stations long. */
    private fun aCave(): Survey =
        Survey("Test").also {
            SurveyUpdater.updateWithNewStation(it, Leg(10f, 45f, -5f))
            SurveyUpdater.updateWithNewStation(it, Leg(8f, 90f, 0f))
            SurveyUpdater.updateWithNewStation(it, Leg(3f, 200f, 30f))
        }

    private fun wireframeOf(survey: Survey) = Wireframe.of(Space3DTransformer().transformTo3D(survey))

    // ---------------------------------------------------------------------------------------
    // The camera itself
    // ---------------------------------------------------------------------------------------

    @Test
    fun theEyeStartsAboveAndToTheSide() {
        val eye = Camera3D().eye
        // 45 degrees down from vertical and 45 degrees round, at the default 50 m.
        assertClose(25f, eye.x, 0.01f)
        assertClose(25f, eye.y, 0.01f)
        assertClose(35.355f, eye.z, 0.01f)
    }

    @Test
    fun draggingSpinsTheCameraAroundTheCave() {
        val spun = Camera3D().rotatedBy(100f, 0f)
        assertClose(Camera3D.INITIAL_ANGLE + 1f, spun.angleY)
        assertEquals(Camera3D.INITIAL_ANGLE, spun.angleX)
    }

    /**
     * At either pole the view direction and the up vector line up, their cross product is zero, and
     * every entry of the view matrix comes out NaN - a cave that vanishes rather than one that
     * flips.
     */
    @Test
    fun theCameraStopsShortOfLookingStraightDown() {
        val overhead = Camera3D().rotatedBy(0f, -10_000f)
        assertTrue(overhead.angleX > 0f, "the camera reached the pole")
        assertTrue(overhead.view.transform(1f, 1f, 1f, 1f).none { it.isNaN() })

        val underneath = Camera3D().rotatedBy(0f, 10_000f)
        assertTrue(underneath.angleX < PI.toFloat(), "the camera reached the other pole")
        assertTrue(underneath.view.transform(1f, 1f, 1f, 1f).none { it.isNaN() })
    }

    @Test
    fun zoomingIsClampedAtBothEnds() {
        assertEquals(Camera3D.MIN_DISTANCE, Camera3D().zoomedBy(0.0001f).distance)
        assertEquals(Camera3D.MAX_DISTANCE, Camera3D().zoomedBy(10_000f).distance)
        assertEquals(25f, Camera3D(distance = 50f).zoomedBy(0.5f).distance)
    }

    /**
     * Panning has to follow the screen, not the world: dragging right moves the cave right whichever
     * way the camera is currently pointing. The check is that the pan lands on the camera's own
     * right-hand axis, whatever that is.
     */
    @Test
    fun panningMovesTheCaveTheWayTheFingerWent() {
        val camera = Camera3D(angleY = 1.3f, angleX = 1.1f, distance = 40f)
        val panned = camera.pannedBy(100f, 0f)

        // The pan, seen from the camera, is purely sideways and to the right.
        val inView = camera.view.transform(panned.panX, panned.panY, panned.panZ, 0f)
        assertTrue(inView[0] > 0f, "dragging right moved the cave left")
        assertClose(0f, inView[1])
        assertClose(0f, inView[2])
    }

    @Test
    fun panningUpAndDownFollowsTheScreenToo() {
        val camera = Camera3D()
        val inView = camera.view.let { view ->
            val panned = camera.pannedBy(0f, 100f)
            view.transform(panned.panX, panned.panY, panned.panZ, 0f)
        }
        // Dragging down the screen moves the cave down the screen.
        assertTrue(inView[1] < 0f, "dragging down moved the cave up")
    }

    @Test
    fun aPanIsBiggerWhenYouAreFurtherAway() {
        val near = Camera3D(distance = 10f).pannedBy(100f, 0f)
        val far = Camera3D(distance = 100f).pannedBy(100f, 0f)
        val nearMoved = near.panX * near.panX + near.panY * near.panY + near.panZ * near.panZ
        val farMoved = far.panX * far.panX + far.panY * far.panY + far.panZ * far.panZ
        assertTrue(farMoved > nearMoved)
    }

    /**
     * The whole cave, on the shape of screen it is actually being looked at on, with room to spare.
     *
     * A portrait phone is the case that matters and the one a naive fit gets wrong: the field of
     * view is vertical, so the horizontal one is much narrower, and a cave fitted to the height
     * hangs off both sides.
     */
    @Test
    fun theWholeCaveIsOnScreenWhenTheViewOpens() {
        val wireframe = wireframeOf(aCave())
        for ((width, height) in listOf(420f to 740f, 1200f to 820f, 900f to 900f)) {
            val aspect = width / height
            val camera = Camera3D(distance = wireframe.distanceToFit(Camera3D(), aspect))
            val transform = camera.transformFor(wireframe.centre, aspect)

            for (station in wireframe.stations) {
                val at =
                    assertNotNull(
                        wireframe.project(transform, station, width, height),
                        "a station went behind the camera on a ${width}x$height screen",
                    )
                assertTrue(
                    at.x in 0f..width && at.y in 0f..height,
                    "a station drew at $at, off a ${width}x$height screen",
                )
            }
        }
    }

    /**
     * And it fills that screen rather than sitting in the middle of it. This is the assertion that
     * fails if the fit goes back to bounding the cave by a sphere that holds from every angle: a
     * cave is long and thin, and that sphere is so much bigger than what is actually on screen that
     * two thirds of a phone is left empty.
     */
    @Test
    fun theCaveFillsTheScreenItIsFittedTo() {
        val wireframe = wireframeOf(aCave())
        val width = 420f
        val height = 740f
        val camera = Camera3D(distance = wireframe.distanceToFit(Camera3D(), width / height))
        val transform = camera.transformFor(wireframe.centre, width / height)

        val drawn = wireframe.stations.mapNotNull { wireframe.project(transform, it, width, height) }
        val across = drawn.maxOf { it.x } - drawn.minOf { it.x }
        val down = drawn.maxOf { it.y } - drawn.minOf { it.y }

        assertTrue(
            across > width * 0.6f || down > height * 0.6f,
            "the cave drew ${across}x$down on a ${width}x$height screen",
        )
    }

    /** A narrower screen needs a camera further back, because the horizontal angle is the tighter. */
    @Test
    fun aNarrowerScreenNeedsAFurtherCamera() {
        val wireframe = wireframeOf(aCave())
        val portrait = wireframe.distanceToFit(Camera3D(), 0.5f)
        val square = wireframe.distanceToFit(Camera3D(), 1f)

        assertTrue(portrait > square, "a portrait screen fitted the cave closer than a square one")
    }

    /**
     * A live survey starts with one station and no legs. There is nothing to fit, and fitting it
     * anyway would be arithmetically valid and visually absurd — a camera a metre from a dot.
     */
    @Test
    fun aSurveyWithNoExtentStillGetsAUsableCamera() {
        val empty = wireframeOf(Survey("Empty"))
        assertEquals(Camera3D.INITIAL_DISTANCE, empty.distanceToFit(Camera3D(), 0.6f))
    }

    @Test
    fun theStartingDistanceFitsTheCave() {
        assertEquals(150f, Camera3D.fittingExtent(100f))
        // A survey with one station has no extent, and would otherwise put the camera on top of it.
        assertEquals(Camera3D.INITIAL_DISTANCE, Camera3D.fittingExtent(0f))
        // A cave longer than the far clip plane would otherwise disappear entirely.
        assertEquals(Camera3D.MAX_DISTANCE, Camera3D.fittingExtent(100_000f))
    }

    // ---------------------------------------------------------------------------------------
    // What gets drawn
    // ---------------------------------------------------------------------------------------

    @Test
    fun legsAndSplaysAreSeparated() {
        val survey = aCave()
        // A plain update is a splay off the active station until three agree and promote it.
        SurveyUpdater.update(survey, Leg(2f, 10f, 0f))

        val wireframe = wireframeOf(survey)

        assertEquals(3, wireframe.legs.size)
        assertEquals(1, wireframe.splays.size)
        assertEquals(4, wireframe.stations.size)
    }

    @Test
    fun theCaveIsCentredOnItsOwnBoundingBox() {
        val survey =
            Survey("Test").also {
                // Ten metres due east, level: the box runs 0..10 in x and nothing in y or z.
                SurveyUpdater.updateWithNewStation(it, Leg(10f, 90f, 0f))
            }

        val wireframe = wireframeOf(survey)

        assertClose(5f, wireframe.centre.x, 0.01f)
        assertClose(0f, wireframe.centre.y, 0.01f)
        assertClose(0f, wireframe.centre.z, 0.01f)
        assertClose(10f, wireframe.extent, 0.01f)
        // Half the diagonal of a box that is ten metres long and nothing else.
        assertClose(5f, wireframe.radius, 0.01f)
    }

    @Test
    fun anEmptySurveyDrawsNothingRatherThanThrowing() {
        val wireframe = wireframeOf(Survey("Empty"))
        assertEquals(0, wireframe.legs.size)
        assertEquals(1, wireframe.stations.size)
        assertEquals(0f, wireframe.extent)
    }

    /**
     * The same survey has to draw the same way twice. `Space` keys its maps on `Station` and `Leg`,
     * neither of which overrides `hashCode`, so iterating it directly is in identity-hash order and
     * differs between runs.
     */
    @Test
    fun theSameSurveyDrawsInTheSameOrderEveryTime() {
        val survey = aCave()
        val first = wireframeOf(survey)
        val second = wireframeOf(survey)
        assertEquals(first.legs.map { it.toString() }, second.legs.map { it.toString() })
        assertEquals(first.stations.map { it.toString() }, second.stations.map { it.toString() })
    }

    // ---------------------------------------------------------------------------------------
    // Projection
    // ---------------------------------------------------------------------------------------

    @Test
    fun theMiddleOfTheCaveIsInTheMiddleOfTheScreen() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val transform = Camera3D().transformFor(wireframe.centre, width / height)

        val screen = assertNotNull(wireframe.project(transform, wireframe.centre, width, height))

        assertClose(width / 2f, screen.x, 0.01f)
        assertClose(height / 2f, screen.y, 0.01f)
    }

    @Test
    fun somethingHigherUpInTheCaveIsHigherUpTheScreen() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val transform = Camera3D().transformFor(wireframe.centre, width / height)
        val centre = wireframe.centre

        val above = assertNotNull(
            wireframe.project(transform, Coord3D(centre.x, centre.y, centre.z + 5f), width, height),
        )

        assertTrue(above.y < height / 2f, "a station five metres up drew below the middle")
    }

    /** Panning right has to move the drawing right, or the gesture is inverted. */
    @Test
    fun panningTheCameraMovesTheDrawing() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val camera = Camera3D()
        val before = assertNotNull(
            wireframe.project(
                camera.transformFor(wireframe.centre, width / height),
                wireframe.centre,
                width,
                height,
            ),
        )
        val panned = camera.pannedBy(200f, 0f)
        val after = assertNotNull(
            wireframe.project(
                panned.transformFor(wireframe.centre, width / height),
                wireframe.centre,
                width,
                height,
            ),
        )

        assertTrue(after.x > before.x, "panning right moved the cave left")
    }

    @Test
    fun somethingBehindTheCameraIsNotDrawn() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val camera = Camera3D(distance = 10f)
        val transform = camera.transformFor(wireframe.centre, width / height)

        // Twice as far out as the eye, in the same direction: comfortably behind it.
        val behind =
            Coord3D(
                wireframe.centre.x + camera.eye.x * 2,
                wireframe.centre.y + camera.eye.y * 2,
                wireframe.centre.z + camera.eye.z * 2,
            )

        assertNull(wireframe.project(transform, behind, width, height))
    }

    /**
     * A passage running past the camera has to keep being drawn. Dropping the whole leg because one
     * end is behind you is what makes a cave disappear as you zoom into it.
     */
    @Test
    fun aLegWithOneEndBehindTheCameraIsClippedRatherThanDropped() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val camera = Camera3D(distance = 10f)
        val transform = camera.transformFor(wireframe.centre, width / height)
        val centre = wireframe.centre
        val behind =
            Coord3D(
                centre.x + camera.eye.x * 2,
                centre.y + camera.eye.y * 2,
                centre.z + camera.eye.z * 2,
            )

        val drawn = assertNotNull(wireframe.projectSegment(transform, centre, behind, width, height))

        // The end that was in front is where it always was.
        val alone = assertNotNull(wireframe.project(transform, centre, width, height))
        assertClose(alone.x, drawn.first.x, 0.01f)
        assertClose(alone.y, drawn.first.y, 0.01f)
        // The other end is now somewhere finite rather than folded back mirrored.
        assertTrue(drawn.second.x.isFinite() && drawn.second.y.isFinite())
    }

    @Test
    fun aLegEntirelyBehindTheCameraIsNotDrawn() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val camera = Camera3D(distance = 10f)
        val transform = camera.transformFor(wireframe.centre, width / height)
        val centre = wireframe.centre
        fun behind(scale: Float) =
            Coord3D(
                centre.x + camera.eye.x * scale,
                centre.y + camera.eye.y * scale,
                centre.z + camera.eye.z * scale,
            )

        assertNull(wireframe.projectSegment(transform, behind(2f), behind(3f), width, height))
    }

    @Test
    fun aLegWhollyInFrontIsProjectedEndToEnd() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val transform = Camera3D().transformFor(wireframe.centre, width / height)
        val centre = wireframe.centre
        val start = Coord3D(centre.x - 2f, centre.y, centre.z)
        val end = Coord3D(centre.x + 2f, centre.y, centre.z)

        val drawn = assertNotNull(wireframe.projectSegment(transform, start, end, width, height))

        assertEquals(assertNotNull(wireframe.project(transform, start, width, height)).x, drawn.first.x)
        assertEquals(assertNotNull(wireframe.project(transform, end, width, height)).x, drawn.second.x)
    }

    /** Perspective: of two stations the same distance apart, the nearer pair looks wider. */
    @Test
    fun nearerPassageLooksBigger() {
        val wireframe = wireframeOf(aCave())
        val (width, height) = viewport
        val camera = Camera3D(angleX = (PI / 2).toFloat(), angleY = 0f, distance = 50f)
        val transform = camera.transformFor(wireframe.centre, width / height)
        val centre = wireframe.centre

        // Absolute, because which way round the two walls land on screen depends on where the
        // camera is standing: here it is due north looking south, so world +x is screen *left*.
        fun apparentWidthAt(towardsCamera: Float): Float {
            fun screenXAt(offset: Float) =
                assertNotNull(
                    wireframe.project(
                        transform,
                        Coord3D(centre.x + offset, centre.y + towardsCamera, centre.z),
                        width,
                        height,
                    ),
                ).x
            return abs(screenXAt(1f) - screenXAt(-1f))
        }

        // The camera is at +y looking back at the origin, so a larger y is nearer to it.
        assertTrue(
            apparentWidthAt(20f) > apparentWidthAt(-20f),
            "the far end of the cave drew wider than the near",
        )
    }
}
