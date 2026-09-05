package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.log.LogType
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.BrushColour
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.InputMode
import org.hwyl.sexytopo.shared.survey.SurveySettings

/** Which screen is showing: the sketch, or the numbers behind it. */
enum class Screen(val label: String) {
    SKETCH("Sketch"),
    TABLE("Table"),
    EXPORT("Export"),
}

enum class SurveyMode(val label: String) {
    EXAMPLE("Demo cave"),
    LIVE("Live survey"),
}

/**
 * Everything the demo's chrome can change, in one place.
 *
 * Pulled out of the composables so a value used by one part of the UI can't drift from what
 * another part reads.
 */
class DemoState(
    val exampleSurvey: Survey,
    initialProjection: Projection2D,
    initialSystemDark: Boolean,
    /** A tool to open on, or null to use whichever the surveyor last chose. */
    initialTool: SketchTool?,
    initialMode: SurveyMode,
    initialScreen: Screen,
    /** Where surveys are kept between runs; a parameter so a test can substitute an in-memory store. */
    val library: SurveyLibrary = SurveyLibrary(),
) {
    var mode by mutableStateOf(initialMode)
    var screen by mutableStateOf(initialScreen)
    var projection by mutableStateOf(initialProjection)
    /** The tool the toolbar has lit. Set through [chooseTool] so it reaches the preferences file. */
    var tool by mutableStateOf(initialTool ?: SketchTool.DEFAULT)
        private set

    /** Whether the caller named a tool rather than leaving it to the saved one. */
    private val seededTool = initialTool != null

    var brushColour by mutableStateOf(Colour.BLACK)
        private set

    /** What the platform says about light and dark, kept live since it can change mid-session. */
    var systemDark by mutableStateOf(initialSystemDark)

    /**
     * Whether to draw dark, derived rather than stored: a plain toggle is how this got forgotten
     * on every restart before.
     */
    val darkMode: Boolean
        get() = preferences.theme.isDark(systemDark)

    /** How the surveyor is holding the instrument, which decides what a run of readings means. */
    var inputMode by mutableStateOf(InputMode.FORWARD)
        private set

    /**
     * How close repeated readings must be before they make a station — not left at the
     * DistoX-tuned default, or a compass-and-tape survey would never promote one.
     */
    var surveySettings by mutableStateOf(SurveySettings.DEFAULT)
        private set

    /** Bring back a calibration that was interrupted; saved on every change so nothing is lost. */
    fun loadCalibration() {
        val saved = library.loadCalibration()
        if (saved.isEmpty()) return
        session.calibration.clear()
        saved.forEach(session.calibration::add)
    }

    fun noteCalibrationChanged() {
        library.saveCalibration(session.calibration.readings)
    }

    /** Bring back what the instrument was doing last time, and keep writing it down. */
    fun loadLog() {
        val saved = library.loadLog(LogType.DEVICE)
        if (saved.isNotEmpty()) session.deviceLog.replaceAll(saved)
        session.onLogged = { library.saveLog(LogType.DEVICE, session.deviceLog.entries) }
    }

    fun clearLog() {
        session.deviceLog.clear()
        library.saveLog(LogType.DEVICE, emptyList())
    }

    fun loadSettings() {
        surveySettings = library.loadSettings()
        preferences = library.loadPreferences()
        session.onStationCreated = ::noteStationCreated
        session.autoReconnect = preferences.reconnection
        session.settings = surveySettings
        restoreSelections()
        session.inputMode = inputMode
    }

    /** Put the surveyor back where they left off, except for whatever the caller seeded itself. */
    private fun restoreSelections() {
        if (!seededTool) tool = preferences.tool
        brushColour = preferences.brushColour
        symbol = preferences.symbol
        inputMode = preferences.inputMode
    }

    var preferences by mutableStateOf(AppPreferences.DEFAULT)
        private set

    /** A station has been made: buzzes if the preference is on, and bumps the counter. */
    fun noteStationCreated() {
        if (preferences.buzzOnNewStation) buzz()
        stationsCreated++
    }

    /**
     * How many stations have been made this session — a counter rather than a callback, since
     * the thing that has to react is the viewport, which belongs to the canvas composable and
     * not to this state.
     */
    var stationsCreated by mutableIntStateOf(0)
        private set

    /**
     * Connect to an instrument, and remember which one it was.
     *
     * Remembered even when the platform has no radio to try it on: what the surveyor owns is worth
     * keeping either way, and the connection screen reads better with their own instrument already
     * chosen.
     */
    fun useInstrument(profile: InstrumentProfile) {
        session.useInstrument(profile)
        if (preferences.lastInstrument != profile.name) {
            updatePreferences(preferences.copy(lastInstrument = profile.name))
        }
    }

    /** The instrument last connected to, if this app has met one and still recognises the name. */
    val lastInstrument: InstrumentProfile?
        get() =
            preferences.lastInstrument?.let { name ->
                InstrumentProfile.ALL.firstOrNull { it.name == name }
            }

    /**
     * Pick the last instrument back up when the app opens.
     *
     * Gated on the auto-reconnect setting, which is the same question asked at a different moment:
     * somebody who wants a dropped instrument chased wants it picked up again on Monday too, and
     * somebody who has turned that off does not want their radio woken by opening the app to look
     * at a survey at home.
     */
    fun resumeLastInstrument() {
        if (!preferences.autoReconnect) return
        val profile = lastInstrument ?: return
        session.useInstrument(profile)
    }

    fun updatePreferences(updated: AppPreferences) {
        preferences = updated
        session.autoReconnect = updated.reconnection
        session.traceFrames = updated.developerMode
        if (!library.savePreferences(updated)) {
            storageProblem = library.lastError ?: "could not save preferences"
        }
    }

    fun updateSettings(settings: SurveySettings) {
        surveySettings = settings
        session.settings = settings
        if (!library.saveSettings(settings)) {
            storageProblem = library.lastError ?: "could not save settings"
        }
    }

    /** Which symbol the stamp tool will place, held here so it survives the dialog closing. */
    var symbol by mutableStateOf(Symbol.ENTRANCE)
        private set

    /** The cross-section whose own drawing is open, if any — a state rather than a separate screen. */
    var editingCrossSection by mutableStateOf<CrossSectionDetail?>(null)

    var viewing3D by mutableStateOf(false)

    /** Whether the manual has the screen. Not persisted. */
    var viewingManual by mutableStateOf(false)

    /** The six sketch toggles, as views onto [preferences] rather than as state of their own. */
    var snapToLines: Boolean
        get() = preferences.snapToLines
        set(value) = updatePreferences(preferences.copy(snapToLines = value))

    var showSplays: Boolean
        get() = preferences.showSplays
        set(value) = updatePreferences(preferences.copy(showSplays = value))

    var showSketch: Boolean
        get() = preferences.showSketch
        set(value) = updatePreferences(preferences.copy(showSketch = value))

    var showLabels: Boolean
        get() = preferences.showStationLabels
        set(value) = updatePreferences(preferences.copy(showStationLabels = value))

    var showGrid: Boolean
        get() = preferences.showGrid
        set(value) = updatePreferences(preferences.copy(showGrid = value))

    var showCompass: Boolean
        get() = preferences.showCompass
        set(value) = updatePreferences(preferences.copy(showCompass = value))

    /** The survey being built, kept even while the demo cave is showing. */
    var liveSurvey by mutableStateOf(Survey(DEFAULT_NEW_SURVEY_NAME))
        private set

    var session by mutableStateOf(SurveySession(liveSurvey))
        private set

    /** Names of the saved surveys, refreshed whenever the library changes. */
    var savedSurveys by mutableStateOf<List<String>>(emptyList())
        private set

    /** Set when a save fails, so the UI can say so without interrupting the surveyor. */
    var storageProblem by mutableStateOf<String?>(null)
        private set

    /**
     * What the Android app would say in a `Toast` — *Saved*, *Started new survey* — shown as a
     * strip under the app bar and cleared a moment later.
     *
     * Not an error channel: [storageProblem] is that. This is the confirmation that a menu item
     * which changes nothing visible actually did something, without which *Save* is a button that
     * appears to do nothing at all.
     */
    var notice by mutableStateOf<String?>(null)

    fun note(message: String) {
        notice = message
    }

    val survey: Survey
        get() = if (mode == SurveyMode.EXAMPLE) exampleSurvey else liveSurvey

    // -------------------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------------------

    fun refreshLibrary() {
        savedSurveys = library.list()
    }

    /** Writes the live survey out after every change. The demo cave is never saved. */
    fun saveLiveSurvey() {
        if (liveSurvey.getAllLegsInChronoOrder().isEmpty() && liveSurvey.planSketch.isEmpty()) return
        val ok = library.save(liveSurvey)
        storageProblem = if (ok) null else (library.lastError ?: "could not save")
        if (ok) refreshLibrary()
    }

    fun openSurvey(name: String): Boolean {
        val loaded = library.open(name) ?: run {
            storageProblem = library.lastError ?: "could not open $name"
            return false
        }
        adopt(loaded)
        importProblem = library.lastWarning
        return true
    }

    fun newSurvey(name: String) {
        adopt(Survey(library.uniqueName(name)))
        saveLiveSurvey()
    }

    /** Removes a survey from storage, starting a fresh one if it was the one open. */
    fun deleteSurvey(name: String) {
        if (!library.delete(name)) {
            storageProblem = library.lastError ?: "could not delete $name"
            return
        }
        storageProblem = null
        if (liveSurvey.name == name) {
            adopt(Survey(DEFAULT_NEW_SURVEY_NAME))
        }
        refreshLibrary()
    }

    /** Survey-shaped files sitting in the app's own storage, waiting to be brought in. */
    fun importCandidates(): List<String> = library.importCandidates()

    /**
     * Brings one in and opens it, returning the name it ended up with — which can differ from
     * the file's, to avoid overwriting a survey already in the library.
     */
    fun importSurvey(fileName: String): String? {
        val imported = library.import(fileName) ?: run {
            storageProblem = library.lastError ?: "could not import $fileName"
            return null
        }
        adopt(imported)
        importProblem = library.lastWarning
        return imported.name
    }

    /**
     * Something came in, but not all of it — separate from [storageProblem], which is about
     * writing rather than reading.
     */
    var importProblem: String? by mutableStateOf(null)

    fun renameLiveSurvey(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == liveSurvey.name) return
        val previous = liveSurvey.name
        liveSurvey.name = library.uniqueName(trimmed)
        if (library.save(liveSurvey)) {
            library.delete(previous)
            refreshLibrary()
        }
        sketchRevision++
    }

    /**
     * `action_file_save_as`: the same survey under a second name, with the first left where it is.
     *
     * The difference from [renameLiveSurvey] is the one that matters underground — the old copy
     * survives, which is what somebody branching a trip off yesterday's survey is asking for. The
     * Android app does it by choosing a directory to write into; here the name *is* the directory.
     */
    fun saveLiveSurveyAs(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == liveSurvey.name) return
        liveSurvey.name = library.uniqueName(trimmed)
        if (library.save(liveSurvey)) {
            refreshLibrary()
        } else {
            storageProblem = library.lastError ?: "could not save"
        }
        sketchRevision++
    }

    private fun adopt(survey: Survey) {
        // The instrument belongs to the surveyor, not to the survey. Opening another one used to
        // drop the session on the floor with its transport still connected and still observed, so
        // the radio stayed on, readings went into a survey nobody could see any more, and the
        // instrument had to be connected again by hand.
        val instrumentInUse = session.profile
        session.disconnect()

        liveSurvey = survey
        session = SurveySession(survey, surveySettings)
        // A new session starts on the class defaults rather than on what is saved, so without
        // this, opening a second survey would quietly put the reconnection settings back to them.
        session.autoReconnect = preferences.reconnection
        session.traceFrames = preferences.developerMode
        session.inputMode = inputMode
        session.onStationCreated = ::noteStationCreated
        instrumentInUse?.let { session.useInstrument(it) }
        mode = SurveyMode.LIVE
        storageProblem = null
        importProblem = null
        sketchRevision++
        refreshLibrary()
    }

    /**
     * Sketches are mutated in place rather than replaced, so nothing about editing one is
     * observable to Compose. This counter is what tells the canvas to look again.
     */
    var sketchRevision by mutableIntStateOf(0)
        private set

    fun noteSketchEdited() {
        sketchRevision++
    }

    /**
     * A station the app has been asked to show on a drawing, by name — deferred because the
     * canvas for the projection being switched to may not exist yet.
     */
    var pendingJump by mutableStateOf<String?>(null)
        private set

    fun showOnDrawing(station: Station, wanted: Projection2D) {
        projection = wanted
        screen = Screen.SKETCH
        pendingJump = station.name
    }

    fun jumpDone() {
        pendingJump = null
    }

    var pendingTableJump by mutableStateOf<String?>(null)
        private set

    fun showInTable(station: Station) {
        screen = Screen.TABLE
        pendingTableJump = station.name
    }

    fun tableJumpDone() {
        pendingTableJump = null
    }

    val revision: Int
        get() = sketchRevision + session.revision

    /** Picking a colour while a non-drawing tool is active switches to drawing, as in the app. */
    fun pickColour(colour: Colour, editor: SketchEditor) {
        brushColour = colour
        editor.activeColour = colour
        if (!tool.usesColour) tool = SketchTool.DRAW
        rememberSelections()
    }

    /**
     * Choose a tool. One-shot tools are not restored on the way back in — see
     * [AppPreferencesStore.restorableTool].
     */
    fun chooseTool(tool: SketchTool) {
        this.tool = tool
        rememberSelections()
    }

    fun chooseSymbol(symbol: Symbol) {
        this.symbol = symbol
        rememberSelections()
    }

    /**
     * The station whose cross-section is waiting to be put somewhere:
     * `stationNameBeingCrossSectioned`.
     */
    var crossSectioning: Station? by mutableStateOf(null)
        private set

    /** What was in the surveyor's hand before, since positioning a section is a one-shot tool. */
    private var toolBeforeCrossSection: SketchTool? = null

    /**
     * `handleNewCrossSection`: arm the tool and say what to do with it.
     *
     * The Android app does not decide where the section goes. It cannot: the only sensible place
     * is wherever there is white paper next to the passage, which the app has no way of knowing
     * and the surveyor can see. So *Create Cross Section* asks, and this is the asking.
     */
    fun beginCrossSection(station: Station) {
        crossSectioning = station
        toolBeforeCrossSection = tool
        chooseTool(SketchTool.POSITION_CROSS_SECTION)
        note(Strings.sketchPositionCrossSectionInstruction)
    }

    /** The tail of `handlePositionCrossSection`: one tap, then the previous tool comes back. */
    fun finishCrossSection() {
        crossSectioning = null
        chooseTool(toolBeforeCrossSection ?: AppPreferences.DEFAULT_TOOL)
        toolBeforeCrossSection = null
    }

    /**
     * Choose how the instrument is being held — the one setting here that changes what the
     * numbers mean, not just how they look.
     */
    fun chooseInputMode(mode: InputMode) {
        inputMode = mode
        // The session promotes readings from the instrument, so it has to be told too — see
        // [SurveySession.inputMode].
        session.inputMode = mode
        rememberSelections()
    }

    private fun rememberSelections() {
        updatePreferences(
            preferences.copy(
                tool = tool,
                brushColour = brushColour,
                symbol = symbol,
                inputMode = inputMode,
            ),
        )
    }

    fun undo(editor: SketchEditor) {
        if (editor.undo()) noteSketchEdited()
    }

    fun redo(editor: SketchEditor) {
        if (editor.redo()) noteSketchEdited()
    }

    fun selectStation(stationName: String): Boolean {
        val station = survey.getStationByName(stationName) ?: return false
        if (station === survey.activeStation) return false
        survey.activeStation = station
        return true
    }

    val displayOptions: DisplayOptions
        get() =
            DisplayOptions(
                showSplays = showSplays,
                showSketch = showSketch,
                showStationLabels = showLabels,
                showGrid = showGrid,
                darkMode = darkMode,
                snapToLines = snapToLines,
                showCompass = showCompass,
                hotCorners = preferences.hotCorners,
                twoFingerMove = preferences.twoFingerMove,
                fadeNonActive = preferences.fadeNonActive,
                highlightLatestLeg = preferences.highlightLatestLeg,
                blueWater = preferences.blueWater,
                showCrossSections = preferences.showCrossSections,
                legacyCrossSections = preferences.legacyCrossSections,
                pinchToZoom = preferences.pinchToZoom,
                deletePathFragments = preferences.deletePathFragments,
                style = preferences.sketchStyle,
            )
}

