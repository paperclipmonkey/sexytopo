package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.SketchDetail
import org.hwyl.sexytopo.shared.model.sketch.SymbolDetail
import org.hwyl.sexytopo.shared.model.sketch.TextDetail

/**
 * One undoable step. Ported from the Java `Sketch`'s twin `sketchHistory` / `undoneHistory` stacks.
 *
 * The Android app stores a bare `SketchDetail` in its history for an addition, and wraps a deletion
 * in a `DeletedDetail` carrying both the thing removed and the things that replaced it. That
 * wrapper exists because erasing does not usually delete: rubbing out the middle of a passage wall
 * removes one stroke and puts *two* back, and undo must reverse the whole exchange in one step.
 * Kotlin lets us say that directly with a sealed type, so the `instanceof DeletedDetail` test and
 * the "can't wrap a DeletedDetail in a DeletedDetail" runtime guard both disappear — the shape of
 * the data makes them impossible.
 */
sealed interface SketchEdit {

    /** Something was added to the sketch. Undo removes it. */
    class Add(val item: SketchItem) : SketchEdit

    /**
     * [item] was removed and [replacements] were put in its place — empty for a plain delete, the
     * surviving fragments for an erase that split a stroke, the moved or rotated copy for a
     * cross-section edit. Undo restores [item] and removes [replacements]; redo does the reverse.
     */
    class Delete(val item: SketchItem, val replacements: List<SketchItem> = emptyList()) : SketchEdit
}

/**
 * A [Sketch] plus the undo/redo history and the editing operations that maintain it.
 *
 * Framework-free by design: it knows nothing of touch events, canvases or pixels. A UI layer
 * converts a gesture into survey-space coordinates and a tolerance in metres, calls one of these
 * methods, and redraws. That is the whole contract, and it is what lets the same editing model sit
 * under Compose on Android, iOS and the web.
 *
 * Ported from `model/sketch/Sketch` together with the parts of `control/graph/GraphView` that
 * decide what a touch means.
 */
class SketchEditor(val sketch: Sketch = Sketch()) {

    private val done = ArrayDeque<SketchEdit>()
    private val undone = ArrayDeque<SketchEdit>()

    /**
     * The stroke currently under the finger. It is already in [Sketch.pathDetails] so it draws as
     * it grows, but it is not in the history until [finishPath] — an abandoned stroke leaves no
     * trace to undo.
     */
    var activePath: PathDetail? = null
        private set

    /** False once anything has been committed since the last [markSaved]. */
    var isSaved: Boolean = true
        private set

    val canUndo: Boolean get() = done.isNotEmpty()

    val canRedo: Boolean get() = undone.isNotEmpty()

    /** The colour new details are drawn in. */
    var activeColour: Colour
        get() = sketch.activeColour
        set(value) {
            sketch.activeColour = value
        }

    fun markSaved() {
        isSaved = true
    }

    // -------------------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------------------

    /**
     * Begin a stroke at [start] (survey metres). Any previous unfinished stroke is abandoned.
     *
     * Snap-to-line, when enabled, is applied by the caller *before* this: it looks for the end of
     * another stroke within [SketchDefaults.SNAP_TO_LINE_SENSITIVITY_DP] of the touch and starts
     * here instead, so walls drawn in several passes join up cleanly. See [snapPointNear].
     */
    fun startPath(start: Coord2D, colour: Colour = activeColour): PathDetail {
        abandonPath()
        val path = sketch.startNewPath(start, colour)
        activePath = path
        return path
    }

    /** Extend the active stroke. A move event with no active stroke starts one, as in the Java. */
    fun extendPath(point: Coord2D) {
        val active = activePath
        if (active == null) {
            startPath(point)
        } else {
            active.lineTo(point)
        }
    }

    /**
     * Finish the active stroke: simplify it, commit it to the sketch and push one undo step.
     *
     * The simplification tolerance comes from the stroke's own extent
     * ([simplificationEpsilon]), so it thins a long wall hard and a small detail barely at all.
     *
     * Unlike the Java, which mutates the path's point list in place, this replaces the detail with
     * a simplified copy *at the same index*, preserving draw order. The identity of the returned
     * detail is the one that goes in the history, so anything the UI cached during the drag (the
     * value returned by [startPath]) is stale afterwards and should be dropped.
     */
    fun finishPath(): PathDetail? {
        val active = activePath ?: return null
        activePath = null

        val simplified = simplify(active.path, simplificationEpsilon(active.path))
        val finished = PathDetail(simplified, active.colour)

        val index = sketch.pathDetails.indexOfFirst { it === active }
        if (index >= 0) {
            sketch.pathDetails[index] = finished
        } else {
            sketch.pathDetails.add(finished)
        }

        record(SketchEdit.Add(finished.asItem()))
        return finished
    }

