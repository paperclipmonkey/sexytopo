package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Which survey the canvas is showing. */
enum class SurveyMode(val label: String) {
    EXAMPLE("Demo cave"),
    LIVE("Live survey"),
}

/**
 * Everything the demo's chrome can change, in one place.
 *
 * Pulled out of the composables because there are now two layouts — one for a phone, one for a
 * screen with room — and threading a dozen values and their setters through both is how a UI ends
 * up with the two quietly disagreeing. They share this object, so a state that exists in one
 * exists in the other, and switching between them by rotating a tablet loses nothing.
 */
class DemoState(
    val exampleSurvey: Survey,
    initialProjection: Projection2D,
    initialDarkMode: Boolean,
    initialTool: SketchTool,
    initialMode: SurveyMode,
    initialScreen: Screen,
) {
    var mode by mutableStateOf(initialMode)
    var screen by mutableStateOf(initialScreen)
    var projection by mutableStateOf(initialProjection)
    var tool by mutableStateOf(initialTool)
    var brushColour by mutableStateOf(Colour.BLACK)
    var darkMode by mutableStateOf(initialDarkMode)

    /**
     * How the surveyor is holding the instrument, which decides what a run of repeated readings
     * means. A mode rather than a per-reading choice, as in the app: a surveyor working back down a
     * passage takes every shot the same way, and being asked each time would be worse than useless.
     *
     * Kept here rather than in the dialog so it survives the dialog closing, and so the field bar
     * can say when it is not FORWARD — a backsight mode left on by accident reverses every leg that
     * follows, and there is nothing in the numbers afterwards to show it happened.
     */
    var inputMode by mutableStateOf(InputMode.FORWARD)

    /**
     * How close repeated readings must be before they make a station.
     *
     * Held here and passed into every [org.hwyl.sexytopo.shared.survey.SurveyUpdater] call rather
     * than left at [SurveySettings.DEFAULT], because the defaults assume a DistoX: on a trip with
     * a compass and tape, three readings never agree to 1.7 degrees, nothing is ever promoted, and
     * the survey silently fills up with splays.
     */
    var surveySettings by mutableStateOf(SurveySettings.DEFAULT)
        private set

    /** Reads the saved tolerances. Called once at startup, alongside the survey library. */
    /**
     * Bring back a calibration that was interrupted.
     *
     * A run is loaded once, when the app opens, and saved on every change — see
     * [noteCalibrationChanged]. Fifty-six shots is twenty minutes, and losing them to a flat
     * battery means doing all of it again.
     */
    fun loadCalibration() {
        val saved = library.loadCalibration()
        if (saved.isEmpty()) return
        session.calibration.clear()
        saved.forEach(session.calibration::add)
    }

    /** Called whenever the run changes, so an interrupted calibration survives a restart. */
    fun noteCalibrationChanged() {
        library.saveCalibration(session.calibration.readings)
    }

    /**
     * Bring back what the instrument was doing last time, and keep writing it down.
     *
     * Loaded once at startup and saved on every line, because the cases the log exists for - a
     * crash, a freeze, a battery going flat in a cave - are exactly the ones where no tidy-up code
     * runs. A hundred lines of JSON is a few kilobytes; writing it every line costs nothing next to
     * losing the reason an instrument would not connect.
     */
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
    }

    /** The app's own preferences: what it does, rather than what a reading means. */
    var preferences by mutableStateOf(AppPreferences.DEFAULT)
        private set

    /**
     * A station has been made.
     *
     * `NewStationNotificationService` listens for the same event on the Android app's own broadcast
     * and vibrates for 200 ms. It matters because of where it happens: the surveyor is holding an
     * instrument, looking at rock, in the dark, and the phone is in a pocket or on a strap. Without
     * this, the third shot of every leg is followed by finding the screen and reading it.
     *
     * Called from both paths that can create one - the instrument, and readings typed in by hand -
     * because the Java's event is broadcast from `SurveyManager` and so covers both.
     */
    fun noteStationCreated() {
        if (preferences.buzzOnNewStation) buzz()
        stationsCreated++
    }

    /**
     * How many stations have been made this session — the port's equivalent of the Android app's
     * `createdReceiver` broadcast, which is what `handleAutoRecentre` listens to.
     *
     * A counter rather than a callback because the thing that has to react is the *viewport*, and
     * the viewport belongs to the canvas composable rather than to this state: see
     * [rememberCanvasController]. Compose watching a number is the simplest way for one to reach
     * the other without either owning the other.
     */
    var stationsCreated by mutableIntStateOf(0)
        private set

    fun updatePreferences(updated: AppPreferences) {
        preferences = updated
        if (!library.savePreferences(updated)) {
            storageProblem = library.lastError ?: "could not save preferences"
        }
    }

    fun updateSettings(settings: SurveySettings) {
        surveySettings = settings
        if (!library.saveSettings(settings)) {
            storageProblem = library.lastError ?: "could not save settings"
        }
    }

    /**
     * Which symbol the stamp tool will place.
     *
     * Held here rather than in the palette so that choosing one and closing the dialog leaves the
     * tool loaded — a surveyor stamps a dozen boulders in a row, not one.
     */
    var symbol by mutableStateOf(Symbol.ENTRANCE)

    /**
     * The cross-section whose own drawing is open, if any.
     *
     * The Android app makes this a separate activity; here it is a state that takes over the
     * screen. Held on the state rather than inside the sketch screen so that it survives the
     * screen being recomposed for any other reason - losing a half-drawn passage outline because
     * a reading arrived would be its own bug.
     */
    var editingCrossSection by mutableStateOf<CrossSectionDetail?>(null)

    /**
     * Whether the 3D view has the screen.
     *
     * Another Android activity turned into a state, for the same reason: it is a different way of
     * looking at the same survey, with its own gestures, and leaving the sketch toolbar under it
     * would be a lie about what the buttons do.
     */
    var viewing3D by mutableStateOf(false)

    /**
     * The six sketch toggles, as views onto [preferences] rather than as state of their own.
     *
     * They were `mutableStateOf` until the drawing menu was split, which meant a surveyor who
     * turned the splays off — or turned snapping on — got the opposite back on the next run. Every
     * one of them is a persisted `SketchPreferences.Toggle` in the Android app, so the fix is to
     * store them where the other six sketch toggles already live and reach them through the same
     * `updatePreferences`, which writes the file.
     *
     * They stay as properties here because that is what the whole app already reads, and because a
     * `var` whose setter persists reads at the call site exactly like the `var` that did not.
     */
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

    /**
     * Where surveys are kept between runs: `localStorage` in the browser, the app's own files
     * directory on Android, Documents on iOS, and the platform's application-data directory on the
     * desktop. [SurveyLibrary] reports rather than throws when a platform will not have it.
     */
    val library = SurveyLibrary()

    /**
     * The survey being built, kept even while the demo cave is showing.
     *
     * A `var` rather than a `val` because a survey can now be opened from storage, which replaces
     * it wholesale. [session] follows it, since a session is bound to one survey.
     */
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

    val survey: Survey
        get() = if (mode == SurveyMode.EXAMPLE) exampleSurvey else liveSurvey

    // -------------------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------------------

    fun refreshLibrary() {
        savedSurveys = library.list()
    }

    /**
     * Writes the live survey out.
     *
     * Called after every change rather than on a timer. A survey is small, the write is
     * synchronous, and the alternative - losing the last few legs when a phone dies in a cave - is
     * exactly the failure this exists to prevent. The demo cave is never saved: it is a fixture,
     * and writing it would clutter the surveyor's own list.
     */
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
        return true
    }

    fun newSurvey(name: String) {
        adopt(Survey(library.uniqueName(name)))
        saveLiveSurvey()
    }

    /**
     * Removes a survey from storage.
     *
     * If it is the one open, the app is left holding a survey that no longer exists anywhere, so
     * it starts a fresh one — better than leaving the surveyor editing something that will not be
     * saved. The autosave effect only writes a survey with legs in it, so the empty replacement
     * does not immediately recreate the directory just deleted.
     */
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
     * Brings one in and opens it, returning the name it ended up with.
     *
     * The name can differ from the file's: [SurveyLibrary.uniqueName] refuses to overwrite a
     * survey already in the library, which is the whole point of importing one that a colleague
     * called the same thing you did.
     */
    fun importSurvey(fileName: String): String? {
        val imported = library.import(fileName) ?: run {
            storageProblem = library.lastError ?: "could not import $fileName"
            return null
        }
        adopt(imported)
        return imported.name
    }

    fun renameLiveSurvey(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == liveSurvey.name) return
        val previous = liveSurvey.name
        liveSurvey.name = library.uniqueName(trimmed)
        if (library.save(liveSurvey)) {
            // The old directory is named after the old name, so it would otherwise linger as a
            // stale copy that reopening would resurrect.
            library.delete(previous)
            refreshLibrary()
        }
        sketchRevision++
    }

    private fun adopt(survey: Survey) {
        liveSurvey = survey
        session = SurveySession(survey)
        mode = SurveyMode.LIVE
        storageProblem = null
        sketchRevision++
        refreshLibrary()
    }

    /**
     * Sketches are mutated in place rather than replaced, so nothing about editing one is
     * observable to Compose. This counter is what tells the canvas to look again; the session
     * keeps its own for incoming readings, and the two are added together at the call site.
     */
    var sketchRevision by mutableIntStateOf(0)
        private set

    fun noteSketchEdited() {
        sketchRevision++
    }

    /**
     * A station the app has been asked to show on a drawing, by name.
     *
     * The table's station menu offers "show it on the plan", which is two things at once: change
     * screen and projection, and move the view. Only the first can be done here — the viewport
     * belongs to the canvas, and the canvas for the projection being switched *to* does not exist
     * until it has been composed. So the request is left here and the sketch picks it up, the same
     * shape as the station-created counter that drives auto-recentre.
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

    val revision: Int
        get() = sketchRevision + session.revision

    /** Picking a colour while a non-drawing tool is active switches to drawing, as in the app. */
    fun pickColour(colour: Colour, editor: SketchEditor) {
        brushColour = colour
        editor.activeColour = colour
        if (!tool.usesColour) tool = SketchTool.DRAW
    }

    fun undo(editor: SketchEditor) {
        if (editor.undo()) noteSketchEdited()
    }

    fun redo(editor: SketchEditor) {
        if (editor.redo()) noteSketchEdited()
    }

    /**
     * Make [stationName] the station the next leg starts from.
     *
     * Only meaningful on the live survey: the demo cave is already complete, and moving its active
     * station would change nothing visible except the highlight. It is allowed anyway, because
     * seeing the brackets follow your finger is how somebody works out what the tool does.
     */
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
                pinchToZoom = preferences.pinchToZoom,
            )
}

