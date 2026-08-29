package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.forDarkMode
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool

/**
 * The whole demo UI, written once and run on Android, iOS, desktop and the browser.
 *
 * Everything the canvas draws comes from the shared Kotlin core ported from the Android app's Java;
 * everything you draw on it goes back into the same shared sketch model, and every reading in the
 * live survey is decoded from a real DistoX wire-format packet by the ported protocol code.
 *
 * There are two layouts below, and the split is at 600dp — the standard Material breakpoint, which
 * in practice means "a phone in portrait" against everything else. A phone gets a proper app
 * shell: a bottom navigation bar, one context-sensitive tool row, and every remaining pixel given
 * to the canvas. Anything wider gets the toolbars laid out flat, because there is room and a
 * surveyor with a tablet or a laptop would rather see the controls than hunt for them.
 *
 * Both layouts drive the same [DemoState], so nothing is available in one and missing from the
 * other, and rotating a tablet across the breakpoint changes only the arrangement.
 */
@Composable
fun App(
    survey: Survey = remember { ExampleSurvey.create() },
    initialProjection: Projection2D = Projection2D.PLAN,
    initialDarkMode: Boolean = false,
    initialTool: SketchTool = SketchTool.MOVE,
    initialMode: SurveyMode = SurveyMode.EXAMPLE,
    initialScreen: Screen = Screen.SKETCH,
) {
    val state =
        remember(survey) {
            DemoState(
                exampleSurvey = survey,
                initialProjection = initialProjection,
                initialDarkMode = initialDarkMode,
                initialTool = initialTool,
                initialMode = initialMode,
                initialScreen = initialScreen,
            )
        }
    val editor = rememberSketchEditor(state)

    WithBundledFont { typography ->
        MaterialTheme(
            colorScheme = if (state.darkMode) darkColorScheme() else lightColorScheme(),
            typography = typography,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth < 600.dp) {
                    PhoneLayout(state, editor)
                } else {
                    WideLayout(state, editor)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Phone
// -------------------------------------------------------------------------------------------

/**
 * The phone shell.
 *
 * [Scaffold] rather than a hand-rolled Column, because it is what puts the bottom bar clear of the
 * system navigation bar — on Android the app draws edge to edge behind it, and on an iPhone there
 * is a home indicator in the same place. Getting that wrong is the difference between a demo that
 * looks native and one that looks like a web page.
 */
@Composable
private fun PhoneLayout(state: DemoState, editor: SketchEditor) {
    Scaffold(
        topBar = { PhoneTopBar(state) },
        bottomBar = {
            Column {
                // Context first, then navigation: the tools belong to the screen above them, so
                // they sit next to it rather than below the thing that switches screens.
                if (state.screen == Screen.SKETCH) {
                    PhoneToolRow(state, editor)
                }
                if (state.mode == SurveyMode.LIVE) {
                    InstrumentBar(state, compact = true)
                }
                NavigationBar {
                    for (screen in Screen.entries) {
                        NavigationBarItem(
                            selected = state.screen == screen,
                            onClick = { state.screen = screen },
                            // A dot rather than an icon: the demo carries no icon font, and a
                            // NavigationBarItem must have something in the slot.
                            icon = { Dot(state.screen == screen) },
                            label = { Text(screen.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenContent(state, editor, Modifier.weight(1f).fillMaxWidth())
            Text(
                text = summarise(state, compact = true),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PhoneTopBar(state: DemoState) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Switching between the generated cave and a survey you build yourself is the headline of
        // the demo, so it stays visible rather than going in the menu.
        for (mode in SurveyMode.entries) {
            FilterChip(
                selected = state.mode == mode,
                onClick = { state.mode = mode },
                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
            )
        }

        Spacer(Modifier.weight(1f))

        Box {
            TextButton(onClick = { menuOpen = true }) { Text("View") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (projection in DRAWABLE_PROJECTIONS) {
                    DropdownMenuItem(
                        text = { Text(projection.displayName) },
                        leadingIcon = { Dot(state.projection == projection) },
                        onClick = {
                            state.projection = projection
                            menuOpen = false
                        },
                    )
                }
                for (toggle in VIEW_TOGGLES) {
                    DropdownMenuItem(
                        text = { Text(toggle.label) },
                        leadingIcon = { Dot(toggle.get(state)) },
                        onClick = { toggle.toggle(state) },
                    )
                }
            }
        }
    }
}

/**
 * The one row of sketching controls a phone gets: the tool, the ink, and undo.
 *
 * Horizontally scrollable because eight brush colours plus three tools plus undo and redo will not
 * fit across a narrow phone at a comfortable touch size — and shrinking them until they do is how
 * a drawing app becomes unusable with cold, muddy hands.
 */
@Composable
private fun PhoneToolRow(state: DemoState, editor: SketchEditor) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tool in DEMO_TOOLS) {
            FilterChip(
                selected = state.tool == tool,
                onClick = { state.tool = tool },
                label = { Text(tool.label, style = MaterialTheme.typography.labelMedium) },
            )
        }

        // Undo before the colours, not after: the row scrolls, and undo is the control a surveyor
        // reaches for in a hurry after a slip of the stylus. Putting eight colour swatches in
        // front of it would push it off the edge of a phone screen exactly when it is wanted.
        TextButton(enabled = editor.canUndo, onClick = { state.undo(editor) }) { Text("Undo") }
        TextButton(enabled = editor.canRedo, onClick = { state.redo(editor) }) { Text("Redo") }

        Spacer(Modifier.size(4.dp))

        for (colour in BRUSH_COLOURS) {
            ColourSwatch(colour, state.brushColour == colour, state.darkMode) {
                state.pickColour(colour, editor)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Tablet, desktop and browser
// -------------------------------------------------------------------------------------------

@Composable
private fun WideLayout(state: DemoState, editor: SketchEditor) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    "SexyTopo — Kotlin Multiplatform",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Survey engine, instrument protocols, sketch model and file format ported " +
                        "from the Android app's Java. This UI is shared Compose code.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ToolbarRow {
                for (mode in SurveyMode.entries) {
                    FilterChip(state.mode == mode, { state.mode = mode }, { Text(mode.label) })
                }
                for (screen in Screen.entries) {
                    FilterChip(
                        state.screen == screen,
                        { state.screen = screen },
                        { Text(screen.label) },
                    )
                }
                for (projection in DRAWABLE_PROJECTIONS) {
                    FilterChip(
                        selected = state.projection == projection,
                        onClick = { state.projection = projection },
                        label = { Text(projection.displayName) },
                    )
                }
            }

            if (state.mode == SurveyMode.LIVE) {
                InstrumentBar(state, compact = false)
            }

            if (state.screen == Screen.SKETCH) {
                ToolbarRow {
                    for (tool in DEMO_TOOLS) {
                        FilterChip(state.tool == tool, { state.tool = tool }, { Text(tool.label) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (colour in BRUSH_COLOURS) {
                            ColourSwatch(colour, state.brushColour == colour, state.darkMode) {
                                state.pickColour(colour, editor)
                            }
                        }
                    }
                }

                ToolbarRow {
                    TextButton(enabled = editor.canUndo, onClick = { state.undo(editor) }) {
                        Text("Undo")
                    }
                    TextButton(enabled = editor.canRedo, onClick = { state.redo(editor) }) {
                        Text("Redo")
                    }
                    for (toggle in VIEW_TOGGLES) {
                        FilterChip(
                            toggle.get(state),
                            { toggle.toggle(state) },
                            { Text(toggle.label) },
                        )
                    }
                }
            }

            ScreenContent(
                state,
                editor,
                Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp),
            )

            Text(
                text = summarise(state, compact = false),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Shared between the two layouts
// -------------------------------------------------------------------------------------------

@Composable
private fun ScreenContent(state: DemoState, editor: SketchEditor, modifier: Modifier) {
    when (state.screen) {
        Screen.TABLE -> SurveyTableView(state.survey, state.revision, modifier)
        Screen.EXPORT -> ExportView(state.survey, state.revision, modifier)
        Screen.SKETCH ->
            SurveyCanvas(
                survey = state.survey,
                projection = state.projection,
                options = state.displayOptions,
                editor = editor,
                modifier = modifier,
                tool = state.tool,
                revision = state.revision,
                onSketchEdit = { state.noteSketchEdited() },
            )
    }
}

/**
 * The live-survey controls. Each press decodes one real DistoX packet; three agreeing readings
 * promote to a station, which is the core interaction of the whole app.
 */
@Composable
private fun InstrumentBar(state: DemoState, compact: Boolean) {
    val session = state.session
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { session.takeReading() }) { Text("Take reading") }
        Text(
            buildString {
                if (!compact) {
                    append(if (session.connected) "connected" else "not connected")
                    append("  ·  ")
                }
                append("${session.readingsTaken} readings")
                session.lastReading?.let {
                    append("  ·  ${oneDp(it.distance)}m ${oneDp(it.azimuth)}°")
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!compact) {
        session.log.firstOrNull()?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** The two projections a survey can be sketched on; the rest are for maths, not for drawing. */
private val DRAWABLE_PROJECTIONS =
    listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)

/** A view toggle, declared once so the phone menu and the wide toolbar cannot drift apart. */
private class ViewToggle(
    val label: String,
    val get: (DemoState) -> Boolean,
    val toggle: (DemoState) -> Unit,
)

private val VIEW_TOGGLES =
    listOf(
        ViewToggle("Sketch", { it.showSketch }, { it.showSketch = !it.showSketch }),
        ViewToggle("Splays", { it.showSplays }, { it.showSplays = !it.showSplays }),
        ViewToggle("Labels", { it.showLabels }, { it.showLabels = !it.showLabels }),
        ViewToggle("Dark", { it.darkMode }, { it.darkMode = !it.darkMode }),
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolbarRow(content: @Composable () -> Unit) {
    // FlowRow rather than Row: even on a wide window the chips must wrap onto another line rather
    // than being squeezed until their labels break mid-word.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

/**
 * A brush colour, shown as it will actually be drawn.
 *
 * The distinction matters in dark mode, where the model draws black ink white so a plan is legible
 * on a black background. A swatch showing the stored colour rather than the drawn one would be a
 * black disc on a black background that then paints white — wrong twice over.
 */
@Composable
private fun ColourSwatch(
    colour: Colour,
    selected: Boolean,
    darkMode: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(if (selected) 30.dp else 24.dp)
            .background(Color(colour.forDarkMode(darkMode).intValue), CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** A filled or hollow dot, standing in for the icons this demo deliberately does not bundle. */
@Composable
private fun Dot(filled: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .background(
                if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
                CircleShape,
            )
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
    )
}