/**
 * The editor for whichever sketch is currently showing — one per sketch, so switching projection
 * doesn't lose undo history. Keyed by identity, since [Sketch] does not override equals.
 */
@Composable
fun rememberSketchEditor(state: DemoState): SketchEditor {
    val editors = remember(state) { mutableMapOf<Sketch, SketchEditor>() }
    val sketch = state.survey.getSketch(state.projection)
    val editor =
        editors.getOrPut(sketch) {
            SketchEditor(sketch).also { it.activeColour = state.brushColour }
        }
    editor.activeColour = state.brushColour
    return editor
}

/** The view that survives a rebuild of the canvas composable, one per sketch. */
@Composable
fun rememberCanvasController(state: DemoState): CanvasController {
    val controllers = remember(state) { mutableMapOf<Sketch, CanvasController>() }
    val sketch = state.survey.getSketch(state.projection)
    return controllers.getOrPut(sketch) { CanvasController() }
}

/** A view toggle, declared once so the drawing menu and any other caller cannot drift apart. */
class ViewToggle(
    val label: String,
    val get: (DemoState) -> Boolean,
    val toggle: (DemoState) -> Unit,
)

/**
 * `drawingMenuBehaviourToggles`: the four settings that change what the app does, not what's drawn.
 *
 * In `drawing.xml`'s own order, under its own labels.
 */