/**
 * The editor for whichever sketch is currently showing.
 *
 * One editor per *sketch*, held for as long as the app is running, rather than one per current
 * selection. The plan and the extended elevation are different sketches and so get separate undo
 * stacks — that part matches the Android app — but they have to keep them. Rebuilding the editor
 * whenever the projection changed meant flipping to the elevation and back silently emptied the
 * plan's history, which is a nasty thing for a drawing app to do: the surveyor's stroke is still
 * there, and undo has quietly forgotten it ever happened.
 *
 * The map is keyed by identity, since [Sketch] does not override equals — which is what is wanted:
 * two sketches are the same sketch only if they are the same object.
 */
@Composable
fun rememberSketchEditor(state: DemoState): SketchEditor {
    val editors = remember(state) { mutableMapOf<Sketch, SketchEditor>() }
    val sketch = state.survey.getSketch(state.projection)
    val editor =
        editors.getOrPut(sketch) {
            SketchEditor(sketch).also { it.activeColour = state.brushColour }
        }
    // The brush is a property of the toolbar, not of the sketch, so it follows the surveyor across.
    editor.activeColour = state.brushColour
    return editor
}

/**
 * The view that survives a rebuild of the canvas composable, one per sketch.
 *
 * Same reasoning as [rememberSketchEditor]: flipping to the elevation and back should not throw
 * away where the surveyor had scrolled to, any more than it should throw away their undo history.
 */
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
 * `drawingMenuBehaviourToggles`: the four in `drawing.xml` that change what the app *does*.
 *
 * The grouping is the Android app's own, not one invented here, and it is a real distinction
 * rather than a tidy one: none of these four changes what is on the screen right now. Turning
 * snapping on does nothing until the next stroke; turning the blue water on does nothing until the
 * next symbol is stamped. Everything in [DISPLAY_TOGGLES] redraws the moment it is tapped.
 */
