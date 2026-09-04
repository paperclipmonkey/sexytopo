package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
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
import org.hwyl.sexytopo.demo.resources.white
import org.hwyl.sexytopo.demo.resources.zoom_in
import org.hwyl.sexytopo.demo.resources.zoom_out
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.jetbrains.compose.resources.painterResource

/**
 * SexyTopo's sketch toolbar: nine columns by two rows of icon buttons on a green panel, with the
 * symbol strip above it.
 *
 * A deliberate copy of `activity_graph.xml`, down to the order of the buttons and the artwork on
 * them — the icons are the app's own PNGs, carried across as Compose resources.
 *
 * Row one is the eight brush colours and zoom in. Row two is the tools — move, draw, symbol, erase,
 * select — then the drawing menu, undo, redo and zoom out.
 *
 * `buttonSymbol` behaves as `GraphActivity.handleAction` makes it behave: it selects the symbol
 * tool, opens the strip the first time it is ever tapped, and toggles the strip when tapped while
 * the symbol tool is already in hand. The strip itself is the app's `symbolToolbar` — a
 * horizontally scrolling row of every symbol on `sexyTopoDarkGreen`, the selected one lit.
 *
 * The one place the two apps' models differ is the label tool. `Symbol.TEXT` is a member of the
 * Android app's symbol enum standing for "the label tool" rather than for a drawing, so it is the
 * first entry on its strip; here it is [SketchTool.TEXT] instead, and the strip's first entry
 * selects that tool. What a surveyor sees and taps is the same either way.
 *
 * The camera at the end of the second row is the one button with no counterpart at all — see
 * [CameraButton]. [camera] is passed in rather than remembered here because the photograph comes
 * back long after the tap that asked for it, by which time this toolbar may have gone: see
 * [rememberPhotoCapture].
 */
