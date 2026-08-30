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
 * Only the display toggles this port can honour are here. The rest of that menu — snap to lines,
 * fade non-active, blue water, show connections and the compass — is listed in the shared model or
 * not yet ported, and offering a switch that does nothing would be worse than not offering it.
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
                state.symbol = chosen
                state.tool = SketchTool.SYMBOL
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
                state.tool = SketchTool.POSITION_CROSS_SECTION
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
                state.tool = SketchTool.ROTATE_CROSS_SECTION
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Move a cross-section") },
            leadingIcon = { CheckDot(state.tool == SketchTool.MOVE_CROSS_SECTION) },
            onClick = {
                state.tool = SketchTool.MOVE_CROSS_SECTION
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
        // `buttonAutoRecentre`, in the Android app's own behaviour group in this same menu, and
        // off by its default. It is a *preference* rather than a view toggle because it should
        // still be on next time: somebody who turned it on did so halfway down a long passage, and
        // having to find it again after a battery change is the opposite of the point.
        DropdownMenuItem(
            text = { Text("Follow the survey") },
            leadingIcon = { CheckDot(state.preferences.autoRecentre) },
            onClick = {
                state.updatePreferences(
                    state.preferences.copy(autoRecentre = !state.preferences.autoRecentre),
                )
            },
        )
        // `sketch_menu_blue_water`, on by its own default. Water is drawn blue on every published
        // cave survey there has ever been, and a surveyor with the brush set to black for wall
        // outlines should not have to change it and change it back to stamp a stream.
        DropdownMenuItem(
            text = { Text("Water is blue") },
            leadingIcon = { CheckDot(state.preferences.blueWater) },
            onClick = {
                state.updatePreferences(
                    state.preferences.copy(blueWater = !state.preferences.blueWater),
                )
            },
        )
        // `sketch_menu_fade_non_active`, off by its default. The question a surveyor keeps asking
        // of a plan is "where am I on this", and in a cave of any size a page of red lines does
        // not answer it. Fading everything that does not hang off the working station does,
        // without moving the view — which matters, because the part of the sketch they are drawing
        // is the part they are standing in.
        DropdownMenuItem(
            text = { Text("Fade all but the working end") },
            leadingIcon = { CheckDot(state.preferences.fadeNonActive) },
            onClick = {
                state.updatePreferences(
                    state.preferences.copy(fadeNonActive = !state.preferences.fadeNonActive),
                )
            },
        )
        // `pref_highlight_latest_leg`, on by its default. In the Android app it lives in the
        // general settings screen rather than here; it is on this menu because this is where this
        // port keeps display choices, and because a preference reachable from nowhere is
        // indistinguishable from one that does not exist.
        DropdownMenuItem(
            text = { Text("Mark the last leg taken") },
            leadingIcon = { CheckDot(state.preferences.highlightLatestLeg) },
            onClick = {
                state.updatePreferences(
                    state.preferences.copy(
                        highlightLatestLeg = !state.preferences.highlightLatestLeg,
                    ),
                )
            },
        )
        for (toggle in VIEW_TOGGLES) {
            DropdownMenuItem(
                text = { Text(toggle.label) },
                leadingIcon = { CheckDot(toggle.get(state)) },
                onClick = { toggle.toggle(state) },
            )
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
        onClick = { state.tool = tool },
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

