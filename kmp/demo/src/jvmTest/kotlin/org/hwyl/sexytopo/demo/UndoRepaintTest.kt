package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Undo takes the line off the *screen* on the next frame, not just out of the model.
 *
 * Reported from the field as undo "not removing the last line drawn but deleting it a few seconds
 * later". `SketchEditor` is a plain object, so nothing about undoing is observable to Compose on
 * its own: the toolbar bumps the app's revision, the canvas rebuilds its scene from that, and the
 * draw pass has to pick the new scene up. Every link in that chain is a place the picture could
 * lag the model, so this checks the picture — the pixels along the stroke, before, during and
 * after — rather than the model the other tests already cover.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UndoRepaintTest {

    private val width = 600
    private val height = 600

    /** The row the test stroke is drawn along, and the span of it that is sampled. */
    private val strokeY = 500
    private val sampledX = 60..180

    private fun ImageComposeScene.touch(type: PointerEventType, x: Float, y: Float) {
        sendPointerEvent(
            eventType = type,
            position = Offset(x, y),
            type = PointerType.Touch,
            buttons =
                if (type == PointerEventType.Release) PointerButtons() else PointerButtons(isPrimaryPressed = true),
        )
    }

    private fun Image.toBitmap(): BufferedImage {
        val png = encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        return ImageIO.read(ByteArrayInputStream(png.bytes))
    }

    /** The colours along the stroke's row, sampled a pixel above and below it too for its width. */
    private fun ImageComposeScene.strokeRow(): IntArray {
        val bitmap = render().toBitmap()
        return (strokeY - 2..strokeY + 2)
            .flatMap { y -> sampledX.map { x -> bitmap.getRGB(x, y) } }
            .toIntArray()
    }

    @Test
    fun undoTakesTheStrokeOffTheScreenOnTheNextFrame() {
        val survey = Survey("Undo")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val sketch = survey.getSketch(Projection2D.PLAN)
        val editor = SketchEditor(sketch)
        val revision = mutableStateOf(0)

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
                    revision = revision.value,
                    onSketchEdit = { revision.value++ },
                )
            }
        try {
            // Blank paper along the row, before anything is drawn there.
            val blank = scene.strokeRow()

            scene.touch(PointerEventType.Press, sampledX.first.toFloat(), strokeY.toFloat())
            scene.touch(PointerEventType.Move, 120f, strokeY.toFloat())
            scene.touch(PointerEventType.Move, sampledX.last.toFloat(), strokeY.toFloat())
            scene.touch(PointerEventType.Release, sampledX.last.toFloat(), strokeY.toFloat())
            val inked = scene.strokeRow()
            assertFalse(blank.contentEquals(inked), "the stroke never appeared on screen")
            assertTrue(editor.canUndo)

            // What the toolbar's undo button does: undo, then tell the app the sketch changed.
            assertTrue(editor.undo())
            revision.value++
            // What the runtime does after every event on a real platform; the headless scene has
            // no event loop to do it for us.
            Snapshot.sendApplyNotifications()
            assertTrue(scene.hasInvalidations(), "undo did not ask for a frame at all")

            // The very next frame, not one some later event happens to cause.
            assertContentEquals(blank, scene.strokeRow(), "the undone stroke is still on screen")
        } finally {
            scene.close()
        }
    }
}