val BEHAVIOUR_TOGGLES =
    listOf(
        ViewToggle(
            Strings.sketchMenuAutoRecentre,
            { it.preferences.autoRecentre },
            { it.updatePreferences(it.preferences.copy(autoRecentre = !it.preferences.autoRecentre)) },
        ),
        ViewToggle(
            Strings.sketchMenuSnapToLines,
            { it.snapToLines },
            { it.snapToLines = !it.snapToLines },
        ),
        ViewToggle(
            Strings.sketchMenuBlueWater,
            { it.preferences.blueWater },
            { it.updatePreferences(it.preferences.copy(blueWater = !it.preferences.blueWater)) },
        ),
        ViewToggle(
            Strings.sketchMenuPinchToZoom,
            { it.preferences.pinchToZoom },
            { it.updatePreferences(it.preferences.copy(pinchToZoom = !it.preferences.pinchToZoom)) },
        ),
    )

/**
 * `drawingMenuDisplayToggles`: the ones that change what is drawn, in `drawing.xml`'s own order
 * and under its own labels.
 *
 * `buttonShowConnections` has nothing behind it here — reaching a neighbouring survey needs a
 * `content://` URI, out of scope for this port — so it is the one row of that group not carried
 * across. `pref_highlight_latest_leg` is not on this menu in the Android app either: it is a
 * Sketching preference, and this port had it here until the settings screens were squared up.
 */
