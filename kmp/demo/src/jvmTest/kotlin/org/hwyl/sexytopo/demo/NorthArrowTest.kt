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
    private fun inkInTheLegendCorner(projection: Projection2D): Int {
        val survey = oneLegNorth()
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = projection,
                    options = DisplayOptions(showGrid = false),
                    editor = SketchEditor(survey.getSketch(projection)),
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        val image =
            try {
                scene.render()
            } finally {
                scene.close()
            }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        val bitmap = ImageIO.read(ByteArrayInputStream(png.bytes))

        // A box above the scale bar and its label, at the left-hand edge: where the arrow goes and
        // where nothing else on this survey reaches.
        var ink = 0
        for (y in bitmap.height - 115 until bitmap.height - 55) {
            for (x in 18 until 58) {
                val rgb = bitmap.getRGB(x, y)
                val darkest = minOf((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
                if (darkest < 100) ink++
            }
        }
        return ink
    }

    @Test
    fun thePlanIsDrawnWithNorthOnIt() {
        val ink = inkInTheLegendCorner(Projection2D.PLAN)

        assertTrue(ink > 20, "no north arrow was drawn above the scale bar ($ink dark pixels)")
    }

    @Test
    fun theExtendedElevationIsNot() {
        // `GraphView.drawCompass` returns immediately unless the projection is the plan, and so
        // does this: an unrolled section has no north, and an arrow on one would be a lie.
        assertEquals(
            0,
            inkInTheLegendCorner(Projection2D.EXTENDED_ELEVATION),
            "something was drawn where the plan's north arrow goes",
        )
    }
}
