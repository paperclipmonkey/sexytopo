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

    /**
     * The centreline is `0xFF0000` and a station is `0x8B0000`, so a pixel's own colour says which
     * of the two drew it. [redPixels] counts both; [stationPixels] counts only the darker one,
     * which is what lets a question about stations be asked without the legs in the answer.
     */
    private fun isRed(r: Int, g: Int, b: Int) = r > 100 && r - g > 60 && r - b > 60

    private fun isStation(r: Int, g: Int, b: Int) = r in 100..200 && g < 60 && b < 60

    /** How many pixels of the plan are the app's red centreline, at [style]. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun redPixels(style: SketchStyle): Long = pixels(style, ::isRed)

    /** How many are the darker red the stations themselves are drawn in. */
    private fun stationPixels(style: SketchStyle): Long = pixels(style, ::isStation)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun pixels(style: SketchStyle, matches: (Int, Int, Int) -> Boolean): Long {
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
                if (matches(r, g, b)) red++
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

    /**
     * And a bigger station is a bigger station, which is the one people notice first.
     *
     * Counted in the station's own colour rather than in all the red on the page, so the legs are
     * not a constant sitting in both numbers: three times the diameter should be about three times
     * the ink, and it is not worth asking the question through a fog.
     */
    @Test
    fun askingForABiggerStationDrawsOne() {
        val ordinary = stationPixels(SketchStyle.DEFAULT)
        val big = stationPixels(SketchStyle.DEFAULT.copy(stationDiameterDp = 30f))

        assertTrue(ordinary > 0, "no stations were drawn at all")
        assertTrue(big > ordinary * 2, "$big against $ordinary")
    }

    /**
     * That a station is drawn as a cross and not as a filled dot.
     *
     * `GraphView.drawStationCross` draws two lines through the point. This port drew a filled
     * circle instead - written down in `SketchStyle` as a divergence, with no reason given - and
     * nothing could tell, because every test that looked at stations asked only whether the ink
     * grew when the setting did. It did: a disc grows too. So this asks *how* it grows, which is
     * the one measurement the two shapes disagree about. Quadruple the diameter and a cross - two
     * arms, each four times as long, at a stroke width that has not moved - draws about four times
     * the ink. A disc draws about sixteen times it. There is no threshold between 4 and 16 that a
     * plausible-but-wrong shape could sneak through.
     */
    @Test
    fun aStationIsACrossRatherThanADot() {
        val small = stationPixels(SketchStyle.DEFAULT.copy(stationDiameterDp = 10f))
        val large = stationPixels(SketchStyle.DEFAULT.copy(stationDiameterDp = 40f))

        assertTrue(small > 0, "no stations were drawn at all")
        val growth = large.toDouble() / small
        assertTrue(
            growth < 8.0,
            "quadrupling the station diameter multiplied the station ink by $growth " +
                "($small to $large), which is area growth: the stations are being drawn as " +
                "filled dots, not as the cross GraphView.drawStationCross draws",
        )
        assertTrue(
            growth > 2.5,
            "quadrupling the station diameter multiplied the station ink by only $growth " +
                "($small to $large), so the arms are not growing with the setting",
        )
    }
}