    /**
     * Drop the in-progress stroke without committing it — used when a long press turns the gesture
     * into a station menu, so the surveyor is not left with a stray mark to undo.
     */
    fun abandonPath() {
        val active = activePath ?: return
        activePath = null
        sketch.pathDetails.removeFirstIdentical(active)
    }

    /** The end of another stroke to snap to, ignoring the stroke being drawn. */
    fun snapPointNear(point: Coord2D, deltaInMetres: Float): Coord2D? =
        findEligibleSnapPointWithin(sketch, point, deltaInMetres, exclude = activePath)

    // -------------------------------------------------------------------------------------
    // Stamps
    // -------------------------------------------------------------------------------------

    /**
     * Place a symbol. [size] is in metres (the Android app converts a fixed 25dp stamp through the
     * current zoom, so a symbol is stamped at a constant size on screen and then scales with the
     * sketch). [angle] is the compass-style bearing for directional symbols — see
     * [directionalSymbolAngle].
     */
    fun addSymbol(
        position: Coord2D,
        symbolName: String,
        size: Float,
        angle: Float = 0f,
        colour: Colour = activeColour,
    ): SymbolDetail {
        val detail = sketch.addSymbolDetail(position, symbolName, size, angle, colour)
        record(SketchEdit.Add(detail.asItem()))
        return detail
    }

    /** Place a text label. [size] is in metres, from a fixed 16sp through the current zoom. */
    fun addText(
        position: Coord2D,
        text: String,
        size: Float,
        colour: Colour = activeColour,
    ): TextDetail {
        val detail = sketch.addTextDetail(position, text, size, colour)
        record(SketchEdit.Add(detail.asItem()))
        return detail
    }

    // -------------------------------------------------------------------------------------
    // Cross-sections
    // -------------------------------------------------------------------------------------

    /** Drop a cross-section at [position] — the second half of the position-cross-section tool. */
    fun addCrossSection(crossSection: CrossSection, position: Coord2D): CrossSectionDetail =
        addCrossSection(CrossSectionDetail(position, crossSection))

    fun addCrossSection(detail: CrossSectionDetail): CrossSectionDetail {
        sketch.crossSectionDetails.add(detail)
        record(SketchEdit.Add(detail.asItem()))
        return detail
    }

    /**
     * Swap one cross-section component for another as a single undo step: how a move, a rotate or a
     * committed sub-sketch edit is applied.
     *
     * This matters more here than in the Android app. There, a cross-section's sub-sketch is
     * mutable, so committing an edit keeps the detail's identity and the history stays valid. In
     * the shared model [CrossSectionDetail] is immutable, so an edit *must* come through here: a
     * silent swap would leave the history pointing at a detail that is no longer in the sketch,
     * and undoing past the component's creation would then leave a duplicate behind.
     */
    fun replaceCrossSection(old: CrossSectionDetail, new: CrossSectionDetail) {
        delete(old, listOf(new))
    }

    /** Move a cross-section by [delta] metres, as one undo step. No-op for a zero delta. */
    fun moveCrossSection(detail: CrossSectionDetail, delta: Coord2D): CrossSectionDetail {
        if (delta.x == 0f && delta.y == 0f) return detail
        val moved = detail.translate(delta)
        replaceCrossSection(detail, moved)
        return moved
    }

    /** Re-aim a cross-section at a new compass bearing, as one undo step. */
    fun rotateCrossSection(detail: CrossSectionDetail, azimuth: Float): CrossSectionDetail {
        val rotated =
            CrossSectionDetail(
                detail.position,
                CrossSection(detail.crossSection.station, azimuth),
                detail.sketch,
            )
        replaceCrossSection(detail, rotated)
        return rotated
    }

    // -------------------------------------------------------------------------------------
    // Deleting and erasing
    // -------------------------------------------------------------------------------------

    /** Delete a detail, optionally putting [replacements] in its place, as one undo step. */
    fun delete(detail: SketchDetail, replacements: List<SketchDetail> = emptyList()) {
        deleteItem(detail.asItem(), replacements.map { it.asItem() })
    }

    /** Delete a cross-section, optionally putting [replacements] in its place. */
    fun delete(detail: CrossSectionDetail, replacements: List<CrossSectionDetail> = emptyList()) {
        deleteItem(detail.asItem(), replacements.map { it.asItem() })
    }

    private fun deleteItem(item: SketchItem, replacements: List<SketchItem>) {
        record(SketchEdit.Delete(item, replacements))
        removeFromSketch(item)
        replacements.forEach { restoreToSketch(it) }
    }

