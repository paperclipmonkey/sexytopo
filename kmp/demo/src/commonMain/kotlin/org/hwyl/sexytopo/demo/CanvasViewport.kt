package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The rendering-side half of the viewport: converting between Compose's [Offset] and the shared
 * model's [Coord2D], and working out what zoom shows the whole survey.
 *
 * Everything else — the survey-metres-to-pixels transform itself, the pan and pinch gestures, the
 * zoom limits — lives in [SketchViewport] in the shared module, ported from the Android app's
 * `GraphView`. This file only exists because Compose has its own geometry types and the shared
 * module must not know about them.
 *
 * The demo previously carried its own viewport with a fit-to-bounds transform. That was easier to
 * write and wrong in a way worth recording: it had no zoom limits and no concept of an absolute
 * scale, so "50 pixels per metre" — which is what a scale bar, a stamped symbol size and an eraser
 * radius all actually need — could not be expressed. The shared viewport is the Android app's model,
 * where the zoom *is* pixels per metre.
 */

fun Coord2D.toOffset(): Offset = Offset(x, y)

fun Offset.toCoord2D(): Coord2D = Coord2D(x, y)

fun SketchViewport.toScreen(coord: Coord2D): Offset = toView(coord).toOffset()

fun SketchViewport.toSurvey(point: Offset): Coord2D = toSurvey(point.toCoord2D())

/**
 * Zoom and centre so the whole of [bounds] is visible with [padding] pixels to spare.
 *
 * Called the first time the canvas is drawn at a known size, and again whenever the survey grows —
 * see [ViewportFit] for when that stops.
 */
fun SketchViewport.fitTo(bounds: Bounds, width: Float, height: Float, padding: Float = 48f) {
    val fit =
        min((width - padding * 2) / bounds.width, (height - padding * 2) / bounds.height)

    // A survey with no extent — the single station a live survey starts with — has nothing to fit
    // to, so it opens at the zoom the Android app opens at. Fitting it would be arithmetically
    // valid and visually absurd: the bounds floor of a millimetre would zoom until the scale bar
    // read centimetres, and the surveyor's first leg would shoot straight off the screen.
    val nothingToFit = bounds.width <= DEGENERATE_EXTENT && bounds.height <= DEGENERATE_EXTENT
    val zoom =
        if (!nothingToFit && fit.isFinite() && fit > 0f) {
            fit
        } else {
            SketchViewport.DEFAULT_PIXELS_PER_METRE
        }

    // The shared viewport's zoom bounds are exclusive and it refuses rather than clamps, so the
    // value is brought inside the range here instead of being silently ignored.
    setZoom(
        zoom.coerceIn(SketchViewport.MIN_ZOOM * 1.01f, SketchViewport.MAX_ZOOM * 0.99f),
        Coord2D.ORIGIN,
    )
    centreOn(Coord2D(bounds.centreX, bounds.centreY), width, height)
}

/**
 * Below this, in metres, a survey counts as having no extent at all — one station, or none. It is
 * a centimetre rather than the [Bounds] floor because a survey that genuinely spans a centimetre
 * is not a survey either.
 */
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
 * Tracks whether the canvas still gets to choose its own viewport, and what it last chose.
 *
 * Deliberately a plain holder rather than Compose state: the fit happens *inside* the draw, because
 * that is the first moment the real canvas size is known — on the very first frame, and in the
 * single-frame headless render used for the screenshots, a size measured through `onSizeChanged`
 * has not arrived yet and the survey would be drawn at one pixel per metre. Writing Compose state
 * from a draw would schedule another frame; this does not need one, since the fitted values are
 * used by the very draw that computes them.
 *
 * It re-fits as the survey grows, not just once. Live surveying starts from a single station and
 * adds a leg every few readings, so a one-shot fit leaves the cave walking off the screen within a
 * minute. The moment the surveyor pans or zooms, the viewport becomes theirs and this stops
 * touching it — an app that re-frames the view under somebody's finger is worse than one that
 * never frames it at all.
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
}

/**
 * Whether two extents are the same to within a millimetre.
 *
 * Compared by value rather than by identity because a fresh [Bounds] is built on every scene
 * rebuild, so identity would report a change on every frame.
 */
private fun Bounds.matches(other: Bounds): Boolean =
    abs(minX - other.minX) < 0.001f &&
        abs(minY - other.minY) < 0.001f &&
        abs(maxX - other.maxX) < 0.001f &&
        abs(maxY - other.maxY) < 0.001f