val BEHAVIOUR_TOGGLES =
    listOf(
        // `buttonAutoRecentre`, off by its default. Worth turning on halfway down a long passage,
        // which is exactly why it has to still be on after a battery change.
        ViewToggle(
            "Follow the survey",
            { it.preferences.autoRecentre },
            { it.updatePreferences(it.preferences.copy(autoRecentre = !it.preferences.autoRecentre)) },
        ),
        ViewToggle("Snap to lines", { it.snapToLines }, { it.snapToLines = !it.snapToLines }),
        // `buttonBlueWater`, on by its default. Water is blue on every published cave survey there
        // has ever been, and a surveyor with the brush set to black for wall outlines should not
        // have to change it and change it back to stamp a stream.
        ViewToggle(
            "Water is blue",
            { it.preferences.blueWater },
            { it.updatePreferences(it.preferences.copy(blueWater = !it.preferences.blueWater)) },
        ),
        // `buttonPinchToZoom`, on by its default, and one preference over both the drawing and the
        // 3D view as it is in the app. Worth having off for anyone drawing with a stylus, where a
        // second contact is usually the side of a hand rather than a pinch.
        ViewToggle(
            "Pinch to zoom",
            { it.preferences.pinchToZoom },
            { it.updatePreferences(it.preferences.copy(pinchToZoom = !it.preferences.pinchToZoom)) },
        ),
    )

/**
 * `drawingMenuDisplayToggles`: the ones that change what is drawn, in the app's own order.
 *
 * `buttonShowConnections` is the one member of that group with nothing behind it here — a
 * neighbouring survey is reached by an absolute `content://` URI, which is a format decision for
 * upstream rather than a porting one — so it is absent rather than present and dead.
 */
