package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.sketch.Colour

/**
 * What the finger does on the sketch. Two flags drive the surrounding UI in the Android app and
 * should drive it here too:
 *  - [usesColour]: the tool lays down ink, so picking a brush colour while another tool is active
 *    switches to [DRAW] (see `GraphActivity.handleAction`).
 *  - [isModal]: the tool was entered for the duration of one gesture rather than chosen from the
 *    toolbar, so it reverts on touch-up (see [SketchToolState.finishGestureIfModal]).
 */
enum class SketchTool(val usesColour: Boolean, val isModal: Boolean) {
    MOVE(usesColour = false, isModal = false),

    DRAW(usesColour = true, isModal = false),

    ERASE(usesColour = false, isModal = false),

    SYMBOL(usesColour = true, isModal = false),

    /** Place a text label (also reached from [SYMBOL] when the chosen symbol is the text one). */
    TEXT(usesColour = true, isModal = false),

    /** Pick the active station, or open its menu when it is already active. */
    SELECT(usesColour = false, isModal = false),

    /** One-shot: the next touch drops a new cross-section at that point. */
    POSITION_CROSS_SECTION(usesColour = false, isModal = false),

    ROTATE_CROSS_SECTION(usesColour = false, isModal = false),

    MOVE_CROSS_SECTION(usesColour = false, isModal = true),

    PINCH_TO_ZOOM(usesColour = false, isModal = true),

    /** Entered by a hot-corner or two-finger touch: pan without leaving the current tool. */
    MODAL_MOVE(usesColour = false, isModal = true),
    ;

    companion object {
        val DEFAULT = MOVE

        /** The Java `fromString` throws on an unrecognised name; this falls back to [DEFAULT] instead. */
        fun fromStringOrDefault(name: String?): SketchTool =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The eight colours offered on the sketch toolbar, out of the 140-odd [Colour] values the model
 * can store.
 */
enum class BrushColour(val colour: Colour) {
    BLACK(Colour.BLACK),
    BROWN(Colour.BROWN),
    GREY(Colour.GREY),
    RED(Colour.RED),
    ORANGE(Colour.ORANGE),
    GREEN(Colour.GREEN),
    BLUE(Colour.BLUE),
    PURPLE(Colour.PURPLE),
    ;

    companion object {
        val DEFAULT = BLACK

        fun fromStringOrDefault(name: String?): BrushColour =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The current and previous tool.
 *
 * The "previous" tool exists so that one-gesture tools (a pinch, a hot-corner pan, a cross-section
 * drag) can hand control back to whatever the surveyor had selected. [current] is only remembered
 * as [previous] when it *differs from the previous already recorded* and is not itself modal, so
 * repeatedly re-selecting the same tool does not clobber the memory of the last genuinely
 * different one.
 */
class SketchToolState(
    current: SketchTool = SketchTool.MOVE,
    previous: SketchTool = SketchTool.SELECT,
) {
    var current: SketchTool = current
        private set

    var previous: SketchTool = previous
        private set

    fun select(tool: SketchTool) {
        if (previous != current && !current.isModal) {
            previous = current
        }
        current = tool
    }

    /**
     * Call on touch-up. A modal tool other than [SketchTool.MOVE_CROSS_SECTION] (which manages its
     * own exit at the end of its drag) reverts: to [previous] if that is a different tool, else to
     * [SketchTool.MOVE].
     *
     * @return true if the touch was consumed by the revert.
     */
    fun finishGestureIfModal(): Boolean {
        if (!current.isModal || current == SketchTool.MOVE_CROSS_SECTION) {
            return false
        }
        select(if (previous != current) previous else SketchTool.MOVE)
        return true
    }
}