val DISPLAY_TOGGLES =
    listOf(
        ViewToggle(
            Strings.sketchMenuFadeNonActive,
            { it.preferences.fadeNonActive },
            { it.updatePreferences(it.preferences.copy(fadeNonActive = !it.preferences.fadeNonActive)) },
        ),
        ViewToggle(
            Strings.sketchMenuShowSplays,
            { it.showSplays },
            { it.showSplays = !it.showSplays },
        ),
        ViewToggle(
            Strings.sketchMenuShowCrossSections,
            { it.preferences.showCrossSections },
            {
                it.updatePreferences(
                    it.preferences.copy(showCrossSections = !it.preferences.showCrossSections),
                )
            },
        ),
        ViewToggle(
            Strings.sketchMenuShowSketch,
            { it.showSketch },
            { it.showSketch = !it.showSketch },
        ),
        ViewToggle(Strings.sketchMenuShowGrid, { it.showGrid }, { it.showGrid = !it.showGrid }),
        ViewToggle(
            Strings.sketchMenuShowStationLabels,
            { it.showLabels },
            { it.showLabels = !it.showLabels },
        ),
        ViewToggle(
            Strings.sketchMenuShowCompass,
            { it.showCompass },
            { it.showCompass = !it.showCompass },
        ),
    )