val DISPLAY_TOGGLES =
    listOf(
        // `buttonFadeNonActive`, off by its default. The question a surveyor keeps asking of a
        // plan is "where am I on this", and in a cave of any size a page of red lines does not
        // answer it. Fading everything that does not hang off the working station does, without
        // moving the view — which matters, because the part of the sketch they are drawing is the
        // part they are standing in.
        ViewToggle(
            "Fade all but the working end",
            { it.preferences.fadeNonActive },
            { it.updatePreferences(it.preferences.copy(fadeNonActive = !it.preferences.fadeNonActive)) },
        ),
        ViewToggle("Show splays", { it.showSplays }, { it.showSplays = !it.showSplays }),
        // `buttonShowXSections`. Turning it off hides the sections *and* stops them being tapped —
        // the app's own "special case: can't tap on invisible X-sections" — because a tap that
        // opens an editor from apparently blank paper is worse than one that does nothing.
        ViewToggle(
            "Show cross-sections",
            { it.preferences.showCrossSections },
            {
                it.updatePreferences(
                    it.preferences.copy(showCrossSections = !it.preferences.showCrossSections),
                )
            },
        ),
        ViewToggle("Show sketch", { it.showSketch }, { it.showSketch = !it.showSketch }),
        ViewToggle("Show grid", { it.showGrid }, { it.showGrid = !it.showGrid }),
        ViewToggle("Show station labels", { it.showLabels }, { it.showLabels = !it.showLabels }),
        // `buttonShowCompass`. The arrow does not swing with the phone here, but on a plan north
        // genuinely is up, so a fixed one is correct rather than approximate.
        ViewToggle("Show north", { it.showCompass }, { it.showCompass = !it.showCompass }),
        // `pref_highlight_latest_leg`, on by its default. In the Android app it lives in the
        // general settings screen rather than this menu; it is here because this is where this
        // port keeps display choices, and a preference reachable from nowhere is
        // indistinguishable from one that does not exist.
        ViewToggle(
            "Mark the last leg taken",
            { it.preferences.highlightLatestLeg },
            {
                it.updatePreferences(
                    it.preferences.copy(highlightLatestLeg = !it.preferences.highlightLatestLeg),
                )
            },
        ),
    )

/**
 * The colours the Android app offers on its sketch toolbar — the shared [BrushColour] list, not a
 * demo-chosen subset of the 144 colours the model can store.
 */
val BRUSH_COLOURS = BrushColour.entries.map { it.colour }

/**
 * The tools the demo offers *as toolbar buttons*, out of the eleven [SketchTool] knows about.
 *
 * The rest are reached from the drawing menu rather than the toolbar — stamping a symbol, placing
 * a label, and the three cross-section gestures — because a nine-column toolbar has no room for
 * them and the Android app hangs most of them off menus too. The two genuinely absent ones are the
 * modal pan gestures, which have no button anywhere: they are entered by a pinch or a hot corner.
 */
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

/**
 * The app bar's subtitle: what the shared core counted, and nothing else.
 *
 * Kept to one short line on purpose. It is an addition to the app's own chrome — SexyTopo has no
 * such line — so it has to earn its place without pushing the app bar down over the cave.
 */
fun subtitle(state: DemoState): String {
    // Reading the revision is what makes this recompute. The survey is mutated in place, so a new
    // leg changes nothing Compose can see; without this the counts would freeze at whatever they
    // were when the app bar last happened to be redrawn for some other reason.
    @Suppress("UNUSED_VARIABLE")
    val revision = state.revision
    val space = state.projection.project(state.survey)
    val legs = space.legMap.keys.count { it.hasDestination() }
    val sketch = state.survey.getSketch(state.projection)
    // Splays are left out: there are typically four per station, so the number is large, dull and
    // the first thing to be truncated on a narrow phone - where it would push out the counts that
    // actually say how big the cave is.
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

    // The hint describes what the finger does on the canvas, so it is only true while the canvas
    // is what is showing. Telling someone reading a table that they can pinch to zoom is the sort
    // of small lie that makes a whole interface feel untrustworthy.
    val hint =
        if (state.screen != Screen.SKETCH) {
            null
        } else {
            when (state.tool) {
                SketchTool.MOVE -> "drag to pan, pinch to zoom"
                SketchTool.ERASE -> "tap a line to rub it out"
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

/**
 * "1 station", "2 stations".
 *
 * Trivial, and worth doing: this line is the first thing on the screen, and a survey that reports
 * "1 legs" reads as unfinished software to exactly the person whose confidence the demo needs.
 */
internal fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

/** What a survey is called before the surveyor names it. */
const val DEFAULT_NEW_SURVEY_NAME = "Live Survey"
