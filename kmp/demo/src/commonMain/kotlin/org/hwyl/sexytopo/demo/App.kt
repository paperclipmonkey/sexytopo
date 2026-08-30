package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.demo.resources.elevation
import org.hwyl.sexytopo.demo.resources.plan
import org.hwyl.sexytopo.demo.resources.table
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.InputMode
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.jetbrains.compose.resources.painterResource

/**
 * The whole demo UI, written once and run on Android, iOS, desktop and the browser.
 *
 * It is a deliberate copy of SexyTopo's own sketch screen — `activity_graph.xml`, its green panels
 * from `colors.xml`, and its toolbar icons, carried across as Compose resources. That is the point:
 * a demo styled to somebody's taste would prove that Compose can draw a UI, which nobody doubts.
 * A demo that a SexyTopo user recognises on an iPhone, and can already use, is an argument.
 *
 * There is one layout rather than a phone one and a tablet one, because the app has one: a
 * nine-column grid of weighted buttons spreads out on a tablet and squares up on a phone by
 * itself. What the demo adds beyond the app's own chrome — the choice of survey, and the export
 * screen — is in the overflow menu, where a fourth screen would go in the app too.
 *
 * Everything the canvas draws comes from the shared Kotlin core ported from the Android app's Java;
 * everything you draw on it goes back into the same shared sketch model, and every reading in the
 * live survey is decoded from a real DistoX wire-format packet by the ported protocol code.
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
    val canvas = rememberCanvasController(state)

    // Pick up where the surveyor left off. A cave trip is not one sitting: the phone goes in a
    // pocket, the app is killed by the OS, and coming back to an empty screen would lose the
    // survey. Opening the most recent one is what makes this usable rather than a toy.
    LaunchedEffect(Unit) {
        state.loadSettings()
        state.refreshLibrary()
        state.savedSurveys.lastOrNull()?.let { state.openSurvey(it) }
    }

    KeepScreenAwake()

    // Save after every change rather than on a timer. A survey is a few tens of kilobytes and the
    // write is synchronous, so the cost is nothing against the thing it prevents: losing the last
    // few legs when a phone dies underground.
    LaunchedEffect(state.revision, state.liveSurvey) {
        if (state.revision > 0) state.saveLiveSurvey()
    }

    WithBundledFont { typography ->
        MaterialTheme(
            colorScheme = if (state.darkMode) darkColorScheme() else lightColorScheme(),
            typography = typography,
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color =
                    if (state.darkMode) {
                        SexyTopoColours.canvasBackgroundNight
                    } else {
                        SexyTopoColours.canvasBackground
                    },
            ) {
                // safeDrawing rather than nothing: on Android the app draws edge to edge behind
                // the status and navigation bars, and on an iPhone there is a notch at one end and
                // a home indicator at the other. Without this the app bar hides under the clock.
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    SexyTopoAppBar(state)

                    ScreenContent(
                        state,
                        editor,
                        canvas,
                        Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
                    )

                    FieldBar(state)

                    if (state.screen == Screen.SKETCH) {
                        SketchToolbar(state, editor, canvas)
                    }
                }
            }
        }
    }
}

/**
 * The app bar: `MaterialToolbar` on `panelBackground` with a white title, and the three
 * always-visible actions from `res/menu/action_bar.xml` that this port can honour — table, plan
 * and extended elevation.
 *
 * The app's fourth always-visible action is save, which is left out rather than drawn as a button
 * that does nothing: this demo holds its survey in memory and has nowhere to put it.
 *
 * The subtitle is the demo's own addition. The app has no such line, but a screenshot of a cave
 * with no numbers on it says very little, and it is where the shared core gets to show that it
 * counted the stations.
 */
