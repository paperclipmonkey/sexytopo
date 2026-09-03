package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The rendering-side half of the viewport: converting between Compose's [Offset] and the shared
 * model's [Coord2D]. Everything else — the transform, gestures, zoom limits — lives in
 * [SketchViewport] in the shared module, ported from the Android app's `GraphView`.
 */

fun Coord2D.toOffset(): Offset = Offset(x, y)

fun Offset.toCoord2D(): Coord2D = Coord2D(x, y)

fun SketchViewport.toScreen(coord: Coord2D): Offset = toView(coord).toOffset()

fun SketchViewport.toSurvey(point: Offset): Coord2D = toSurvey(point.toCoord2D())

/** Zoom and centre so the whole of [bounds] is visible with [padding] pixels to spare. */
fun SketchViewport.fitTo(bounds: Bounds, width: Float, height: Float, padding: Float = 48f) {
    val fit =
        min((width - padding * 2) / bounds.width, (height - padding * 2) / bounds.height)

    // A survey with no extent yet — the single station a live survey starts with — opens at the
    // default zoom instead of fitting to a near-zero bounds and zooming in absurdly far.
    val nothingToFit = bounds.width <= DEGENERATE_EXTENT && bounds.height <= DEGENERATE_EXTENT
    val zoom =
        if (!nothingToFit && fit.isFinite() && fit > 0f) {
            fit
        } else {
            SketchViewport.DEFAULT_PIXELS_PER_METRE
        }

    // The shared viewport's zoom bounds are exclusive and it refuses rather than clamps.
    setZoom(
        zoom.coerceIn(SketchViewport.MIN_ZOOM * 1.01f, SketchViewport.MAX_ZOOM * 0.99f),
        Coord2D.ORIGIN,
    )
    centreOn(Coord2D(bounds.centreX, bounds.centreY), width, height)
}

/** Below this, in metres, a survey counts as having no extent at all. */
private const val DEGENERATE_EXTENT = 0.01f

/** The extent of everything drawn, in survey space. Used only to choose the opening zoom. */
class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = max(maxX - minX, 0.001f)
    val height: Float get() = max(maxY - minY, 0.001f)
    val centreX: Float get() = (minX + maxX) / 2
    val centreY: Float get() = (minY + maxY) / 2

    companion object {
        fun of(points: List<Coord2D>): Bounds {
            if (points.isEmpty()) return Bounds(-1f, -1f, 1f, 1f)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in points) {
                minX = min(minX, p.x)
                maxX = max(maxX, p.x)
                minY = min(minY, p.y)
                maxY = max(maxY, p.y)
            }
            return Bounds(minX, minY, maxX, maxY)
        }
    }
}

/**
 * Tracks whether the canvas still gets to choose its own viewport. A plain holder rather than
 * Compose state, since the fit happens *inside* the draw. Stops re-fitting the moment the
 * surveyor pans or zooms.
 */
class ViewportFit {
    var userHasTakenControl: Boolean = false

    private var fittedTo: Bounds? = null

    fun shouldFitTo(bounds: Bounds): Boolean {
        if (userHasTakenControl) return false
        val last = fittedTo ?: return true
        return !last.matches(bounds)
    }

    fun noteFitted(bounds: Bounds) {
        fittedTo = bounds
    }

    /** Forget what was last fitted, so the next draw fits again. */
    fun forget() {
        fittedTo = null
    }
}

/** Compared by value: a fresh [Bounds] is built on every scene rebuild. */
private fun Bounds.matches(other: Bounds): Boolean =
    abs(minX - other.minX) < 0.001f &&
        abs(minY - other.minY) < 0.001f &&
        abs(maxX - other.maxX) < 0.001f &&
        abs(maxY - other.maxY) < 0.001f
