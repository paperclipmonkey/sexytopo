package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.io.export.SvgExporter

/**
 * What goes into the drawing that leaves the cave: `dialog_svg_export`, and the settings screen
 * behind it.
 *
 * The Android app puts these in two places at once. `SvgExporter.showOptionsDialog` asks thirteen
 * of them at the moment of export and writes the answers back to the preferences; the other four -
 * the three stroke widths and this port's own *Draw the sketch* - live only on
 * `preferences_export_svg.xml`, a screen reached from the settings menu that nobody exporting a
 * file is looking at. They are one dialog here, on the export screen, because they are one
 * question: what should this file contain. The answers persist either way, so the surveyor who
 * sets them up once at home does not set them again at the entrance.
 *
 * ## Why any of this is worth a screen
 *
 * An SVG is what a survey looks like to everybody who was not on the trip, and what it needs to
 * contain depends entirely on where it is going. A drawing headed for Inkscape to be composed with
 * three other trips wants no legend, no grid and a transparent background, because all of those
 * will be added once at the end over the whole cave. A drawing headed for a club newsletter wants
 * the legend, the scale bar, the north arrow and the surveyors' names, because it is going to be
 * printed on its own. A drawing headed for a landowner wants the centreline gone. Exporting the
 * same file for all three and expecting the recipient to delete what they do not want is how a
 * survey ends up redrawn by hand.
 */