@Composable
private fun SexyTopoAppBar(state: DemoState) {
    var menuOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(NamingIntent.NONE) }
    var editingTrip by remember { mutableStateOf(false) }
    var editingSettings by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    if (editingTrip) {
        TripDetailsDialog(
            survey = state.liveSurvey,
            today = todayIso(),
            onDismiss = { editingTrip = false },
            onSaved = {
                editingTrip = false
                state.noteSketchEdited()
            },
        )
    }

    if (connecting) {
        InstrumentDialog(state = state, onDismiss = { connecting = false })
    }

    if (importing) {
        ImportDialog(
            state = state,
            onDismiss = { importing = false },
            onImported = { importing = false },
        )
    }

    if (editingSettings) {
        SurveySettingsDialog(
            settings = state.surveySettings,
            onDismiss = { editingSettings = false },
            onSave = {
                state.updateSettings(it)
                editingSettings = false
            },
        )
    }

    deleting?.let { name ->
        DeleteSurveyDialog(
            name = name,
            isOpen = state.mode == SurveyMode.LIVE && state.liveSurvey.name == name,
            onDismiss = { deleting = null },
            onConfirm = {
                state.deleteSurvey(name)
                deleting = null
            },
        )
    }

    if (naming != NamingIntent.NONE) {
        SurveyNameDialog(
            intent = naming,
            current = state.liveSurvey.name,
            onDismiss = { naming = NamingIntent.NONE },
            onConfirm = { name ->
                when (naming) {
                    NamingIntent.NEW -> state.newSurvey(name)
                    NamingIntent.RENAME -> state.renameLiveSurvey(name)
                    NamingIntent.NONE -> Unit
                }
                naming = NamingIntent.NONE
            },
        )
    }
    val panel =
        if (state.darkMode) {
            SexyTopoColours.panelBackgroundNight
        } else {
            SexyTopoColours.panelBackground
        }

    Row(
        Modifier.fillMaxWidth().background(panel).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                state.survey.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = SexyTopoColours.onPanel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle(state),
                style = MaterialTheme.typography.labelSmall,
                color = SexyTopoColours.onPanel.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ToolbarButton(
            painter = painterResource(Res.drawable.table),
            description = "Table",
            modifier = Modifier.widthOfAnAction(),
            selected = state.screen == Screen.TABLE,
            onClick = { state.screen = if (state.screen == Screen.TABLE) Screen.SKETCH else Screen.TABLE },
        )
        ToolbarButton(
            painter = painterResource(Res.drawable.plan),
            description = "Plan",
            modifier = Modifier.widthOfAnAction(),
            selected = state.screen == Screen.SKETCH && state.projection == Projection2D.PLAN,
            onClick = {
                state.screen = Screen.SKETCH
                state.projection = Projection2D.PLAN
            },
        )
        ToolbarButton(
            painter = painterResource(Res.drawable.elevation),
            description = "Extended elevation",
            modifier = Modifier.widthOfAnAction(),
            selected =
                state.screen == Screen.SKETCH &&
                    state.projection == Projection2D.EXTENDED_ELEVATION,
            onClick = {
                state.screen = Screen.SKETCH
                state.projection = Projection2D.EXTENDED_ELEVATION
            },
        )

        Box {
            OverflowGlyph(
                Modifier
                    .clickable { menuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Survey management first, because in the field it is what the menu is for.
                DropdownMenuItem(
                    text = { Text("New survey…") },
                    leadingIcon = { Text(" ") },
                    onClick = {
                        naming = NamingIntent.NEW
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename survey…") },
                    leadingIcon = { Text(" ") },
                    onClick = {
                        // Both of these edit the surveyor's own survey, so show it: editing the
                        // name or the team of something the screen is not displaying is the kind
                        // of thing that gets noticed three trips later.
                        state.mode = SurveyMode.LIVE
                        naming = NamingIntent.RENAME
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Trip details…") },
                    leadingIcon = { Text(if (state.liveSurvey.trip != null) "✓" else " ") },
                    onClick = {
                        state.mode = SurveyMode.LIVE
                        editingTrip = true
                        menuOpen = false
                    },
                )
                for (name in state.savedSurveys) {
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = {
                            Text(
                                if (state.mode == SurveyMode.LIVE &&
                                    state.liveSurvey.name == name
                                ) {
                                    "✓"
                                } else {
                                    " "
                                },
                            )
                        },
                        // Deleting is on the row rather than behind a "manage surveys" screen, and
                        // it asks first: a survey is a trip somebody cannot repeat.
                        trailingIcon = {
                            Text(
                                "✕",
                                modifier = Modifier.clickable {
                                    deleting = name
                                    menuOpen = false
                                }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        },
                        onClick = {
                            state.openSurvey(name)
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(SurveyMode.EXAMPLE.label) },
                    leadingIcon = { Text(if (state.mode == SurveyMode.EXAMPLE) "✓" else " ") },
                    onClick = {
                        state.mode = SurveyMode.EXAMPLE
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Export") },
                    leadingIcon = { Text(if (state.screen == Screen.EXPORT) "✓" else " ") },
                    onClick = {
                        state.screen = Screen.EXPORT
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Instrument…") },
                    leadingIcon = { Text(if (state.session.connected) "✓" else " ") },
                    onClick = {
                        state.mode = SurveyMode.LIVE
                        connecting = true
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Import…") },
                    leadingIcon = { Text(" ") },
                    onClick = {
                        importing = true
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Surveying…") },
                    leadingIcon = { Text(" ") },
                    onClick = {
                        editingSettings = true
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Dark mode") },
                    leadingIcon = { Text(if (state.darkMode) "✓" else " ") },
                    onClick = { state.darkMode = !state.darkMode },
                )
            }
        }
    }
}

/** Action icons in the app bar: the toolbar button height, and about as wide. */
private fun Modifier.widthOfAnAction(): Modifier = this.width(44.dp)

/**
 * The three-dot overflow, drawn rather than typed.
 *
 * `⋮` (U+22EE) is not in Liberation Sans, which this app bundles precisely so that text renders
 * identically everywhere — so on every platform it came out as a missing-glyph box. Drawing it is
 * three lines of code and cannot fail on a font.
 */
@Composable
private fun OverflowGlyph(modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Box(Modifier.size(3.dp).background(SexyTopoColours.onPanel, CircleShape))
        }
    }
}

@Composable
private fun ScreenContent(
    state: DemoState,
    editor: SketchEditor,
    canvas: CanvasController,
    modifier: Modifier,
) {
    when (state.screen) {
        Screen.TABLE ->
            SurveyTableView(
                survey = state.survey,
                revision = state.revision,
                modifier = modifier,
                onEdited = { state.noteSketchEdited() },
                // The demo cave is a fixture; editing it would be surprising and would not be
                // saved. The surveyor's own survey is the one that can be corrected.
                editable = state.mode == SurveyMode.LIVE,
            )
        Screen.EXPORT -> ExportView(state.survey, state.revision, modifier)
        Screen.SKETCH -> SketchScreen(state, editor, canvas, modifier)
    }
}

/**
 * The sketch, plus the one thing it cannot do for itself.
 *
 * The canvas reports *where* a label goes; typing *what* it says needs a keyboard, so the dialog
 * lives out here where there is a layout to put one in.
 */
@Composable
private fun SketchScreen(
    state: DemoState,
    editor: SketchEditor,
    canvas: CanvasController,
    modifier: Modifier,
) {
    // Position in survey coordinates, and the text size in metres for the current zoom.
    var placing by remember { mutableStateOf<Pair<Coord2D, Float>?>(null) }

    placing?.let { (position, size) ->
        LabelDialog(
            onDismiss = { placing = null },
            onConfirm = { text ->
                editor.addText(position, text, size)
                placing = null
                state.noteSketchEdited()
            },
        )
    }

    SurveyCanvas(
        survey = state.survey,
        projection = state.projection,
        options = state.displayOptions,
        editor = editor,
        canvas = canvas,
        modifier = modifier,
        tool = state.tool,
        revision = state.revision,
        onSketchEdit = { state.noteSketchEdited() },
        onSelectStation = { state.selectStation(it) },
        onPlaceLabel = { position, size -> placing = position to size },
    )
}

/**
 * The field controls: where a reading gets into the survey.
 *
 * Two ways in, and the first is the one that matters on iOS. **Safari has no Web Bluetooth**, so on
 * the platform this port exists for there is no way to hear from an instrument at all — a surveyor
 * reads the DistoX display and types it. "Simulate" keeps the simulated instrument alongside,
 * because it is still the quickest way to show somebody what the app does without an instrument in
 * the room.
 *
 * Both paths feed the same ported [SurveyUpdater], so a typed reading behaves exactly as a radioed
 * one: three that agree within tolerance promote to a station.
 *
 * They appear only over the surveyor's own survey. Over the demo cave this is a way back to it
 * instead — see [DemoCaveBar].
 */
@Composable
private fun FieldBar(state: DemoState) {
    if (state.mode == SurveyMode.EXAMPLE) {
        DemoCaveBar(state)
        return
    }

    val session = state.session
    val dark = state.darkMode
    var entering by remember { mutableStateOf(false) }
    var namingStation by remember { mutableStateOf(false) }

    if (entering) {
        ManualReadingDialog(
            inputMode = state.inputMode,
            onInputMode = { state.inputMode = it },
            onDismiss = { entering = false },
            onAdd = { leg, asSplay ->
                // liveSurvey, not state.survey: this composable only runs in LIVE mode, and being
                // explicit here is what stops that ever silently changing.
                val survey = state.liveSurvey
                if (asSplay) {
                    // A splay is wall detail, taken where you stand. There is no far end to have
                    // stood at, so the input mode does not apply to one.
                    SurveyBuilder.addSplay(survey, survey.activeStation, leg)
                } else {
                    SurveyUpdater.update(survey, leg, state.inputMode, state.surveySettings)
                }
                state.noteSketchEdited()
                entering = false
            },
        )
    }

    if (namingStation) {
        StationActionsDialog(
            survey = state.liveSurvey,
            station = state.liveSurvey.activeStation,
            onDismiss = { namingStation = false },
            onEdited = {
                namingStation = false
                state.noteSketchEdited()
            },
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (dark) SexyTopoColours.innerPanelNight else SexyTopoColours.innerPanel,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { entering = true }) { Text("Add reading") }
        Button(onClick = { session.takeReading() }) { Text("Simulate") }
        // Which station the next leg hangs off is the single most important thing on this screen,
        // and it was not on it at all. Tapping it names the station and says what is there, which
        // is what a surveyor does at a junction the moment they reach one.
        Text(
            buildString {
                append("From ${state.liveSurvey.activeStation.name}")
                // Only when it is not the default. A backsight mode left on by accident reverses
                // every leg that follows, and nothing in the numbers afterwards shows it happened.
                if (state.inputMode != InputMode.FORWARD) {
                    append("  ·  ${labelFor(state.inputMode).lowercase()}")
                }
                append("  ·  ${plural(state.liveSurvey.getAllStationsInChronoOrder().size, "station")}")
                session.lastReading?.let {
                    append("  ·  ${oneDp(it.distance)}m ${oneDp(it.azimuth)}°")
                }
                state.storageProblem?.let { append("  ·  not saved: $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color =
                when {
                    state.storageProblem != null -> MaterialTheme.colorScheme.error
                    dark -> SexyTopoColours.legendNight
                    else -> SexyTopoColours.legend
                },
            modifier = Modifier.clickable { namingStation = true },
        )
        Spacer(Modifier.weight(1f))
    }
}

/**
 * What the field bar says while the demo cave is showing.
 *
 * Recording controls must not appear here: the demo cave is a fixture, it is deliberately never
 * saved, and anything put into it would vanish at the next restart. Until now the bar was simply
 * left out over it, which was safe and left the app opening on a screen whose only route to the
 * surveyor's own survey was a three-dot menu — on the screen a new user sees first, and the moment
 * they are most likely to be looking for one.
 *
 * So the space says what this is and offers the one thing worth doing from here.
 */
@Composable
private fun DemoCaveBar(state: DemoState) {
    val dark = state.darkMode

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dark) SexyTopoColours.innerPanelNight else SexyTopoColours.innerPanel)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { state.mode = SurveyMode.LIVE }) {
            Text(if (state.savedSurveys.isEmpty()) "Start surveying" else "My survey")
        }
        Text(
            "An example. Nothing recorded here is kept.",
            style = MaterialTheme.typography.bodySmall,
            color = if (dark) SexyTopoColours.legendNight else SexyTopoColours.legend,
        )
        Spacer(Modifier.weight(1f))
    }
}
