package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * The whole demo UI, written once and run on iOS, desktop and the browser.
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
    var mode by remember { mutableStateOf(initialMode) }
    var screen by remember { mutableStateOf(initialScreen) }
    var projection by remember { mutableStateOf(initialProjection) }
    var tool by remember { mutableStateOf(initialTool) }
    var brushColour by remember { mutableStateOf(Colour.BLACK) }
    var showSplays by remember { mutableStateOf(true) }
    var showSketch by remember { mutableStateOf(true) }
    var showLabels by remember { mutableStateOf(true) }
    var darkMode by remember { mutableStateOf(initialDarkMode) }

    val liveSurvey = remember { Survey("Live Survey") }
    val session = remember(liveSurvey) { SurveySession(liveSurvey) }
    val shown = if (mode == SurveyMode.EXAMPLE) survey else liveSurvey

    // Sketches are mutated in place, so an explicit counter drives recomposition. The session
    // keeps its own, which is added in so incoming readings redraw the canvas too.
    var sketchRevision by remember { mutableIntStateOf(0) }
    // One editor per sketch: swapping to the elevation gives it its own undo stack, as in the
    // Android app where each sketch owns its history.
    val editor =
        remember(shown, projection) {
            SketchEditor(shown.getSketch(projection)).also { it.activeColour = brushColour }
        }

    WithBundledFont { typography ->
        MaterialTheme(
            colorScheme = if (darkMode) darkColorScheme() else lightColorScheme(),
            typography = typography,
        ) {
            Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxWidth < 560.dp

                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                "SexyTopo — Kotlin Multiplatform",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!compact) {
                                Text(
                                    "Survey engine, instrument protocols, sketch model and file " +
                                        "format ported from the Android app's Java. This UI is " +
                                        "shared Compose code.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        if (compact) {
                            // One dense scrollable row: four stacked toolbars would leave a phone
                            // with no canvas at all, and the canvas is the product.
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for (m in SurveyMode.entries) {
                                    FilterChip(mode == m, { mode = m }, { Text(m.label) })
                                }
                                for (sc in Screen.entries) {
                                    FilterChip(screen == sc, { screen = sc }, { Text(sc.label) })
                                }
                                for (p in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
                                    FilterChip(
                                        projection == p,
                                        { projection = p },
                                        { Text(if (p == Projection2D.PLAN) "Plan" else "Elevation") },
                                    )
                                }
                                for (t in DEMO_TOOLS) {
                                    FilterChip(tool == t, { tool = t }, { Text(t.label) })
                                }
                                for (c in BRUSH_COLOURS) {
                                    ColourSwatch(c, brushColour == c) {
                                        brushColour = c
                                        editor.activeColour = c
                                        // Picking a colour while another tool is active switches to
                                        // drawing, which is what SketchTool.usesColour is for.
                                        if (!tool.usesColour) tool = SketchTool.DRAW
                                    }
                                }
                                TextButton(
                                    enabled = editor.canUndo,
                                    onClick = { if (editor.undo()) sketchRevision++ },
                                ) { Text("Undo") }
                                FilterChip(darkMode, { darkMode = !darkMode }, { Text("Dark") })
                            }
                            if (mode == SurveyMode.LIVE) {
                                InstrumentBar(session)
                            }
                        } else {
                        ToolbarRow {
                            for (m in SurveyMode.entries) {
                                FilterChip(mode == m, { mode = m }, { Text(m.label) })
                            }
                            for (sc in Screen.entries) {
                                FilterChip(screen == sc, { screen = sc }, { Text(sc.label) })
                            }
                            for (p in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
                                FilterChip(
                                    selected = projection == p,
                                    onClick = { projection = p },
                                    label = { Text(p.displayName) },
                                )
                            }
                        }

                        if (mode == SurveyMode.LIVE) {
                            InstrumentBar(session)
                        }

                        if (screen == Screen.SKETCH) ToolbarRow {
                            for (t in DEMO_TOOLS) {
                                FilterChip(tool == t, { tool = t }, { Text(t.label) })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (c in BRUSH_COLOURS) {
                                    ColourSwatch(c, brushColour == c) {
                                        brushColour = c
                                        editor.activeColour = c
                                        if (!tool.usesColour) tool = SketchTool.DRAW
                                    }
                                }
                            }
                        }

                        if (screen == Screen.SKETCH) ToolbarRow {
                            TextButton(
                                enabled = editor.canUndo,
                                onClick = { if (editor.undo()) sketchRevision++ },
                            ) { Text("Undo") }
                            TextButton(
                                enabled = editor.canRedo,
                                onClick = { if (editor.redo()) sketchRevision++ },
                            ) { Text("Redo") }
                            FilterChip(showSketch, { showSketch = !showSketch }, { Text("Sketch") })
                            FilterChip(showSplays, { showSplays = !showSplays }, { Text("Splays") })
                            FilterChip(showLabels, { showLabels = !showLabels }, { Text("Labels") })
                            FilterChip(darkMode, { darkMode = !darkMode }, { Text("Dark") })
                        }
                        }

                        if (screen == Screen.TABLE) {
                            SurveyTableView(
                                survey = shown,
                                revision = sketchRevision + session.revision,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        } else if (screen == Screen.EXPORT) {
                            ExportView(
                                survey = shown,
                                revision = sketchRevision + session.revision,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        } else
                        SurveyCanvas(
                            survey = shown,
                            projection = projection,
                            options =
                                DisplayOptions(
                                    showSplays = showSplays,
                                    showSketch = showSketch,
                                    showStationLabels = showLabels,
                                    darkMode = darkMode,
                                ),
                            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp),
                            editor = editor,
                            tool = tool,
                            revision = sketchRevision + session.revision,
                            onSketchEdit = { sketchRevision++ },
                        )

                        Text(
                            text = summarise(shown, projection, tool, compact),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The live-survey controls. Each press decodes one real DistoX packet; three agreeing readings
 * promote to a station, which is the core interaction of the whole app.
 */
@Composable
private fun InstrumentBar(session: SurveySession) {
    ToolbarRow {
        Button(onClick = { session.takeReading() }) { Text("Take reading") }
        Text(
            buildString {
                append(if (session.connected) "connected" else "not connected")
                append("  ·  ${session.readingsTaken} readings")
                session.lastReading?.let {
                    append("  ·  last ${oneDp(it.distance)}m ${oneDp(it.azimuth)}°")
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    session.log.firstOrNull()?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * The colours the Android app offers on its sketch toolbar — the shared [BrushColour] list, not a
 * demo-chosen subset of the 144 colours the model can store.
 */
private val BRUSH_COLOURS = BrushColour.entries.map { it.colour }

/**
 * The tools the demo offers, out of the eleven [SketchTool] knows about.
 *
 * The rest — placing symbols and labels, and the four cross-section gestures — need chrome this
 * demo does not have: a symbol palette, a text field, a cross-section editor screen. The shared
 * model supports them already.
 */
private val DEMO_TOOLS = listOf(SketchTool.MOVE, SketchTool.DRAW, SketchTool.ERASE)

/** Toolbar labels. The shared enum carries behaviour, not display strings. */
private val SketchTool.label: String
    get() =
        when (this) {
            SketchTool.MOVE -> "Move"
            SketchTool.DRAW -> "Draw"
            SketchTool.ERASE -> "Erase"
            else -> name.lowercase().replaceFirstChar { it.uppercase() }
        }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolbarRow(content: @Composable () -> Unit) {
    // FlowRow rather than Row: on a phone in portrait the chips must wrap onto another line
    // instead of being squeezed until their labels break mid-word.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun ColourSwatch(colour: Colour, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 26.dp else 20.dp)
            .background(Color(colour.intValue), CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

private fun summarise(
    survey: Survey,
    projection: Projection2D,
    tool: SketchTool,
    compact: Boolean,
): String {
    val space = projection.project(survey)
    val legs = space.legMap.keys.count { it.hasDestination() }
    val splays = space.legMap.size - legs
    val sketch = survey.getSketch(projection)
    val hint =
        when (tool) {
            SketchTool.MOVE -> "drag to pan, pinch to zoom"
            SketchTool.ERASE -> "tap a line to rub it out"
            else -> "drag to draw a passage wall"
        }
    if (compact) {
        return "${space.stationMap.size} stations · $legs legs · " +
            "${sketch.pathDetails.size} sketch lines · $hint"
    }
    return "${survey.name}: ${space.stationMap.size} stations, $legs legs, $splays splays, " +
        "${sketch.pathDetails.size} sketch lines  ·  ${projection.displayName}  ·  " +
        "${platformName()}  ·  $hint"
}