/**
 * The colours the Android app offers on its sketch toolbar — the shared [BrushColour] list, not a
 * demo-chosen subset of the 144 colours the model can store.
 */
val BRUSH_COLOURS = BrushColour.entries.map { it.colour }

/** The tools offered as toolbar buttons, out of the eleven [SketchTool] knows about. */
val DEMO_TOOLS =
    listOf(SketchTool.MOVE, SketchTool.DRAW, SketchTool.ERASE, SketchTool.SELECT)

/** Toolbar labels. The shared enum carries behaviour, not display strings. */
val SketchTool.label: String
    get() =
        when (this) {
            SketchTool.MOVE -> "Move"
            SketchTool.DRAW -> "Draw"
            SketchTool.ERASE -> "Erase"
            SketchTool.SELECT -> "Select"
            SketchTool.SYMBOL -> "Symbol"
            SketchTool.TEXT -> "Label"
            SketchTool.POSITION_CROSS_SECTION -> "Cross-section"
            SketchTool.ROTATE_CROSS_SECTION -> "Re-aim"
            SketchTool.MOVE_CROSS_SECTION -> "Move section"
            else -> name.lowercase().replaceFirstChar { it.uppercase() }
        }

/** The app bar's subtitle: what the shared core counted, and nothing else. */
fun subtitle(state: DemoState): String {
    @Suppress("UNUSED_VARIABLE")
    val revision = state.revision
    val space = state.projection.project(state.survey)
    val legs = space.legMap.keys.count { it.hasDestination() }
    val sketch = state.survey.getSketch(state.projection)
    return "${plural(space.stationMap.size, "station")} · ${plural(legs, "leg")} · " +
        plural(sketch.pathDetails.size, "line")
}

