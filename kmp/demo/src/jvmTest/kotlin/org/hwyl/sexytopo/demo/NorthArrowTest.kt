package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The north arrow, checked by looking at the pixels.
 *
 * A plan with no north on it is a picture rather than a survey. The exported SVG has carried one
 * since the legend was ported and the drawing on screen had not, which is the sort of gap that
 * survives any number of unit tests: nothing was wrong, something was simply absent.
 *
 * Rendered through `ImageComposeScene`, the same headless Skia the demo PNGs and the speed test
 * use — so this asserts what is actually drawn rather than what the code says it draws.
 */
class NorthArrowTest {

    private val width = 420
    private val height = 600

    /** One leg due north, which in plan runs up the middle and nowhere near the legend. */
    private fun oneLegNorth(): Survey =
        Survey("T").also { SurveyBuilder.updateWithNewStation(it, Leg(10f, 0f, 0f)) }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        projection: Projection2D,
        options: DisplayOptions = DisplayOptions(showGrid = false),
        headingDegrees: Float? = null,
    ): BufferedImage {
        val survey = oneLegNorth()
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = projection,
                    options = options,
                    editor = SketchEditor(survey.getSketch(projection)),
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                    headingDegrees = headingDegrees,
                )
            }
        val image =
            try {
                scene.render()
            } finally {
                scene.close()
            }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        return ImageIO.read(ByteArrayInputStream(png.bytes))
    }

    private fun inkIn(bitmap: BufferedImage, xs: IntRange, ys: IntRange): Int {
        var ink = 0
        for (y in ys) {
            for (x in xs) {
                val rgb = bitmap.getRGB(x, y)
                val darkest = minOf((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
                if (darkest < 100) ink++
            }
        }
        return ink
    }

    private fun inkInTheLegendCorner(
        projection: Projection2D,
        options: DisplayOptions = DisplayOptions(showGrid = false),
    ): Int =
        inkIn(render(projection, options), 18 until 58, height - 115 until height - 55)

    /**
     * The arrow's own patch of screen, quartered about roughly where it turns.
     *
     * "Roughly" is on purpose. Every assertion below compares the same half of the same box
     * between two renders, so whatever else is drawn in there — the top of the scale bar, a corner
     * of the cave — contributes equally to both and cancels. Nothing here depends on the box being
     * centred on the arrow to the pixel, only on it holding the whole of it.
     */
    private val arrowBoxX = 5 until 65
    private val arrowBoxY = height - 100 until height - 30
    private val arrowMidX = 35
    private val arrowMidY = height - 65

    @Test
    fun thePlanIsDrawnWithNorthOnIt() {
        val ink = inkInTheLegendCorner(Projection2D.PLAN)

        assertTrue(ink > 20, "no north arrow was drawn above the scale bar ($ink dark pixels)")
    }

    @Test
    fun itCanBeTakenOffAgain() {
        // `buttonShowCompass`, which is on by default in the app and here.
        val ink =
            inkInTheLegendCorner(
                Projection2D.PLAN,
                DisplayOptions(showGrid = false, showCompass = false),
            )

        assertEquals(0, ink, "the north arrow was still drawn with the toggle off")
    }

    @Test
    fun theExtendedElevationIsNot() {
        // `GraphView.drawCompass` returns immediately unless the projection is the plan.
        assertEquals(
            0,
            inkInTheLegendCorner(Projection2D.EXTENDED_ELEVATION),
            "something was drawn where the plan's north arrow goes",
        )
    }

    /**
     * Turn to face east and north is on your left, so the arrow has to swing left with it.
     *
     * This is the whole point of the compass and the half of it the port was missing: it drew the
     * arrow and had nothing to turn it with, so on a phone it was a label saying which way up the
     * paper was rather than which way the caver was facing.
     */
    @Test
    fun theArrowSwingsRoundWithTheDevice() {
        val facingNorth = render(Projection2D.PLAN, headingDegrees = 0f)
        val facingSouth = render(Projection2D.PLAN, headingDegrees = 180f)
        val facingEast = render(Projection2D.PLAN, headingDegrees = 90f)
        val facingWest = render(Projection2D.PLAN, headingDegrees = 270f)

        val above = { image: BufferedImage -> inkIn(image, arrowBoxX, arrowBoxY.first until arrowMidY) }
        val below = { image: BufferedImage -> inkIn(image, arrowBoxX, arrowMidY until arrowBoxY.last + 1) }
        val left = { image: BufferedImage -> inkIn(image, arrowBoxX.first until arrowMidX, arrowBoxY) }
        val right = { image: BufferedImage -> inkIn(image, arrowMidX until arrowBoxX.last + 1, arrowBoxY) }

        assertTrue(
            above(facingNorth) > above(facingSouth),
            "facing north the arrow should point up the screen: ${above(facingNorth)} above " +
                "against ${above(facingSouth)} when facing south",
        )
        assertTrue(
            below(facingSouth) > below(facingNorth),
            "facing south it should point down instead: ${below(facingSouth)} below against " +
                "${below(facingNorth)} when facing north",
        )
        assertTrue(
            left(facingEast) > left(facingWest),
            "facing east, north is to the left: ${left(facingEast)} against ${left(facingWest)}",
        )
        assertTrue(
            right(facingWest) > right(facingEast),
            "facing west, north is to the right: ${right(facingWest)} against ${right(facingEast)}",
        )
    }

    /** A device with no compass gets the picture it always got: north up, as the paper has it. */
    @Test
    fun withNoCompassTheArrowIsDrawnPointingNorthUp() {
        val noCompass = render(Projection2D.PLAN, headingDegrees = null)
        val facingNorth = render(Projection2D.PLAN, headingDegrees = 0f)

        for (y in arrowBoxY) {
            for (x in arrowBoxX) {
                assertEquals(
                    facingNorth.getRGB(x, y),
                    noCompass.getRGB(x, y),
                    "the arrow differs at ($x, $y) from the one drawn for a heading of zero",
                )
            }
        }
    }
}
