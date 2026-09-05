package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A stroke that another detector abandons must leave nothing behind.
 *
 * A second finger landing mid-stroke turns the gesture into a pan, and `detectModalMove` abandons
 * the half-drawn line so the surveyor is not left with a mark they never meant to make. The
 * drawing loop then leaves on the consumed change - but it had `started`, and its finishing code
 * ran regardless. With snap-to-lines on, snapping the last point first *started* a fresh stroke at
 * the snap point (`extendPath` begins one when none is active) and `finishPath` committed it: a
 * dot on the end of a wall, from a pan made to get away from that wall, sitting in the undo
 * history as if somebody had drawn it.
 */
// InternalComposeUiApi: `ComposeScenePointer` is the only way to put two fingers on a headless
// scene, and a two-finger gesture is the whole subject here.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class StrokeAbandonmentTest {

    private val width = 600
    private val height = 600

    private fun finger(id: Int, x: Float, y: Float, pressed: Boolean = true) =
        ComposeScenePointer(PointerId(id.toLong()), Offset(x, y), pressed, PointerType.Touch)

    private fun ImageComposeScene.fingers(type: PointerEventType, vararg pointers: ComposeScenePointer) {
        sendPointerEvent(eventType = type, pointers = pointers.toList())
    }

    @Test
    fun aSecondFingerAbandoningASnappedStrokeLeavesNoDotBehind() {
        val survey = Survey("Pan")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val sketch = survey.getSketch(Projection2D.PLAN)
        val editor = SketchEditor(sketch)

        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.PLAN,
                    options = DisplayOptions(showGrid = false, snapToLines = true, twoFingerMove = true),
                    editor = editor,
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.DRAW,
                )
            }
        try {
            scene.render()

            // A wall to snap to, ending at (180, 500).
            scene.fingers(PointerEventType.Press, finger(0, 60f, 500f))
            scene.fingers(PointerEventType.Move, finger(0, 120f, 500f))
            scene.fingers(PointerEventType.Move, finger(0, 180f, 500f))
            scene.fingers(PointerEventType.Release, finger(0, 180f, 500f, pressed = false))
            scene.render()
            assertEquals(1, sketch.pathDetails.size, "the wall was not drawn")

            // A second stroke, brought to within snapping reach of the wall's end...
            scene.fingers(PointerEventType.Press, finger(0, 300f, 350f))
            scene.fingers(PointerEventType.Move, finger(0, 250f, 420f))
            scene.fingers(PointerEventType.Move, finger(0, 195f, 500f))
            assertNotNull(editor.activePath, "the second stroke never started")

            // ...when a second finger lands and the gesture becomes a pan.
            scene.fingers(PointerEventType.Press, finger(0, 195f, 500f), finger(1, 400f, 400f))
            scene.fingers(PointerEventType.Move, finger(0, 205f, 510f), finger(1, 410f, 410f))
            scene.fingers(PointerEventType.Release, finger(0, 205f, 510f), finger(1, 410f, 410f, pressed = false))
            scene.fingers(PointerEventType.Release, finger(0, 205f, 510f, pressed = false))
            scene.render()

            assertNull(editor.activePath, "the abandoned stroke is still active")
            assertEquals(1, sketch.pathDetails.size, "the pan left a mark on the sketch")
            assertEquals(
                1,
                generateSequence { if (editor.undo()) Unit else null }.count(),
                "the pan left something in the undo history",
            )
        } finally {
            scene.close()
        }
    }
}
