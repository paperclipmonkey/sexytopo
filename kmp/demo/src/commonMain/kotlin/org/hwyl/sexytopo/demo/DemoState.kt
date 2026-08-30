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
    fun loadSettings() {
        surveySettings = library.loadSettings()
    }

    fun updateSettings(settings: SurveySettings) {
        surveySettings = settings
        if (!library.saveSettings(settings)) {
            storageProblem = library.lastError ?: "could not save settings"
        }
    }

    var showSplays by mutableStateOf(true)
    var showSketch by mutableStateOf(true)
    var showLabels by mutableStateOf(true)
    var showGrid by mutableStateOf(true)

    /**
     * Where surveys are kept between runs. On the browser host this is real storage; elsewhere it
     * is in-memory until WP3 lands, and [SurveyLibrary] reports rather than throws either way.
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

/** The checkable display items from `res/menu/drawing.xml` that this port can honour. */
val VIEW_TOGGLES =
    listOf(
        ViewToggle("Show splays", { it.showSplays }, { it.showSplays = !it.showSplays }),
        ViewToggle("Show sketch", { it.showSketch }, { it.showSketch = !it.showSketch }),
        ViewToggle("Show station labels", { it.showLabels }, { it.showLabels = !it.showLabels }),
        ViewToggle("Show grid", { it.showGrid }, { it.showGrid = !it.showGrid }),
    )

/**
 * The colours the Android app offers on its sketch toolbar — the shared [BrushColour] list, not a
 * demo-chosen subset of the 144 colours the model can store.
 */
val BRUSH_COLOURS = BrushColour.entries.map { it.colour }

/**
 * The tools the demo offers, out of the eleven [SketchTool] knows about.
 *
 * The rest — placing symbols and labels, and the four cross-section gestures — need chrome this
 * demo does not have: a symbol palette, a text field, a cross-section editor screen. The shared
 * model supports them already, which is why the toolbar draws their buttons greyed rather than
 * leaving gaps.
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
