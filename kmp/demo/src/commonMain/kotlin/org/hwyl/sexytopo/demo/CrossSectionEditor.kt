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
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.jetbrains.compose.resources.painterResource

/**
 * Drawing the shape of the passage, standing at one station.
 *
 * Ported from `CrossSectionActivity` and `CrossSectionView`. A cross-section on the plan is a star
 * of splays — the wall, floor and roof shots taken at that station — and a star of rays is not a
 * passage. What makes it one is the outline a surveyor draws round it, joining the splay ends into
 * a wall and closing the gaps where nobody took a shot. That outline is the sub-sketch every
 * [CrossSectionDetail] has carried since the model was ported, which the SVG exporter already draws
 * and the native format already round-trips; this is the thing that could not, until now, put
 * anything into it.
 *
 * It is the same canvas, the same tools, the same viewport and the same undo stack as the plan —
 * over a different world. That reuse is the interesting part: `SurveyScene.forCrossSection` is
 * twenty lines, and everything else came for free from the shared sketch engine.
 *
 * ## Committing
 *
 * Drawing happens in a *copy* of the sub-sketch, so Cancel really does leave the original alone.
 * Done writes the copy's paths back into the live detail in place, as the Java does, rather than
 * swapping the detail for a new one — see [commitCrossSectionSketch] for why that matters to the
 * plan's undo history.
 *
 * Only paths are kept, exactly as `CrossSectionActivity.commitAndFinish` does. A cross-section can
 * hold symbols and labels in the model, and the Android app drops them on commit; matching that is
 * better than silently storing something its own editor would throw away.
 */
@Composable
fun CrossSectionEditor(
    survey: Survey,
    detail: CrossSectionDetail,
    darkMode: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The working copy, and the editor over it. Keyed on the detail so reopening a different
    // section starts again rather than carrying the last one's undo stack across.
    val working = remember(detail) { detail.sketch.copy() }
    val editor = remember(detail) { SketchEditor(working) }
    val canvas = remember(detail) { CanvasController() }
    var tool by remember(detail) { mutableStateOf(SketchTool.DRAW) }
    var revision by remember(detail) { mutableStateOf(0) }

    val scene = remember(detail, revision) { SurveyScene.forCrossSection(detail, working) }

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
            options =
                DisplayOptions(
                    // Splays are the whole subject here, so the plan's toggle does not apply.
                    showSplays = true,
                    showSketch = true,
                    // One station, at the origin, whose name is already in the title bar.
                    showStationLabels = false,
                    showGrid = true,
                    darkMode = darkMode,
                ),
            editor = editor,
            canvas = canvas,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            tool = tool,
            revision = revision,
            onSketchEdit = { revision++ },
            sceneOverride = scene,
        )

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

/**
 * The tools this editor offers.
 *
 * `CrossSectionActivity.disableUnsupportedTools` hides *Select* — there is no station to select
 * here but the one at the origin — and this port hides the rest of the plan's cross-section tools
 * for the reason its own `onNewCrossSection` gives: cross-sections do not nest.
 */
private val CROSS_SECTION_TOOLS =
    listOf(SketchTool.MOVE, SketchTool.DRAW, SketchTool.ERASE)

/**
 * Write the working copy back into the live cross-section.
 *
 * Mutating the detail in place rather than swapping in a new one is `commitAndFinish`'s own choice,
 * and its comment says why: the plan's undo and redo stacks hold references to this object, so
 * replacing it would leave them pointing at something no longer in the sketch — and undoing past
 * the section's creation would then leave a duplicate behind. The editor has its own undo, so
 * committing is deliberately not a step on the plan's.
 *
 * Only the paths survive, as in the original.
 */
internal fun commitCrossSectionSketch(
    survey: Survey,
    detail: CrossSectionDetail,
    working: Sketch,
) {
    val committed = Sketch()
    committed.pathDetails = working.pathDetails.toMutableList()
    detail.sketch = committed
    // The plan is what holds this section, so the plan is what has become unsaved. The demo saves
    // on every change rather than on this flag, but the flag is what the Java sets and what a
    // future save-on-close would read.
    survey.isSaved = false
}
