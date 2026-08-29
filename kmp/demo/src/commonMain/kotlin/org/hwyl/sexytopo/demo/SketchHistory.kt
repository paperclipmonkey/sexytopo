package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch

/**
 * Undo/redo for sketch editing, by snapshotting the detail lists before each change.
 *
 * The Android app models this more cleverly — a deletion becomes a `DeletedDetail` wrapper holding
 * its replacements, so an erase that splits a path into fragments can be undone precisely. Snapshots
 * are coarser but behave identically from the user's side, and they cannot drift out of step with
 * the sketch, which the wrapper approach can. Committed details are never mutated in place, so a
 * shallow copy of each list is a sound snapshot.
 */
class SketchHistory(private val limit: Int = 100) {

    private class Snapshot(
        val paths: List<PathDetail>,
        val symbols: List<org.hwyl.sexytopo.shared.model.sketch.SymbolDetail>,
        val labels: List<org.hwyl.sexytopo.shared.model.sketch.TextDetail>,
    )

    private val undos = ArrayDeque<Snapshot>()
    private val redos = ArrayDeque<Snapshot>()

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    private fun snapshot(sketch: Sketch) =
        Snapshot(
            sketch.pathDetails.toList(),
            sketch.symbolDetails.toList(),
            sketch.textDetails.toList(),
        )

    private fun restore(sketch: Sketch, snapshot: Snapshot) {
        sketch.pathDetails = snapshot.paths.toMutableList()
        sketch.symbolDetails = snapshot.symbols.toMutableList()
        sketch.textDetails = snapshot.labels.toMutableList()
    }

    /** Call immediately BEFORE mutating [sketch]. A new edit invalidates the redo stack. */
    fun record(sketch: Sketch) {
        undos.addLast(snapshot(sketch))
        while (undos.size > limit) undos.removeFirst()
        redos.clear()
    }

    fun undo(sketch: Sketch): Boolean {
        val previous = undos.removeLastOrNull() ?: return false
        redos.addLast(snapshot(sketch))
        restore(sketch, previous)
        return true
    }

    fun redo(sketch: Sketch): Boolean {
        val next = redos.removeLastOrNull() ?: return false
        undos.addLast(snapshot(sketch))
        restore(sketch, next)
        return true
    }

    fun clear() {
        undos.clear()
        redos.clear()
    }
}

/**
 * Erasing splits paths rather than deleting them outright, matching
 * `PathDetail.getPathFragmentsOutsideRadius` in the Android app: the parts of a stroke outside the
 * eraser survive as separate strokes. Rubbing out the middle of a passage wall leaves both ends.
 *
 * @return true if anything was erased.
 */
fun eraseAt(sketch: Sketch, point: Coord2D, radius: Float): Boolean {
    val survivors = mutableListOf<PathDetail>()
    var changed = false

    for (detail in sketch.pathDetails) {
        if (detail.getDistanceFrom(point) > radius) {
            survivors.add(detail)
            continue
        }
        changed = true
        survivors.addAll(detail.getPathFragmentsOutsideRadius(point, radius))
    }

    if (changed) {
        sketch.pathDetails = survivors
    }

    // Symbols and labels are point-like, so they are removed rather than split.
    val symbolsBefore = sketch.symbolDetails.size
    sketch.symbolDetails.removeAll { it.getDistanceFrom(point) <= radius }
    val labelsBefore = sketch.textDetails.size
    sketch.textDetails.removeAll { it.getDistanceFrom(point) <= radius }

    return changed ||
        sketch.symbolDetails.size != symbolsBefore ||
        sketch.textDetails.size != labelsBefore
}
