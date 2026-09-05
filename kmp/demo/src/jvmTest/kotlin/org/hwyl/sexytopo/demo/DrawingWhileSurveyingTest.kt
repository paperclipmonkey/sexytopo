package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drawing and surveying happen at the same time.
 *
 * One person holds the instrument and one draws, and the drawer does not stop for a shot. So a
 * reading has to be able to land in the middle of a stroke without disturbing it: the stroke goes
 * on under the pen, is committed when the pen lifts, and can be undone like any other. Reported
 * from an iPad in the field, where the line being drawn vanished every time a shot came in — and
 * on the build before that one, stayed on the sketch but could not be undone.
 *
 * The canvas is driven through `ImageComposeScene`'s own pointer events, the same headless
 * Compose the rendering tests use, so this exercises the real gesture loops rather than the
 * editor underneath them. A reading is what the app does when one arrives: the survey grows and
 * the revision the canvas is composed with goes up.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DrawingWhileSurveyingTest {

    private val width = 600
    private val height = 600

    private val pressed = PointerButtons(isPrimaryPressed = true)
    private val lifted = PointerButtons()

    private fun ImageComposeScene.touch(type: PointerEventType, x: Float, y: Float) {
        sendPointerEvent(
            eventType = type,
            position = Offset(x, y),
            type = PointerType.Touch,
            buttons = if (type == PointerEventType.Release) lifted else pressed,
        )
    }

    private fun assertNear(expected: Coord2D, actual: Coord2D, what: String) {
        assertTrue(
            abs(expected.x - actual.x) < 0.001f && abs(expected.y - actual.y) < 0.001f,
            "$what: expected $expected, got $actual",
        )
    }

    @Test
    fun aReadingArrivingMidStrokeLeavesTheStrokeUnderThePen() {
        val survey = Survey("Live")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val sketch = survey.getSketch(Projection2D.PLAN)
        val editor = SketchEditor(sketch)
        val canvas = CanvasController()
        val revision = mutableStateOf(0)

        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(showGrid = false),
                    editor = editor,
                    canvas = canvas,
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.DRAW,
                    revision = revision.value,
                )
            }
        try {
            // The first frame lays the canvas out and fits the view to the one leg.
            scene.render()

            // Pen down in the bottom-left, well away from the station, and past the touch slop.
            scene.touch(PointerEventType.Press, 60f, 500f)
            scene.touch(PointerEventType.Move, 120f, 500f)
            scene.render()
            assertNotNull(editor.activePath, "the stroke never started")
            val start = canvas.viewport.toSurvey(Coord2D(60f, 500f))
            val offsetBefore = canvas.viewport.offset
            val zoomBefore = canvas.viewport.pixelsPerMetre

            // A shot lands: the survey grows, and the app bumps the revision the canvas is
            // composed with, exactly as `SurveySession` and `DemoState` do.
            SurveyBuilder.updateWithNewStation(survey, Leg(6f, 90f, 0f))
            revision.value++
            scene.render()

            assertNotNull(editor.activePath, "the reading took the stroke away from under the pen")
            assertEquals(offsetBefore, canvas.viewport.offset, "the paper moved under the pen")
            assertEquals(zoomBefore, canvas.viewport.pixelsPerMetre, "the paper zoomed under the pen")

            // The pen carries on, and lifts.
            scene.touch(PointerEventType.Move, 180f, 500f)
            val end = canvas.viewport.toSurvey(Coord2D(180f, 500f))
            scene.touch(PointerEventType.Release, 180f, 500f)
            scene.render()

            assertNull(editor.activePath, "the stroke was not finished when the pen lifted")
            assertEquals(1, sketch.pathDetails.size, "expected one committed stroke")
            val path = sketch.pathDetails.single().path
            assertNear(start, path.first(), "the stroke's start")
            assertNear(end, path.last(), "the stroke's end")

            // And it is one undo step, like any other stroke.
            assertTrue(editor.canUndo, "the stroke is not in the undo history")
            assertTrue(editor.undo())
            assertTrue(sketch.pathDetails.isEmpty(), "undo did not take the stroke back")

            // Once the pen was up, the view was free to re-frame the grown survey.
            assertTrue(
                offsetBefore != canvas.viewport.offset || zoomBefore != canvas.viewport.pixelsPerMetre,
                "the view never caught up with the new leg after the pen lifted",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun aStrokeFinishedNormallyIsStillOneUndoStep() {
        // The control: nothing arrives, and the outcome must be the same.
        val survey = Survey("Quiet")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val sketch = survey.getSketch(Projection2D.PLAN)
        val editor = SketchEditor(sketch)

        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(showGrid = false),
                    editor = editor,
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.DRAW,
                )
            }
        try {
            scene.render()
            scene.touch(PointerEventType.Press, 60f, 500f)
            scene.touch(PointerEventType.Move, 120f, 500f)
            scene.touch(PointerEventType.Move, 180f, 500f)
            scene.touch(PointerEventType.Release, 180f, 500f)
            scene.render()

            assertEquals(1, sketch.pathDetails.size)
            assertTrue(editor.canUndo)
        } finally {
            scene.close()
        }
    }
}
