package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.demo.resources.eraser
import org.hwyl.sexytopo.demo.resources.move
import org.hwyl.sexytopo.demo.resources.pencil
import org.hwyl.sexytopo.demo.resources.select
import org.hwyl.sexytopo.demo.resources.settings
import org.hwyl.sexytopo.demo.resources.text
import org.hwyl.sexytopo.demo.resources.redo
import org.hwyl.sexytopo.demo.resources.undo
import org.hwyl.sexytopo.demo.resources.zoom_in
import org.hwyl.sexytopo.demo.resources.zoom_out
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
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
    var symbolStripOpen by remember(detail) { mutableStateOf(false) }
    var symbolStripEverOpened by remember(detail) { mutableStateOf(false) }
    var symbol by remember(detail) { mutableStateOf(preferences.symbol) }
    // Not persisted, unlike the main sketch's own brush colour: a cross-section is a quick aside,
    // and starting each one from whatever it was last drawn in keeps that choice from leaking
    // into every station's plan.
    var brushColour by remember(detail) { mutableStateOf(working.activeColour) }
    var menuOpen by remember(detail) { mutableStateOf(false) }

    val scene =
        remember(detail, revision) { SurveyScene.forCrossSection(detail, working, survey) }

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
            // `title_activity_cross_section`, which is what the activity's own label is, plus
            // the two `showAsAction="always"` icons of `res/menu/cross_section.xml` on the right
            // where the app bar puts them — not a Cancel text button on the far left.
            Text(
                Strings.titleCrossSection,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = SexyTopoColours.onPanel,
            )
            // Named, because an icon drawn from path data has nothing for a screen reader to
            // read out and nothing for a check to ask for. `cross_section.xml` gives both a title
            // even though it shows neither; these are those titles.
            Box(
                Modifier
                    .semantics { contentDescription = Strings.actionCrossSectionCancel }
                    .testTag("cross-section-cancel")
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                CancelIcon(SexyTopoColours.onPanel)
            }
            Box(
                Modifier
                    .semantics { contentDescription = Strings.actionCrossSectionDone }
                    .testTag("cross-section-done")
                    .clickable {
                        commitCrossSectionSketch(survey, detail, working)
                        onDone()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                DoneIcon(SexyTopoColours.onPanel)
            }
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

        // `activity_cross_section.xml` is `activity_graph.xml`'s toolbar, button for button: the
        // same nine columns by two rows, the same artwork, and `disableUnsupportedTools` greying
        // out exactly one of them — *Select*, since there is no other station here to select.
        val panel =
            if (darkMode) {
                SexyTopoColours.panelBackgroundNight
            } else {
                SexyTopoColours.panelBackground
            }

        Column(Modifier.fillMaxWidth().background(panel)) {
            if (symbolStripOpen) {
                CrossSectionSymbolStrip(
                    tool = tool,
                    symbol = symbol,
                    darkMode = darkMode,
                    onTool = { tool = it },
                    onSymbol = { symbol = it },
                    onClose = { symbolStripOpen = false },
                )
            }

            Row(Modifier.fillMaxWidth()) {
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
                ToolbarButton(
                    painter = painterResource(Res.drawable.zoom_in),
                    description = Strings.toolbarZoomIn,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    onClick = { canvas.zoomIn() },
                )
            }

            Row(Modifier.fillMaxWidth()) {
                ToolbarButton(
                    painter = painterResource(Res.drawable.move),
                    description = Strings.toolbarMove,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    selected = tool == SketchTool.MOVE,
                    onClick = { tool = SketchTool.MOVE },
                )
                ToolbarButton(
                    painter = painterResource(Res.drawable.pencil),
                    description = Strings.toolbarDraw,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    selected = tool == SketchTool.DRAW,
                    onClick = { tool = SketchTool.DRAW },
                )
                ToolbarButton(
                    painter = painterResource(Res.drawable.text),
                    description = Strings.toolbarSymbol,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    selected = tool == SketchTool.SYMBOL || tool == SketchTool.TEXT,
                    onClick = {
                        val wasAlreadyInSymbolMode =
                            tool == SketchTool.SYMBOL || tool == SketchTool.TEXT
                        if (tool != SketchTool.TEXT) tool = SketchTool.SYMBOL
                        if (!symbolStripEverOpened || wasAlreadyInSymbolMode) {
                            symbolStripEverOpened = true
                            symbolStripOpen = !symbolStripOpen
                        }
                    },
                )
                ToolbarButton(
                    painter = painterResource(Res.drawable.eraser),
                    description = Strings.toolbarEraser,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    selected = tool == SketchTool.ERASE,
                    onClick = { tool = SketchTool.ERASE },
                )
                // Drawn and disabled rather than left out, as `disableUnsupportedTools` leaves it.
                ToolbarButton(
                    painter = painterResource(Res.drawable.select),
                    description = Strings.toolbarSelector,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    onClick = {},
                )
                Box(Modifier.weight(1f)) {
                    ToolbarButton(
                        painter = painterResource(Res.drawable.settings),
                        description = Strings.toolbarSettings,
                        darkMode = darkMode,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { menuOpen = true },
                    )
                    CrossSectionMenu(
                        expanded = menuOpen,
                        onCentre = {
                            canvas.refit()
                            menuOpen = false
                        },
                        onDismiss = { menuOpen = false },
                    )
                }
                ToolbarButton(
                    painter = painterResource(Res.drawable.undo),
                    description = Strings.toolbarUndo,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    enabled = editor.canUndo,
                    onClick = {
                        editor.undo()
                        revision++
                    },
                )
                ToolbarButton(
                    painter = painterResource(Res.drawable.redo),
                    description = Strings.toolbarRedo,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    enabled = editor.canRedo,
                    onClick = {
                        editor.redo()
                        revision++
                    },
                )
                ToolbarButton(
                    painter = painterResource(Res.drawable.zoom_out),
                    description = Strings.toolbarZoomOut,
                    darkMode = darkMode,
                    modifier = Modifier.weight(1f),
                    onClick = { canvas.zoomOut() },
                )
            }
        }
    }
}

