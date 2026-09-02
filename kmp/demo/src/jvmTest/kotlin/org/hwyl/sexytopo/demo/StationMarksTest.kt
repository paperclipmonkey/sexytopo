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
import kotlin.test.assertTrue

/**
 * What is drawn beside a station, other than its name.
 *
 * `GraphView.drawStations` lays out a row to the right of every station - the name, then an icon
 * for each thing the station carries - and this port drew the name and stopped. Two marks were
 * missing: a **comment**, stored and exported and shown in the table but never drawn, which is the
 * half a surveyor actually looks at underground; and the **survey's name** in brackets after the
 * origin's, the only thing on the page saying which cave it is once two surveys are open.
 *
 * Both are checked by rendering and differencing, since a value that round-trips through the file
 * and never reaches the canvas passes every test written one layer up.
 */
class StationMarksTest {

    private val width = 360
    private val height = 640

    private fun survey(name: String, comment: String): Survey {
        val survey = Survey(name)
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        survey.getStationByName("2")!!.comment = comment
        return survey
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun plan(name: String, comment: String): BufferedImage {
        val survey = survey(name, comment)
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(showGrid = false),
                    editor = editor,
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        val image = try { scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        return ImageIO.read(ByteArrayInputStream(png.bytes))
    }

    private fun differingPixels(a: BufferedImage, b: BufferedImage): Long {
        var differing = 0L
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) differing++
            }
        }
        return differing
    }

    /**
     * A comment written at a station shows on the plan.
     *
     * Nothing about the survey's shape changes when a comment is added, so the opening zoom is
     * identical, which is what makes a whole-image difference a fair measurement here rather than
     * a picture shifted a pixel.
     */
    @Test
    fun aStationWithACommentIsMarked() {
        val plain = plan("Cave", "")
        val commented = plan("Cave", "sump, not passed")

        val differing = differingPixels(plain, commented)
        assertTrue(
            differing > 20,
            "a comment at a station should put a mark on the plan ($differing pixels differ)",
        )
    }

    /** And the origin says which survey it is the origin of, as `GraphView.drawStations` does. */
    @Test
    fun theOriginCarriesTheSurveysName() {
        val short = plan("A", "")
        val long = plan("Lost John's Cave", "")

        val differing = differingPixels(short, long)
        assertTrue(
            differing > 50,
            "the survey's name should be drawn after the origin's ($differing pixels differ)",
        )
    }
}
