package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
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

/**
 * The whole demo UI, written once and run on iOS, desktop and the browser.
 *
 * Everything the canvas draws comes from the shared Kotlin core ported from the Android app's Java;
 * everything you draw on it goes back into the same shared sketch model and would serialise to the
 * same JSON the Android app reads.
 */
@Composable
fun App(
    survey: Survey = remember { ExampleSurvey.create() },
    initialProjection: Projection2D = Projection2D.PLAN,
    initialDarkMode: Boolean = false,
    initialTool: CanvasTool = CanvasTool.PAN,
) {
    var projection by remember { mutableStateOf(initialProjection) }
    var tool by remember { mutableStateOf(initialTool) }
    var brushColour by remember { mutableStateOf(Colour.BLACK) }
    var showSplays by remember { mutableStateOf(true) }
    var showSketch by remember { mutableStateOf(true) }
    var showLabels by remember { mutableStateOf(true) }
    var darkMode by remember { mutableStateOf(initialDarkMode) }

    // The sketch is mutated in place, so an explicit revision counter drives recomposition.
    var revision by remember { mutableIntStateOf(0) }
    val history = remember(survey) { SketchHistory() }

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
                                "Survey model, projection maths, sketch model and file format " +
                                    "ported from the Android app's Java. This UI is shared " +
                                    "Compose code.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    ToolbarRow {
                        for (p in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
                            FilterChip(
                                selected = projection == p,
                                onClick = { projection = p },
                                label = { Text(p.displayName) },
                            )
                        }
                    }

                    ToolbarRow {
                        for (t in CanvasTool.entries) {
                            FilterChip(
                                selected = tool == t,
                                onClick = { tool = t },
                                label = { Text(t.displayName) },
                            )
                        }
                        Box(Modifier.padding(start = 4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (c in BRUSH_COLOURS) {
                                    ColourSwatch(
                                        colour = c,
                                        selected = brushColour == c,
                                        onClick = { brushColour = c; tool = CanvasTool.DRAW },
                                    )
                                }
                            }
                        }
                    }

                    ToolbarRow {
                        TextButton(
                            enabled = history.canUndo,
                            onClick = {
                                if (history.undo(survey.getSketch(projection))) revision++
                            },
                        ) { Text("Undo") }
                        TextButton(
                            enabled = history.canRedo,
                            onClick = {
                                if (history.redo(survey.getSketch(projection))) revision++
                            },
                        ) { Text("Redo") }
                        FilterChip(showSketch, { showSketch = !showSketch }, { Text("Sketch") })
                        FilterChip(showSplays, { showSplays = !showSplays }, { Text("Splays") })
                        FilterChip(showLabels, { showLabels = !showLabels }, { Text("Labels") })
                        FilterChip(darkMode, { darkMode = !darkMode }, { Text("Dark") })
                    }

                    SurveyCanvas(
                        survey = survey,
                        projection = projection,
                        options =
                            DisplayOptions(
                                showSplays = showSplays,
                                showSketch = showSketch,
                                showStationLabels = showLabels,
                                darkMode = darkMode,
                            ),
                        modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 220.dp),
                        tool = tool,
                        brushColour = brushColour,
                        revision = revision,
                        onSketchEdit = { revision++ },
                        history = history,
                    )

                    Text(
                        text = summarise(survey, projection, tool, compact),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                }
            }
        }
    }
}

/** The colours the Android app offers on its sketch toolbar. */
private val BRUSH_COLOURS =
    listOf(Colour.BLACK, Colour.RED, Colour.BLUE, Colour.DARK_GREEN, Colour.BROWN, Colour.ORANGE)

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
    tool: CanvasTool,
    compact: Boolean,
): String {
    val space = projection.project(survey)
    val legs = space.legMap.keys.count { it.hasDestination() }
    val splays = space.legMap.size - legs
    val sketch = survey.getSketch(projection)
    val hint =
        when (tool) {
            CanvasTool.PAN -> "drag to pan, pinch to zoom"
            CanvasTool.DRAW -> "drag to draw a passage wall"
            CanvasTool.ERASE -> "drag over a line to rub it out"
        }
    if (compact) {
        return "${space.stationMap.size} stations · $legs legs · ${sketch.pathDetails.size} " +
            "sketch lines · $hint"
    }
    return "${survey.name}: ${space.stationMap.size} stations, $legs legs, $splays splays, " +
        "${sketch.pathDetails.size} sketch lines  ·  ${projection.displayName}  ·  " +
        "${platformName()}  ·  $hint"
}
