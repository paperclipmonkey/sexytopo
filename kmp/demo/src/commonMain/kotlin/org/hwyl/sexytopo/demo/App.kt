package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Image
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.demo.resources.add
import org.hwyl.sexytopo.demo.resources.add_splay
import org.hwyl.sexytopo.demo.resources.elevation
import org.hwyl.sexytopo.demo.resources.plan
import org.hwyl.sexytopo.demo.resources.save
import org.hwyl.sexytopo.demo.resources.table
import org.hwyl.sexytopo.shared.comms.ShotTrouble
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.store.SurveyZip
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

                        Notice(state)

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
 * The app bar: `action_bar.xml`'s four `showAsAction="always"` actions — Save, Table, Plan,
 * Elevation — then the overflow, plus a subtitle showing what the shared core counted.
 *
 * None of the four carries a selected state, as none of the Android app's does: they are four
 * `startActivity` calls, and the screen you are on is the one you can see. Lighting the current
 * one also made it invisible, since `buttonHighlight` is white and so is the icon.
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
    var finding by remember { mutableStateOf(false) }
    var undoingLastLeg by remember { mutableStateOf(false) }
    var editingGeneral by remember { mutableStateOf(false) }
    var editingInstruments by remember { mutableStateOf(false) }

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
            onAdd = { added ->
                addLegOutright(
                    survey = state.liveSurvey,
                    leg = added.leg,
                    asSplay = splay,
                    toName = added.toName,
                    toComment = added.toComment,
                    lrud = added.lrud,
                    lrudMode = state.preferences.lrudMode,
                    fromName = added.fromName,
                    legComment = added.legComment,
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

    if (finding) {
        FindStationDialog(
            survey = state.survey,
            onDismiss = { finding = false },
            onGo = { station ->
                state.selectStation(station.name)
                state.showOnDrawing(station, state.projection)
                finding = false
            },
        )
    }

    if (undoingLastLeg) {
        DeleteLastLegDialog(
            survey = state.liveSurvey,
            onDismiss = { undoingLastLeg = false },
            onDelete = {
                state.liveSurvey.undoAddLeg()
                state.noteSketchEdited()
                undoingLastLeg = false
            },
        )
    }

    if (editingGeneral) {
        GeneralSettingsDialog(state = state, onDismiss = { editingGeneral = false })
    }

    if (editingSettings) {
        SurveySettingsDialog(
            sketch = state.survey.getSketch(state.projection),
            onDismiss = { editingSettings = false },
            onSaved = {
                state.noteSketchEdited()
                editingSettings = false
            },
        )
    }

    if (editingInstruments) {
        InstrumentSettingsDialog(
            settings = state.surveySettings,
            preferences = state.preferences,
            onDismiss = { editingInstruments = false },
            onSave = { settings, preferences ->
                state.updateSettings(settings)
                state.updatePreferences(preferences)
                editingInstruments = false
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
                    NamingIntent.NEW -> {
                        state.newSurvey(name)
                        state.note(Strings.fileStartedNewSurvey)
                    }
                    NamingIntent.SAVE_AS -> {
                        state.saveLiveSurveyAs(name)
                        state.note(Strings.fileSurveySaved)
                    }
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
            // The screen's own name, as every one of the app's activities is labelled — not the
            // survey's, which `GraphView.drawLegend` puts on the drawing itself.
            Text(
                screenTitle(state),
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

        // `action_save`, the first of `action_bar.xml`'s four always-visible actions. This port
        // writes after every change, so the button is a flush and a confirmation rather than the
        // only thing standing between a trip and losing it — but it is where a SexyTopo user's
        // thumb goes, and a survey saved on demand before leaving a cave is worth the column.
        ToolbarButton(
            painter = painterResource(Res.drawable.save),
            description = Strings.actionFileSave,
            modifier = Modifier.widthOfAnAction(),
            onClick = {
                state.mode = SurveyMode.LIVE
                state.saveLiveSurvey()
                state.note(
                    if (state.storageProblem == null) {
                        Strings.fileSurveySaved
                    } else {
                        Strings.fileSaveSurveyError
                    },
                )
            },
        )
        ToolbarButton(
            painter = painterResource(Res.drawable.table),
            description = Strings.actionTable,
            modifier = Modifier.widthOfAnAction(),
            onClick = { state.screen = Screen.TABLE },
        )
        ToolbarButton(
            painter = painterResource(Res.drawable.plan),
            description = Strings.actionPlan,
            modifier = Modifier.widthOfAnAction(),
            onClick = {
                state.screen = Screen.SKETCH
                state.projection = Projection2D.PLAN
            },
        )
        ToolbarButton(
            painter = painterResource(Res.drawable.elevation),
            description = Strings.actionElevation,
            modifier = Modifier.widthOfAnAction(),
            onClick = {
                state.screen = Screen.SKETCH
                state.projection = Projection2D.EXTENDED_ELEVATION
            },
        )

        Box {
            OverflowGlyph(
                Modifier
                    // The one control on the app bar with no name of its own to read out, and the
                    // way into every menu — so it is named here for a screen reader as well as for
                    // the checks that drive the app through the same tree.
                    .semantics { contentDescription = Strings.actionOverflow }
                    .testTag("overflow")
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
                // `action_bar.xml`'s own submenus, one page at a time: Material 3 has no nested
                // DropdownMenu, so the one menu swaps its contents.
                if (page == MenuPage.TOP) {
                    MenuGroup(Strings.actionFile, MenuPage.FILE) { page = it }
                    MenuGroup(Strings.actionView, MenuPage.VIEW) { page = it }
                    MenuGroup(Strings.actionDevice, MenuPage.INSTRUMENT) { page = it }
                    MenuGroup(Strings.actionInput, MenuPage.INPUT) { page = it }
                    MenuGroup(Strings.actionTools, MenuPage.TOOLS) { page = it }
                    MenuGroup(Strings.actionSettings, MenuPage.SETTINGS) { page = it }
                    MenuGroup(Strings.actionHelp, MenuPage.HELP) { page = it }
                    HorizontalDivider()
                    // `connection_group`: checkable, and disabled until an instrument has been
                    // chosen, exactly as `onPrepareOptionsMenu` leaves it.
                    MenuAction(
                        Strings.actionConnection,
                        checked = state.session.connected,
                        enabled = state.session.profile != null,
                    ) {
                        state.mode = SurveyMode.LIVE
                        connecting = true
                        menuOpen = false
                    }
                }

                if (page != MenuPage.TOP) {
                    // The one row on this menu with no counterpart in `action_bar.xml`, because an
                    // Android submenu has the system's own back. "‹" not "<": the bundled font has
                    // it, and `FontCoverageTest` checks that.
                    MenuAction(BACK_ROW) { page = page.parent }
                    HorizontalDivider()
                }

                // `action_file`: `basic_file_handling`, then `import_export`.
                if (page == MenuPage.FILE) {
                    MenuAction(Strings.actionFileNew) {
                        naming = NamingIntent.NEW
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuGroup(Strings.actionFileOpen, MenuPage.OPEN) { page = it }
                    MenuAction(Strings.actionFileSave) {
                        state.mode = SurveyMode.LIVE
                        state.saveLiveSurvey()
                        state.note(
                            if (state.storageProblem == null) {
                                Strings.fileSurveySaved
                            } else {
                                Strings.fileSaveSurveyError
                            },
                        )
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.actionFileSaveAs) {
                        state.mode = SurveyMode.LIVE
                        naming = NamingIntent.SAVE_AS
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuGroup(Strings.actionFileDelete, MenuPage.DELETE) { page = it }
                    HorizontalDivider()
                    MenuGroup(Strings.actionFileImport, MenuPage.IMPORT) { page = it }
                    MenuAction(
                        Strings.actionFileExport,
                        checked = state.screen == Screen.EXPORT,
                    ) {
                        state.screen = Screen.EXPORT
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    // The same zip the export screen's own button writes: `SurveyZipSharer` is
                    // reached from the app's File menu too, not only from the export screen.
                    MenuAction(Strings.actionFileShare) {
                        val where =
                            saveBinaryFile(
                                SurveyZip.fileNameFor(state.survey),
                                SurveyZip.archive(state.survey),
                            )
                        state.note(where ?: Strings.fileSaveSurveyError)
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `action_file_open`: the Android app opens a folder chooser, which this port has
                // no equivalent of — its surveys live in one place, so they are listed instead.
                if (page == MenuPage.OPEN) {
                    if (state.savedSurveys.isEmpty()) {
                        MenuAction(Strings.noData, enabled = false) {}
                    }
                    for (name in state.savedSurveys) {
                        MenuAction(
                            name,
                            checked = state.mode == SurveyMode.LIVE &&
                                state.liveSurvey.name == name,
                        ) {
                            state.openSurvey(name)
                            menuOpen = false
                            page = MenuPage.TOP
                        }
                    }
                }

                // `action_file_delete`, likewise: which survey, then the app's own confirmation.
                if (page == MenuPage.DELETE) {
                    if (state.savedSurveys.isEmpty()) {
                        MenuAction(Strings.noData, enabled = false) {}
                    }
                    for (name in state.savedSurveys) {
                        MenuAction(name) {
                            deleting = name
                            menuOpen = false
                            page = MenuPage.TOP
                        }
                    }
                }

                // `action_file_import`'s own submenu. Folder import is not offered: this port has
                // one storage root and no folder chooser to point at another.
                if (page == MenuPage.IMPORT) {
                    MenuAction(Strings.actionFileImportFile) {
                        importing = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `action_view`: the six views, then `view_display`.
                if (page == MenuPage.VIEW) {
                    MenuAction(Strings.actionTrip) {
                        state.mode = SurveyMode.LIVE
                        editingTrip = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(
                        Strings.actionTable,
                        checked = state.screen == Screen.TABLE,
                    ) {
                        state.screen = Screen.TABLE
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(
                        Strings.actionPlan,
                        checked = state.screen == Screen.SKETCH &&
                            state.projection == Projection2D.PLAN,
                    ) {
                        state.screen = Screen.SKETCH
                        state.projection = Projection2D.PLAN
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(
                        Strings.actionElevation,
                        checked = state.screen == Screen.SKETCH &&
                            state.projection == Projection2D.EXTENDED_ELEVATION,
                    ) {
                        state.screen = Screen.SKETCH
                        state.projection = Projection2D.EXTENDED_ELEVATION
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.action3d, checked = state.viewing3D) {
                        state.viewing3D = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.actionStats) {
                        showingStats = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    HorizontalDivider()
                    // `view_display`, whose one member is `action_fullscreen`.
                    MenuAction(
                        Strings.actionFullscreen,
                        checked = state.preferences.fullScreen,
                    ) {
                        state.updatePreferences(
                            state.preferences.copy(fullScreen = !state.preferences.fullScreen),
                        )
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    HorizontalDivider()
                    // This port's own, and the one thing on this menu the Android app has no
                    // counterpart for: a generated cave to look at before a real one exists.
                    MenuAction(
                        SurveyMode.EXAMPLE.label,
                        checked = state.mode == SurveyMode.EXAMPLE,
                    ) {
                        state.mode = SurveyMode.EXAMPLE
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `action_device_menu`: *Connect…*, then whatever the connected instrument's own
                // `getCustomCommands` adds. Only the DistoX family's calibration is carried here.
                if (page == MenuPage.INSTRUMENT) {
                    MenuAction(
                        Strings.actionDeviceConnect,
                        checked = state.session.connected,
                    ) {
                        state.mode = SurveyMode.LIVE
                        connecting = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(
                        Strings.deviceCommandCalibration,
                        checked = state.session.calibrating,
                    ) {
                        state.mode = SurveyMode.LIVE
                        calibrating = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `action_input`'s `input_mode_group`, which is `checkableBehavior="single"`.
                if (page == MenuPage.INPUT) {
                    for (mode in InputMode.entries) {
                        MenuAction(
                            labelFor(mode),
                            checked = state.inputMode == mode,
                        ) {
                            state.chooseInputMode(mode)
                            menuOpen = false
                            page = MenuPage.TOP
                        }
                    }
                }

                // `action_tools`: `tools_group_edit`, `tools_group_manual_entry`,
                // `tools_group_diagnostics`. *Generate Test Survey* is *View → Example cave*
                // here, since this port ships one rather than generating one.
                if (page == MenuPage.TOOLS) {
                    MenuAction(Strings.actionUndoLastLeg) {
                        undoingLastLeg = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.actionFindStation) {
                        finding = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    HorizontalDivider()
                    // Unlike *Add reading* on the field bar, these write the leg down
                    // immediately rather than holding it to the instrument's rules.
                    MenuAction(Strings.actionAddLeg) {
                        addingLeg = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.actionAddSplay) {
                        addingSplay = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    HorizontalDivider()
                    MenuAction(Strings.actionSystemLog) {
                        showingLog = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `action_settings`: *System* and *Survey*, exactly the app's two.
                if (page == MenuPage.SETTINGS) {
                    MenuGroup(Strings.actionSettingsSystem, MenuPage.SYSTEM_SETTINGS) { page = it }
                    MenuAction(Strings.actionSettingsSurvey) {
                        editingSettings = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `preferences_main.xml`: the sections of the Android settings screen this port
                // carries. Export, Team, Copyright and Developer are not among them yet.
                if (page == MenuPage.SYSTEM_SETTINGS) {
                    MenuAction(Strings.settingsGeneralTitle) {
                        editingGeneral = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.settingsSketchingTitle) {
                        editingSketchStyle = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.settingsManualDataEntryTitle) {
                        editingManualEntry = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    MenuAction(Strings.settingsInstrumentsTitle) {
                        editingInstruments = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }

                // `help_menu`.
                if (page == MenuPage.HELP) {
                    MenuAction(Strings.actionGuide) {
                        state.viewingManual = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                    // Not decoration: this build carries GPL-3.0 code whose licence and authors
                    // need to be visible somewhere.
                    MenuAction(Strings.actionAbout) {
                        showingAbout = true
                        menuOpen = false
                        page = MenuPage.TOP
                    }
                }
            }
        }
    }
}

/**
 * `android:label` on whichever of the app's activities this screen stands for.
 *
 * The export screen is the one with no counterpart: the Android app's export is a format-picker
 * dialog over whatever screen was showing, and has no title of its own.
 */
private fun screenTitle(state: DemoState): String =
    when {
        state.screen == Screen.TABLE -> Strings.titleTable
        state.screen == Screen.EXPORT -> Screen.EXPORT.label
        state.projection == Projection2D.EXTENDED_ELEVATION -> Strings.titleElevation
        else -> Strings.titlePlan
    }

/** What leaves a page of the overflow menu, since a `DropdownMenu` has no back of its own. */
internal const val BACK_ROW = "\u2039 Back"

/** Which page of the overflow menu is showing: `action_bar.xml`'s submenus, one at a time. */
enum class MenuPage {
    TOP,
    FILE,
    OPEN,
    DELETE,
    IMPORT,
    VIEW,
    INSTRUMENT,
    INPUT,
    TOOLS,
    SETTINGS,
    SYSTEM_SETTINGS,
    HELP,
}

/**
 * Where *Back* goes: the menu that opened this one, which for the pages nested two deep is not the
 * top of the menu.
 */
internal val MenuPage.parent: MenuPage
    get() =
        when (this) {
            MenuPage.OPEN, MenuPage.DELETE, MenuPage.IMPORT -> MenuPage.FILE
            MenuPage.SYSTEM_SETTINGS -> MenuPage.SETTINGS
            else -> MenuPage.TOP
        }

/**
 * One item of the overflow menu.
 *
 * Every row carries a [CheckDot] whether or not it is checkable, so labels line up in a column the
 * way an Android menu's do — a row without one sits four pixels left of its neighbours.
 */
@Composable
private fun MenuAction(
    label: String,
    checked: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        leadingIcon = { CheckDot(checked) },
        onClick = onClick,
        modifier = Modifier.testTag(tagFor(label)),
    )
}

@Composable
private fun MenuGroup(label: String, opens: MenuPage, onOpen: (MenuPage) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { CheckDot(false) },
        // "›" not ">": the bundled font has it, and `FontCoverageTest` checks that.
        trailingIcon = { Text("\u203A", style = MaterialTheme.typography.bodyMedium) },
        onClick = { onOpen(opens) },
        modifier = Modifier.testTag(tagFor(label)),
    )
}

/**
 * A stable handle for a row of the overflow menu.
 *
 * Compose's `testTag` becomes the DOM element's `id` in the browser's accessibility tree, so this
 * is what lets the browser tests ask for *Undo Last Reading* by name instead of working out which
 * pixel it is drawn at. Derived from the label rather than passed in at forty call sites: the
 * labels are `Strings`, and `AndroidStringsTest` holds every one of them to `strings.xml`, so a
 * tag cannot drift without a test saying so.
 *
 * Two rows on the same page must not slug to the same thing. They do not: the only labels this
 * port repeats are on pages that are never open at once.
 */
internal fun tagFor(label: String): String =
    "menu-" +
        label
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")

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
 * What the Android app would say in a `Toast`, in the one place this port can put it.
 *
 * The Android app confirms *Saved*, *Started new survey* and the rest with a toast, which is a
 * platform affordance Compose Multiplatform has on none of its four targets. A strip under the app
 * bar that clears itself is the same promise: a menu item that changes nothing on screen still
 * says it did something.
 */
@Composable
private fun Notice(state: DemoState) {
    val message = state.notice ?: return

    LaunchedEffect(message) {
        delay(NOTICE_MILLIS)
        // Only if it is still the same one: a second action while this is up replaces the text,
        // and clearing unconditionally would cut the newer message short.
        if (state.notice == message) state.notice = null
    }

    Text(
        message,
        Modifier
            .fillMaxWidth()
            .background(SexyTopoColours.innerPanel)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = SexyTopoColours.legend,
    )
}

/** Long enough to read at arm's length by head torch, short enough not to sit in the way. */
private const val NOTICE_MILLIS = 2500L

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

/**
 * The table's two floating action buttons: `manuallyAddSplay` above `manuallyAddStation`.
 *
 * Its own composable because both of them open the same dialog the *Tools* menu does, and the
 * dialog needs state that belongs beside the buttons rather than in the app bar.
 */
@Composable
private fun ManualEntryFabs(state: DemoState, modifier: Modifier = Modifier) {
    var addingLeg by remember { mutableStateOf(false) }
    var addingSplay by remember { mutableStateOf(false) }

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
            onAdd = { added ->
                addLegOutright(
                    survey = state.liveSurvey,
                    leg = added.leg,
                    asSplay = splay,
                    toName = added.toName,
                    toComment = added.toComment,
                    lrud = added.lrud,
                    lrudMode = state.preferences.lrudMode,
                    fromName = added.fromName,
                    legComment = added.legComment,
                )
                if (!splay) state.noteStationCreated()
                state.noteSketchEdited()
                addingLeg = false
                addingSplay = false
            },
        )
    }

    Column(
        modifier.padding(FAB_MARGIN_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FAB_MARGIN_DP.dp),
    ) {
        SmallFloatingActionButton(onClick = { addingSplay = true }) {
            Image(
                painter = painterResource(Res.drawable.add_splay),
                contentDescription = Strings.manualAddSplayTitle,
                modifier = Modifier.size(22.dp),
            )
        }
        FloatingActionButton(onClick = { addingLeg = true }) {
            Image(
                painter = painterResource(Res.drawable.add),
                contentDescription = Strings.manualAddStationTitle,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** `R.dimen.fab_margin` and `fab_vertical_spacing`, which are the same 16dp. */
private const val FAB_MARGIN_DP = 16

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
            Box(modifier) {
                SurveyTableView(
                    survey = state.survey,
                    revision = state.revision,
                    modifier = Modifier.fillMaxSize(),
                    darkMode = state.darkMode,
                    lrudFields = state.preferences.lrudFields,
                    lrudMode = state.preferences.lrudMode,
                    onEdited = { state.noteSketchEdited() },
                    editable = state.mode == SurveyMode.LIVE,
                    onStation = { menuFromTable = true; menuFor = it.name },
                    scrollTo = state.pendingTableJump,
                    onScrolled = { state.tableJumpDone() },
                )
                // `fabAddStation` and `fabAddSplay`, which `activity_table.xml` stacks in the
                // bottom corner of the table and `updateManualReadingsFabVisibility` hides when
                // `pref_manual_controls` is off.
                if (state.mode == SurveyMode.LIVE && state.preferences.manualControls) {
                    ManualEntryFabs(state, Modifier.align(Alignment.BottomEnd))
                }
            }
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
        crossSectioning = state.crossSectioning,
        onCrossSectionPositioned = { state.finishCrossSection() },
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
        // `handleNewCrossSection`: the section is not drawn yet. The tool is armed for this
        // station and the next tap on the paper says where it goes, which is the one thing about
        // a cross-section only the surveyor can decide.
        onCreateCrossSection = { at ->
            state.beginCrossSection(at)
            onClose()
        },
        onDeleteCrossSection = { editor.delete(it) },
        // `handleRotateCrossSection`: the tool takes over the next drag, and the section swings
        // round its station under the finger until it cuts the passage square.
        onSetCrossSectionDirection = { state.chooseTool(SketchTool.ROTATE_CROSS_SECTION) },
        fromTable = fromTable,
        legacyCrossSections = state.preferences.legacyCrossSections,
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
        // The chip on the field bar is about the active station's *name*; its comment and its
        // passage measurements are on the station's own menu, a long press away, as upstream.
        StationActionsDialog(
            survey = state.liveSurvey,
            station = state.liveSurvey.activeStation,
            fields = StationFields.NAME,
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
            Button(
                onClick = { entering = true },
                modifier = Modifier.testTag("add-reading"),
            ) { Text(Strings.addReading) }
        }
        if (controls.simulator) {
            Button(
                onClick = { session.takeReading() },
                modifier = Modifier.testTag("simulate"),
            ) { Text(Strings.simulate) }
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
        Button(
            onClick = { state.mode = SurveyMode.LIVE },
            modifier = Modifier.testTag("start-surveying"),
        ) {
            Text(
                if (state.savedSurveys.isEmpty()) Strings.startSurveying else Strings.mySurvey,
            )
        }
        Text(
            Strings.demoCaveIsNotKept,
            style = MaterialTheme.typography.bodySmall,
            color = if (dark) SexyTopoColours.legendNight else SexyTopoColours.legend,
        )
        Spacer(Modifier.weight(1f))
    }
}
