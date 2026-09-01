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
import kotlin.math.abs
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
    private fun surveyWithADrawnSection(
        drawInside: Boolean,
        withSection: Boolean = true,
    ): Pair<Survey, SketchEditor> {
        val survey = Survey("Section")
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 0f, 0f))
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(3f, 270f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(3f, 90f, 0f))

        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        if (!withSection) return survey to editor
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
    private fun plan(
        drawInside: Boolean,
        options: DisplayOptions,
        withSection: Boolean = true,
    ): BufferedImage {
        val (survey, editor) = surveyWithADrawnSection(drawInside, withSection)
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

    private fun BufferedImage.count(rgb: Int): Long = count { it and 0xFFFFFF == rgb }

    private fun BufferedImage.count(matches: (Int) -> Boolean): Long {
        var found = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matches(getRGB(x, y))) found++
            }
        }
        return found
    }

    /**
     * Salmon-ish rather than exactly `0xFF8080`.
     *
     * A splay is one dp wide, and a one-pixel line that does not happen to land on a pixel centre
     * is drawn as two half-covered rows - so a plan full of splays can contain not one pixel of
     * the exact colour. Asked exactly, the check below reported zero salmon in a picture that
     * plainly has splays in it. What identifies the colour is its shape: red at full strength with
     * green and blue equal and about half.
     */
    private fun isSalmon(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        return r > 230 && g in 100..200 && b in 100..200 && abs(g - b) < 12
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
     * That a cross-section's splays are drawn as splays: the app's own splay colour, and gone when
     * the splays are turned off.
     *
     * `GraphView.drawCrossSection` draws the star through `drawLegs`, the same routine that draws
     * every other segment on the page, so a section's shots get `splayPaint`, the splay width, the
     * `SHOW_SPLAYS` toggle and the dashing that marks a shot as foreshortened. This port drew them
     * silver, at its own 1.2dp, always visible and never dashed - a reasonable substitute while a
     * section had no frame to mark it out, and redundant now that it has one.
     *
     * The three cases together are what make this a check rather than a coincidence: the star adds
     * salmon that a plan without a section does not have, turning the splays off takes all of it
     * away, and the frame stays either way, which is what says the missing pixels are the star and
     * not the section.
     */
    @Test
    fun aCrossSectionsSplaysAreDrawnAsSplays() {
        val frame = SexyTopoColours.crossSectionFrame.toArgb() and 0xFFFFFF
        val shown = DisplayOptions(showGrid = false, showStationLabels = false)
        val hidden = DisplayOptions(showGrid = false, showStationLabels = false, showSplays = false)

        val withStar = plan(false, shown).count(::isSalmon)
        val withoutSection = plan(false, shown, withSection = false).count(::isSalmon)
        val splaysOff = plan(false, hidden)

        assertTrue(
            withStar > withoutSection + 50,
            "the section's star should add splay-coloured ink to the plan ($withStar against " +
                "$withoutSection with no section at all)",
        )
        assertEquals(
            0L,
            splaysOff.count(::isSalmon),
            "turning the splays off should take the section's star with them",
        )
        assertTrue(
            splaysOff.count(frame) > 200L,
            "and should leave the frame, which the Java draws either way",
        )
    }

    /**
     * A survey running along [azimuth] for two legs, with or without a section at the middle
     * station. Used to isolate the mark drawn on the station itself.
     */
    private fun straightPassage(azimuth: Float, withSection: Boolean): Pair<Survey, SketchEditor> {
        val survey = Survey("Indicator")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, azimuth, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, azimuth, 0f))
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        if (withSection) {
            val station = survey.getStationByName("2")!!
            // Dropped exactly on its own station rather than beside it. Anywhere else and the
            // section's position widens `SurveyScene.bounds`, the opening zoom changes to take it
            // in, and the two renders then differ by the whole survey shifted a few pixels -
            // which is a difference, and not the one being measured. The first version of this
            // check reported the mark as 63 by 579 for that reason.
            val where = Projection2D.PLAN.project(survey).stationMap[station]!!
            editor.addCrossSection(CrossSectioner.section(survey, station), where)
        }
        return survey to editor
    }

    /**
     * The bounding box of everything the section adds to a plan drawn with the sketch turned off.
     *
     * With the sketch hidden there is no frame, no connector and no star - `GraphView` draws all
     * three from `drawSketch`, and the indicator from `drawStations`, which is why hiding the
     * sketch is what isolates it. So the pixels that differ between the two renders are the
     * indicator and nothing else, and the shape of the box they fall in is its direction.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun indicatorBox(
        azimuth: Float,
        options: DisplayOptions =
            DisplayOptions(showGrid = false, showStationLabels = false, showSketch = false),
    ): IntArray {

        fun render(withSection: Boolean): BufferedImage {
            val (survey, editor) = straightPassage(azimuth, withSection)
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

        val with = render(true)
        val without = render(false)
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until with.height) {
            for (x in 0 until with.width) {
                if (with.getRGB(x, y) != without.getRGB(x, y)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * That the mark on a sectioned station lies *across* the passage, which is the one thing it
     * has to say.
     *
     * `GraphView.drawCrossSectionIndicator` draws a line a metre long in the section's own plane,
     * with an arrowhead along the bearing, at every station carrying a section - and this port
     * drew nothing at all. A section is drawn wherever it was dragged to, so with no mark at the
     * station there is no way to see which stations have been sectioned short of tapping each one.
     *
     * The direction is where this goes wrong quietly, because the Java's arithmetic looks like a
     * mistake: it takes the cosine and sine of a *compass bearing* and uses them as x and y. That
     * is deliberate and it is the whole trick - a bearing of `a` is the screen direction
     * `(sin a, -cos a)`, so `(cos a, sin a)` is that turned a right angle, which is the plane the
     * section was cut in. Copy it as written and it is right; "fix" it and the mark lies along the
     * passage instead of across it, which is exactly backwards and looks plausible.
     *
     * So the check is the one a surveyor would make: point the passage north and the mark should
     * be wide and flat, point it east and the same mark should be tall and thin.
     */
    @Test
    fun aSectionedStationIsMarkedAcrossThePassage() {
        val northSouth = indicatorBox(0f)
        val eastWest = indicatorBox(90f)

        val nsWidth = northSouth[2] - northSouth[0]
        val nsHeight = northSouth[3] - northSouth[1]
        val ewWidth = eastWest[2] - eastWest[0]
        val ewHeight = eastWest[3] - eastWest[1]

        assertTrue(nsWidth > 0 && ewHeight > 0, "no mark was drawn on the sectioned station at all")
        assertTrue(
            nsWidth > nsHeight,
            "a passage running north should be marked across, east-west ($nsWidth by $nsHeight)",
        )
        assertTrue(
            ewHeight > ewWidth,
            "a passage running east should be marked across, north-south ($ewWidth by $ewHeight)",
        )
    }

    /**
     * And the mark survives both toggles that take the section itself off the page.
     *
     * The Android app draws it from `drawStations`, which reads the sketch directly; only
     * `drawCrossSections` sits behind `SHOW_X_SECTIONS` and only `drawSketch` behind `SHOW_SKETCH`.
     * It looks like an oversight and is worth keeping: with the sections cleared off to read the
     * passage walls, the stations still say which of them have been sectioned. Asserted rather than
     * assumed, because a port that "tidied" this would be quietly hiding the only mark there is.
     */
    @Test
    fun theMarkOutlivesTheTogglesThatHideTheSection() {
        val sketchHidden =
            indicatorBox(
                0f,
                DisplayOptions(showGrid = false, showStationLabels = false, showSketch = false),
            )
        val sectionsHidden =
            indicatorBox(
                0f,
                DisplayOptions(
                    showGrid = false,
                    showStationLabels = false,
                    showCrossSections = false,
                ),
            )

        assertTrue(
            sketchHidden[2] - sketchHidden[0] > 0,
            "the mark should survive the sketch being hidden",
        )
        assertTrue(
            sectionsHidden[2] - sectionsHidden[0] > 0,
            "the mark should survive the cross-sections being hidden",
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
