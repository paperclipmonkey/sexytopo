package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.demo.resources.eraser
import org.hwyl.sexytopo.demo.resources.move
import org.hwyl.sexytopo.demo.resources.pencil
import org.hwyl.sexytopo.demo.resources.redo
import org.hwyl.sexytopo.demo.resources.undo
import org.hwyl.sexytopo.demo.resources.zoom_in
import org.hwyl.sexytopo.demo.resources.zoom_out
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.jetbrains.compose.resources.painterResource

/**
 * Drawing the shape of the passage, standing at one station.
 *
 * It is the same canvas, tools, viewport and undo stack as the plan, over a different world —
 * `SurveyScene.forCrossSection` is twenty lines, and everything else comes free from the shared
 * sketch engine.
 */
/**
 * What the cross-section editor's canvas is drawn with.
 *
 * A plain function rather than inlined into the composable, so it can be tested — a `@Composable`
 * cannot be called from a `jvmTest`.
 */
internal fun crossSectionDisplayOptions(darkMode: Boolean, preferences: AppPreferences) =
    DisplayOptions(
        showSplays = true,
        showSketch = true,
        // One station, at the origin, whose name is already in the title bar.
        showStationLabels = false,
        showGrid = true,
        darkMode = darkMode,
        hotCorners = preferences.hotCorners,
        twoFingerMove = preferences.twoFingerMove,
        pinchToZoom = preferences.pinchToZoom,
        style = preferences.sketchStyle,
        deletePathFragments = preferences.deletePathFragments,
    )

@Composable
fun CrossSectionEditor(
    survey: Survey,
    detail: CrossSectionDetail,
    darkMode: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    preferences: AppPreferences = AppPreferences.DEFAULT,
) {
    // Keyed on the detail so reopening a different section starts again rather than carrying
    // the last one's undo stack across.
    val working = remember(detail) { detail.sketch.copy() }
    val editor = remember(detail) { SketchEditor(working) }
    val canvas = remember(detail) { CanvasController() }
    var tool by remember(detail) { mutableStateOf(SketchTool.DRAW) }
    var revision by remember(detail) { mutableStateOf(0) }
    // Not persisted, unlike the main sketch's own brush colour: a cross-section is a quick aside,
    // and starting each one from whatever it was last drawn in keeps that choice from leaking
    // into every station's plan.
    var brushColour by remember(detail) { mutableStateOf(working.activeColour) }

    val scene = remember(detail, revision) { SurveyScene.forCrossSection(detail, working) }

    fun pickColour(colour: Colour) {
        brushColour = colour
        editor.activeColour = colour
        // As the main toolbar does: choosing a colour is choosing to draw with it.
        if (!tool.usesColour) tool = SketchTool.DRAW
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (darkMode) {
                        SexyTopoColours.panelBackgroundNight
                    } else {
                        SexyTopoColours.panelBackground
                    },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel", color = SexyTopoColours.onPanel) }
            Spacer(Modifier.weight(1f))
            Text(
                "Passage at ${detail.station.name}",
                style = MaterialTheme.typography.titleSmall,
                color = SexyTopoColours.onPanel,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    commitCrossSectionSketch(survey, detail, working)
                    onDone()
                },
            ) { Text("Done", color = SexyTopoColours.onPanel) }
        }

        SurveyCanvas(
            survey = survey,
            projection = Projection2D.CROSS_SECTION,
            options = crossSectionDisplayOptions(darkMode, preferences),
            editor = editor,
            canvas = canvas,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            tool = tool,
            revision = revision,
            onSketchEdit = { revision++ },
            sceneOverride = scene,
        )

        // `CrossSectionActivity.disableUnsupportedTools` hides only *Select* here — the Android
        // app's own cross-section editor keeps the full colour row this port had never wired up.
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (darkMode) {
                        SexyTopoColours.panelBackgroundNight
                    } else {
                        SexyTopoColours.panelBackground
                    },
                ),
        ) {
            for (colour in BRUSH_COLOURS) {
                ColourButton(
                    colour = colour,
                    selected = brushColour == colour,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                ) {
                    pickColour(colour)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (darkMode) {
                        SexyTopoColours.panelBackgroundNight
                    } else {
                        SexyTopoColours.panelBackground
                    },
                ),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (option in CROSS_SECTION_TOOLS) {
                ToolbarButton(
                    painter =
                        painterResource(
                            when (option) {
                                SketchTool.MOVE -> Res.drawable.move
                                SketchTool.ERASE -> Res.drawable.eraser
                                else -> Res.drawable.pencil
                            },
                        ),
                    description = option.label,
                    modifier = Modifier.weight(1f),
                    selected = tool == option,
                    onClick = { tool = option },
                )
            }
            ToolbarButton(
                painter = painterResource(Res.drawable.undo),
                description = "Undo",
                modifier = Modifier.weight(1f),
                enabled = editor.canUndo,
                onClick = {
                    editor.undo()
                    revision++
                },
            )
            ToolbarButton(
                painter = painterResource(Res.drawable.redo),
                description = "Redo",
                modifier = Modifier.weight(1f),
                enabled = editor.canRedo,
                onClick = {
                    editor.redo()
                    revision++
                },
            )
            ToolbarButton(
                painter = painterResource(Res.drawable.zoom_in),
                description = "Zoom in",
                modifier = Modifier.weight(1f),
                onClick = { canvas.zoomIn() },
            )
            ToolbarButton(
                painter = painterResource(Res.drawable.zoom_out),
                description = "Zoom out",
                modifier = Modifier.weight(1f),
                onClick = { canvas.zoomOut() },
            )
        }
    }
}

/** `CrossSectionActivity.disableUnsupportedTools` hides *Select*: there is no other station here. */
private val CROSS_SECTION_TOOLS =
    listOf(SketchTool.MOVE, SketchTool.DRAW, SketchTool.ERASE)

/**
 * Write the working copy back into the live cross-section, mutating the detail in place rather
 * than swapping it: the plan's undo and redo stacks hold references to this object, and
 * replacing it would leave them pointing at something no longer in the sketch.
 */
internal fun commitCrossSectionSketch(
    survey: Survey,
    detail: CrossSectionDetail,
    working: Sketch,
) {
    val committed = Sketch()
    committed.pathDetails = working.pathDetails.toMutableList()
    detail.sketch = committed
    survey.isSaved = false
}