    /**
     * The eraser: one press removes at most one thing.
     *
     * The order of business is the Android app's, and it matters:
     *  1. if cross-sections are shown, a press anywhere inside one deletes it — its whole body is
     *     hit-tested, not just its centre;
     *  2. otherwise the nearest *visible* detail within [toleranceInMetres] is found (10dp through
     *     the current zoom), and nothing happens if there isn't one;
     *  3. a path is normally *split* rather than deleted: the parts of the stroke outside the
     *     eraser survive as new strokes, so rubbing out the middle of a wall leaves both ends. Turn
     *     [deletePathFragments] off to delete whole strokes instead.
     *
     * Note the Android app only erases on touch-*down*, not while dragging, so the eraser is a
     * tapping tool rather than a rubbing one; a UI driving this should do the same.
     *
     * @return true if anything was deleted.
     */
    fun eraseAt(
        point: Coord2D,
        toleranceInMetres: Float,
        pixelsPerMetre: Float,
        deletePathFragments: Boolean = true,
        showCrossSections: Boolean = true,
        minCrossSectionPixels: Float = SketchDefaults.MIN_VISIBLE_CROSS_SECTION_DP,
    ): Boolean {
        if (showCrossSections) {
            val section = findCrossSectionBodyAt(sketch, point)
            if (section != null) {
                delete(section)
                return true
            }
        }

        val nearest =
            findNearestVisibleItemWithin(
                sketch,
                point,
                toleranceInMetres,
                pixelsPerMetre,
                minCrossSectionPixels,
            ) ?: return false

        when (nearest) {
            is SketchItem.Section -> delete(nearest.detail)
            is SketchItem.Drawn -> {
                val detail = nearest.detail
                if (deletePathFragments && detail is PathDetail) {
                    delete(detail, detail.getPathFragmentsOutsideRadius(point, toleranceInMetres))
                } else {
                    delete(detail)
                }
            }
        }
        return true
    }

    // -------------------------------------------------------------------------------------
    // Undo / redo
    // -------------------------------------------------------------------------------------

    /**
     * Reverse the most recent edit.
     *
     * Restored details are appended to their list rather than put back where they were, so undo can
     * change the drawing order of overlapping ink. That is true of the Java as well and is left
     * alone: sketch details are drawn with opaque strokes, so order is rarely visible.
     */
    fun undo(): Boolean {
        val edit = done.removeLastOrNull() ?: return false
        when (edit) {
            is SketchEdit.Add -> removeFromSketch(edit.item)
            is SketchEdit.Delete -> {
                restoreToSketch(edit.item)
                edit.replacements.forEach { removeFromSketch(it) }
            }
        }
        undone.addLast(edit)
        return true
    }

    /** Re-apply the most recently undone edit. */
    fun redo(): Boolean {
        val edit = undone.removeLastOrNull() ?: return false
        when (edit) {
            is SketchEdit.Add -> restoreToSketch(edit.item)
            is SketchEdit.Delete -> {
                removeFromSketch(edit.item)
                edit.replacements.forEach { restoreToSketch(it) }
            }
        }
        done.addLast(edit)
        return true
    }

    /**
     * Push an edit. Any new edit discards the redo stack — you cannot redo down a branch you have
     * drawn away from.
     */
    private fun record(edit: SketchEdit) {
        isSaved = false
        done.addLast(edit)
        undone.clear()
    }

    private fun restoreToSketch(item: SketchItem) {
        when (item) {
            is SketchItem.Section -> sketch.crossSectionDetails.add(item.detail)
            is SketchItem.Drawn ->
                when (val detail = item.detail) {
                    is PathDetail -> sketch.pathDetails.add(detail)
                    is SymbolDetail -> sketch.symbolDetails.add(detail)
                    is TextDetail -> sketch.textDetails.add(detail)
                    else -> Unit
                }
        }
    }

    private fun removeFromSketch(item: SketchItem) {
        when (item) {
            is SketchItem.Section -> sketch.crossSectionDetails.removeFirstIdentical(item.detail)
            is SketchItem.Drawn ->
                when (val detail = item.detail) {
                    is PathDetail -> sketch.pathDetails.removeFirstIdentical(detail)
                    is SymbolDetail -> sketch.symbolDetails.removeFirstIdentical(detail)
                    is TextDetail -> sketch.textDetails.removeFirstIdentical(detail)
                    else -> Unit
                }
        }
    }
}

/**
 * Remove the first element that *is* [element], by identity.
 *
 * Sketch details have no value equality — two identical strokes drawn twice are two different
 * things — and the history holds references, so removal has to be by identity. `List.remove` in the
 * Java behaves the same way for these types because they inherit `Object.equals`.
 */
private fun <T : Any> MutableList<T>.removeFirstIdentical(element: T) {
    val index = indexOfFirst { it === element }
    if (index >= 0) {
        removeAt(index)
    }
}