@Composable
fun SketchToolbar(
    state: DemoState,
    editor: SketchEditor,
    canvas: CanvasController,
    camera: PhotoCapture,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var symbolStripOpen by remember { mutableStateOf(false) }
    // `symbolToolbarOpenedOnce`: the app opens the strip the first time the symbol button is ever
    // tapped, to teach the surveyor it is there, and only toggles it thereafter.
    var symbolStripEverOpened by remember { mutableStateOf(false) }

    // Read the revision so this toolbar recomposes when the sketch changes: SketchEditor is a
    // plain object, so `editor.canUndo` is not something Compose can observe on its own.
    @Suppress("UNUSED_VARIABLE")
    val revision = state.revision

    val panel =
        if (state.darkMode) {
            SexyTopoColours.panelBackgroundNight
        } else {
            SexyTopoColours.panelBackground
        }

    // `setSketchButtonsStatus`: with the sketch hidden there is nothing to draw on, so every
    // button that marks it is disabled and the tool falls back to moving the view.
    val canSketch = state.showSketch
    if (!canSketch && state.tool != SketchTool.MOVE) state.chooseTool(SketchTool.MOVE)

    Column(modifier.fillMaxWidth().background(panel)) {
        if (symbolStripOpen) {
            SymbolStrip(state) { symbolStripOpen = false }
        }

        Row(Modifier.fillMaxWidth()) {
            for (colour in BRUSH_COLOURS) {
                ColourButton(
                    colour = colour,
                    selected = state.brushColour == colour,
                    darkMode = state.darkMode,
                    enabled = canSketch,
                    modifier = Modifier.weight(1f),
                ) {
                    state.pickColour(colour, editor)
                }
            }
            ToolbarButton(
                painter = painterResource(Res.drawable.zoom_in),
                description = Strings.toolbarZoomIn,
                darkMode = state.darkMode,
                modifier = Modifier.weight(1f),
                onClick = { canvas.zoomIn() },
            )
            // The tenth column, which is empty because there are only nine things to put in this
            // row. `android:columnCount` is 9 and this port's grid is 10 wide, since the camera
            // below is the port's own button; a `GridLayout` given a tenth column and nine
            // children in a row leaves exactly this hole, and without it the rows stop lining up
            // — nine cells of weight one over ten of weight one puts every icon in the bottom row
            // a few pixels to the left of the one above it.
            Spacer(Modifier.weight(1f))
        }

        // `layout_marginTop="-4dp"`, which every button of the app's second row carries: the two
        // rows overlap by four pixels, so the grid is a shade tighter than two 40dp rows.
        Row(Modifier.fillMaxWidth().offset(y = SECOND_ROW_OVERLAP_DP.dp)) {
            ToolButton(
                state,
                SketchTool.MOVE,
                painterResource(Res.drawable.move),
                Strings.toolbarMove,
            )
            ToolButton(
                state,
                SketchTool.DRAW,
                painterResource(Res.drawable.pencil),
                Strings.toolbarDraw,
                enabled = canSketch,
            )
            SymbolButton(state, enabled = canSketch) {
                val wasAlreadyInSymbolMode =
                    state.tool == SketchTool.SYMBOL || state.tool == SketchTool.TEXT
                if (state.tool != SketchTool.TEXT) state.chooseTool(SketchTool.SYMBOL)
                if (!symbolStripEverOpened || wasAlreadyInSymbolMode) {
                    symbolStripEverOpened = true
                    symbolStripOpen = !symbolStripOpen
                }
            }
            ToolButton(
                state,
                SketchTool.ERASE,
                painterResource(Res.drawable.eraser),
                Strings.toolbarEraser,
                enabled = canSketch,
            )
            ToolButton(
                state,
                SketchTool.SELECT,
                painterResource(Res.drawable.select),
                Strings.toolbarSelector,
            )

            Box(Modifier.weight(1f)) {
                ToolbarButton(
                    painter = painterResource(Res.drawable.settings),
                    description = Strings.toolbarSettings,
                    darkMode = state.darkMode,
                    modifier = Modifier.fillMaxWidth().testTag("drawing-menu"),
                    onClick = { menuOpen = true },
                )
                DrawingMenu(state, canvas, menuOpen) { menuOpen = false }
            }

            ToolbarButton(
                painter = painterResource(Res.drawable.undo),
                description = Strings.toolbarUndo,
                darkMode = state.darkMode,
                modifier = Modifier.weight(1f),
                enabled = canSketch && editor.canUndo,
                onClick = { state.undo(editor) },
            )
            ToolbarButton(
                painter = painterResource(Res.drawable.redo),
                description = Strings.toolbarRedo,
                darkMode = state.darkMode,
                modifier = Modifier.weight(1f),
                enabled = canSketch && editor.canRedo,
                onClick = { state.redo(editor) },
            )
            ToolbarButton(
                painter = painterResource(Res.drawable.zoom_out),
                description = Strings.toolbarZoomOut,
                darkMode = state.darkMode,
                modifier = Modifier.weight(1f),
                onClick = { canvas.zoomOut() },
            )
            // Last, rather than beside the tools it belongs with: every button before it sits in
            // the column `activity_graph.xml` puts it in, and inserting one in the middle would
            // move the four that follow into somebody else's place.
            CameraButton(
                state,
                // With the sketch hidden there is nothing to pin a photograph to:
                // `setSketchButtonsStatus` disables every button that marks the drawing, and this
                // one would be worse than merely useless, since the tool it arms would be taken
                // straight back out of the surveyor's hand by the fallback to MOVE above.
                enabled = canSketch,
                // A device with no camera has nothing for this to open, so the button is drawn
                // spent. It stays pressable all the same, and answers with the reason.
                hasCamera = camera.available,
            ) {
                // Dead buttons are how \"the photograph button does nothing\" becomes a bug
                // report. Every platform's `whyNoCamera` is written to be read by a surveyor
                // standing in a cave — a simulator has no camera, a desktop's webcam is pointed
                // at the desk — so the tap that finds nothing says which it is.
                if (camera.available) camera.capture() else state.note(whyNoCamera())
            }
        }
    }
}

/**
 * `symbolToolbar`: the app's own scrolling strip of symbols, on `sexyTopoDarkGreen`, sitting
 * between the drawing and the button grid.
 *
 * Its first entry is the label tool, which is where `Symbol.TEXT` sits on the app's own strip.
 */