/**
 * The same strip the plan's toolbar carries, over this editor's own tool state.
 *
 * Its own composable rather than a shared one because the plan's reads and writes [DemoState] —
 * and the whole point of this editor is that its choices are a copy, discarded on *Cancel*.
 */
@Composable
private fun CrossSectionSymbolStrip(
    tool: SketchTool,
    symbol: Symbol,
    darkMode: Boolean,
    onTool: (SketchTool) -> Unit,
    onSymbol: (Symbol) -> Unit,
    onClose: () -> Unit,
) {
    val size = SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp
    val highlight =
        if (darkMode) SexyTopoColours.buttonHighlightNight else SexyTopoColours.buttonHighlight

    Row(
        Modifier
            .fillMaxWidth()
            .background(SexyTopoColours.symbolToolbarBackground)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(size)
                .then(if (tool == SketchTool.TEXT) Modifier.background(highlight) else Modifier)
                .clickable { onTool(SketchTool.TEXT) }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.text),
                contentDescription = Strings.toolbarText,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )
        }
        for (option in Symbol.entries) {
            val lit = tool == SketchTool.SYMBOL && symbol == option
            Box(
                Modifier
                    .size(size)
                    .then(if (lit) Modifier.background(highlight) else Modifier)
                    .clickable {
                        onSymbol(option)
                        onTool(SketchTool.SYMBOL)
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                SymbolGlyph(option, SexyTopoColours.onPanel, size - 8.dp)
            }
        }
        Box(
            Modifier.size(size).clickable(onClick = onClose).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", style = MaterialTheme.typography.titleMedium, color = SexyTopoColours.onPanel)
        }
    }
}

/**
 * `buttonMenu` in the cross-section editor.
 *
 * `drawing.xml` is inflated here as it is on the plan, but almost nothing on it applies: there is
 * no last leg to undo, no splays to hide and no station labels to show, since the whole world is
 * one station and its wall shots. What is left is the one action that means something.
 */
@Composable
private fun CrossSectionMenu(expanded: Boolean, onCentre: () -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(Strings.sketchMenuCentreView) }, onClick = onCentre)
    }
}

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
