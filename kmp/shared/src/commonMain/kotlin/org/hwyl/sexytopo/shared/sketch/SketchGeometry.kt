package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.SketchDetail
import org.hwyl.sexytopo.shared.model.sketch.SymbolDetail
import org.hwyl.sexytopo.shared.model.sketch.TextDetail
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bounding boxes, hit distances and visibility tests for sketch details.
 *
 * The shared model's details are plain data with no incrementally-maintained bounding box, so
 * these rules live here as functions instead. This file is the authority — `SketchDetail
 * .getDistanceFrom` in the model is a simplification.
 */

// -------------------------------------------------------------------------------------------
// Bounding boxes
// -------------------------------------------------------------------------------------------

fun boundsOf(detail: SketchDetail): DetailBounds =
    when (detail) {
        is PathDetail -> boundsOf(detail.path)
        is TextDetail -> boundsOfText(detail)
        // A symbol's box is just its position; size is handled by the visibility rule instead.
        is SymbolDetail -> DetailBounds.EMPTY + detail.position
        else -> DetailBounds.EMPTY
    }

/**
 * Text is drawn with its position at the left edge of the first line's baseline, so the glyphs
 * extend rightwards and upwards from it and any extra lines extend downwards. Width is estimated as
 * 0.6 of the size per character of the longest line — crude, but it is what the hit test and the
 * visibility check both use, so a port must match it exactly or selection will feel different.
 */
private fun boundsOfText(detail: TextDetail): DetailBounds {
    val position = detail.position
    val size = detail.size
    val lines = detail.text.split("\n")
    val longestLine = lines.maxOf { it.length }
    val width = longestLine * size * 0.6f
    return DetailBounds.EMPTY +
        position +
        Coord2D(position.x + width, position.y - size) +
        Coord2D(position.x, position.y + size * (lines.size - 1))
}

/**
 * A cross-section on the plan occupies the extent of its projected splays plus anything drawn in
 * its sub-sketch, both measured relative to the component's centre. A minimum half-extent of one
 * metre is forced in so a station with no splays still leaves something big enough to tap.
 */
fun boundsOf(detail: CrossSectionDetail): DetailBounds {
    val position = detail.position
    var bounds =
        DetailBounds.EMPTY +
            position.add(-MIN_CROSS_SECTION_HALF_EXTENT, -MIN_CROSS_SECTION_HALF_EXTENT) +
            position.add(MIN_CROSS_SECTION_HALF_EXTENT, MIN_CROSS_SECTION_HALF_EXTENT)

    // Only the outer ends of the projected splays matter; every splay starts at the centre.
    for (line in detail.crossSection.getProjection().legMap.values) {
        bounds += line.end + position
    }

    val subSketch = boundsOf(detail.sketch)
    bounds += DetailBounds.EMPTY + (subSketch.topLeft + position) + (subSketch.bottomRight + position)
    return bounds
}

fun boundsOf(sketch: Sketch): DetailBounds {
    var bounds = DetailBounds.EMPTY
    for (path in sketch.pathDetails) bounds += boundsOf(path)
    for (symbol in sketch.symbolDetails) bounds += boundsOf(symbol)
    for (text in sketch.textDetails) bounds += boundsOf(text)
    for (section in sketch.crossSectionDetails) bounds += boundsOf(section)
    return bounds
}

const val MIN_CROSS_SECTION_HALF_EXTENT: Float = 1.0f

// -------------------------------------------------------------------------------------------
// Hit distances
// -------------------------------------------------------------------------------------------

/**
 * Symbols and text are given a deliberate advantage over lines: inside their own body the reported
 * distance is halved, so a stamp drawn on top of a passage wall wins the eraser, while a line
 * crossing near it can still be picked from outside.
 */
fun distanceFrom(detail: SketchDetail, point: Coord2D): Float =
    when (detail) {
        // Distance to the nearest segment; Float.MAX_VALUE for a path of fewer than two points.
        is PathDetail -> detail.getDistanceFrom(point)
        is SymbolDetail -> distanceFromSymbol(detail, point)
        is TextDetail -> distanceFromText(detail, point)
        else -> detail.getDistanceFrom(point)
    }

private fun distanceFromSymbol(detail: SymbolDetail, point: Coord2D): Float {
    val radius = detail.size / 2
    val distance = getDistance(point, detail.position)
    return if (distance <= radius) distance * 0.5f else distance - radius
}

private fun distanceFromText(detail: TextDetail, point: Coord2D): Float {
    val bounds = boundsOfText(detail)
    if (point.x >= bounds.left &&
        point.x <= bounds.right &&
        point.y >= bounds.top &&
        point.y <= bounds.bottom
    ) {
        val centre = Coord2D((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
        return getDistance(point, centre) * 0.5f
    }
    val distX = max(0f, max(bounds.left - point.x, point.x - bounds.right))
    val distY = max(0f, max(bounds.top - point.y, point.y - bounds.bottom))
    return sqrt(distX * distX + distY * distY)
}

/**
 * A cross-section is measured from its centre only.
 *
 * That is why erasing hit-tests the whole body separately (see [findCrossSectionBodyAt]): a press
 * inside a large cross-section but away from its centre would otherwise miss it entirely.
 */
fun distanceFrom(detail: CrossSectionDetail, point: Coord2D): Float = getDistance(point, detail.position)

// -------------------------------------------------------------------------------------------
// Visibility
// -------------------------------------------------------------------------------------------

/**
 * Hit-testing shares this check with the renderer on purpose: you cannot erase what you cannot
 * see.
 */
fun couldBeVisibleAtScale(detail: SketchDetail, pixelsPerMetre: Float): Boolean =
    when (detail) {
        // A symbol's box is a point, so it uses its drawn size rather than its bounds.
        is SymbolDetail -> detail.size * pixelsPerMetre >= 1
        else -> boundsOf(detail).maxDimension * pixelsPerMetre >= 1
    }

/**
 * A cross-section needs a few pixels rather than one to count as visible: `MIN_VISIBLE_PIXELS` in
 * the Java is 8dp, so the caller passes that converted to pixels.
 */
fun couldBeVisibleAtScale(
    detail: CrossSectionDetail,
    pixelsPerMetre: Float,
    minVisiblePixels: Float,
): Boolean = boundsOf(detail).maxDimension * pixelsPerMetre >= minVisiblePixels