@Composable
private fun SymbolStrip(state: DemoState, onClose: () -> Unit) {
    val size = SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp

    Row(
        Modifier
            .fillMaxWidth()
            .background(SexyTopoColours.symbolToolbarBackground)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `Symbol.TEXT`, which this port models as a tool rather than as a symbol.
        Box(
            Modifier
                .size(size)
                .then(
                    if (state.tool == SketchTool.TEXT) {
                        Modifier.background(highlightFor(state.darkMode))
                    } else {
                        Modifier
                    },
                )
                .testTag("symbol-label")
                .clickable { state.chooseTool(SketchTool.TEXT) }
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

        for (symbol in Symbol.entries) {
            val lit = state.tool == SketchTool.SYMBOL && state.symbol == symbol
            Box(
                Modifier
                    .size(size)
                    .then(
                        if (lit) Modifier.background(highlightFor(state.darkMode)) else Modifier,
                    )
                    .semantics { contentDescription = symbol.therionName }
                    .testTag("symbol-${symbol.therionName}")
                    .clickable {
                        state.chooseSymbol(symbol)
                        state.chooseTool(SketchTool.SYMBOL)
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                SymbolGlyph(symbol, SexyTopoColours.symbolGlyph, size - 8.dp)
            }
        }

        Box(
            Modifier
                .size(size)
                .semantics { contentDescription = Strings.toolbarSymbolClose }
                .testTag("symbol-close")
                .clickable(onClick = onClose)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            // "×" not "✕": the bundled font has Latin-1 and no Dingbats.
            Text(
                "×",
                style = MaterialTheme.typography.titleMedium,
                color = SexyTopoColours.onPanel,
            )
        }
    }
}

/**
 * `buttonSymbol`, whose face is the symbol it will stamp.
 *
 * `selectSymbol` redraws the Android button with the chosen symbol's own artwork inside a border,
 * so a surveyor can see what a tap will leave without opening the strip. Here the same, drawn
 * through the path data the canvas uses — except while the label tool is in hand, when it shows
 * the app's `text` icon, since a label has no symbol.
 */
@Composable
private fun RowScope.SymbolButton(state: DemoState, enabled: Boolean, onClick: () -> Unit) {
    val selected = state.tool == SketchTool.SYMBOL || state.tool == SketchTool.TEXT
    val dim = if (enabled) 1f else DISABLED_BUTTON_ALPHA

    Box(
        Modifier
            .weight(1f)
            .height(SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp)
            .then(
                if (selected) {
                    Modifier.background(highlightFor(state.darkMode))
                } else {
                    Modifier
                },
            )
            .testTag("symbol-tool")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.tool == SketchTool.TEXT) {
            Image(
                painter = painterResource(Res.drawable.text),
                contentDescription = Strings.toolbarText,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f).alpha(dim),
            )
        } else {
            // `selectSymbol` draws the border in `Color.BLACK` and the artwork untinted, lit or
            // not; the black-on-green of the unselected face is what the app looks like.
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .alpha(dim)
                    .border(1.dp, SexyTopoColours.symbolGlyph),
                contentAlignment = Alignment.Center,
            ) {
                SymbolGlyph(
                    state.symbol,
                    SexyTopoColours.symbolGlyph,
                    SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp - 14.dp,
                )
            }
        }
    }
}

/**
 * The camera: take a photograph, then tap the paper to say where it was taken.
 *
 * Hand-rolled for the same reason [SymbolButton] is — its face is drawn rather than a `Painter`,
 * which is all [ToolbarButton] knows how to show. Every other icon on this bar is one of the app's
 * own `res/drawable-hdpi` PNGs carried across unchanged, and there is no `camera.png` among them
 * to carry: the Android app has never taken a photograph. So this one is drawn, and drawing it
 * beats inventing a PNG that has nothing upstream to be faithful to.
 *
 * Lit while [SketchTool.PLACE_PHOTO] is armed, which is the whole of the standing reminder that a
 * photograph has been taken and is waiting for somewhere to go — the instruction strip that says
 * so in words clears itself after a couple of seconds.
 *
 * [enabled] and [hasCamera] both dim it, and only the first stops it responding. A button with the
 * sketch hidden has nothing to do and says nothing; a button on a device with no camera has an
 * answer worth hearing, and giving it costs one tap rather than a bug report.
 */
