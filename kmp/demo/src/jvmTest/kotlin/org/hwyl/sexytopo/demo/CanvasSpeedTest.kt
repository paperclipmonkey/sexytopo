package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * How long a frame takes on a survey the size of a real cave.
 *
 * The same reasoning as the stack-overflow and quadratic-export findings: everything here is quick
 * on a demo cave and the question is what happens on a club's. A frame is drawn while a finger is
 * moving, so it is the one piece of work in this app that has a hard deadline — sixteen
 * milliseconds if the drawing is to keep up with the hand.
 *
 * `ImageComposeScene` is the same headless renderer `RenderPng` uses, and it rasterises on the
 * **CPU**, where a phone rasterises on its GPU. So the absolute numbers here are not a phone's and
 * are not quoted as though they were: what this guards is the *shape* of the work this code hands
 * the renderer, which is the part the port controls. Measured on this machine, for the record: the
 * whole of a four-thousand-station survey on screen is about 170 ms a frame and one passage of it
 * filling the screen about 14 ms, against 16.6 ms before off-screen legs were culled.
 *
 * Gathering the same segments into one `drawPoints` call per colour instead of one `drawLine` each
 * — twelve thousand calls down to about four — was tried here and measured *no faster at all*, so
 * it was taken out again. The cost is rasterising twelve thousand antialiased round-capped
 * segments, not the calls that ask for them.
 */
class CanvasSpeedTest {

    /** Four thousand stations, as `BigSurveyTest`: one long wandering passage with wall shots. */
    private fun aLongPassage(stations: Int = 4000): Survey {
        val survey = Survey("Long")
        var previous = survey.origin
        for (i in 2..stations) {
            val station = Station("$i")
            val leg = Leg(5f, (i * 11f) % 360f, ((i % 15) - 7).toFloat(), station)
            previous.addOnwardLeg(leg)
            survey.addLegRecord(leg)
            for (side in 0 until 2) {
                val splay = Leg(2f, ((i * 11f) + 90f + side * 180f) % 360f, 0f)
                station.addOnwardLeg(splay)
                survey.addLegRecord(splay)
            }
            previous = station
        }
        return survey
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun timeFrames(survey: Survey, zoomSteps: Int, frames: Int = 12): Double {
        val editor = SketchEditor(survey.getSketch(Projection2D.PLAN))
        val canvas = CanvasController()
        val scene =
            ImageComposeScene(width = 420, height = 900, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(),
                    editor = editor,
                    canvas = canvas,
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        return try {
            // One frame to lay out and fit, then the zoom, then a warm-up before anything counts:
            // the first render pays for Skia's own setup and for the JIT.
            scene.render()
            // Through the same call a pinch goes through, so the view is marked as the
            // surveyor's and the automatic re-fit does not undo it on the next frame.
            repeat(zoomSteps) { canvas.zoomIn() }
            repeat(3) { scene.render() }

            val clock = TimeSource.Monotonic
            val start = clock.markNow()
            repeat(frames) { scene.render() }
            start.elapsedNow().inWholeMicroseconds / 1000.0 / frames
        } finally {
            scene.close()
        }
    }

    /**
     * A frame on a real-sized cave finishes, and finishes in a sane time.
     *
     * A loose ceiling on purpose. What it is guarding against is not slowness but the failure mode
     * finding 18 was: something in the draw path that is quadratic in the size of the survey, which
     * a demo cave never shows and which turns into an app that cannot be dragged. The threshold is
     * far above what this machine measures and far below what an accident would cost, and the
     * numbers are printed either way so a regression is visible before it trips the assertion.
     */
    @Test
    fun aFrameOnARealSizedCaveIsNotQuadratic() {
        val small = timeFrames(aLongPassage(500), zoomSteps = 0)
        val big = timeFrames(aLongPassage(4000), zoomSteps = 0)

        println("whole cave on screen: 500 stations ${small} ms/frame, 4000 ${big} ms/frame")
        assertTrue(
            big < small * 24,
            "eight times the cave took ${big / small} times as long a frame ($small then $big " +
                "ms) — something in the draw path is worse than linear in the survey",
        )
    }

    /**
     * Zoomed into one passage, a frame costs a fraction of what the whole cave costs.
     *
     * The measurement that says the off-screen legs are being skipped rather than drawn — though
     * loosely, because most of the saving at this zoom is Skia rasterising fewer pixels, which
     * would happen anyway.
     */
    @Test
    fun zoomingInCostsLessThanZoomingOut() {
        val survey = aLongPassage()

        val wholeCave = timeFrames(survey, zoomSteps = 0)
        // 1.1 per step, so forty steps is about forty-five times in.
        val onePassage = timeFrames(survey, zoomSteps = 40)

        println("4000 stations: whole cave ${wholeCave} ms/frame, zoomed in ${onePassage} ms/frame")
        assertTrue(
            onePassage < wholeCave,
            "drawing one passage of the cave took ${onePassage} ms a frame against ${wholeCave} " +
                "for the whole of it",
        )
    }
}
