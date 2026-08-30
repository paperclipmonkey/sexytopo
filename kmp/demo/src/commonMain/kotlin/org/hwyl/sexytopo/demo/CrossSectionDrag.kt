package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.sketch.SketchEditor

/**
 * What a finger is doing to a cross-section, while it is still doing it.
 *
 * Ported from the two halves of `GraphView` that drive `MOVE_CROSS_SECTION` and
 * `ROTATE_CROSS_SECTION`: `handleMoveCrossSection` and `handleRotateCrossSection`. Both are drags
 * that show a preview and commit on release, and both are worth having because the placement the
 * app makes for you is a guess.
 *
 * The bearing in particular is a guess. `CrossSectioner` picks it by bisecting the corner
 * mid-passage and following the single leg at a dead end, which is right often enough to be worth
 * doing automatically and wrong often enough that a surveyor standing in the passage needs to be
 * able to say so — a section drawn square to the wrong axis is not a smaller mistake than no
 * section at all, it is a misleading one.
 *
 * This is a value: every drag frame makes a new one rather than mutating, so the preview drawn on
 * the canvas and the edit finally committed are computed by exactly the same code. The alternative
 * — a preview drawn from the finger and a commit computed from the event — is how a preview comes
 * to disagree with its result.
 */
internal class SectionDrag(
    val mode: SectionDragMode,
    val detail: CrossSectionDetail,
    /** Where the drag started, in survey metres. */
    val from: Coord2D,
    /** Where the finger is now, in survey metres. */
    val finger: Coord2D = from,
    /**
     * What the section swings around while being re-aimed: its station's position in the main
     * projection, as in `GraphView.getRotationPivot`.
     *
     * Null when the station is not in the projection at all — which the original guards against
     * too, and which really happens: an extended elevation does not contain every station of the
     * plan.
     */
    val pivot: Coord2D? = null,
) {

    /** The same drag with the finger somewhere else. */
    fun movedTo(point: Coord2D): SectionDrag = SectionDrag(mode, detail, from, point, pivot)

    /** How far the finger has travelled, in survey metres. */
    val delta: Coord2D get() = finger - from

    /**
     * The compass bearing this drag is aiming at, or null if it is not aiming at anything.
     *
     * Null covers both of the original's guards: no pivot to measure from, and a finger sitting
     * exactly on the pivot, where the direction is undefined rather than north.
     */
    val azimuth: Float?
        get() {
            val pivot = pivot ?: return null
            if (finger.x == pivot.x && finger.y == pivot.y) return null
            return bearingOf(pivot, finger)
        }

    /**
     * What the section would look like if the finger lifted now — drawn as the preview, and used to
     * work out whether there is anything to commit.
     *
     * A drag that has not produced a usable value yet previews the section unchanged, so the
     * surveyor sees the section they grabbed rather than a flicker of something else.
     */
    fun preview(): CrossSectionDetail =
        when (mode) {
            SectionDragMode.MOVE -> detail.translate(delta)
            SectionDragMode.ROTATE -> azimuth?.let { detail.withAngle(it) } ?: detail
        }

    /**
     * Apply the drag, as one undo step.
     *
     * @return true if the sketch changed, so the caller knows whether to save. A drag that ends
     *   where it started changes nothing — the Java tests the delta for exactly this reason, and
     *   without it every accidental brush against a section would push an undo step that undoes
     *   nothing visible.
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
 * The compass bearing from one survey point to another, in degrees clockwise from north.
 *
 * Ported from `GraphView.toAzimuth`. Sketch space has y increasing downwards and, after
 * [org.hwyl.sexytopo.shared.model.graph.Projection2D]'s flip, north is up — so this is the same
 * arithmetic as the screen-space [bearingOf] and deliberately shares its implementation. Anything
 * else would let the two drift apart, and the symptom would be a section aimed a few degrees off
 * from where the preview said.
 */
internal fun bearingOf(from: Coord2D, to: Coord2D): Float =
    bearingOf(to.x - from.x, to.y - from.y)
