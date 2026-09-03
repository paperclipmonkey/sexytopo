package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min

/**
 * The touch thresholds and zoom limits of the sketch view.
 *
 * Everything here is in density-independent pixels; the UI layer multiplies by the display density
 * to get real pixels and then divides by [SketchViewport.pixelsPerMetre] to get survey metres.
 */
object SketchDefaults {

    const val DELETE_DETAILS_WITHIN_DP: Float = 10.0f

    const val SELECTION_SENSITIVITY_DP: Float = 25.0f

    /** How near another stroke's end a new stroke has to start or finish to snap to it. */
    const val SNAP_TO_LINE_SENSITIVITY_DP: Float = 25.0f

    const val MIN_VISIBLE_CROSS_SECTION_DP: Float = 8.0f

    const val CROSS_SECTION_HANDLE_HIT_PADDING_DP: Float = 8.0f

    /** A symbol is stamped at this size on screen, then converted to metres through the zoom. */
    const val SYMBOL_STARTING_SIZE_DP: Float = 25.0f

    /** The active-station highlight is drawn at 1.1 times this, and the stroke at 1.25 times it. */
    const val STATION_CROSS_DIAMETER_DP: Float = 10.0f

    const val STATION_STROKE_WIDTH_DP: Float = 2.0f

    const val TEXT_STARTING_SIZE_SP: Float = 16.0f

    /**
     * A touch this close to a corner — as a fraction of the shorter screen edge — pans instead of
     * drawing, so you can move the view without switching tools.
     */
    const val HOT_CORNER_DISTANCE_PROPORTION: Float = 0.05f

    const val SNAP_TO_LINES_DEFAULT: Boolean = false

    const val DELETE_PATH_FRAGMENTS_DEFAULT: Boolean = true
}

/**
 * The mapping between survey space (metres) and the view (pixels), plus the zoom and pan gestures
 * that change it.
 *
 * Sketch geometry is stored in metres and never in pixels — that is what lets a sketch survive
 * zooming, round-trip through the shared JSON, and be exported to Therion at any scale.
 */
class SketchViewport(
    pixelsPerMetre: Float = DEFAULT_PIXELS_PER_METRE,
    offset: Coord2D = Coord2D.ORIGIN,
) {
    /** Pixels of screen per metre of cave. Zooming in increases it. */
    var pixelsPerMetre: Float = pixelsPerMetre
        private set

    /** The survey point currently at the top-left of the view. */
    var offset: Coord2D = offset
        private set

    fun toSurvey(viewPoint: Coord2D): Coord2D =
        Coord2D(viewPoint.x / pixelsPerMetre + offset.x, viewPoint.y / pixelsPerMetre + offset.y)

    fun toView(surveyPoint: Coord2D): Coord2D =
        Coord2D((surveyPoint.x - offset.x) * pixelsPerMetre, (surveyPoint.y - offset.y) * pixelsPerMetre)

    fun toSurveyDistance(pixels: Float): Float = pixels / pixelsPerMetre

    /** Pan so that the view moves with the finger: [downOffset] is [offset] when the drag began. */
    fun panBy(viewDelta: Coord2D, downOffset: Coord2D = offset) {
        offset = downOffset - viewDelta.scale(1 / pixelsPerMetre)
    }

    fun centreOn(surveyPoint: Coord2D, viewWidth: Float, viewHeight: Float) {
        offset =
            Coord2D(
                surveyPoint.x - (viewWidth / 2f) / pixelsPerMetre,
                surveyPoint.y - (viewHeight / 2f) / pixelsPerMetre,
            )
    }

    /** Multiply the zoom, keeping [focusOnScreen] pinned. */
    fun adjustZoomBy(factor: Float, focusOnScreen: Coord2D): Boolean =
        setZoom(pixelsPerMetre * factor, focusOnScreen)

    /**
     * Set the zoom, keeping the survey point under [focusOnScreen] under it.
     *
     * Faithful to the original in two respects worth knowing about: the bounds are *exclusive*
     * (`MIN_ZOOM >= new || new >= MAX_ZOOM` rejects), and an out-of-range zoom is refused outright
     * rather than clamped — so a pinch that overshoots simply stops moving.
     *
     * @return true if the zoom changed.
     */
    fun setZoom(newZoom: Float, focusOnScreen: Coord2D): Boolean {
        if (MIN_ZOOM >= newZoom || newZoom >= MAX_ZOOM) {
            return false
        }
        val focusInSurveyCoords = toSurvey(focusOnScreen)
        val delta = focusInSurveyCoords - offset
        offset = focusInSurveyCoords - delta.scale(pixelsPerMetre / newZoom)
        pixelsPerMetre = newZoom
        return true
    }

    /** The grid spacing in metres at the current zoom. */
    fun minorGridBoxSizeMetres(): Int =
        when {
            pixelsPerMetre > 15 -> 1
            pixelsPerMetre > 2 -> 10
            else -> 100
        }

    companion object {
        const val DEFAULT_PIXELS_PER_METRE: Float = 60.0f

        const val MIN_ZOOM: Float = 0.1f

        const val MAX_ZOOM: Float = 500.0f

        const val ZOOM_IN_INCREMENT: Float = 1.1f

        const val ZOOM_OUT_INCREMENT: Float = 0.9f
    }
}

