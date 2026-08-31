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
import kotlin.test.assertTrue

/**
 * Finding 28, made into a test: is every drawn size on the canvas a *dp*?
 *
 * `DrawScope` measures in physical pixels. A bare `2.5f` passed to `drawLine` is two and a half
 * device pixels whatever the screen, so on a phone at three of them to the dp the centreline comes
 * out a hairline, the stations come out pinheads, and the whole cave is a third of the size it
 * should be. Every drawn size in this port was one of those bare numbers, and what hid it is that
 * the station *labels* were right — text is in `sp`, which does scale — and the touch tolerances
 * were right too, having been converted properly. It was found by reading and fixed by reading,
 * because the browser CI runs at one device pixel to the dp and so cannot see it.
 *
 * But `ImageComposeScene` takes a `Density`, and a phone is only a number. Render the same survey
 * twice — once at 1x, once at three times the size *and* three times the density — and the second
 * is what a 3x phone would show. If the sizes are dp, the two pictures are the same picture at
 * different resolutions and the *proportion* of each that is ink is the same. If they are raw
 * pixels, everything in the second is drawn a third as thick and the proportion collapses.
 *
 * That is the entire bug, and it needs no phone.
 */
class DrawingDensityTest {

    private val width = 360
    private val height = 640

    /** A few legs and splays: enough centreline, stations and labels to have ink to measure. */
    private fun survey(): Survey {
        val survey = Survey("Density")
        SurveyBuilder.updateWithNewStation(survey, Leg(8f, 20f, 0f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(2f, 110f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(6f, 80f, -5f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(2.5f, 170f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(7f, 140f, 5f))
        return survey
    }

    /**
     * What fraction of the drawn plan is *centreline* — the app's red legs and salmon splays.
     *
     * A fraction rather than a count, because the point is to compare two images of different
     * sizes: nine times the pixels at 3x means nine times the ink if — and only if — everything
     * was drawn three times as thick.
     *
     * The centreline rather than all the ink, and that distinction is the whole difficulty. Most
     * of what is on this canvas is *text* — station names, the scale bar's label, the compass — and
     * text is in `sp`, which scaled correctly even when finding 28 was live. Counting every dark
     * pixel therefore measures mostly the half that was never broken: with the bug reintroduced
     * the total-ink ratio only fell from 0.98 to 0.77, which a threshold loose enough to tolerate
     * antialiasing would wave through. It did, on the first version of this test.
     *
     * The legs and splays are the things drawn from a dp, and they are the only red on the page.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun inkFraction(scale: Int): Double {
        val survey = survey()
        val projection = Projection2D.PLAN
        val scene =
            ImageComposeScene(
                width = width * scale,
                height = height * scale,
                density = Density(scale.toFloat()),
            ) {
                SurveyCanvas(
                    survey = survey,
                    projection = projection,
                    // The grid would dominate the count and is drawn from its own dp constant
                    // anyway; this is about the centreline, the stations and the legend.
                    options = DisplayOptions(showGrid = false),
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

        var centreline = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val rgb = bitmap.getRGB(x, y)
                val red = (rgb shr 16) and 0xff
                val green = (rgb shr 8) and 0xff
                val blue = rgb and 0xff
                // Red legs and salmon splays: strongly red, and not grey. The station dots are a
                // dark red that clears this too.
                if (red > 100 && red - green > 60 && red - blue > 60) centreline++
            }
        }
        return centreline.toDouble() / (bitmap.width.toDouble() * bitmap.height)
    }

    @Test
    fun theCaveIsTheSameSizeOnAPhoneAsOnADesktop() {
        val atOne = inkFraction(1)
        val atThree = inkFraction(3)

        assertTrue(
            atOne > 0.0005,
            "no centreline was drawn at 1x, so this test proves nothing ($atOne)",
        )

        // The same picture at three times the resolution: near enough the same fraction of it is
        // centreline. Measured both ways before these thresholds were chosen: 1.11 as the code
        // stands, 0.44 with finding 28 put back — 0.0049 of the picture at 1x against 0.0022 at
        // 3x, because the cave is three times longer across the page and no thicker.
        //
        // It is 1.11 rather than 1.00 because of antialiasing, and in the honest direction: a
        // 2.5px line at 1x spends much of its width in half-covered edge pixels that are too pale
        // to count as red, while the same line at 7.5px is mostly solid core. So the fraction
        // *rises* slightly with density. Hence a window rather than an equality, and hence the
        // upper bound at 1.3 — far enough above 1.11 for the fringe, far below the 3.0 that a
        // size scaled twice would give.
        val ratio = atThree / atOne
        assertTrue(
            ratio > 0.8,
            "the centreline thins on a dense screen: ${"%.4f".format(atOne)} of the picture at 1x " +
                "and ${"%.4f".format(atThree)} at 3x, a ratio of ${"%.2f".format(ratio)}. That is " +
                "finding 28 again — a drawn size is a raw pixel rather than a dp, so it does not " +
                "grow with the screen and the whole cave comes out small and faint on a phone.",
        )
        assertTrue(
            ratio < 1.3,
            "the centreline thickened on a dense screen (ratio ${"%.2f".format(ratio)}), which " +
                "means a size is being scaled twice",
        )
    }
}
