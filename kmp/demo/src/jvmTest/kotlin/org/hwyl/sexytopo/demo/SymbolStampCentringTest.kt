package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A cave symbol is drawn where it was put, and turns on the spot.
 *
 * Neither was true. `Symbol.toPath` builds its artwork already centred on the origin — its own
 * documentation says so — and the canvas then centred it a second time with a
 * `translate(-VIEWPORT / 2, -VIEWPORT / 2)` of its own. Two centrings where one was wanted, so
 * every stamp in every survey was drawn half its own size up and to the left of the point it was
 * stamped at, and a directional one swung about a corner instead of turning on the spot.
 *
 * The second centring was added to fix the first version of the bug, back when `toPath` really did
 * return a box running from (0, 0) to (40, 40). `toPath` was later changed to centre the artwork
 * itself and the compensation was left behind, which is the ordinary way a fix outlives its cause.
 *
 * ## Why it went unnoticed, and why this test looks rather than reasons
 *
 * Nothing was measuring where the ink landed. `SymbolStampTest` checks what a stamp *records* —
 * name, position, angle, size — and every one of those was right the whole time: the model, the
 * eraser's `SymbolDetail.getDistanceFrom`, the SVG and Therion exports and the symbol strip all
 * agreed on the centre, and the canvas alone disagreed. So the visible symptom was that a symbol
 * could not be rubbed out where it appeared to be, and could be rubbed out from a patch of blank
 * paper half a stamp away.
 *
 * These render the canvas and difference the pixels, because that is the only layer the bug lived
 * in. The first asks where the ink is against where the viewport says the point is; the second
 * asks whether turning a symbol moves it by a whole stamp or by the couple of pixels its own
 * hand-drawn asymmetry accounts for.
 */
class SymbolStampCentringTest {

    private val width = 360
    private val height = 640

    /** A symbol big enough that half of it is many pixels, so the failure is unmistakable. */
    private val stampInMetres = 3f

    /** Well inside the survey's own extent, so adding it cannot change the opening zoom. */
    private val stampedAt = Coord2D(2f, 2f)

    private fun survey(): Survey =
        Survey("Cave").also {
            SurveyBuilder.updateWithNewStation(it, Leg(10f, 90f, 0f))
            SurveyBuilder.updateWithNewStation(it, Leg(10f, 180f, 0f))
        }

    /**
     * The plan, with an optional water-flow arrow stamped on it, and the viewport it was drawn
     * through.
     *
     * The controller is handed back because only it knows where a survey point ended up on the
     * screen, and the whole question here is whether the ink agrees with that.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun plan(angle: Float?, at: Coord2D = stampedAt): Pair<BufferedImage, CanvasController> {
        val survey = survey()
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        if (angle != null) {
            editor.addSymbol(
                position = at,
                symbolName = "water-flow",
                size = stampInMetres,
                angle = angle,
            )
        }
        val canvas = CanvasController()
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(showGrid = false),
                    editor = editor,
                    canvas = canvas,
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        val image = try { scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        return ImageIO.read(ByteArrayInputStream(png.bytes)) to canvas
    }

    /** Where the pixels differ between two renders, as a box, or null if none do. */
    private fun differenceBox(a: BufferedImage, b: BufferedImage): IntArray? {
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (left > right) null else intArrayOf(left, right, top, bottom)
    }

    /**
     * The difference between the two renders is the stamp and nothing else.
     *
     * Worth asserting rather than assuming. Adding a symbol widens `SurveyScene`'s bounds if it
     * falls outside the survey's, which would change the opening zoom and make every pixel differ
     * — and a box the size of the screen would then be measured as though it were a symbol. This
     * catches that, so the two tests below are measuring what they think they are.
     */
    private fun stampBox(a: BufferedImage, b: BufferedImage): IntArray {
        val box = differenceBox(a, b) ?: error("stamping a symbol changed nothing on the canvas")
        val boxWidth = box[1] - box[0]
        val boxHeight = box[3] - box[2]
        assertTrue(
            boxWidth < width / 2 && boxHeight < height / 2,
            "the difference between the two renders is ${boxWidth}x$boxHeight, which is too big " +
                "to be one stamp — the drawing has moved, so nothing below is measuring a symbol",
        )
        return box
    }

    private fun centreOf(box: IntArray): Pair<Float, Float> =
        (box[0] + box[1]) / 2f to (box[2] + box[3]) / 2f

    /**
     * A stamp is drawn round the point it was stamped at, not off to one side of it.
     *
     * The measurement everything else agreed on: `SymbolDetail.getDistanceFrom` measures from the
     * centre, so this is also the point the eraser reaches for. Before the fix the ink sat half a
     * stamp up and left of it — at three metres and this zoom, tens of pixels.
     */
    @Test
    fun aStampIsDrawnRoundThePointItWasStampedAt() {
        val (plain, _) = plan(angle = null)
        val (stamped, canvas) = plan(angle = 0f)

        val (inkX, inkY) = centreOf(stampBox(plain, stamped))
        val expected = canvas.viewport.toView(stampedAt)

        // Two pixels, which covers the stroke's own width falling unevenly across the box and the
        // half-pixel of an even-sized box. Nothing like the half-stamp the bug moved it by.
        assertTrue(
            abs(inkX - expected.x) <= 2f && abs(inkY - expected.y) <= 2f,
            "a symbol stamped at $stampedAt should be drawn round (${expected.x}, ${expected.y}) " +
                "and its ink is centred on ($inkX, $inkY)",
        )
    }

    /**
     * Turning a symbol turns it about itself, rather than swinging it round a corner.
     *
     * A separate symptom of the same fault, and worth its own check because it is the one a
     * surveyor sees happen: aiming a water-flow arrow used to walk it off the passage.
     *
     * Measured against the size of the stamp rather than against a pixel count, and that is the
     * careful part. A symbol turned through 180 degrees does *not* land its ink in exactly the same
     * box: the artwork is hand-drawn and its ink need not sit dead centre in its own 40-unit
     * square, so a point reflection about the centre moves the box by twice however far off centre
     * the ink is. That is a property of the drawing and not a bug — this one moves by three pixels.
     * What a wrong pivot does is different in kind: it moves the ink by a whole stamp. So the
     * question asked is which of the two this is, with the threshold at a quarter of a stamp —
     * comfortably above the artwork's own asymmetry and far below the 1.4 stamps the bug moved it.
     */
    @Test
    fun turningAStampTurnsItOnTheSpotRatherThanSwingingItRound() {
        val (plain, _) = plan(angle = null)
        val (upright, canvas) = plan(angle = 0f)
        val (turned, _) = plan(angle = 180f)

        val (uprightX, uprightY) = centreOf(stampBox(plain, upright))
        val (turnedX, turnedY) = centreOf(stampBox(plain, turned))

        val stampInPixels = stampInMetres * canvas.viewport.pixelsPerMetre
        val moved = hypot(uprightX - turnedX, uprightY - turnedY)

        assertTrue(
            moved < stampInPixels / 4,
            "turning a symbol through 180 degrees moved it ${moved}px, which is " +
                "${moved / stampInPixels} of the ${stampInPixels}px stamp: it is swinging about a " +
                "corner rather than turning on the spot",
        )
    }
}
