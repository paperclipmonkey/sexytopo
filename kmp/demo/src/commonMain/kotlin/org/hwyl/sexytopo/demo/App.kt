package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.InputMode
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
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
    /** Opens straight into the 3D view. Only used by the headless renderer. */
    initialView3D: Boolean = false,
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
            ).also { it.viewing3D = initialView3D }
        }
    val editor = rememberSketchEditor(state)
    val canvas = rememberCanvasController(state)

    // Pick up where the surveyor left off. A cave trip is not one sitting: the phone goes in a
    // pocket, the app is killed by the OS, and coming back to an empty screen would lose the
    // survey. Opening the most recent one is what makes this usable rather than a toy.
    LaunchedEffect(Unit) {
        // Before anything is read, let alone written: in a browser, saved surveys are storage the
        // browser is entitled to reclaim until it has been asked not to. A no-op everywhere else.
        requestDurableStorage()
        state.loadSettings()
        state.loadCalibration()
        state.loadLog()
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
                    // Drawing inside a cross-section takes the whole screen, as it takes a whole
                    // activity in the Android app: it is a different world with its own tools and
                    // its own undo, and leaving the plan's toolbar under it would be a lie about
                    // what the buttons do.
                    val editing = state.editingCrossSection
                    if (editing != null) {
                        CrossSectionEditor(
                            survey = state.survey,
                            detail = editing,
                            darkMode = state.darkMode,
                            preferences = state.preferences,
                            onCancel = { state.editingCrossSection = null },
                            onDone = {
                                state.editingCrossSection = null
                                state.noteSketchEdited()
                            },
                        )
                    } else if (state.viewingManual) {
                        ManualView(onClose = { state.viewingManual = false })
                    } else if (state.viewing3D) {
                        ThreeDView(
                            survey = state.survey,
                            revision = state.revision,
                            darkMode = state.darkMode,
                            onClose = { state.viewing3D = false },
                            pinchToZoom = state.preferences.pinchToZoom,
                        )
                    } else {
                        // `action_fullscreen`. The app bar is the one piece of chrome a surveyor
                        // mid-stroke has no use for, and in landscape — which is how a wide
                        // passage gets drawn — it is a sixth of the paper.
                        if (state.preferences.fullScreen) {
                            FullScreenHandle {
                                state.updatePreferences(
                                    state.preferences.copy(fullScreen = false),
                                )
                            }
                        } else {
                            SexyTopoAppBar(state)
                        }

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
    var calibrating by remember { mutableStateOf(false) }
    var showingStats by remember { mutableStateOf(false) }
    var showingLog by remember { mutableStateOf(false) }
    var showingAbout by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(MenuPage.TOP) }
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

    if (calibrating) {
        CalibrationDialog(state = state, onDismiss = { calibrating = false })
    }

    if (showingAbout) {
        AboutDialog(onDismiss = { showingAbout = false })
    }

    if (showingStats) {
        StatsDialog(
            survey = state.survey,
            revision = state.revision,
            onDismiss = { showingStats = false },
        )
    }

    if (showingLog) {
        // Read through `logRevision` rather than straight off the log: `ActivityLog` is a plain
        // class, so appending to it is invisible to Compose and the dialog would show whatever was
        // there when it opened.
        val entries = remember(state.session.logRevision) { state.session.deviceLog.entries }
        LogDialog(
            entries = entries,
            onClear = { state.clearLog() },
            onDismiss = { showingLog = false },
        )
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
            preferences = state.preferences,
            onDismiss = { editingSettings = false },
            onSave = { settings, preferences ->
                state.updateSettings(settings)
                state.updatePreferences(preferences)
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
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = {
                    menuOpen = false
                    page = MenuPage.TOP
                },
                // One width for every page. Material sizes a menu to its longest label, so the
                // popup was 164 pixels on the top page and 112 on Help — it shrank under the
                // finger that had just opened it, and a submenu jumping about while you read it is
                // the sort of thing that makes an app feel unfinished. 200dp fits "Instrument >"
                // and leaves room for a saved survey's name beside its delete cross.
                modifier = Modifier.width(200.dp),
            ) {
                // `action_bar.xml`'s own five submenus, and for the same reason it has them: this
                // list had grown to fourteen rows plus one per saved survey, which is 672 pixels
                // before a single survey is saved — taller than an iPhone SE. Compose scrolls a
                // popup that does not fit, so nothing was unreachable, but *About* was drawn half
                // off the bottom edge and nothing said the list continued.
                //
                // The words are the app's, and so is the order: File, View, Instrument,
                // Settings, Help.
                if (page == MenuPage.TOP) {
                    MenuGroup("File", MenuPage.FILE) { page = it }
                    MenuGroup("View", MenuPage.VIEW) { page = it }
                    MenuGroup("Instrument", MenuPage.INSTRUMENT) { page = it }
                    MenuGroup("Settings", MenuPage.SETTINGS) { page = it }
                    MenuGroup("Help", MenuPage.HELP) { page = it }
                }

                if (page != MenuPage.TOP) {
                    // First row rather than a chevron in the corner: a surveyor in gloves needs a
                    // way back that is the same size as everything else on the menu.
                    DropdownMenuItem(
                        text = { Text("< Back") },
                        leadingIcon = { CheckDot(false) },
                        onClick = { page = MenuPage.TOP },
                    )
                }

                if (page == MenuPage.HELP) {
                    // `help_menu`, which holds exactly these two in exactly this order. It was
                    // flattened to a single *About…* row while the manual was missing; now that
                    // the manual is here it is the submenu the app always had.
                    DropdownMenuItem(
                        text = { Text("Manual") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            state.viewingManual = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    // `action_about`. Not decoration: this build carries several thousand lines of
                    // somebody else's GPL-3.0 code, and until it existed neither their names nor
                    // the licence appeared anywhere a user could see them.
                    DropdownMenuItem(
                        text = { Text("About") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            showingAbout = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                }

                if (page == MenuPage.FILE) {
                    // Survey management first, because in the field it is what the menu is for.
                    DropdownMenuItem(
                        text = { Text("New survey…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            naming = NamingIntent.NEW
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename survey…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            // This edits the surveyor's own survey, so show it: renaming
                            // something the screen is not displaying is the kind of thing that
                            // gets noticed three trips later.
                            state.mode = SurveyMode.LIVE
                            naming = NamingIntent.RENAME
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    // `action_file_open`, which in the app is a dialog and here is the list
                    // itself: on a phone the shortest way to a survey is its name.
                    for (name in state.savedSurveys) {
                        DropdownMenuItem(
                            text = { Text(name) },
                            leadingIcon = {
                                CheckDot(
                                    state.mode == SurveyMode.LIVE && state.liveSurvey.name == name,
                                )
                            },
                            // Deleting is on the row rather than behind a "manage surveys"
                            // screen, and it asks first: a survey is a trip somebody cannot
                            // repeat.
                            trailingIcon = {
                                // "×" and not "✕": the bundled font has Latin-1 and no Dingbats,
                                // so the prettier cross renders as a missing-glyph box.
                                Text(
                                    "×",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.clickable {
                                        deleting = name
                                        menuOpen = false
                                        page = MenuPage.TOP
                                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            },
                            onClick = {
                                state.openSurvey(name)
                                menuOpen = false
                                page = MenuPage.TOP
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Import…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            importing = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        leadingIcon = { CheckDot(state.screen == Screen.EXPORT) },
                        onClick = {
                            state.screen = Screen.EXPORT
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                }

                if (page == MenuPage.VIEW) {
                    DropdownMenuItem(
                        text = { Text(SurveyMode.EXAMPLE.label) },
                        leadingIcon = { CheckDot(state.mode == SurveyMode.EXAMPLE) },
                        onClick = {
                            state.mode = SurveyMode.EXAMPLE
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    // `action_trip` is in the app's View submenu, not its File one.
                    DropdownMenuItem(
                        text = { Text("Trip details…") },
                        leadingIcon = { CheckDot(state.liveSurvey.trip != null) },
                        onClick = {
                            state.mode = SurveyMode.LIVE
                            editingTrip = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("3D") },
                        leadingIcon = { CheckDot(state.viewing3D) },
                        onClick = {
                            state.viewing3D = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Statistics…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            showingStats = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                }

                if (page == MenuPage.INSTRUMENT) {
                    DropdownMenuItem(
                        text = { Text("Connect…") },
                        leadingIcon = { CheckDot(state.session.connected) },
                        onClick = {
                            state.mode = SurveyMode.LIVE
                            connecting = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Calibrate…") },
                        leadingIcon = { CheckDot(state.session.calibrating) },
                        onClick = {
                            state.mode = SurveyMode.LIVE
                            calibrating = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    // `action_system_log`, which the app keeps under Tools; it is here because
                    // every line in it is about an instrument.
                    DropdownMenuItem(
                        text = { Text("Log…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            showingLog = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                }

                if (page == MenuPage.SETTINGS) {
                    DropdownMenuItem(
                        text = { Text("Full screen") },
                        leadingIcon = { CheckDot(state.preferences.fullScreen) },
                        onClick = {
                            state.updatePreferences(
                                state.preferences.copy(fullScreen = !state.preferences.fullScreen),
                            )
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Surveying…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            editingSettings = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Dark mode") },
                        leadingIcon = { CheckDot(state.darkMode) },
                        onClick = { state.darkMode = !state.darkMode },
                    )
                }
            }
        }
    }
}

/**
 * Which page of the overflow menu is showing: `action_bar.xml`'s submenus, one at a time.
 *
 * A submenu rather than a nested popup because Material 3 has no nested `DropdownMenu`, and
 * because a popup hanging off another popup on a phone is a thing to aim at with a gloved finger.
 * Swapping the contents of the one menu keeps every row the full width of it.
 */
enum class MenuPage {
    TOP,
    FILE,
    VIEW,
    INSTRUMENT,
    SETTINGS,
    HELP,
}

/** A row that opens one of the submenus. */
@Composable
private fun MenuGroup(label: String, opens: MenuPage, onOpen: (MenuPage) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { CheckDot(false) },
        // "›" and not ">": the bundled font does have it, which `FontCoverageTest` checks. The
        // ">" here was a precaution taken against a font limit that turned out not to exist.
        trailingIcon = { Text("\u203A", style = MaterialTheme.typography.bodyMedium) },
        onClick = { onOpen(opens) },
    )
}

/**
 * What is left of the app bar in full screen: a grab handle, and a way back.
 *
 * Something has to stay. Hiding the app bar hides the only route to the overflow menu, and a
 * surveyor who turned this on and could not turn it off would have to delete the app — so this is
 * eighteen pixels of the same green with a bar drawn across it, and a tap anywhere on it brings
 * the app bar back. A drawn bar rather than a typed chevron because a grab handle is a bar — the
 * font would in fact draw "›" perfectly well, which `FontCoverageTest` now settles.
 */
@Composable
private fun FullScreenHandle(onExit: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(SexyTopoColours.panelBackground)
            .clickable(onClick = onExit)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(SexyTopoColours.onPanel, RoundedCornerShape(2.dp)),
        )
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
    // The station whose menu is open, held by name rather than by object: an edit that renames or
    // deletes it rebuilds the survey's stations, and a menu holding the old object would go on
    // offering actions against a station that is no longer in the survey.
    //
    // It lives out here rather than inside the sketch because both halves of the app open it: a
    // long press on the drawing, and a tap on a station's name in the table.
    var menuFor by remember { mutableStateOf<String?>(null) }
    // Which of the two opened it. The Android app has two station menus and they differ.
    var menuFromTable by remember { mutableStateOf(false) }

    StationMenuFor(
        state = state,
        editor = editor,
        name = menuFor,
        fromTable = menuFromTable,
        onClose = { menuFor = null },
    )

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
                onStation = { menuFromTable = true; menuFor = it.name },
                scrollTo = state.pendingTableJump,
                onScrolled = { state.tableJumpDone() },
            )
        Screen.EXPORT -> ExportView(state.survey, state.revision, modifier, state.projection)
        Screen.SKETCH ->
            SketchScreen(
                state,
                editor,
                canvas,
                modifier,
                onLongPressStation = { menuFromTable = false; menuFor = it },
            )
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
    onLongPressStation: (String) -> Unit,
) {
    // Position in survey coordinates, and the text size in metres for the current zoom.
    var placing by remember { mutableStateOf<Pair<Coord2D, Float>?>(null) }

    // `GraphActivity.handleAutoRecentre`, which listens for the same station-created broadcast the
    // buzz does. Keyed on the count rather than run on every recomposition, and skipped at zero so
    // opening the app does not count as a station having just been made.
    LaunchedEffect(state.stationsCreated, state.projection) {
        if (state.stationsCreated > 0 && state.preferences.autoRecentre) {
            stationPositionIn(state.survey, state.projection, state.survey.activeStation)
                ?.let(canvas::centreOn)
        }
    }

    // "Show it on the plan", asked for from the table. It cannot be done there: the viewport
    // belongs to this canvas, and this canvas did not exist when the menu was tapped. The request
    // is cleared whether or not the station turns out to be in this projection, so a station that
    // is not — nothing is, in a cross-section — does not leave a jump pending for ever.
    LaunchedEffect(state.pendingJump, state.projection) {
        state.pendingJump?.let { wanted ->
            state.survey.getStationByName(wanted)
                ?.let { stationPositionIn(state.survey, state.projection, it) }
                ?.let(canvas::centreOn)
            state.jumpDone()
        }
    }

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
        symbol = state.symbol,
        onOpenCrossSection = { state.editingCrossSection = it },
        onLongPressStation = onLongPressStation,
    )
}

/**
 * The station menu, wherever it was opened from.
 *
 * One dialog reached two ways — a long press on the drawing and a tap on a station's name in the
 * table — because it is one menu in the app too, near enough: `context_station.xml` and
 * `table_station_selected.xml` differ only in the two items that make no sense in the other place.
 */
@Composable
private fun StationMenuFor(
    state: DemoState,
    editor: SketchEditor,
    name: String?,
    fromTable: Boolean,
    onClose: () -> Unit,
) {
    val station = name?.let { state.survey.getStationByName(it) }
    if (name != null && station == null) {
        // Renamed or deleted out from under the menu.
        onClose()
        return
    }
    if (station == null) return

    StationMenuDialog(
        survey = state.survey,
        station = station,
        projection = state.projection,
        sketch = editor.sketch,
        onDismiss = onClose,
        onEdited = {
            onClose()
            state.noteSketchEdited()
        },
        // `selectStation` only moves the marker; saving is the caller's job, as it is on the
        // select tool's own path. Dropping the return value here meant the active station moved on
        // screen and was back where it started after a restart.
        onMakeActive = { if (state.selectStation(it.name)) state.noteSketchEdited() },
        onOpenCrossSection = { state.editingCrossSection = it },
        onCreateCrossSection = { at ->
            // Drawn beside the station rather than on top of it: the plan's own centreline is
            // under the finger that opened the menu, and a section dropped there covers the
            // passage it describes. The offset is the app's own starting section size, so it lands
            // clear of the line at any zoom.
            val position = crossSectionPositionFor(state.survey, at, state.projection)
            if (position != null) {
                editor.addCrossSection(sectionFor(state.survey, at), position)
                state.noteSketchEdited()
            }
            onClose()
        },
        onDeleteCrossSection = { editor.delete(it) },
        fromTable = fromTable,
        onShowOn = { at, wanted -> state.showOnDrawing(at, wanted) },
        onShowInTable = { state.showInTable(it) },
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
                } else if (
                    SurveyUpdater.update(survey, leg, state.inputMode, state.surveySettings)
                ) {
                    state.noteStationCreated()
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