/** The one-line status used by the desktop window title and the tests. */
fun summarise(state: DemoState, compact: Boolean): String {
    val survey = state.survey
    val space = state.projection.project(survey)
    val legs = space.legMap.keys.count { it.hasDestination() }
    val splays = space.legMap.size - legs
    val sketch = survey.getSketch(state.projection)

    // Null while off the sketch screen, since the hint describes what the finger does on canvas.
    val hint =
        if (state.screen != Screen.SKETCH) {
            null
        } else {
            when (state.tool) {
                SketchTool.MOVE -> "drag to pan, pinch to zoom"
                SketchTool.ERASE -> "rub over the lines to take them out"
                SketchTool.SELECT -> "tap a station to survey on from it"
                SketchTool.SYMBOL -> "tap to stamp, drag to aim it"
                SketchTool.TEXT -> "tap where the label goes"
                SketchTool.POSITION_CROSS_SECTION -> "tap a station to slice the passage there"
                SketchTool.ROTATE_CROSS_SECTION ->
                    "drag a cross-section round its station to re-aim it"
                SketchTool.MOVE_CROSS_SECTION -> "drag a cross-section somewhere clearer"
                else -> "drag to draw a passage wall"
            }
        }

    if (compact) {
        return listOfNotNull(
            "${space.stationMap.size} stations",
            "$legs legs",
            "${sketch.pathDetails.size} sketch lines",
            hint,
        ).joinToString(" · ")
    }
    return listOfNotNull(
        "${survey.name}: ${space.stationMap.size} stations, $legs legs, $splays splays, " +
            "${sketch.pathDetails.size} sketch lines",
        state.projection.displayName,
        platformName(),
        hint,
    ).joinToString("  ·  ")
}

/** "1 station", "2 stations". */
internal fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

/** What a survey is called before the surveyor names it. */
const val DEFAULT_NEW_SURVEY_NAME = "Live Survey"
