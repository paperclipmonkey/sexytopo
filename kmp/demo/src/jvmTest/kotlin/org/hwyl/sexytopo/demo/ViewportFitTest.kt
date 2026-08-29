package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the canvas is allowed to choose the view, and when it must stop.
 *
 * This is the seam an adversarial review found twice. A fit that happens once looks right for the
 * demo cave, which is complete before the first frame, and is wrong for the interaction the app
 * actually exists for: a live survey starts as a single station and grows a leg every few readings.
 */
class ViewportFitTest {

    private fun bounds(minX: Float, minY: Float, maxX: Float, maxY: Float) =
        Bounds(minX, minY, maxX, maxY)

    @Test
    fun theFirstFrameIsAlwaysFitted() {
        assertTrue(ViewportFit().shouldFitTo(bounds(0f, 0f, 10f, 10f)))
    }

    @Test
    fun anUnchangedSurveyIsNotRefitted() {
        val fit = ViewportFit()
        val extent = bounds(0f, 0f, 10f, 10f)
        fit.noteFitted(extent)

        assertFalse(fit.shouldFitTo(extent), "nothing has moved, so nothing should be re-framed")
        assertFalse(
            fit.shouldFitTo(bounds(0f, 0f, 10f, 10f)),
            "and a fresh Bounds object of the same extent is the same extent",
        )
    }

    @Test
    fun aGrowingSurveyIsRefitted() {
        val fit = ViewportFit()
        fit.noteFitted(bounds(0f, 0f, 10f, 10f))

        assertTrue(
            fit.shouldFitTo(bounds(0f, 0f, 25f, 10f)),
            "a new leg took the cave past the edge of the screen",
        )
    }

    @Test
    fun onceTheSurveyorHasMovedTheViewItIsTheirs() {
        val fit = ViewportFit()
        fit.noteFitted(bounds(0f, 0f, 10f, 10f))
        fit.userHasTakenControl = true

        assertFalse(
            fit.shouldFitTo(bounds(0f, 0f, 200f, 200f)),
            "re-framing the view under somebody's finger is worse than never framing it",
        )
    }

    /**
     * The reason the trigger is the centreline's extent and not the whole scene's: a stroke drawn
     * near the edge enlarges the scene, and re-framing on that would move the paper under the pen.
     */
    @Test
    fun aFittedViewportShowsTheWholeSurvey() {
        val viewport = SketchViewport()
        val extent = bounds(-10f, -5f, 10f, 5f)
        viewport.fitTo(extent, 800f, 600f)

        for (corner in
            listOf(
                Coord2D(extent.minX, extent.minY),
                Coord2D(extent.maxX, extent.minY),
                Coord2D(extent.minX, extent.maxY),
                Coord2D(extent.maxX, extent.maxY),
            )
        ) {
            val onScreen = viewport.toScreen(corner)
            assertTrue(
                onScreen.x in 0f..800f && onScreen.y in 0f..600f,
                "corner $corner landed at $onScreen, off the canvas",
            )
        }
    }

    @Test
    fun aSurveyOfOneStationOpensAtTheAppsDefaultZoom() {
        // Not fitted: the bounds floor of a millimetre would zoom until the scale bar read
        // centimetres, and the surveyor's first leg would shoot straight off the screen.
        val viewport = SketchViewport()
        viewport.fitTo(Bounds.of(listOf(Coord2D(3f, 3f))), 400f, 800f)
        assertTrue(
            viewport.pixelsPerMetre == SketchViewport.DEFAULT_PIXELS_PER_METRE,
            "expected the default zoom, was ${viewport.pixelsPerMetre}",
        )
    }
}
