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
import org.hwyl.sexytopo.shared.sketch.SketchStyle
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That asking for a heavier line actually draws one.
 *
 * `SketchStyleTest` covers the arithmetic and the file, which is the half that is easy to get
 * right and easy to test. The half that has gone wrong in this port before — twice, in findings 48
 * and 49 — is the *connection*: a value that round-trips perfectly and that nothing on the way to
 * the screen ever reads. A number in a file is not a thicker line.
 *
 * So this renders the same survey twice through the same headless Skia the demo PNGs use, once at
 * the app's own leg width and once at three times it, and counts the red. Nothing else about the
 * two pictures differs.
 */
class DrawingSizeTest {

    private val width = 360
    private val height = 640

    private fun survey(): Survey {
        val survey = Survey("Sizes")
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 20f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(6f, 80f, -5f))
        SurveyBuilder.updateWithNewStation(survey, Leg(7f, 140f, 5f))
        return survey
    }

    /** How many pixels of the plan are the app's red centreline, at [style]. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun redPixels(style: SketchStyle): Long {
        val survey = survey()
        val projection = Projection2D.PLAN
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = projection,
                    // No grid and no labels: the question is about the centreline, and the station
                    // names are drawn in the same red, so leaving them in would put a constant
                    // into both counts and blunt the comparison.
                    options =
                        DisplayOptions(showGrid = false, showStationLabels = false, style = style),
                    editor = SketchEditor(survey.getSketch(projection)),
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        val image = try { scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        val bitmap = ImageIO.read(ByteArrayInputStream(png.bytes))

        var red = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val rgb = bitmap.getRGB(x, y)
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                if (r > 100 && r - g > 60 && r - b > 60) red++
            }
        }
        return red
    }

    @Test
    fun askingForAHeavierCentrelineDrawsOne() {
        val ordinary = redPixels(SketchStyle.DEFAULT)
        val heavy = redPixels(SketchStyle.DEFAULT.copy(legWidthDp = 6f))

        assertTrue(ordinary > 500, "no centreline was drawn at all ($ordinary)")
        // Three times the width over the same length of cave. Not asserted as exactly three: the
        // station dots are in the count and do not grow with the legs, and a wider line spends
        // proportionally less of itself in the pale antialiased fringe.
        assertTrue(
            heavy > ordinary * 2,
            "asking for a leg width of 6dp against 2dp drew $heavy red pixels against $ordinary, " +
                "so the setting reaches the file and not the page",
        )
    }

    /** And a bigger station is a bigger station, which is the one people notice first. */
    @Test
    fun askingForABiggerStationDrawsOne() {
        val ordinary = redPixels(SketchStyle.DEFAULT.copy(legWidthDp = SketchStyle.SMALLEST))
        val big =
            redPixels(
                SketchStyle.DEFAULT.copy(
                    legWidthDp = SketchStyle.SMALLEST,
                    stationDiameterDp = 30f,
                ),
            )

        assertTrue(big > ordinary * 2, "$big against $ordinary")
    }
}
