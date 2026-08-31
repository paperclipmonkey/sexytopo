package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.demo.resources.black
import org.hwyl.sexytopo.demo.resources.blue
import org.hwyl.sexytopo.demo.resources.brown
import org.hwyl.sexytopo.demo.resources.eraser
import org.hwyl.sexytopo.demo.resources.green
import org.hwyl.sexytopo.demo.resources.grey
import org.hwyl.sexytopo.demo.resources.move
import org.hwyl.sexytopo.demo.resources.orange
import org.hwyl.sexytopo.demo.resources.pencil
import org.hwyl.sexytopo.demo.resources.purple
import org.hwyl.sexytopo.demo.resources.red
import org.hwyl.sexytopo.demo.resources.redo
import org.hwyl.sexytopo.demo.resources.select
import org.hwyl.sexytopo.demo.resources.settings
import org.hwyl.sexytopo.demo.resources.text
import org.hwyl.sexytopo.demo.resources.undo
import org.hwyl.sexytopo.demo.resources.zoom_in
import org.hwyl.sexytopo.demo.resources.zoom_out
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.jetbrains.compose.resources.painterResource

/**
 * SexyTopo's sketch toolbar: nine columns by two rows of icon buttons on a green panel.
 *
 * This is a deliberate copy of `activity_graph.xml`, down to the order of the buttons and the
 * artwork on them — the icons are the app's own PNGs, carried across as Compose resources. A
 * surveyor who uses SexyTopo should be able to pick up an iPhone running this and already know
 * where everything is; a toolbar redesigned to somebody's taste would have proved nothing except
 * that Compose can draw buttons.
 *
 * Row one is the eight brush colours and zoom in. Row two is the tools — move, draw, symbol, erase,
 * select — then the drawing menu, undo, redo and zoom out.
 *
 * One of those buttons is drawn but disabled: the symbol tool, which the shared model supports
 * (`SketchTool.SYMBOL`) and this demo has no palette to drive - the app's symbol artwork is SVG in
 * its assets rather than the PNGs the toolbar uses, so it is not carried across. Showing it greyed
 * rather than leaving a gap keeps the layout honest in both directions: the toolbar is the app's,
 * and what the demo cannot do is visible rather than quietly missing.
 */
