package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.max
import kotlin.math.min

/**
 * The mapping between survey space (metres, y already flipped for the screen by Projection2D) and
 * the pixels on the canvas.
 *
 * A read-only view only ever needs the forward direction. Drawing needs the inverse too — a stylus
 * touch arrives in pixels and has to become a point in metres before it can join a sketch — so the
 * transform is pulled out here where both the renderer and the gesture handlers can reach it.
 *
 * Sketch geometry is stored in metres, never pixels, exactly as in the Android app. That is what
 * lets a sketch survive zooming, and what lets it round-trip through the shared JSON format.
 */
class Viewport(
    val bounds: Bounds,
    val size: Size,
    val zoom: Float,
    val pan: Offset,
    val padding: Float = 48f,
) {
    /** Pixels per metre at the current zoom, after fitting the survey to the viewport. */
    val pixelsPerMetre: Float
        get() {
            if (size.width <= 0f || size.height <= 0f) return zoom
            val fit =
                min(
                    (size.width - padding * 2) / bounds.width,
                    (size.height - padding * 2) / bounds.height,
                )
            // A degenerate survey (one station, no extent) would otherwise fit at infinity.
            return if (fit.isFinite() && fit > 0f) fit * zoom else zoom
        }

    fun toScreen(coord: Coord2D): Offset {
        val scale = pixelsPerMetre
        return Offset(
            (coord.x - bounds.centreX) * scale + size.width / 2 + pan.x,
            (coord.y - bounds.centreY) * scale + size.height / 2 + pan.y,
        )
    }

    fun toSurvey(point: Offset): Coord2D {
        val scale = pixelsPerMetre
        if (scale == 0f) return Coord2D.ORIGIN
        return Coord2D(
            (point.x - size.width / 2 - pan.x) / scale + bounds.centreX,
            (point.y - size.height / 2 - pan.y) / scale + bounds.centreY,
        )
    }

    /** A screen distance expressed in survey metres — for hit-testing and eraser radii. */
    fun toSurveyDistance(pixels: Float): Float {
        val scale = pixelsPerMetre
        return if (scale == 0f) pixels else pixels / scale
    }
}

/** The extent of everything drawn, in survey space. */
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
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            return Bounds(minX, minY, maxX, maxY)
        }
    }
}
