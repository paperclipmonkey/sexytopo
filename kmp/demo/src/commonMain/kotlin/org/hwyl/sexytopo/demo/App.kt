package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
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
import org.hwyl.sexytopo.shared.comms.ShotTrouble
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
 * A deliberate copy of SexyTopo's own sketch screen — its layout, colours and toolbar icons — so
 * a SexyTopo user recognises it and can already use it.
 */
@Composable
fun App(
    survey: Survey = remember { ExampleSurvey.create() },
    initialProjection: Projection2D = Projection2D.PLAN,
    /** What the platform reports; overridden by the theme preference unless it is Automatic. */
    systemDark: Boolean = isSystemInDarkTheme(),
    /** Open on a named tool; by default, whichever tool the surveyor last selected. */
    initialTool: SketchTool? = null,
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
                initialSystemDark = systemDark,
                initialTool = initialTool,
                initialMode = initialMode,
                initialScreen = initialScreen,
            ).also { it.viewing3D = initialView3D }
        }
    val editor = rememberSketchEditor(state)
    val canvas = rememberCanvasController(state)

    // `SideEffect`, not a plain assignment: writing to state during composition would make the
    // UI recompose forever.
    SideEffect { state.systemDark = systemDark }

    LaunchedEffect(Unit) {
        // Before anything is read, let alone written: in a browser, saved surveys are storage
        // the browser is entitled to reclaim until it has been asked not to.
        requestDurableStorage()
        state.loadSettings()
        state.loadCalibration()
        state.loadLog()
        state.refreshLibrary()
        state.savedSurveys.lastOrNull()?.let { state.openSurvey(it) }
        // After the survey, never before: opening one builds a fresh session, which would drop an
        // instrument attached to the old one.
        state.resumeLastInstrument()
    }

    KeepScreenAwake()

    // Runs wherever the surveyor is, not just while a dialog is open — reconnection couldn't
    // happen while they were drawing instead of looking at a dialog.
    val instrument = state.session.profile
    LaunchedEffect(state.session, instrument) {
        if (instrument == null) return@LaunchedEffect
        while (true) {
            delay(TICK_MILLIS)
            state.session.tick()
        }
    }

    LaunchedEffect(state.revision, state.liveSurvey) {
        if (state.revision > 0) state.saveLiveSurvey()
    }

    WithBundledFont { typography ->
      // Provided here rather than threaded down four dialogs deep — see [LocalAngleEntry].
      CompositionLocalProvider(LocalAngleEntry provides state.preferences.angleEntry) {
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
                // Without safeDrawing the app bar hides under the status bar or a notch.
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
}

/**
 * The app bar: the three always-visible actions from `action_bar.xml` this port can honour, plus
 * a subtitle — the demo's own addition — showing what the shared core counted.
 */
@Composable
private fun SexyTopoAppBar(state: DemoState) {
    var menuOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(NamingIntent.NONE) }
    var editingTrip by remember { mutableStateOf(false) }
    var editingSettings by remember { mutableStateOf(false) }
    var editingManualEntry by remember { mutableStateOf(false) }
    var editingSketchStyle by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var showingStats by remember { mutableStateOf(false) }
    var showingLog by remember { mutableStateOf(false) }
    var showingAbout by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(MenuPage.TOP) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var addingLeg by remember { mutableStateOf(false) }
    var addingSplay by remember { mutableStateOf(false) }

    // Always against `liveSurvey`, never `state.survey`: the demo cave is read-only.
    if (addingLeg || addingSplay) {
        val splay = addingSplay
        AddLegDialog(
            survey = state.liveSurvey,
            asSplay = splay,
            lrudFields = state.preferences.lrudFields,
            onDismiss = {
                addingLeg = false
                addingSplay = false
            },
            onAdd = { leg, toName, toComment, lrud ->
                addLegOutright(
                    survey = state.liveSurvey,
                    leg = leg,
                    asSplay = splay,
                    toName = toName,
                    toComment = toComment,
                    lrud = lrud,
                    lrudMode = state.preferences.lrudMode,
                )
                if (!splay) state.noteStationCreated()
                state.noteSketchEdited()
                addingLeg = false
                addingSplay = false
            },
        )
    }

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
        // `ActivityLog` is a plain class, so appending to it is invisible to Compose otherwise.
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

    if (editingManualEntry) {
        ManualEntrySettingsDialog(
            preferences = state.preferences,
            onDismiss = { editingManualEntry = false },
            onSave = {
                state.updatePreferences(it)
                editingManualEntry = false
            },
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

    if (editingSketchStyle) {
        SketchStyleDialog(
            preferences = state.preferences,
            onDismiss = { editingSketchStyle = false },
            onSave = {
                state.updatePreferences(it)
                editingSketchStyle = false
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
                // 200dp fits "Instrument >" without the menu visibly resizing between pages.
                modifier = Modifier.width(200.dp),
            ) {
                // `action_bar.xml`'s own five submenus: File, View, Instrument, Tools, Settings,
                // Help.
                if (page == MenuPage.TOP) {
                    MenuGroup("File", MenuPage.FILE) { page = it }
                    MenuGroup("View", MenuPage.VIEW) { page = it }
                    MenuGroup("Instrument", MenuPage.INSTRUMENT) { page = it }
                    MenuGroup("Tools", MenuPage.TOOLS) { page = it }
                    MenuGroup("Settings", MenuPage.SETTINGS) { page = it }
                    MenuGroup("Help", MenuPage.HELP) { page = it }
                }

                if (page != MenuPage.TOP) {
                    DropdownMenuItem(
                        text = { Text("< Back") },
                        leadingIcon = { CheckDot(false) },
                        onClick = { page = page.parent },
                    )
                }

                if (page == MenuPage.TOOLS) {
                    // Unlike *Add reading* on the field bar, these write the leg down
                    // immediately rather than holding it to the instrument's rules.
                    DropdownMenuItem(
                        text = { Text("Add a leg") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            addingLeg = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add a splay") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            addingSplay = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                }

                if (page == MenuPage.HELP) {
                    DropdownMenuItem(
                        text = { Text("Manual") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            state.viewingManual = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    // Not decoration: this build carries GPL-3.0 code whose licence and authors
                    // need to be visible somewhere.
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
                            state.mode = SurveyMode.LIVE
                            naming = NamingIntent.RENAME
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    for (name in state.savedSurveys) {
                        DropdownMenuItem(
                            text = { Text(name) },
                            leadingIcon = {
                                CheckDot(
                                    state.mode == SurveyMode.LIVE && state.liveSurvey.name == name,
                                )
                            },
                            trailingIcon = {
                                // "×" not "✕": the bundled font has Latin-1 and no Dingbats, so
                                // the prettier cross renders as a missing-glyph box.
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
                        text = { Text("Manual entry…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            editingManualEntry = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Sketching…") },
                        leadingIcon = { CheckDot(false) },
                        onClick = {
                            editingSketchStyle = true
                            menuOpen = false
                            page = MenuPage.TOP
                        },
                    )
                    MenuGroup("Theme: ${state.preferences.theme.label}", MenuPage.THEME) {
                        page = it
                    }
                }

                if (page == MenuPage.THEME) {
                    for (theme in AppTheme.entries) {
                        DropdownMenuItem(
                            text = { Text(theme.label) },
                            leadingIcon = { CheckDot(state.preferences.theme == theme) },
                            onClick = {
                                state.updatePreferences(state.preferences.copy(theme = theme))
                                // Stays on the page: comparing two themes needs to see the
                                // effect without navigating back each time.
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Which page of the overflow menu is showing: `action_bar.xml`'s submenus, one at a time. */
enum class MenuPage {
    TOP,
    FILE,
    VIEW,
    INSTRUMENT,
    TOOLS,
    SETTINGS,
    HELP,
    THEME,
}

internal val MenuPage.parent: MenuPage
    get() = if (this == MenuPage.THEME) MenuPage.SETTINGS else MenuPage.TOP

@Composable
private fun MenuGroup(label: String, opens: MenuPage, onOpen: (MenuPage) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { CheckDot(false) },
        // "›" not ">": the bundled font has it, and `FontCoverageTest` checks that.
        trailingIcon = { Text("\u203A", style = MaterialTheme.typography.bodyMedium) },
        onClick = { onOpen(opens) },
    )
}

/**
 * What is left of the app bar in full screen: a grab handle, and a way back — something has to
 * stay, or a surveyor who turned this on could not turn it off.
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

private fun Modifier.widthOfAnAction(): Modifier = this.width(44.dp)

/**
 * The three-dot overflow, drawn rather than typed: `⋮` is not in Liberation Sans, which this app
 * bundles precisely so text renders identically everywhere.
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
    // Held by name rather than by object: an edit that renames or deletes the station would
    // otherwise leave a menu holding a reference to a station no longer in the survey.
    var menuFor by remember { mutableStateOf<String?>(null) }
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
                editable = state.mode == SurveyMode.LIVE,
                onStation = { menuFromTable = true; menuFor = it.name },
                scrollTo = state.pendingTableJump,
                onScrolled = { state.tableJumpDone() },
            )
        Screen.EXPORT ->
            ExportView(
                state.survey,
                state.revision,
                modifier,
                state.projection,
                svgOptions = state.preferences.svgExport,
                onSvgOptionsChange = {
                    state.updatePreferences(state.preferences.copy(svgExport = it))
                },
                therionOptions = state.preferences.therionExport,
                onTherionOptionsChange = {
                    state.updatePreferences(state.preferences.copy(therionExport = it))
                },
            )
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

/** The sketch, plus the label dialog it needs a keyboard for and can't provide itself. */
@Composable
private fun SketchScreen(
    state: DemoState,
    editor: SketchEditor,
    canvas: CanvasController,
    modifier: Modifier,
    onLongPressStation: (String) -> Unit,
) {
    var placing by remember { mutableStateOf<Pair<Coord2D, Float>?>(null) }

    LaunchedEffect(state.stationsCreated, state.projection) {
        if (state.stationsCreated > 0 && state.preferences.autoRecentre) {
            stationPositionIn(state.survey, state.projection, state.survey.activeStation)
                ?.let(canvas::centreOn)
        }
    }

    // Cleared whether or not the station is in this projection, so it never leaves a jump
    // pending forever.
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

/** The station menu, wherever it was opened from — a long press on the drawing, or a table row. */
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
        // Dropping the return value here meant the active station moved on screen and was back
        // where it started after a restart — saving is the caller's job.
        onMakeActive = { if (state.selectStation(it.name)) state.noteSketchEdited() },
        onOpenCrossSection = { state.editingCrossSection = it },
        onCreateCrossSection = { at ->
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
        lrudMode = state.preferences.lrudMode,
    )
}

/**
 * The field controls: where a reading gets into the survey.
 *
 * **Safari has no Web Bluetooth**, so on the platform this port exists for there is no way to
 * hear from an instrument at all — a surveyor reads the DistoX display and types it, through the
 * same ported [SurveyUpdater] a radio would feed.
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
    // The field bar's own copy, because the app bar's is gone in full-screen mode and the
    // instrument is exactly what somebody in full screen is most likely to need to see to.
    var connecting by remember { mutableStateOf(false) }

    if (connecting) {
        InstrumentDialog(state = state, onDismiss = { connecting = false })
    }

    if (entering) {
        ManualReadingDialog(
            inputMode = state.inputMode,
            onInputMode = { state.chooseInputMode(it) },
            onDismiss = { entering = false },
            lrudFields = state.preferences.lrudFields,
            onAdd = { leg, asSplay, lrud ->
                addTypedReading(
                    survey = state.liveSurvey,
                    leg = leg,
                    asSplay = asSplay,
                    lrud = lrud,
                    inputMode = state.inputMode,
                    settings = state.surveySettings,
                    lrudMode = state.preferences.lrudMode,
                    onStationCreated = { state.noteStationCreated() },
                )
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
            lrudMode = state.preferences.lrudMode,
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (dark) SexyTopoColours.innerPanelNight else SexyTopoColours.innerPanel,
            ),
    ) {
    session.trouble?.let { trouble -> ShotTroubleBanner(trouble, session.troubleDetail) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val controls = FieldControls.of(state.preferences, session.profile)
        if (controls.manualEntry) {
            Button(onClick = { entering = true }) { Text("Add reading") }
        }
        if (controls.simulator) {
            Button(onClick = { session.takeReading() }) { Text("Simulate") }
        }
        Text(
            buildString {
                append("From ${state.liveSurvey.activeStation.name}")
                // A backsight mode left on by accident reverses every leg that follows, with
                // nothing in the numbers afterwards to show it happened.
                if (state.inputMode != InputMode.FORWARD) {
                    append("  ·  ${labelFor(state.inputMode).lowercase()}")
                }
                append("  ·  ${plural(state.liveSurvey.getAllStationsInChronoOrder().size, "station")}")
                session.lastReading?.let {
                    append("  ·  ${oneDp(it.distance)}m ${oneDp(it.azimuth)}°")
                }
                state.storageProblem?.let { append("  ·  not saved: $it") }
                state.importProblem?.let { append("  ·  $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color =
                when {
                    state.storageProblem != null || state.importProblem != null ->
                        MaterialTheme.colorScheme.error
                    dark -> SexyTopoColours.legendNight
                    else -> SexyTopoColours.legend
                },
            modifier = Modifier.clickable { namingStation = true },
        )
        Spacer(Modifier.weight(1f))

        // Right-hand end, and on every screen: the surveyor asking "did that shot go in?" should
        // not have to open a menu to find out.
        val status = connectionStatusOf(session)
        if (status != ConnectionStatus.CONNECTED && status != ConnectionStatus.NONE) {
            ConnectionChip(status = status, dark = dark, onClick = { connecting = true })
        }
        ConnectionIndicator(status = status, dark = dark, onClick = { connecting = true })
    }
    }
}

/** Why the instrument will not shoot, where somebody waiting for a reading is looking. */
@Composable
private fun ShotTroubleBanner(trouble: ShotTrouble, detail: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            trouble.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            trouble.whatToDo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the field bar says while the demo cave is showing: recording controls must not appear
 * here, since the demo cave is a fixture that is never saved.
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