@Composable
fun SketchToolbar(
    state: DemoState,
    editor: SketchEditor,
    canvas: CanvasController,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Read the revision so this toolbar recomposes when the sketch changes. SketchEditor is a
    // plain object, so `editor.canUndo` is not something Compose can observe: without this line
    // the undo button stays greyed out after the first stroke, because nothing else the toolbar
    // reads has changed and it is never asked to draw itself again.
    @Suppress("UNUSED_VARIABLE")
    val revision = state.revision

    val panel =
        if (state.darkMode) {
            SexyTopoColours.panelBackgroundNight
        } else {
            SexyTopoColours.panelBackground
        }

    Column(modifier.fillMaxWidth().background(panel)) {
        Row(Modifier.fillMaxWidth()) {
            for (colour in BRUSH_COLOURS) {
                ColourButton(
                    colour = colour,
                    selected = state.brushColour == colour,
                    darkMode = state.darkMode,
                    modifier = Modifier.weight(1f),
                ) {
                    state.pickColour(colour, editor)
                }
            }
            ToolbarButton(
                painter = painterResource(Res.drawable.zoom_in),
                description = "Zoom in",
                modifier = Modifier.weight(1f),
                onClick = { canvas.zoomIn() },
            )
        }

        Row(Modifier.fillMaxWidth()) {
            ToolButton(state, SketchTool.MOVE, painterResource(Res.drawable.move), "Move")
            ToolButton(state, SketchTool.DRAW, painterResource(Res.drawable.pencil), "Draw")
            // The app's own button here is the symbol palette, whose artwork is SVG this port
            // does not carry. The text tool is the half of it that needs no artwork, and is what a
            // surveyor reaches for most often anyway: "sump", "boulder choke", "continues".
            ToolButton(state, SketchTool.TEXT, painterResource(Res.drawable.text), "Label")
            ToolButton(state, SketchTool.ERASE, painterResource(Res.drawable.eraser), "Erase")
            ToolButton(state, SketchTool.SELECT, painterResource(Res.drawable.select), "Select")

            Box(Modifier.weight(1f)) {
                ToolbarButton(
                    painter = painterResource(Res.drawable.settings),
                    description = "Drawing menu",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { menuOpen = true },
                )
                DrawingMenu(state, canvas, menuOpen) { menuOpen = false }
            }

            ToolbarButton(
                painter = painterResource(Res.drawable.undo),
                description = "Undo",
                modifier = Modifier.weight(1f),
                enabled = editor.canUndo,
                onClick = { state.undo(editor) },
            )
            ToolbarButton(
                painter = painterResource(Res.drawable.redo),
                description = "Redo",
                modifier = Modifier.weight(1f),
                enabled = editor.canRedo,
                onClick = { state.redo(editor) },
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
 * The menu behind the settings button, from `res/menu/drawing.xml`.
 *
 * ## Why it is two things and not one
 *
 * `drawing.xml` is three groups: actions, behaviour toggles, display toggles. This port carries all
 * three *and* the items it reaches from here rather than from a nine-column toolbar with no room
 * left — the symbol palette, the three cross-section gestures, finding a station — which took the
 * single list to eighteen rows. Eighteen rows is a popup the height of a phone: it scrolls on a
 * small one, and a menu you have to scroll to find "show grid" in is one you stop opening.
 *
 * So the twelve toggles moved into a dialog of their own, under the app's own two group names, and
 * what is left here is the seven things that *do* something when tapped. That is the split
 * `drawing.xml` already draws; this port only had to stop ignoring it.
 *
 * `buttonShowConnections` is the one item of that menu still absent, because a neighbouring survey
 * is reached by an absolute `content://` URI — a format decision for upstream rather than a porting
 * one — and a switch that does nothing is worse than no switch.
 */
@Composable
private fun DrawingMenu(
    state: DemoState,
    canvas: CanvasController,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    var choosingSymbol by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var deletingLastLeg by remember { mutableStateOf(false) }
    var adjustingDisplay by remember { mutableStateOf(false) }

    if (adjustingDisplay) {
        DrawingOptionsDialog(state) { adjustingDisplay = false }
    }

    if (finding) {
        FindStationDialog(
            survey = state.survey,
            onDismiss = { finding = false },
            onGo = { station ->
                stationPositionIn(state.survey, state.projection, station)
                    ?.let(canvas::centreOn)
                finding = false
            },
        )
    }

    if (deletingLastLeg) {
        DeleteLastLegDialog(
            survey = state.survey,
            onDismiss = { deletingLastLeg = false },
            onDelete = {
                state.survey.undoAddLeg()
                state.noteSketchEdited()
                deletingLastLeg = false
            },
        )
    }

    if (choosingSymbol) {
        SymbolPaletteDialog(
            onDismiss = { choosingSymbol = false },
            onChosen = { chosen ->
                state.chooseSymbol(chosen)
                state.chooseTool(SketchTool.SYMBOL)
                choosingSymbol = false
            },
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Centre view") },
            onClick = {
                canvas.refit()
                onDismiss()
            },
        )
        // A menu item rather than a tenth button in a nine-column toolbar — and the app puts it in
        // a menu too, on the station's own long-press menu — which this port now has as well, in
        // `StationMenu.kt`, though the symbol palette is not on it: a symbol is placed anywhere,
        // not at a station.
        DropdownMenuItem(
            text = { Text("Symbol…") },
            leadingIcon = { CheckDot(state.tool == SketchTool.SYMBOL) },
            onClick = {
                choosingSymbol = true
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Cross-section at a station") },
            leadingIcon = {
                CheckDot(state.tool == SketchTool.POSITION_CROSS_SECTION)
            },
            onClick = {
                state.chooseTool(SketchTool.POSITION_CROSS_SECTION)
                onDismiss()
            },
        )
        // Both halves of "the app's guess was not quite right". The bearing is guessed by
        // CrossSectioner and the position by whoever tapped, and a section drawn square to the
        // wrong axis or sitting on top of the passage is worse than a rough one placed by hand.
        DropdownMenuItem(
            text = { Text("Re-aim a cross-section") },
            leadingIcon = { CheckDot(state.tool == SketchTool.ROTATE_CROSS_SECTION) },
            onClick = {
                state.chooseTool(SketchTool.ROTATE_CROSS_SECTION)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Move a cross-section") },
            leadingIcon = { CheckDot(state.tool == SketchTool.MOVE_CROSS_SECTION) },
            onClick = {
                state.chooseTool(SketchTool.MOVE_CROSS_SECTION)
                onDismiss()
            },
        )
        // `action_find_station`, from the Android app's tools menu. It is here rather than behind
        // the three dots because it is a *drawing* action: the answer is a change to what the
        // canvas is showing.
        DropdownMenuItem(
            text = { Text("Find a station…") },
            onClick = {
                finding = true
                onDismiss()
            },
        )
        // `buttonDeleteLastLeg`. The one destructive action a surveyor reaches for often: a shot
        // taken by accident, or one taken from the wrong station, wants to be gone before the next
        // one goes in — and going to the table to find it is three taps and a scroll away from
        // where they are standing.
        DropdownMenuItem(
            text = { Text("Delete the last leg") },
            onClick = {
                deletingLastLeg = true
                onDismiss()
            },
        )
        // The twelve toggles, one row instead of twelve. Last in the menu because it is the one
        // row here that opens something rather than doing something, and because everything above
        // it is what a surveyor with cold hands is actually reaching for.
        DropdownMenuItem(
            text = { Text("What the drawing shows\u2026") },
            onClick = {
                adjustingDisplay = true
                onDismiss()
            },
        )
    }
}

/**
 * The twelve sketch toggles, in the app's own two groups.
 *
 * A dialog rather than a submenu because a submenu of eight rows has the same problem the parent
 * had, and because these are the settings somebody changes once and leaves: opening them, ticking
 * three, and closing once beats a popup that shuts on every tap.
 *
 * It applies as it goes rather than on a Save button — [ViewToggle.toggle] writes the preferences
 * file — so the drawing behind the dialog changes under the surveyor's finger. That is the point:
 * "show grid" is a question you answer by looking, and a dialog that made you close it first to
 * find out would be asking you to guess.
 */
@Composable
private fun DrawingOptionsDialog(state: DemoState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What the drawing shows") },
        text = {
            // Scrolling, because twelve rows and two headings do not fit the height Material gives
            // a dialog on a small phone, and a dialog that cannot scroll silently clips instead.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToggleSection("Shown", DISPLAY_TOGGLES, state)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToggleSection("Behaviour", BEHAVIOUR_TOGGLES, state)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** One of the two groups: a heading, then its rows. */
@Composable
private fun ToggleSection(heading: String, toggles: List<ViewToggle>, state: DemoState) {
    Text(
        heading,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    for (toggle in toggles) {
        Row(
            Modifier
                .fillMaxWidth()
                // The whole row, not just the switch: a checkbox-sized target is a poor one in a
                // cave, and the label is the part being aimed at anyway.
                .clickable { toggle.toggle(state) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(toggle.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = toggle.get(state), onCheckedChange = { toggle.toggle(state) })
        }
    }
}

/** A tool button: the same as any other, but lit while its tool is the active one. */
@Composable
private fun RowScope.ToolButton(
    state: DemoState,
    tool: SketchTool,
    painter: Painter,
    description: String,
) {
    ToolbarButton(
        painter = painter,
        description = description,
        modifier = Modifier.weight(1f),
        selected = state.tool == tool,
        onClick = { state.chooseTool(tool) },
    )
}

/**
 * One button in the grid.
 *
 * `toolbar_button_height` is 40dp in the Android app, and the buttons share the width evenly across
 * nine columns — so on a narrow phone they are square-ish and on a tablet they spread out, which is
 * what the GridLayout with `layout_columnWeight="1"` does.
 */
@Composable
fun ToolbarButton(
    painter: Painter,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp)
            .then(
                if (selected) {
                    Modifier.background(SexyTopoColours.onPanel.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            // Square, not full-width: the artwork is square, so a full-width box would centre the
            // glyph inside a wide invisible rectangle - which only shows up when something is
            // drawn around the box, as the selection ring on a colour swatch is.
            //
            // Greyed rather than absent: see the note on SketchToolbar about the two tools this
            // demo cannot drive, and about undo before there is anything to undo.
            modifier = Modifier.fillMaxHeight().aspectRatio(1f).alpha(if (enabled) 1f else 0.35f),
        )
    }
}

/** A brush colour. The app draws these as its own swatch PNGs, so this uses them too. */
@Composable
private fun RowScope.ColourButton(
    colour: Colour,
    selected: Boolean,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val painter =
        when (colour) {
            Colour.BLACK -> painterResource(Res.drawable.black)
            Colour.BROWN -> painterResource(Res.drawable.brown)
            Colour.GREY -> painterResource(Res.drawable.grey)
            Colour.RED -> painterResource(Res.drawable.red)
            Colour.ORANGE -> painterResource(Res.drawable.orange)
            Colour.GREEN -> painterResource(Res.drawable.green)
            Colour.BLUE -> painterResource(Res.drawable.blue)
            else -> painterResource(Res.drawable.purple)
        }

    Box(
        modifier
            .height(SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = "Brush colour",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .then(
                        // The app has no selected state on these; a ring is added because without
                        // one there is no way at all to tell which colour the brush is holding,
                        // and on a demo somebody is watching over your shoulder that matters.
                        if (selected) {
                            Modifier.border(
                                2.dp,
                                if (darkMode) SexyTopoColours.onPanel else SexyTopoColours.legend,
                            )
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}

