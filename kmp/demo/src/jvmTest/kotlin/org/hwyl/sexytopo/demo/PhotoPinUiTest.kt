package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.width
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.PhotoStore
import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PhotoDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color as AwtColour
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A photograph pinned to the drawing, driven the way a surveyor drives it.
 *
 * The pieces underneath — the model, the JSON, the store, the zip — are checked in the shared
 * module. What is left here is everything that only exists once there is a finger on the glass: the
 * tap and the drag that place a pin, the hit test that opens one again, the pin actually being
 * drawn, and the shape of the toolbar the camera button was added to.
 *
 * Driven through `ImageComposeScene`'s own pointer events for the canvas and through the semantics
 * tree for the toolbar and the viewer, which is what the neighbouring tests in this directory do
 * and for the same reasons: a gesture asserted one layer up would pass on a canvas that never
 * received the touch at all.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class PhotoPinUiTest {

    private val width = 600
    private val height = 600

    private val pressed = PointerButtons(isPrimaryPressed = true)
    private val lifted = PointerButtons()

    private fun ImageComposeScene.touch(type: PointerEventType, at: Offset) {
        sendPointerEvent(
            eventType = type,
            position = at,
            type = PointerType.Touch,
            buttons = if (type == PointerEventType.Release) lifted else pressed,
        )
    }

    /** A tap: down and straight back up, nowhere near the touch slop. */
    private fun ImageComposeScene.tap(at: Offset) {
        touch(PointerEventType.Press, at)
        touch(PointerEventType.Release, at)
        render()
    }

    /**
     * A drag in a straight line, in several steps.
     *
     * Several rather than one because of how `detectDragGestures` starts: the move that carries the
     * finger past the touch slop is the one that opens the drag, and the bearing at that instant is
     * measured from that point to itself, which is zero. Only the moves after it say anything. A
     * drag written as a single move would therefore report north whichever way it went, and a check
     * that never noticed would be a check on nothing.
     */
    private fun ImageComposeScene.dragAcross(from: Offset, to: Offset) {
        touch(PointerEventType.Press, from)
        for (step in 1..DRAG_STEPS) {
            touch(PointerEventType.Move, from + (to - from) * (step.toFloat() / DRAG_STEPS))
        }
        touch(PointerEventType.Release, to)
        render()
    }

    /** A short right-angled passage, which is all the paper these need. */
    private fun passage(): Survey {
        val survey = Survey("Photo")
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 90f, 0f))
        return survey
    }

    /** What the canvas reported, and how many times it reported anything at all. */
    private class Pinned {
        var placements = 0
        var position: Coord2D = Coord2D.ORIGIN
        var angle: Float = Float.NaN
    }

    private fun placementScene(
        survey: Survey,
        canvas: CanvasController,
        pinned: Pinned,
        placingPhoto: Boolean = true,
    ): ImageComposeScene {
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        return ImageComposeScene(width = width, height = height, density = Density(1f)) {
            SurveyCanvas(
                survey = survey,
                projection = Projection2D.PLAN,
                // Hot corners off: a touch that starts in one of the four corners pans the view
                // whatever tool is in hand, and a pin these tests never asked for would be a
                // failure with nothing wrong behind it.
                options = DisplayOptions(showGrid = false, hotCorners = false),
                editor = editor,
                canvas = canvas,
                modifier = Modifier.fillMaxSize(),
                tool = SketchTool.PLACE_PHOTO,
                placingPhoto = placingPhoto,
                onPlacePhoto = { position, angle ->
                    pinned.placements++
                    pinned.position = position
                    pinned.angle = angle
                },
            )
        }
    }

    private fun assertNear(expected: Float, actual: Float, what: String, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")

    // -----------------------------------------------------------------------------------------
    // Placing a pin
    // -----------------------------------------------------------------------------------------

    /**
     * A tap pins the photograph where the finger went down, facing north.
     *
     * North because a tap says nothing about which way the surveyor was looking, and a pin has to
     * point somewhere. The alternative — refusing to place one until a direction is given — would
     * mean a photograph of the ceiling could not be recorded at all.
     */
    @Test
    fun aTapPinsThePhotographWhereItWasTappedFacingNorth() {
        val survey = passage()
        val canvas = CanvasController()
        val pinned = Pinned()
        val scene = placementScene(survey, canvas, pinned)
        try {
            scene.render()
            scene.tap(FINGER_DOWN_AT)

            assertEquals(1, pinned.placements, "a tap with a photograph in hand pinned nothing")
            val wanted = canvas.viewport.toSurvey(FINGER_DOWN_AT.toCoord2D())
            assertNear(wanted.x, pinned.position.x, "the pin's easting")
            assertNear(wanted.y, pinned.position.y, "the pin's northing")
            assertEquals(0f, pinned.angle, "a tap should leave the pin facing north")
        } finally {
            scene.close()
        }
    }

    /**
     * A drag pins the photograph where the finger landed and aims it where the finger went.
     *
     * Both halves matter and they pull in opposite directions, which is why they are asserted
     * together: the drag says which way the surveyor was looking, not where they were standing. A
     * pin dropped at the end of the drag would put the camera several metres down the passage from
     * the spot the photograph was actually taken at.
     *
     * The bearings are the ones [SymbolStampTest] holds the directional symbol stamp to, against
     * the same expected numbers, because a photograph and a water-flow arrow dragged the same way
     * are meant to end up pointing the same way. Three of them rather than one, so a canvas that
     * ignored the drag and pinned everything north could not pass.
     */
    @Test
    fun aDragPinsWhereTheFingerLandedAndAimsWhereItWent() {
        for ((corner, bearing) in DRAGS) {
            val survey = passage()
            val canvas = CanvasController()
            val pinned = Pinned()
            val scene = placementScene(survey, canvas, pinned)
            try {
                scene.render()
                scene.dragAcross(FINGER_DOWN_AT, corner)

                assertEquals(1, pinned.placements, "a drag towards $corner pinned nothing")
                assertNear(bearing, pinned.angle, "the bearing dragged towards $corner", 0.5f)

                // Where the finger landed, allowing for the touch slop: the drag does not begin
                // until the finger has moved far enough to prove it is a drag, so the pin lands at
                // the point that proved it rather than at the very first pixel touched.
                val landed = canvas.viewport.toSurvey(FINGER_DOWN_AT.toCoord2D())
                val leftOff = canvas.viewport.toSurvey(corner.toCoord2D())
                val fromLanding = getDistance(pinned.position, landed)
                val fromLift = getDistance(pinned.position, leftOff)
                assertTrue(
                    fromLanding <= canvas.viewport.toSurveyDistance(TOUCH_SLOP_ALLOWANCE_PX),
                    "the pin should be where the finger landed, and was ${fromLanding}m away " +
                        "dragging towards $corner",
                )
                assertTrue(
                    fromLanding < fromLift,
                    "the pin was put nearer where the drag ended (${fromLift}m) than where it " +
                        "began (${fromLanding}m): a photograph is taken from where the surveyor " +
                        "stood, not from where they pointed",
                )
            } finally {
                scene.close()
            }
        }
    }

    /**
     * With nothing in hand, a tap on the paper means nothing.
     *
     * The camera can be opened and backed out of — a surveyor changes their mind, or the phone
     * refuses — and the tool is armed the moment the camera is asked for rather than when a picture
     * comes back. Without this guard the next tap on the drawing would pin a photograph that does
     * not exist, leaving a pin that opens onto nothing.
     */
    @Test
    fun withNoPhotographInHandATapPinsNothing() {
        val survey = passage()
        val canvas = CanvasController()
        val pinned = Pinned()
        val scene = placementScene(survey, canvas, pinned, placingPhoto = false)
        try {
            scene.render()
            scene.tap(FINGER_DOWN_AT)
            scene.dragAcross(FINGER_DOWN_AT, Offset(420f, 380f))

            assertEquals(
                0,
                pinned.placements,
                "the canvas pinned a photograph that was never taken",
            )
        } finally {
            scene.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Opening a pin again
    // -----------------------------------------------------------------------------------------

    /** The passage with one pin on its plan, at [PIN_AT]. */
    private fun surveyWithAPin(): Pair<Survey, PhotoDetail> {
        val survey = passage()
        val pin =
            survey
                .getSketch(Projection2D.PLAN)
                .addPhotoDetail(PIN_AT, photoId = PHOTO_ID, size = PIN_SIZE_M, angle = 0f)
        return survey to pin
    }

    private fun pinScene(
        survey: Survey,
        canvas: CanvasController,
        opened: MutableList<PhotoDetail>,
        showPhotoPins: Boolean = true,
        tool: SketchTool = SketchTool.SELECT,
    ): ImageComposeScene {
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        return ImageComposeScene(width = width, height = height, density = Density(1f)) {
            SurveyCanvas(
                survey = survey,
                projection = Projection2D.PLAN,
                options =
                    DisplayOptions(
                        showGrid = false,
                        hotCorners = false,
                        showPhotoPins = showPhotoPins,
                    ),
                editor = editor,
                canvas = canvas,
                modifier = Modifier.fillMaxSize(),
                tool = tool,
                onOpenPhoto = { opened.add(it) },
            )
        }
    }

    /**
     * Somewhere on the paper the pin is not, and still on the canvas.
     *
     * Measured away from the pin rather than fixed, since where the pin is drawn depends on the
     * zoom the view opened at, and a hard-coded empty spot could quietly come to sit on top of it.
     */
    private fun awayFrom(pin: Offset): Offset =
        if (pin.y > height / 2) Offset(pin.x, pin.y - CLEAR_PAPER_PX)
        else Offset(pin.x, pin.y + CLEAR_PAPER_PX)

    /**
     * A pin opens its photograph, and clear paper opens nothing.
     *
     * The pair is the point. A hit test generous enough to catch every pin also catches half the
     * drawing, and a surveyor who cannot tap the passage without a photograph appearing over it
     * will stop tapping the passage.
     */
    @Test
    fun tappingAPinOpensThePhotographAndTappingEmptyPaperDoesNot() {
        val (survey, pin) = surveyWithAPin()
        val canvas = CanvasController()
        val opened = mutableListOf<PhotoDetail>()
        val scene = pinScene(survey, canvas, opened)
        try {
            scene.render()
            val onScreen = canvas.viewport.toScreen(PIN_AT)

            scene.tap(awayFrom(onScreen))
            assertTrue(
                opened.isEmpty(),
                "a tap on clear paper opened a photograph that is not there",
            )

            scene.tap(onScreen)
            assertEquals(1, opened.size, "tapping the pin did not open the photograph")
            assertSame(pin, opened.single(), "the wrong pin was opened")
        } finally {
            scene.close()
        }
    }

    /**
     * A pin opens its photograph under every tool but the rubber.
     *
     * Written as a table because the fault it is guarding against was one of omission, and a fault
     * of omission is invisible in a test that names one tool. Opening a photograph was handled in
     * the pencil's tap and the selector's, and nowhere else — so with the pan tool in hand, which
     * is what `SketchTool.DEFAULT` is and therefore what the app opens in, a tap on a pin did
     * nothing whatever. The way to look at a photograph was to notice that, go to the toolbar, and
     * pick a different tool; and the tools either side of the ones that work include the rubber,
     * which does not open a pin but removes it.
     *
     * The rubber is left out on purpose rather than forgotten: a tool drawn as an eraser has to
     * erase what is under it, photo pins included, and `SketchEditor.eraseAt` is what does that.
     * Its absence from this list is the statement that it is the *only* tool a tap can lose a pin
     * under.
     *
     * The two cross-section tools and the modal ones are left out because they are entered for one
     * gesture and are not a tool anybody is left holding.
     */
    @Test
    fun aPinOpensItsPhotographUnderEveryToolButTheRubber() {
        val opensAPin =
            listOf(
                SketchTool.MOVE,
                SketchTool.DRAW,
                SketchTool.SELECT,
                SketchTool.SYMBOL,
                SketchTool.TEXT,
            )

        for (tool in opensAPin) {
            val (survey, pin) = surveyWithAPin()
            val canvas = CanvasController()
            val opened = mutableListOf<PhotoDetail>()
            val scene = pinScene(survey, canvas, opened, tool = tool)
            try {
                scene.render()
                scene.tap(canvas.viewport.toScreen(PIN_AT))
                assertEquals(
                    1,
                    opened.size,
                    "tapping the pin with $tool in hand did not open the photograph",
                )
                assertSame(pin, opened.single(), "$tool opened the wrong pin")
            } finally {
                scene.close()
            }
        }
    }

    /**
     * Under the rubber a tap takes the pin off, which is the one place that is right.
     *
     * The other half of the table above, and the reason this is a test rather than a line of prose:
     * "every tool but the rubber opens it" is only worth anything if the rubber really is the
     * exception, and if it stops being one this fails rather than quietly agreeing.
     */
    @Test
    fun theRubberTakesAPinOffRatherThanOpeningIt() {
        val (survey, _) = surveyWithAPin()
        val canvas = CanvasController()
        val opened = mutableListOf<PhotoDetail>()
        val sketch = survey.getSketch(Projection2D.PLAN)
        val scene = pinScene(survey, canvas, opened, tool = SketchTool.ERASE)
        try {
            scene.render()
            scene.tap(canvas.viewport.toScreen(PIN_AT))
            assertTrue(opened.isEmpty(), "the rubber opened a photograph instead of erasing")
            assertTrue(
                sketch.photoDetails.isEmpty(),
                "the rubber left the pin behind: ${sketch.photoDetails.size} still on the drawing",
            )
        } finally {
            scene.close()
        }
    }

    /**
     * A pin the surveyor has turned off is neither drawn nor touchable.
     *
     * The drawing half is asserted alongside the touch half deliberately: it is what says the pin
     * really was invisible, and an invisible thing taking taps is the worst version of this — the
     * drawing opens a photograph and there is nothing on the screen to say why.
     */
    @Test
    fun aHiddenPinIsNeitherDrawnNorTappable() {
        val (survey, _) = surveyWithAPin()
        val canvas = CanvasController()
        val opened = mutableListOf<PhotoDetail>()
        val scene = pinScene(survey, canvas, opened, showPhotoPins = false)
        try {
            scene.render()
            scene.tap(canvas.viewport.toScreen(PIN_AT))

            assertTrue(
                opened.isEmpty(),
                "a pin nobody can see took the tap meant for the paper underneath it",
            )
        } finally {
            scene.close()
        }

        assertEquals(
            0L,
            inkOnThePlan(withPin = true, showPhotoPins = false),
            "a pin turned off should not be drawn at all",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Drawing a pin
    // -----------------------------------------------------------------------------------------

    /**
     * Purple pixels on a rendered plan, with or without the pin that is the only purple thing on
     * it.
     *
     * Purple for [CrossSectionOnThePlanTest]'s reason: the plan already draws red legs, black
     * sketch lines and grey grid, and a count of a colour the picture uses elsewhere is a count
     * that cannot fail for the right reason.
     *
     * The pin sits on the origin station rather than out on its own, so that adding it does not
     * widen the bounds the view fits itself to. Anywhere else and the two renders would differ by
     * the whole survey having shifted, which is a difference but not the one being measured.
     */
    private fun inkOnThePlan(withPin: Boolean, showPhotoPins: Boolean = true): Long {
        val survey = passage()
        if (withPin) {
            survey
                .getSketch(Projection2D.PLAN)
                .addPhotoDetail(
                    Coord2D.ORIGIN,
                    photoId = PHOTO_ID,
                    size = PIN_SIZE_M,
                    angle = 0f,
                    colour = Colour.PURPLE,
                )
        }
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options =
                        DisplayOptions(
                            showGrid = false,
                            showStationLabels = false,
                            showPhotoPins = showPhotoPins,
                        ),
                    editor = editor,
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                )
            }
        val image = try { scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        val plan = ImageIO.read(ByteArrayInputStream(png.bytes))

        var found = 0L
        for (y in 0 until plan.height) {
            for (x in 0 until plan.width) {
                if (plan.getRGB(x, y) and 0xFFFFFF == Colour.PURPLE.baseValue) found++
            }
        }
        return found
    }

    /**
     * The pin is actually drawn.
     *
     * Rendered rather than reasoned about, because the question is what is on the screen: a pin
     * assembled correctly, hit-tested correctly and never painted would pass every other check in
     * this class while leaving the surveyor with a drawing that says nothing about where they took
     * their photographs.
     */
    @Test
    fun aPinIsDrawnOnThePlan() {
        val withPin = inkOnThePlan(withPin = true)
        val without = inkOnThePlan(withPin = false)

        assertEquals(0L, without, "nothing on a plan is this colour until a pin is put on it")
        assertTrue(withPin > 20L, "the pin should be drawn on the plan ($withPin pixels of it)")
    }

    // -----------------------------------------------------------------------------------------
    // The toolbar the camera was added to
    // -----------------------------------------------------------------------------------------

    /**
     * Both rows of the toolbar are ten equal cells, and the camera is the last of them.
     *
     * This is the invariant three of the browser checks encode and none of them can verify: they
     * work out where a toolbar button is by dividing the bar's width by ten and counting along, so
     * a silent return to the app's original nine columns would send the script that means to zoom
     * out into the camera instead — and it would not fail, it would take a photograph and then go
     * on checking whatever came up next.
     *
     * Measured off the real laid-out bounds rather than counted off the source, since what the
     * scripts divide is the width on the screen.
     */
    @Test
    fun bothToolbarRowsAreTenEqualCellsWithTheCameraLast() = runComposeUiTest {
        setContent { App(survey = ExampleSurvey.create()) }

        val move = onNodeWithContentDescription(Strings.toolbarMove).getUnclippedBoundsInRoot()
        val zoomOut = onNodeWithContentDescription(Strings.toolbarZoomOut).getUnclippedBoundsInRoot()
        val zoomIn = onNodeWithContentDescription(Strings.toolbarZoomIn).getUnclippedBoundsInRoot()
        val camera = onNodeWithTag("camera-tool").getUnclippedBoundsInRoot()

        val cell = camera.width.value
        val bar = camera.right.value - move.left.value
        val columns = bar / cell

        assertNear(
            10f,
            columns,
            "the second row measured $columns cells of ${cell}dp across ${bar}dp. Three browser " +
                "checks find a toolbar button at width/10 and count along, so a row that is not " +
                "ten wide sends the one that means to zoom out into the camera",
            0.05f,
        )
        assertNear(zoomOut.width.value, cell, "the camera is not the same width as its neighbours")
        assertNear(
            zoomOut.right.value,
            camera.left.value,
            "the camera should be the cell straight after zoom out, and last in the row",
            0.5f,
        )

        // Row one: eight colour swatches, zoom in, and the Spacer that keeps the tenth cell empty
        // so the two rows line up under one another. Zoom in is therefore the ninth cell of ten,
        // sitting directly above zoom out with nothing above the camera.
        assertNear(
            8f,
            (zoomIn.left.value - move.left.value) / cell,
            "eight colour swatches should come before zoom in on the first row",
            0.05f,
        )
        assertNear(zoomOut.left.value, zoomIn.left.value, "zoom in should sit above zoom out", 0.5f)
        assertTrue(
            zoomIn.right.value < camera.left.value + 0.5f,
            "nothing on the first row should reach the camera's column: it is a Spacer",
        )
        assertTrue(
            zoomIn.top.value < camera.top.value,
            "the camera belongs on the second row, under the colours rather than beside them",
        )
    }

    /**
     * The camera button is there, is drawn spent on a machine with no camera, and is still
     * pressable.
     *
     * Spent because a button that looks live and opens nothing is how "the photograph button does
     * nothing" becomes a bug report; pressable because the tap is what fetches the reason, and the
     * desktop's reason — a webcam points at the desk — is worth hearing. This runs on the JVM,
     * where whyNoCamera always reports one, so the dimmed state is the one under test here.
     */
    @Test
    fun theCameraButtonIsDrawnSpentOnAMachineWithNoCameraAndStillPresses() = runComposeUiTest {
        assertTrue(whyNoCamera().isNotBlank(), "the desktop should say why it has no camera")

        setContent { App(survey = ExampleSurvey.create()) }

        // Drawn spent, asked of the pixels. The corner of the button is the bar's own panel colour
        // and the darkest pixel on it is the camera glyph, so the two together say both halves at
        // once: there is a camera drawn, and it is not drawn at full strength.
        val shot = onNodeWithTag("camera-tool").captureToImage().toPixelMap()
        val panel = shot[0, 0].luminance()
        var darkest = Float.MAX_VALUE
        for (y in 0 until shot.height) {
            for (x in 0 until shot.width) {
                darkest = minOf(darkest, shot[x, y].luminance())
            }
        }
        assertTrue(
            darkest < panel * SPENT_GLYPH_FRACTION,
            "nothing darker than the panel is drawn on the camera button at all ($darkest " +
                "against a panel of $panel)",
        )
        assertTrue(
            darkest > FULL_STRENGTH_INK,
            "the camera is drawn at full strength ($darkest), so nothing on the button says the " +
                "machine it is on has no camera to open",
        )

        // Still pressable, and the press is what fetches the reason. A dead button is how "the
        // photograph button does nothing" becomes a bug report, so what a surveyor gets for
        // pressing it is the sentence this platform wrote about its own missing camera.
        onNodeWithTag("camera-tool").performClick()
        onNodeWithText(whyNoCamera()).assertExists()

        // And it survives having answered: pressed again, it is still there to press.
        onNodeWithTag("camera-tool").performClick()
        onNodeWithTag("camera-tool").assertExists()
    }

    // -----------------------------------------------------------------------------------------
    // The viewer behind a pin
    // -----------------------------------------------------------------------------------------

    private class Viewing {
        var removals = 0
        var dismissals = 0
    }

    private fun viewerFor(
        store: FileStore,
        viewing: Viewing,
        caption: String = "",
    ): @Composable () -> Unit = {
        PhotoViewer(
            detail =
                PhotoDetail(
                    position = Coord2D.ORIGIN,
                    photoId = PHOTO_ID,
                    size = PIN_SIZE_M,
                    angle = 0f,
                    caption = caption,
                    colour = Colour.BLACK,
                ),
            store = store,
            path = SURVEY_PATH,
            surveyName = SURVEY_NAME,
            onRemove = { viewing.removals++ },
            onDismiss = { viewing.dismissals++ },
        )
    }

    /** A store holding [bytes] as this survey's one photograph, or holding nothing at all. */
    private fun storeHolding(bytes: ByteArray?): FileStore =
        InMemoryFileStore().also { store ->
            if (bytes != null) {
                PhotoStore.save(store, SURVEY_PATH, SURVEY_NAME, PHOTO_ID, bytes)
            }
        }

    /** A real, tiny, decodable JPEG, so the two failure messages have something to be unlike. */
    private fun aJpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = AwtColour(0x40, 0x80, 0xC0)
        graphics.fillRect(0, 0, 8, 8)
        graphics.dispose()
        val bytes = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "jpg", bytes), "this JVM cannot write a JPEG")
        return bytes.toByteArray()
    }

    /**
     * A pin whose picture never arrived says so.
     *
     * A survey handed over as its JSON alone keeps every pin and none of the pictures, which is a
     * thing cavers do every trip, so this is ordinary rather than exceptional. An empty box would
     * leave the surveyor wondering whether the app had broken.
     */
    @Test
    fun aPinWithNoPictureBehindItSaysTheFolderHasNone() = runComposeUiTest {
        val viewing = Viewing()
        setContent(viewerFor(storeHolding(null), viewing))

        onNodeWithText(Strings.photoMissing).assertExists()
        onNodeWithTag("photo-image").assertDoesNotExist()
    }

    /**
     * A picture that will not decode says something different.
     *
     * Different because the answer is different: absent means ask whoever sent the survey for the
     * pictures, damaged means ask them for this one again. Asserted apart from the missing case so
     * that a viewer which quietly collapsed the two — or which threw rather than saying either —
     * fails here rather than underground.
     */
    @Test
    fun aPictureThatWillNotDecodeSaysItIsDamagedRatherThanMissing() = runComposeUiTest {
        val viewing = Viewing()
        setContent(viewerFor(storeHolding("this is not a photograph".encodeToByteArray()), viewing))

        onNodeWithText(Strings.photoUnreadable).assertExists()
        onNodeWithText(Strings.photoMissing).assertDoesNotExist()
        onNodeWithTag("photo-image").assertDoesNotExist()
    }

    /** And a picture that does decode is shown, with neither excuse in front of it. */
    @Test
    fun aPictureThatDecodesIsShownWithNeitherExcuse() = runComposeUiTest {
        val viewing = Viewing()
        setContent(viewerFor(storeHolding(aJpeg()), viewing))

        onNodeWithTag("photo-image").assertExists()
        onNodeWithTag("photo-message").assertDoesNotExist()
    }

    /**
     * A caption written anywhere else travels with the pin and is shown under the picture.
     *
     * Nothing in this port writes one — the camera flow takes the empty default — so it would be
     * easy to drop and nobody here would notice. A survey that arrives from another tool can carry
     * one, and a caption silently thrown away is worse than a pin that never had it.
     */
    @Test
    fun aCaptionIsShownUnderThePicture() = runComposeUiTest {
        val viewing = Viewing()
        setContent(viewerFor(storeHolding(aJpeg()), viewing, caption = "Sump pool, looking in"))

        onNodeWithTag("photo-caption").assertExists()
        onNodeWithText("Sump pool, looking in").assertExists()
    }

    /**
     * The two buttons do what they say.
     *
     * They are easy to swap and the consequence is not symmetrical: a close that removed the pin
     * would take a photograph off the drawing every time somebody glanced at one.
     */
    @Test
    fun removeTakesThePinOffAndCloseOnlyCloses() = runComposeUiTest {
        val viewing = Viewing()
        setContent(viewerFor(storeHolding(aJpeg()), viewing))

        onNodeWithTag("photo-remove").performClick()
        assertEquals(1, viewing.removals, "the remove button did not ask for the pin to go")
        assertEquals(0, viewing.dismissals, "removing is not dismissing")

        onNodeWithTag("photo-close").performClick()
        assertEquals(1, viewing.dismissals, "the close button did not close the viewer")
        assertEquals(1, viewing.removals, "closing took the pin off the drawing")
    }

    private companion object {
        /** Where the finger goes down, well clear of the corners and of the canvas edges. */
        val FINGER_DOWN_AT = Offset(220f, 380f)

        /**
         * Where each drag ends, and the bearing it should produce: screen y grows downwards and
         * compass bearings run clockwise from north, so a drag up the screen is 0 and one to the
         * right is 90. These are [SymbolStampTest]'s own numbers.
         */
        val DRAGS =
            listOf(
                Offset(420f, 380f) to 90f,
                Offset(220f, 560f) to 180f,
                Offset(370f, 230f) to 45f,
            )

        /**
         * How finely a drag is sampled.
         *
         * Finely, because a real finger is sampled every frame: the drag opens at the first move
         * that has gone past the touch slop, so a drag reported in four large jumps would open
         * fifty pixels along and put the pin further from the landing than a phone ever would.
         */
        const val DRAG_STEPS = 20

        /**
         * How far from the landing the pin may be and still count as put there.
         *
         * Compose's touch slop is eighteen pixels at this density, so the drag opens a little way
         * along; twice that is comfortably inside it and nowhere near the far end of any drag here.
         */
        const val TOUCH_SLOP_ALLOWANCE_PX = 40f

        /** Where the pin under test sits, in survey metres, and how big it is drawn. */
        val PIN_AT = Coord2D(3f, -3f)
        const val PIN_SIZE_M = 1.2f

        /** Far enough from the pin to be clear paper, and still on a 600-pixel canvas. */
        const val CLEAR_PAPER_PX = 200f

        /**
         * How much darker than the panel the spent glyph must still be to count as drawn, and how
         * dark it would have to be to count as drawn at full strength.
         *
         * Fractions of the panel's own brightness rather than fixed values, so that these say the
         * same thing whichever way round the theme is: the panel is much darker at night, and so
         * is everything on it.
         */
        const val SPENT_GLYPH_FRACTION = 0.6f
        const val FULL_STRENGTH_INK = 0.02f

        const val SURVEY_NAME = "Swildons"
        const val PHOTO_ID = "1"
        val SURVEY_PATH = listOf("surveys", SURVEY_NAME)
    }
}