/**
 * The angle of the vector from [to] back to [from], in degrees, in screen coordinates.
 *
 * Note the argument order is the reverse of what you might expect (it measures `from - to`), which
 * matters because the symbol tool relies on it.
 */
fun angleBetweenDegrees(from: Coord2D, to: Coord2D): Float {
    val radians = atan2((from.y - to.y).toDouble(), (from.x - to.x).toDouble()).toFloat()
    return (radians.toDouble() * 180.0 / PI).toFloat()
}

/**
 * The bearing to stamp a directional symbol at, given where the finger went down and came up.
 *
 * A directional symbol (an entrance, a water flow, a rimstone dam) is placed at the *down* point
 * and aimed along the drag. The extra -90 turn is because the symbol artwork points up rather than
 * along the positive x axis.
 */
fun directionalSymbolAngle(downOnView: Coord2D, upOnView: Coord2D): Float =
    adjustAngle(angleBetweenDegrees(downOnView, upOnView), -90f)

/**
 * The compass azimuth of a vector in plan-view survey coordinates: 0 is North, 90 is East.
 *
 * In plan view +x is East and -y is North, so the bearing is `atan2(dx, -dy)` rather than the
 * usual `atan2(dy, dx)`. Used when a cross-section is rotated by dragging away from its station.
 */
fun planAzimuth(dx: Float, dy: Float): Float {
    val degrees = (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
    return ((degrees % 360) + 360) % 360
}

fun hitsHotCorner(
    x: Float,
    y: Float,
    viewWidth: Float,
    viewHeight: Float,
    proportion: Float = SketchDefaults.HOT_CORNER_DISTANCE_PROPORTION,
): Boolean {
    val cornerDelta = min(viewWidth, viewHeight) * proportion
    val hitLeft = x < cornerDelta
    val hitRight = x > viewWidth - cornerDelta
    val hitTop = y < cornerDelta
    val hitBottom = y > viewHeight - cornerDelta
    return (hitLeft && (hitBottom || hitTop)) || (hitRight && (hitBottom || hitTop))
}

fun hotCornerSide(
    viewWidth: Float,
    viewHeight: Float,
    proportion: Float = SketchDefaults.HOT_CORNER_DISTANCE_PROPORTION,
): Float = min(viewWidth, viewHeight) * proportion

/**
 * The top-left of each hot-corner square, in view pixels: top-left, top-right, bottom-left,
 * bottom-right.
 *
 * Four, not three, and that is a deliberate departure. `GraphView.didEventHitHotCorner` tests all
 * four corners, but `drawHotCorners` tints only three of them — top-left, top-right, bottom-right.
 * The bottom-left corner of the Android sketch is therefore a live control that nothing on screen
 * mentions: touch it while drawing and the view pans instead, which reads as the app losing the
 * stroke. Drawing all four is a smaller change than making the fourth inert, and it is the one that
 * matches what the touch handler actually does.
 */
fun hotCornerTopLefts(
    viewWidth: Float,
    viewHeight: Float,
    proportion: Float = SketchDefaults.HOT_CORNER_DISTANCE_PROPORTION,
): List<Coord2D> {
    val side = hotCornerSide(viewWidth, viewHeight, proportion)
    return listOf(
        Coord2D(0f, 0f),
        Coord2D(viewWidth - side, 0f),
        Coord2D(0f, viewHeight - side),
        Coord2D(viewWidth - side, viewHeight - side),
    )
}
