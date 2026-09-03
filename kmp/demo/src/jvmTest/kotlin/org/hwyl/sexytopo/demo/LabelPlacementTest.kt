package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Labels are what a surveyor writes on the drawing rather than in the numbers, and they have to
 * behave like part of the drawing: undoable, erasable, and saved with everything else.
 */
class LabelPlacementTest {

    @Test
    fun aLabelIsAddedWhereItWasPlaced() {
        val editor = SketchEditor()

        editor.addText(Coord2D(3f, -4f), "Sump", size = 0.5f)

        val label = editor.sketch.textDetails.single()
        assertEquals("Sump", label.text)
        assertEquals(Coord2D(3f, -4f), label.position)
        assertEquals(0.5f, label.size)
    }

    /** One undo step, like a stroke: the toolbar's undo has to reach it. */
    @Test
    fun aLabelCanBeUndoneAndRedone() {
        val editor = SketchEditor()
        editor.addText(Coord2D.ORIGIN, "Boulder choke", size = 0.5f)

        assertTrue(editor.canUndo)
        editor.undo()
        assertTrue(editor.sketch.textDetails.isEmpty())

        editor.redo()
        assertEquals("Boulder choke", editor.sketch.textDetails.single().text)
    }

    /**
     * The size handed to [SketchEditor.addText] is in *survey* metres, converted from screen sp by
     * the canvas, so a label keeps its size in the cave rather than on the screen it was placed on.
     * Two labels placed at different zooms should therefore differ in stored size.
     */
    @Test
    fun theStoredSizeIsInSurveyUnits() {
        val editor = SketchEditor()

        editor.addText(Coord2D.ORIGIN, "close up", size = 0.1f)
        editor.addText(Coord2D(10f, 0f), "zoomed out", size = 2f)

        assertEquals(listOf(0.1f, 2f), editor.sketch.textDetails.map { it.size })
    }
}
