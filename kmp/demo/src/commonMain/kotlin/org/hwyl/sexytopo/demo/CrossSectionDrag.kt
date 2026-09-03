package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.sketch.SketchEditor

/**
 * What a finger is doing to a cross-section, while it is still doing it.
 *
 * A value: every drag frame makes a new one rather than mutating, so the preview drawn on the
 * canvas and the edit finally committed are computed by exactly the same code.
 */
internal class SectionDrag(
    val mode: SectionDragMode,
    val detail: CrossSectionDetail,
    val from: Coord2D,
    val finger: Coord2D = from,
    /**
     * What the section swings around while being re-aimed: its station's position in the main
     * projection. Null when the station is not in the projection at all — an extended elevation
     * does not contain every station of the plan.
     */
    val pivot: Coord2D? = null,
) {

    fun movedTo(point: Coord2D): SectionDrag = SectionDrag(mode, detail, from, point, pivot)

    val delta: Coord2D get() = finger - from

    /** Null when there is no pivot, or the finger sits exactly on it (direction undefined). */
    val azimuth: Float?
        get() {
            val pivot = pivot ?: return null
            if (finger.x == pivot.x && finger.y == pivot.y) return null
            return bearingOf(pivot, finger)
        }

    /** What the section would look like if the finger lifted now. */
    fun preview(): CrossSectionDetail =
        when (mode) {
            SectionDragMode.MOVE -> detail.translate(delta)
            SectionDragMode.ROTATE -> azimuth?.let { detail.withAngle(it) } ?: detail
        }

    /**
     * Apply the drag, as one undo step.
     *
     * @return true if the sketch changed. A drag that ends where it started changes nothing, so
     *   an accidental brush against a section doesn't push an undo step that undoes nothing
     *   visible.
     */
    fun commit(editor: SketchEditor): Boolean =
        when (mode) {
            SectionDragMode.MOVE -> {
                val delta = delta
                if (delta.x == 0f && delta.y == 0f) {
                    false
                } else {
                    editor.moveCrossSection(detail, delta)
                    true
                }
            }

            SectionDragMode.ROTATE -> {
                val azimuth = azimuth
                if (azimuth == null) {
                    false
                } else {
                    editor.rotateCrossSection(detail, azimuth)
                    true
                }
            }
        }
}

internal enum class SectionDragMode {
    /** Slide the whole section, drawing and all, to somewhere clearer on the plan. */
    MOVE,

    /** Swing the slice round its station to cut the passage square. */
    ROTATE,
}

/**
 * The compass bearing from one survey point to another, in degrees clockwise from north. Shares
 * its implementation with the screen-space [bearingOf] so the two cannot drift apart.
 */
internal fun bearingOf(from: Coord2D, to: Coord2D): Float =
    bearingOf(to.x - from.x, to.y - from.y)
