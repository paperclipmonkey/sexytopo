package org.hwyl.sexytopo.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchViewport

/**
 * The view onto one sketch, shared between the canvas that draws it and the toolbar that changes
 * it. [revision] exists because [SketchViewport] is a plain object with no idea Compose exists.
 */
class CanvasController {

    val viewport = SketchViewport()

    val fit = ViewportFit()

    var revision by mutableIntStateOf(0)
        private set

    /**
     * True while a finger or pencil is on the paper. Set by the canvas, which is the only thing
     * that can know; read by everything that would move the view out from under it.
     */
    var isTouched: Boolean = false
        private set

    /** A [centreOn] asked for mid-touch, held back until the touch lifts. Only the latest is kept. */
    private var deferredCentre: Coord2D? = null

    fun touchBegan() {
        isTouched = true
    }

    /**
     * The touch has lifted, or the gesture tracking it was torn down: either way the view is free
     * to move again, and anything held back while it could not is applied now.
     */
    fun touchEnded() {
        isTouched = false
        val centre = deferredCentre
        deferredCentre = null
        if (centre != null) centreOn(centre) else invalidate()
    }

    /** Recorded during the draw: the zoom buttons need a point to zoom about. */
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

    /** The increments are the Android app's own — 1.1 and 0.9 from `GraphActivity`. */
    private fun zoomBy(factor: Float) {
        val centre = Coord2D(viewWidth / 2f, viewHeight / 2f)
        if (viewport.adjustZoomBy(factor, centre)) {
            fit.userHasTakenControl = true
            invalidate()
        }
    }

    /**
     * Put [surveyPoint] in the middle of the view. The zoom is left alone, deliberately, and the
     * viewport is marked as the surveyor's so the automatic re-fit stops here.
     *
     * Not while a touch is down. The station that arrives mid-stroke is the one this is most
     * often asked to follow, and re-centring then moves the paper under a pen that has not moved:
     * the stroke jumps to wherever the same screen point now is. Held until the touch lifts.
     */
    fun centreOn(surveyPoint: Coord2D) {
        if (isTouched) {
            deferredCentre = surveyPoint
            return
        }
        if (viewWidth <= 0f || viewHeight <= 0f) return
        viewport.centreOn(surveyPoint, viewWidth, viewHeight)
        fit.userHasTakenControl = true
        invalidate()
    }

    /** Puts the view back where the app would have put it. */
    fun refit() {
        fit.userHasTakenControl = false
        fit.forget()
        invalidate()
    }
}
