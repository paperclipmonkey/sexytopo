package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The passage outline drawn inside a cross-section, shown on the plan it belongs to.
 *
 * `GraphView.drawCrossSection` calls `drawCrossSectionSubSketch`, which scales the section's own
 * sketch by the plan's cross-section scale, translates it to where the section sits, and draws it
 * with the same routine as the main sketch. This port drew the splay star and a marker dot and
 * never read `CrossSectionDetail.sketch` at all.
 *
 * Which makes the feature's whole point invisible. A surveyor drops a section, taps it, draws the
 * shape of the passage inside it — and comes back to a plan showing the same star of splays it
 * showed before. The drawing is saved, exports correctly, and reopens in the editor; there is
 * simply nothing on the plan to say so, and the reasonable conclusion is that it did not save.
 *
 * Rendered rather than reasoned about, because the question is *what is on the screen*: a scene
 * assembled correctly and never drawn would pass any test written one layer up.
 */
class CrossSectionOnThePlanTest {

    private val width = 360
    private val height = 640

    /**
     * A short passage with a section at station 2, drawn inside in a colour nothing else uses.
     *
     * Purple, because the plan already draws red legs, black sketch lines, grey grid and the
     * app's own cross-section colour — and a check that counts a colour the picture uses
     * elsewhere is a check that cannot fail for the right reason.
     */
    private fun surveyWithADrawnSection(drawInside: Boolean): Pair<Survey, SketchEditor> {
        val survey = Survey("Section")
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 0f, 0f))
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(3f, 270f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(3f, 90f, 0f))

        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        val detail =
            editor.addCrossSection(CrossSectioner.section(survey, station), Coord2D(6f, -8f))
        if (drawInside) {
            // A box, in the section's own coordinates, which is what the editor commits.
            val inside = SketchEditor(detail.sketch)
            inside.startPath(Coord2D(-2f, -2f), Colour.PURPLE)
            inside.extendPath(Coord2D(2f, -2f))
            inside.extendPath(Coord2D(2f, 2f))
            inside.extendPath(Coord2D(-2f, 2f))
            inside.extendPath(Coord2D(-2f, -2f))
            inside.finishPath()
            detail.sketch = inside.sketch
        }
        return survey to editor
    }

    /** How many pixels of the rendered plan are the colour drawn inside the section. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun purplePixels(drawInside: Boolean): Long {
        val (survey, editor) = surveyWithADrawnSection(drawInside)
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(showGrid = false, showStationLabels = false),
                    editor = editor,
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        val image = try { scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        val bitmap = ImageIO.read(ByteArrayInputStream(png.bytes))

        val wanted = Colour.PURPLE.baseValue
        var found = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getRGB(x, y) and 0xFFFFFF == wanted) found++
            }
        }
        return found
    }

    /**
     * The check itself: a section with a drawing inside it puts that drawing on the plan.
     *
     * Paired with the empty-section case, so a rendering that painted the whole canvas purple
     * would fail rather than pass twice.
     */
    @Test
    fun aPassageOutlineDrawnInsideASectionIsOnThePlan() {
        val withOutline = purplePixels(drawInside = true)
        val without = purplePixels(drawInside = false)

        assertTrue(
            without == 0L,
            "nothing on a plan is this colour until the section is drawn in ($without pixels)",
        )
        assertTrue(
            withOutline > 20L,
            "the outline drawn inside the section should be on the plan ($withOutline pixels)",
        )
    }
}
