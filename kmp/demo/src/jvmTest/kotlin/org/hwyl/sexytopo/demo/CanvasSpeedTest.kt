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
 * the renderer, which is the part the port controls.
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

            // Timing twelve frames as one block and dividing was the original, and it let a
            // single scheduler preemption inflate the whole measurement; the test below compares
            // *differences* between two such numbers, so noise in either one moved its tolerance
            // rather than its subject, and it failed twice in a day on a busy machine while
            // passing on its own.
            val clock = TimeSource.Monotonic
            var best = Double.MAX_VALUE
            repeat(frames) {
                val start = clock.markNow()
                scene.render()
                val ms = start.elapsedNow().inWholeMicroseconds / 1000.0
                if (ms < best) best = ms
            }
            best
        } finally {
            scene.close()
        }
    }

    /**
     * A frame on a real-sized cave finishes, and finishes in a sane time.
     *
     * A loose ceiling on purpose. What it is guarding against is not slowness but the failure mode:
     * something in the draw path that is quadratic in the size of the survey, which
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
     * Mapping the points and building the path is cheap; what costs time is rasterising them,
     * and rasterising is what Skia already skips. So there is no cull here, and this test is what
     * says so: if a stroke ever becomes expensive to *prepare* rather than to draw, the second
     * number moves and this fails.
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
        // The denominator first: if the drawing does not cost meaningfully more with all of it on
        // screen than without, the fixture is not demonstrating anything and the comparison below
        // would pass or fail on noise. Say so rather than report either.
        val costOnScreen = drawnOut - plainOut
        assertTrue(
            costOnScreen > 1.0,
            "eight thousand strokes on screen cost only $costOnScreen ms a frame more than none, " +
                "so this fixture cannot show whether the off-screen case is cheaper",
        )
        val costOffScreen = drawnIn - plainIn
        assertTrue(
            costOffScreen < costOnScreen * 0.2,
            "with almost none of the drawing on screen the strokes still cost $costOffScreen ms a " +
                "frame, against $costOnScreen with all of them showing — " +
                "${(costOffScreen / costOnScreen * 100).toInt()}% rather than under 20%.\n" +
                "\n" +
                "Read this as a ratio before reading it as a regression. What it compares is " +
                "*preparing* a stroke against *rasterising* it, so it moves with the machine: a " +
                "box whose CPU is slow relative to its rasteriser reports a higher percentage " +
                "without anything having changed in the app. It has been seen at 30-50% on a " +
                "loaded shared container and under 20% on CI, with identical code — checked by " +
                "running an older commit side by side, which reported the same. So: if this fails " +
                "on a busy machine, re-run it on a quiet one before believing it. If it fails on " +
                "CI, something really has made stroke preparation expensive.",
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
