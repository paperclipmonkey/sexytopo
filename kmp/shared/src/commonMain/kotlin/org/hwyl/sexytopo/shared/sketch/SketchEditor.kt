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
 * One undoable step.
 *
 * Erasing does not usually delete: rubbing out the middle of a passage wall removes one stroke and
 * puts *two* back, and undo must reverse the whole exchange in one step — which is what [Delete]'s
 * replacements are for.
 */
sealed interface SketchEdit {

    class Add(val item: SketchItem) : SketchEdit

    /**
     * [item] was removed and [replacements] were put in its place — empty for a plain delete, the
     * surviving fragments for an erase that split a stroke, the moved or rotated copy for a
     * cross-section edit. Undo restores [item] and removes [replacements]; redo does the reverse.
     */
    class Delete(val item: SketchItem, val replacements: List<SketchItem> = emptyList()) : SketchEdit

    /**
     * Several edits from one gesture — a drag of the eraser across several strokes — undone or
     * redone together. See [SketchEditor.inOneUndoStep].
     */
    class Batch(val edits: List<SketchEdit>) : SketchEdit
}

/**
 * A [Sketch] plus the undo/redo history and the editing operations that maintain it.
 *
 * Framework-free by design: it knows nothing of touch events, canvases or pixels. A UI layer
 * converts a gesture into survey-space coordinates and a tolerance in metres, calls one of these
 * methods, and redraws.
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

    var isSaved: Boolean = true
        private set

    val canUndo: Boolean get() = done.isNotEmpty()

    val canRedo: Boolean get() = undone.isNotEmpty()

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
     * This matters more here than in the Android app, where a cross-section's sub-sketch is mutable
     * and committing an edit keeps the detail's identity. In the immutable shared model, an edit
     * *must* come through here — a silent swap would leave the history pointing at a detail no
     * longer in the sketch, and undoing past its creation would leave a duplicate behind.
     */
    fun replaceCrossSection(old: CrossSectionDetail, new: CrossSectionDetail) {
        delete(old, listOf(new))
    }

    fun moveCrossSection(detail: CrossSectionDetail, delta: Coord2D): CrossSectionDetail {
        if (delta.x == 0f && delta.y == 0f) return detail
        val moved = detail.translate(delta)
        replaceCrossSection(detail, moved)
        return moved
    }

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

    fun delete(detail: SketchDetail, replacements: List<SketchDetail> = emptyList()) {
        deleteItem(detail.asItem(), replacements.map { it.asItem() })
    }

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
     * One call is one dab of the eraser. The Android app makes exactly one per touch — dragging
     * across a wall there does nothing — but this port's canvas deliberately calls it all along a
     * drag instead. See `rubAlong`.
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
     * Restored details are appended to their list rather than put back where they were, so undo can
     * change the drawing order of overlapping ink — left alone since sketch details are opaque, so
     * order is rarely visible.
     */
    fun undo(): Boolean {
        val edit = done.removeLastOrNull() ?: return false
        applyUndo(edit)
        undone.addLast(edit)
        return true
    }

    fun redo(): Boolean {
        val edit = undone.removeLastOrNull() ?: return false
        applyRedo(edit)
        done.addLast(edit)
        return true
    }

    // A batch undoes its edits in reverse and redoes them in the order they were made, the same as
    // if they had never been collapsed into one step at all — see [inOneUndoStep].
    private fun applyUndo(edit: SketchEdit) {
        when (edit) {
            is SketchEdit.Add -> removeFromSketch(edit.item)
            is SketchEdit.Delete -> {
                restoreToSketch(edit.item)
                edit.replacements.forEach { removeFromSketch(it) }
            }
            is SketchEdit.Batch -> edit.edits.asReversed().forEach { applyUndo(it) }
        }
    }

    private fun applyRedo(edit: SketchEdit) {
        when (edit) {
            is SketchEdit.Add -> restoreToSketch(edit.item)
            is SketchEdit.Delete -> {
                removeFromSketch(edit.item)
                edit.replacements.forEach { restoreToSketch(it) }
            }
            is SketchEdit.Batch -> edit.edits.forEach { applyRedo(it) }
        }
    }

    /**
     * While a transaction is open, [record] holds edits here instead of pushing them straight to
     * [done] — [PublishedApi] rather than `private` only so [inOneUndoStep], which has to be public
     * to be useful outside this module, can be inlined into it.
     */
    @PublishedApi
    internal var transactionEdits: MutableList<SketchEdit>? = null

    /**
     * Run [block], collapsing every edit it records into a single undo step.
     *
     * For the eraser dragged across several strokes: without this, one press of ctrl+z after such a
     * drag takes back only the last stroke crossed, not the whole rub. A nested call joins the
     * outermost transaction rather than starting its own, so this is safe to call from inside a
     * method that might itself already be inside one.
     */
    inline fun <T> inOneUndoStep(block: () -> T): T {
        val startedHere = transactionEdits == null
        if (startedHere) transactionEdits = mutableListOf()
        try {
            return block()
        } finally {
            if (startedHere) closeTransaction()
        }
    }

    @PublishedApi
    internal fun closeTransaction() {
        val edits = transactionEdits ?: return
        transactionEdits = null
        when (edits.size) {
            0 -> Unit
            1 -> pushDone(edits[0])
            else -> pushDone(SketchEdit.Batch(edits))
        }
    }

    private fun pushDone(edit: SketchEdit) {
        done.addLast(edit)
        undone.clear()
    }

    private fun record(edit: SketchEdit) {
        isSaved = false
        val transaction = transactionEdits
        if (transaction != null) transaction.add(edit) else pushDone(edit)
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
 * things — and the history holds references, so removal has to be by identity.
 */
private fun <T : Any> MutableList<T>.removeFirstIdentical(element: T) {
    val index = indexOfFirst { it === element }
    if (index >= 0) {
        removeAt(index)
    }
}
