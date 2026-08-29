package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
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
            )
        }
    val editor = rememberSketchEditor(state)
    val canvas = rememberCanvasController(state)

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
                    SexyTopoAppBar(state)

                    ScreenContent(
                        state,
                        editor,
                        canvas,
                        Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
                    )

                    if (state.mode == SurveyMode.LIVE) {
                        InstrumentBar(state)
                    }

                    if (state.screen == Screen.SKETCH) {
                        SketchToolbar(state, editor, canvas)
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
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // What the demo adds to the app's own menus: which survey is showing, and a look
                // at what it exports as.
                for (mode in SurveyMode.entries) {
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        leadingIcon = { Text(if (state.mode == mode) "✓" else " ") },
                        onClick = {
                            state.mode = mode
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Export") },
                    leadingIcon = { Text(if (state.screen == Screen.EXPORT) "✓" else " ") },
                    onClick = {
                        state.screen = Screen.EXPORT
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Dark mode") },
                    leadingIcon = { Text(if (state.darkMode) "✓" else " ") },
                    onClick = { state.darkMode = !state.darkMode },
                )
            }
        }
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
    when (state.screen) {
        Screen.TABLE -> SurveyTableView(state.survey, state.revision, modifier)
        Screen.EXPORT -> ExportView(state.survey, state.revision, modifier)
        Screen.SKETCH ->
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
            )
    }
}

/**
 * The live-survey controls. Each press decodes one real DistoX packet; three agreeing readings
 * promote to a station, which is the core interaction of the whole app.
 *
 * In the Android app this is not a bar at all — readings arrive over Bluetooth while the phone is
 * in a pocket. It is here because the demo has no radio, and a button is the honest stand-in.
 */
@Composable
private fun InstrumentBar(state: DemoState) {
    val session = state.session
    Row(
        Modifier
            .fillMaxWidth()
            .background(SexyTopoColours.innerPanel)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { session.takeReading() }) { Text("Take reading") }
        Text(
            buildString {
                append("${session.readingsTaken} readings")
                session.lastReading?.let {
                    append("  ·  ${oneDp(it.distance)}m ${oneDp(it.azimuth)}°")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = SexyTopoColours.legend,
        )
        Spacer(Modifier.weight(1f))
    }
}
