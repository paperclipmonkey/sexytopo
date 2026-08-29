package org.hwyl.sexytopo.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchViewport

/**
 * The view onto one sketch, shared between the canvas that draws it and the toolbar that changes it.
 *
 * The viewport used to live inside the canvas composable, which was fine while the only way to move
 * it was to drag it. SexyTopo's toolbar has zoom-in and zoom-out buttons, so it has to be reachable
 * from outside — and hoisting it is also what lets the "centre the view" action in the app's
 * drawing menu work at all.
 *
 * [revision] exists because [SketchViewport] is a plain object with no idea Compose exists.
 * Anything that moves the view bumps it, and the canvas reads it, so the draw happens.
 */
class CanvasController {

    val viewport = SketchViewport()

    val fit = ViewportFit()

    var revision by mutableIntStateOf(0)
        private set

    /**
     * The canvas size in pixels, recorded during the draw.
     *
     * The zoom buttons need a point to zoom about — the middle of the view, since there is no
     * finger to zoom around — and only the draw knows how big the view is.
     */
    var viewWidth: Float = 0f
        private set

    var viewHeight: Float = 0f
        private set

    fun noteViewSize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
    }

    fun invalidate() {
        revision++
    }

    /** A drag or a pinch: from here on the viewport belongs to the surveyor, not to the app. */
    fun transformBy(centroid: Coord2D, pan: Coord2D, zoom: Float) {
        viewport.adjustZoomBy(zoom, centroid)
        viewport.panBy(pan)
        fit.userHasTakenControl = true
        invalidate()
    }

    fun zoomIn() = zoomBy(SketchViewport.ZOOM_IN_INCREMENT)

    fun zoomOut() = zoomBy(SketchViewport.ZOOM_OUT_INCREMENT)

    /**
     * The toolbar's zoom step, about the centre of the view.
     *
     * The increments are the Android app's own — 1.1 and 0.9 from `GraphActivity` — so a tap zooms
     * by the amount a surveyor's hand is used to.
     */
    private fun zoomBy(factor: Float) {
        val centre = Coord2D(viewWidth / 2f, viewHeight / 2f)
        if (viewport.adjustZoomBy(factor, centre)) {
            fit.userHasTakenControl = true
            invalidate()
        }
    }

    /** Puts the view back where the app would have put it. The app's "centre view" menu action. */
    fun refit() {
        fit.userHasTakenControl = false
        fit.forget()
        invalidate()
    }
}