@Composable
private fun RowScope.CameraButton(
    state: DemoState,
    enabled: Boolean,
    hasCamera: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Box(
        Modifier
            .weight(1f)
            .height(SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp)
            .then(
                if (state.tool == SketchTool.PLACE_PHOTO) {
                    Modifier.background(highlightFor(state.darkMode))
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = Strings.toolbarPhoto }
            .testTag("camera-tool")
            .clickable(enabled = enabled) {
                // `handleAction` buzzes before it has looked at which button was pressed, so
                // every button on this bar buzzes. [SymbolButton] is the one that does not, and
                // that is an oversight rather than a precedent.
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.alpha(if (enabled && hasCamera) 1f else DISABLED_BUTTON_ALPHA)) {
            CameraGlyph(
                SexyTopoColours.symbolGlyph,
                SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp - 14.dp,
            )
        }
    }
}

/**
 * A camera, drawn from a path rather than shipped as artwork.
 *
 * Black whether or not the button is lit, exactly as [SymbolGlyph] is and for the reason recorded
 * there: `buttonHighlight` is white, so a glyph that went white to stand out on the green panel
 * would be a blank square at the very moment the button was selected. That was reported from a
 * phone with a screenshot, and it is not a mistake worth making twice; the tool icons either side
 * of this one are black PNGs on the same green, so black is also what the app looks like.
 *
 * The 24-unit box is [DoneIcon]'s and [CancelIcon]'s, which take theirs from the Android vector
 * drawables they are copies of. This one has no drawable behind it, so the shape is chosen rather
 * than transcribed: a body, the raised hood over the viewfinder, and a lens. The hood is what
 * makes it read as a camera at twenty-six pixels across; without it a rounded box with a ring in
 * it is a target.
 */
@Composable
private fun CameraGlyph(colour: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.minDimension / CAMERA_VIEWPORT
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            // Stroked, not filled: a filled camera at this size is a black blob, and every path
            // the symbol strip draws is stroked too.
            drawPath(cameraPath, colour, style = Stroke(width = CAMERA_STROKE))
        }
    }
}

/** The box the camera is drawn in, and the width of its line inside that box. */
private const val CAMERA_VIEWPORT = 24f

private const val CAMERA_STROKE = 2f

/** Built once and kept, as [symbolPaths] is: this is redrawn on every recomposition of the bar. */
private val cameraPath: Path by lazy {
    Path().apply {
        addRoundRect(RoundRect(Rect(2f, 7f, 22f, 20.5f), CornerRadius(2.5f, 2.5f)))
        // The hood, open at the bottom so the body's own line closes it.
        moveTo(8f, 7f)
        lineTo(9.5f, 4f)
        lineTo(14.5f, 4f)
        lineTo(16f, 7f)
        addOval(Rect(8f, 9.75f, 16f, 17.75f))
    }
}

/** `buttonHighlight`, which is what the app tints a selected button's background with. */
private fun highlightFor(darkMode: Boolean): Color =
    if (darkMode) SexyTopoColours.buttonHighlightNight else SexyTopoColours.buttonHighlight

/**
 * The menu behind the settings button, from `res/menu/drawing.xml`.
 *
 * `drawing.xml` is three groups: actions, behaviour toggles, display toggles. Carrying all three
 * plus the items reached only from here — symbol palette, the three cross-section gestures, finding
 * a station — pushed a single list to eighteen rows, more than a small phone can show without
 * scrolling. So the twelve toggles moved into a dialog of their own, and what is left here is the
 * seven things that *do* something when tapped.
 *
 * `buttonShowConnections` is the one item of that menu still absent, because a neighbouring survey
 * is reached by an absolute `content://` URI, and a switch that does nothing is worse than no switch.
 */
@Composable
private fun DrawingMenu(
    state: DemoState,
    canvas: CanvasController,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    var deletingLastLeg by remember { mutableStateOf(false) }
    var adjustingDisplay by remember { mutableStateOf(false) }

    if (adjustingDisplay) {
        DrawingOptionsDialog(state) { adjustingDisplay = false }
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

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // `drawingMenuActions`, in its own order. `buttonRedo` is on it too but declared
        // `android:visible="false"`, and redo has a button of its own on the toolbar.
        DropdownMenuItem(
            text = { Text(Strings.sketchMenuDeleteLastLeg) },
            modifier = Modifier.testTag(tagFor(Strings.sketchMenuDeleteLastLeg)),
            onClick = {
                deletingLastLeg = true
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(Strings.sketchMenuCentreView) },
            modifier = Modifier.testTag(tagFor(Strings.sketchMenuCentreView)),
            onClick = {
                // `centreViewOnActiveStation`, not a refit: the app keeps the surveyor's zoom.
                stationPositionIn(state.survey, state.projection, state.survey.activeStation)
                    ?.let(canvas::centreOn)
                onDismiss()
            },
        )
        HorizontalDivider()
        // The twelve toggles of `drawingMenuBehaviourToggles` and `drawingMenuDisplayToggles`,
        // one row instead of twelve: flat, this menu reached eighteen rows, which is 864 pixels
        // of popup on an iPhone SE's 667-pixel screen.
        DropdownMenuItem(
            text = { Text(Strings.toolbarSettings) },
            modifier = Modifier.testTag(tagFor(Strings.toolbarSettings)),
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
 * Applies as it goes rather than on a Save button — [ViewToggle.toggle] writes the preferences file
 * directly — so the drawing behind the dialog changes live as each switch is flipped.
 */
@Composable
private fun DrawingOptionsDialog(state: DemoState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.toolbarSettings) },
        text = {
            // `drawing.xml`'s own order: `drawingMenuBehaviourToggles` comes before
            // `drawingMenuDisplayToggles`.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToggleSection(BEHAVIOUR_GROUP, BEHAVIOUR_TOGGLES, state)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToggleSection(SHOWN_GROUP, DISPLAY_TOGGLES, state)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.actionCrossSectionDone) }
        },
    )
}