@Composable
fun SvgExportDialog(
    options: SvgExporter.Options,
    onDismiss: () -> Unit,
    onSave: (SvgExporter.Options) -> Unit,
) {
    var white by remember { mutableStateOf(options.whiteBackground) }
    var legend by remember { mutableStateOf(options.showLegend) }
    var north by remember { mutableStateOf(options.showNorthArrow) }
    var scaleBar by remember { mutableStateOf(options.showScaleBar) }
    var team by remember { mutableStateOf(options.showTeam) }
    var copyright by remember { mutableStateOf(options.showCopyright) }
    var tagline by remember { mutableStateOf(options.showTagline) }
    var sketch by remember { mutableStateOf(options.showSketch) }
    var symbols by remember { mutableStateOf(options.showSymbols) }
    var crossSections by remember { mutableStateOf(options.showCrossSections) }
    var centreline by remember { mutableStateOf(options.showCentreline) }
    var stations by remember { mutableStateOf(options.showStations) }
    var splays by remember { mutableStateOf(options.showSplays) }
    var grid by remember { mutableStateOf(options.showGrid) }
    var lineWidth by remember { mutableStateOf(options.sketchStrokeWidth.toString()) }
    var legWidth by remember { mutableStateOf(options.legStrokeWidth.toString()) }
    var splayWidth by remember { mutableStateOf(options.splayStrokeWidth.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SVG export") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "What goes in the file. Worth setting before a trip rather than after one: " +
                        "a drawing headed for Inkscape and a drawing headed for a newsletter " +
                        "want opposite answers to most of these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The drawing itself.
                Toggle(
                    title = "Draw the sketch",
                    detail =
                        "The passage walls and everything drawn by hand. Off gives a centreline " +
                            "diagram with nothing traced over it.",
                    checked = sketch,
                    onCheckedChange = { sketch = it },
                )
                Toggle(
                    title = "Draw the symbols",
                    detail = "Stamped symbols: entrances, chokes, water and the rest.",
                    checked = symbols,
                    onCheckedChange = { symbols = it },
                )
                Toggle(
                    title = "Draw the cross-sections",
                    detail = "The passage sections dropped on the plan, each beside its station.",
                    checked = crossSections,
                    onCheckedChange = { crossSections = it },
                )
                Toggle(
                    title = "Draw the centreline",
                    detail = "The survey legs. The line the numbers actually describe.",
                    checked = centreline,
                    onCheckedChange = { centreline = it },
                )
                Toggle(
                    title = "Draw the splays",
                    detail =
                        "Every shot to a wall. Honest, and on a well-splayed cave it is most of " +
                            "the ink on the page.",
                    checked = splays,
                    onCheckedChange = { splays = it },
                )
                Toggle(
                    title = "Name the stations",
                    detail = "Each station's name beside it, which is how a survey gets extended.",
                    checked = stations,
                    onCheckedChange = { stations = it },
                )

                HorizontalDivider()

                // The page around it.
                Toggle(
                    title = "White background",
                    detail =
                        "Off exports a transparent page, which is what you want when the drawing " +
                            "is going to be laid over something else - and what looks like a " +
                            "blank file if you open it on its own.",
                    checked = white,
                    onCheckedChange = { white = it },
                )
                Toggle(
                    title = "Draw the grid",
                    detail = "A metre grid behind the cave, for tracing and for scale.",
                    checked = grid,
                    onCheckedChange = { grid = it },
                )

                HorizontalDivider()

                // The strip underneath. Each of the four below is inside the legend, so turning
                // the legend off takes all of them with it however they are set - which is why
                // they are disabled rather than hidden when it is off. See the note on
                // `pref_auto_reconnect_window`: a row that vanishes is a row a surveyor cannot
                // find again to work out why their setting did nothing.
                Toggle(
                    title = "Add a legend",
                    detail =
                        "A strip under the drawing with the survey's name, the date and how long " +
                            "the cave is. The four below live in it.",
                    checked = legend,
                    onCheckedChange = { legend = it },
                )
                Toggle(
                    title = "Add a north arrow",
                    detail = "In the legend, on plans. An extended elevation has no north.",
                    checked = north,
                    onCheckedChange = { north = it },
                    enabled = legend,
                )
                Toggle(
                    title = "Add a scale bar",
                    detail = "Without one, a printed survey is a picture rather than a survey.",
                    checked = scaleBar,
                    onCheckedChange = { scaleBar = it },
                    enabled = legend,
                )
                Toggle(
                    title = "Name the surveyors",
                    detail = "Who was on the trip, from the trip details.",
                    checked = team,
                    onCheckedChange = { team = it },
                    enabled = legend,
                )
                Toggle(
                    title = "Show the copyright line",
                    detail =
                        "The copyright holder and licence are written into the file either way; " +
                            "this also prints them where a reader will see them.",
                    checked = copyright,
                    onCheckedChange = { copyright = it },
                    enabled = legend,
                )
                Toggle(
                    title = "Credit SexyTopo",
                    detail = "A line saying what drew this.",
                    checked = tagline,
                    onCheckedChange = { tagline = it },
                    enabled = legend,
                )

                HorizontalDivider()

                Text(
                    "Line widths, in pixels at " + SvgExporter.SCALE + " to the metre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberField(lineWidth, { lineWidth = it }, "Drawn line width")
                NumberField(legWidth, { legWidth = it }, "Leg width")
                NumberField(splayWidth, { splayWidth = it }, "Splay width")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        options.copy(
                            whiteBackground = white,
                            showGrid = grid,
                            showSketch = sketch,
                            showSymbols = symbols,
                            showCrossSections = crossSections,
                            showCentreline = centreline,
                            showSplays = splays,
                            showStations = stations,
                            showLegend = legend,
                            showNorthArrow = north,
                            showScaleBar = scaleBar,
                            showTeam = team,
                            showCopyright = copyright,
                            showTagline = tagline,
                            sketchStrokeWidth =
                                AppPreferencesStore.strokeWidth(
                                    lineWidth,
                                    options.sketchStrokeWidth,
                                ),
                            legStrokeWidth =
                                AppPreferencesStore.strokeWidth(legWidth, options.legStrokeWidth),
                            splayStrokeWidth =
                                AppPreferencesStore.strokeWidth(
                                    splayWidth,
                                    options.splayStrokeWidth,
                                ),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
