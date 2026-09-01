package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** The plan rendered as a bitmap, for a scene with (or without) a drawing inside the section. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun plan(drawInside: Boolean, options: DisplayOptions): BufferedImage {
        val (survey, editor) = surveyWithADrawnSection(drawInside)
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = options,
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

    private fun BufferedImage.count(rgb: Int): Long {
        var found = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getRGB(x, y) and 0xFFFFFF == rgb) found++
            }
        }
        return found
    }

    /** How many pixels of the rendered plan are the colour drawn inside the section. */
    private fun purplePixels(drawInside: Boolean): Long =
        plan(drawInside, DisplayOptions(showGrid = false, showStationLabels = false))
            .count(Colour.PURPLE.baseValue)

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

    /**
     * That a cross-section on the plan is drawn inside a frame, and that the legacy setting takes
     * it away.
     *
     * `GraphView.drawCrossSectionBorder` and `drawCrossSectionHandle` draw a rounded rectangle
     * round the section in the app bar's own green, with a drag bar along the top. This port drew
     * neither, which left a section as a star of lines floating on the drawing: nothing said how
     * far it extended, nothing said it could be moved, and nothing tied it to the station it was
     * taken at. Counted in the frame's own colour, which nothing else on a plan uses.
     *
     * The legacy case is the other half. `pref_legacy_cross_sections` is the Android app's way of
     * keeping the old drawing for anyone who preferred it, and a setting that changes nothing is
     * the defect this port has hit more than any other - so it is asserted, not assumed.
     */
    @Test
    fun aCrossSectionOnThePlanIsDrawnInsideAFrame() {
        val framed =
            plan(false, DisplayOptions(showGrid = false, showStationLabels = false))
                .count(SexyTopoColours.crossSectionFrame.toArgb() and 0xFFFFFF)
        val legacy =
            plan(
                    false,
                    DisplayOptions(
                        showGrid = false,
                        showStationLabels = false,
                        legacyCrossSections = true,
                    ),
                )
                .count(SexyTopoColours.crossSectionFrame.toArgb() and 0xFFFFFF)

        assertTrue(framed > 200L, "the section was drawn without a frame ($framed pixels of it)")
        assertEquals(
            0L,
            legacy,
            "legacy cross-sections should have no frame at all, and $legacy pixels of one were " +
                "drawn: the setting reaches the file and not the canvas",
        )
    }

    /**
     * The connector stops at the frame rather than running across whatever is drawn inside it.
     *
     * `GraphView.clipSegmentToRectBoundary`, which is worth its own check because it is the one
     * piece of the frame that is arithmetic rather than drawing, and because both of its edge
     * cases are silent: a station already inside the frame gets no connector at all, and one on a
     * line that never enters gets the unclipped end back.
     */
    @Test
    fun theConnectorIsClippedToTheFrame() {
        val frame = Rect(100f, 100f, 200f, 200f)

        assertEquals(
            null,
            clipSegmentToRectBoundary(Offset(150f, 150f), Offset(150f, 160f), frame),
            "a station inside the frame has no connector to draw",
        )

        val fromTheLeft = clipSegmentToRectBoundary(Offset(0f, 150f), Offset(150f, 150f), frame)
        assertEquals(Offset(100f, 150f), fromTheLeft, "should stop on the frame's left edge")

        val fromAbove = clipSegmentToRectBoundary(Offset(150f, 0f), Offset(150f, 150f), frame)
        assertEquals(Offset(150f, 100f), fromAbove, "should stop on the frame's top edge")
    }
}
