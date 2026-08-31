package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
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

    /**
     * A drawing to match a cave that size: strokes of a dozen points each, spread over the same
     * ground the passage covers. Same shape as `BigSurveyTest`'s.
     */
    private fun Survey.drawOn(strokes: Int) {
        val sketch = getSketch(Projection2D.PLAN)
        for (i in 0 until strokes) {
            val x = (i % 100) * 2f
            val y = (i / 100) * 2f
            sketch.pathDetails.add(
                PathDetail(List(12) { Coord2D(x + it * 0.1f, y + (it % 3) * 0.1f) }, Colour.BLACK),
            )
        }
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
     * A drawing that is off the screen costs almost nothing, so it is not culled.
     *
     * The question the centreline cull does not answer. A cave surveyed over many trips carries
     * thousands of strokes, and each is mapped into screen coordinates and built into a `Path`
     * every frame whether or not any of it is showing — which looks exactly like the legs did
     * before they were culled.
     *
     * Measured, it is not the same at all. Eight thousand strokes cost **67 ms a frame** with all
     * of them on screen and **0.4 ms** with almost none of them — a third of one per cent of the
     * frame, inside the noise. Mapping the points and building the path is cheap; what cost the
     * time was rasterising them, and rasterising is what Skia already skips. So there is no cull
     * here, and this test is what says so: if a stroke ever becomes expensive to *prepare* rather
     * than to draw, the second number moves and this fails.
     *
     * The first number is worth its own note, because nothing here fixes it: a fully traced cave
     * with the whole of it on screen is 120 ms a frame in this renderer. That is rasterisation of
     * eight thousand visible strokes, so no amount of culling touches it — it would want drawing
     * less of the drawing, which changes what the surveyor sees and is a decision, not a fix.
     */
    @Test
    fun aDrawingThatIsOffTheScreenCostsAlmostNothing() {
        val plain = aLongPassage(1000)
        val drawn = aLongPassage(1000).also { it.drawOn(8000) }

        val plainOut = timeFrames(plain, zoomSteps = 0)
        val drawnOut = timeFrames(drawn, zoomSteps = 0)
        val plainIn = timeFrames(plain, zoomSteps = 40)
        val drawnIn = timeFrames(drawn, zoomSteps = 40)

        println(
            "1000 stations, 8000 strokes: zoomed out ${plainOut} without / ${drawnOut} with; " +
                "zoomed in ${plainIn} without / ${drawnIn} with",
        )
        assertTrue(
            drawnIn < plainIn + (drawnOut - plainOut) * 0.2,
            "with almost none of the drawing on screen it still cost ${drawnIn - plainIn} ms a " +
                "frame, against ${drawnOut - plainOut} with all of it — preparing the strokes has " +
                "become expensive enough to be worth culling, which it was not when this was written",
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
