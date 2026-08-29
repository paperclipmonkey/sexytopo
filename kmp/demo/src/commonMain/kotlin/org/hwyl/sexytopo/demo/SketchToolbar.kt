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
 * Two of those buttons are drawn but disabled: the symbol and select tools exist in the shared
 * model (`SketchTool.SYMBOL`, `SketchTool.SELECT`) and this demo has no symbol palette or station
 * menu to drive them. Showing them greyed rather than leaving gaps keeps the layout honest in both
 * directions: the toolbar is the app's, and what the demo cannot do is visible rather than quietly
 * missing.
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
            ToolbarButton(
                painter = painterResource(Res.drawable.text),
                description = "Symbol",
                modifier = Modifier.weight(1f),
                enabled = false,
                onClick = {},
            )
            ToolButton(state, SketchTool.ERASE, painterResource(Res.drawable.eraser), "Erase")
            ToolbarButton(
                painter = painterResource(Res.drawable.select),
                description = "Select",
                modifier = Modifier.weight(1f),
                enabled = false,
                onClick = {},
            )

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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Centre view") },
            onClick = {
                canvas.refit()
                onDismiss()
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

/** A tick, for the checkable items in the drawing menu. */
@Composable
private fun CheckDot(checked: Boolean) {
    Text(
        if (checked) "✓" else " ",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
