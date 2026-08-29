package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The whole demo UI, written once and run on iOS, desktop and (where enabled) the browser.
 *
 * Everything below the toolbar is drawn by [SurveyCanvas]; everything it draws comes from the
 * shared Kotlin core ported from the Android app's Java.
 */
@Composable
fun App(
    survey: Survey = remember { ExampleSurvey.create() },
    initialProjection: Projection2D = Projection2D.PLAN,
    initialDarkMode: Boolean = false,
) {
    var projection by remember { mutableStateOf(initialProjection) }
    var showSplays by remember { mutableStateOf(true) }
    var showSketch by remember { mutableStateOf(true) }
    var showLabels by remember { mutableStateOf(true) }
    var darkMode by remember { mutableStateOf(initialDarkMode) }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "SexyTopo — Kotlin Multiplatform proof of concept",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Survey model, projection maths and file format ported from the Android " +
                            "app's Java; this UI is shared Compose Multiplatform.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = projection == Projection2D.PLAN,
                        onClick = { projection = Projection2D.PLAN },
                        label = { Text("Plan") },
                    )
                    FilterChip(
                        selected = projection == Projection2D.EXTENDED_ELEVATION,
                        onClick = { projection = Projection2D.EXTENDED_ELEVATION },
                        label = { Text("Extended elevation") },
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = showSketch,
                        onClick = { showSketch = !showSketch },
                        label = { Text("Sketch") },
                    )
                    FilterChip(
                        selected = showSplays,
                        onClick = { showSplays = !showSplays },
                        label = { Text("Splays") },
                    )
                    FilterChip(
                        selected = showLabels,
                        onClick = { showLabels = !showLabels },
                        label = { Text("Labels") },
                    )
                    FilterChip(
                        selected = darkMode,
                        onClick = { darkMode = !darkMode },
                        label = { Text("Dark") },
                    )
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                Text(
                    text = summarise(survey, projection),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private fun summarise(survey: Survey, projection: Projection2D): String {
    val space = projection.project(survey)
    val legs = space.legMap.keys.count { it.hasDestination() }
    val splays = space.legMap.size - legs
    return "${survey.name}: ${space.stationMap.size} stations, $legs legs, $splays splays  ·  " +
        "${projection.displayName}  ·  running on ${platformName()}  ·  drag to pan, pinch to zoom"
}
