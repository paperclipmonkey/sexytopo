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

    var showSplays by mutableStateOf(true)
    var showSketch by mutableStateOf(true)
    var showLabels by mutableStateOf(true)

    /** The survey built live from the simulated instrument, kept even while it is not shown. */
    val liveSurvey = Survey("Live Survey")
    val session = SurveySession(liveSurvey)

    val survey: Survey
        get() = if (mode == SurveyMode.EXAMPLE) exampleSurvey else liveSurvey

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

    val displayOptions: DisplayOptions
        get() =
            DisplayOptions(
                showSplays = showSplays,
                showSketch = showSketch,
                showStationLabels = showLabels,
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
 * The colours the Android app offers on its sketch toolbar — the shared [BrushColour] list, not a
 * demo-chosen subset of the 144 colours the model can store.
 */
val BRUSH_COLOURS = BrushColour.entries.map { it.colour }

/**
 * The tools the demo offers, out of the eleven [SketchTool] knows about.
 *
 * The rest — placing symbols and labels, and the four cross-section gestures — need chrome this
 * demo does not have: a symbol palette, a text field, a cross-section editor screen. The shared
 * model supports them already.
 */
val DEMO_TOOLS = listOf(SketchTool.MOVE, SketchTool.DRAW, SketchTool.ERASE)

/** Toolbar labels. The shared enum carries behaviour, not display strings. */
val SketchTool.label: String
    get() =
        when (this) {
            SketchTool.MOVE -> "Move"
            SketchTool.DRAW -> "Draw"
            SketchTool.ERASE -> "Erase"
            else -> name.lowercase().replaceFirstChar { it.uppercase() }
        }

/** The one-line status under the canvas, in two lengths. */
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