/**
 * `drawingMenuDisplayToggles` and `drawingMenuBehaviourToggles`, named after the group ids the
 * Android menu gives them — the app draws them as two divider-separated blocks with no headings,
 * which a dialog has room to label.
 */
private const val SHOWN_GROUP = "Shown"

private const val BEHAVIOUR_GROUP = "Behaviour"

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
                // The whole row is clickable, not just the switch.
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
    enabled: Boolean = true,
) {
    ToolbarButton(
        painter = painter,
        description = description,
        darkMode = state.darkMode,
        modifier = Modifier.weight(1f),
        selected = state.tool == tool,
        enabled = enabled,
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
    darkMode: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .height(SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp)
            .then(
                // `buttonHighlight`, the colour `selectSketchTool` tints the background with.
                if (selected) Modifier.background(highlightFor(darkMode)) else Modifier,
            )
            .clickable(enabled = enabled) {
                // `handleAction` starts with `performHapticFeedback(VIRTUAL_KEY)`, before it has
                // looked at which button this is: every press on the toolbar is felt, which on
                // a phone held in a wet glove is how the surveyor knows it registered.
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
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
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .alpha(if (enabled) 1f else DISABLED_BUTTON_ALPHA),
        )
    }
}

/** What `setSketchButtonsStatus` leaves a disabled sketch button looking like. */
private const val DISABLED_BUTTON_ALPHA = 0.35f

/** `activity_graph.xml`'s `layout_marginTop` on every button of the second row. */
private const val SECOND_ROW_OVERLAP_DP = -4

/**
 * A brush colour. The app draws these as its own swatch PNGs, so this uses them too.
 *
 * `internal` rather than `private`: [CrossSectionEditor] draws the same swatches, since the
 * Android app's own cross-section editor offers the full colour row too — only *Select* is
 * disabled there.
 */
@Composable
internal fun RowScope.ColourButton(
    colour: Colour,
    selected: Boolean,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val painter =
        when (colour) {
            // `setSketchButtonsStatus` swaps the black swatch for the white one at night, since
            // black on `sexyTopoDarkGreen` is a hole rather than a button.
            Colour.BLACK ->
                if (darkMode) {
                    painterResource(Res.drawable.white)
                } else {
                    painterResource(Res.drawable.black)
                }
            Colour.BROWN -> painterResource(Res.drawable.brown)
            Colour.GREY -> painterResource(Res.drawable.grey)
            Colour.RED -> painterResource(Res.drawable.red)
            Colour.ORANGE -> painterResource(Res.drawable.orange)
            Colour.GREEN -> painterResource(Res.drawable.green)
            Colour.BLUE -> painterResource(Res.drawable.blue)
            else -> painterResource(Res.drawable.purple)
        }

    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .height(SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP.dp)
            .then(
                // `selectBrushColour` tints the selected swatch's *background* with
                // `buttonHighlight`, the same as a selected tool. This port drew a ring round the
                // swatch instead, which is not what the app looks like.
                if (selected) Modifier.background(highlightFor(darkMode)) else Modifier,
            )
            .clickable(enabled = enabled) {
                // The same `VIRTUAL_KEY` buzz as every other toolbar button; a colour goes
                // through `handleAction` too.
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = descriptionOf(colour),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .alpha(if (enabled) 1f else DISABLED_BUTTON_ALPHA),
        )
    }
}

/** `sketch_toolbar_colour_*`: what each swatch is called, for a screen reader and a long press. */
private fun descriptionOf(colour: Colour): String =
    when (colour) {
        Colour.BLACK -> Strings.toolbarColourMain
        Colour.BROWN -> Strings.toolbarColourBrown
        Colour.GREY -> Strings.toolbarColourGrey
        Colour.RED -> Strings.toolbarColourRed
        Colour.ORANGE -> Strings.toolbarColourOrange
        Colour.GREEN -> Strings.toolbarColourGreen
        Colour.BLUE -> Strings.toolbarColourBlue
        else -> Strings.toolbarColourPurple
    }

