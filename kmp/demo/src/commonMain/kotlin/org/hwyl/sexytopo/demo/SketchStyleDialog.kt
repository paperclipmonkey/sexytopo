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
import org.hwyl.sexytopo.shared.sketch.SketchStyle

/**
 * How big everything on the drawing is drawn: `preferences_sketching.xml`, as its own screen.
 *
 * Its own dialog rather than more rows on *Surveying*, because that is the shape the Android app
 * has — `preferences_main.xml` lists Sketching and Instruments as separate screens — and because
 * the tolerances answer "what counts as the same shot" while these answer "can I read this by head
 * torch", which are questions asked at different moments by different people.
 *
 * ## Why a surveyor touches this at all
 *
 * A plan read on a desk and a plan read at arm's length under a helmet, through a scratched screen,
 * in a dry bag, by a light pointed at the rock rather than the phone, are not the same picture.
 * The defaults are the Android app's, and the Android app's are a compromise; the point of the
 * screen is that the compromise is not the same for everybody, and the person who needs a heavier
 * line needs it at the station rather than at home.
 */
@Composable
fun SketchStyleDialog(
    preferences: AppPreferences,
    onDismiss: () -> Unit,
    onSave: (AppPreferences) -> Unit,
) {
    val style = preferences.sketchStyle
    var legWidth by remember { mutableStateOf(style.legWidthDp.toString()) }
    var splayWidth by remember { mutableStateOf(style.splayWidthDp.toString()) }
    var lineWidth by remember { mutableStateOf(style.sketchLineWidthDp.toString()) }
    var stationDiameter by remember { mutableStateOf(style.stationDiameterDp.toString()) }
    var stationLabel by remember { mutableStateOf(style.stationLabelSizeSp.toString()) }
    var legend by remember { mutableStateOf(style.legendSizeSp.toString()) }
    var symbol by remember { mutableStateOf(style.symbolSizeDp.toString()) }
    var text by remember { mutableStateOf(style.textSizeSp.toString()) }
    var fragments by remember { mutableStateOf(preferences.deletePathFragments) }
    var legacySections by remember { mutableStateOf(preferences.legacyCrossSections) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sketching") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "How big things are drawn. Worth more than it sounds underground: these are " +
                        "the numbers that decide whether a plan can be read at arm's length by " +
                        "head torch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                NumberField(legWidth, { legWidth = it }, "Leg width (dp)")
                NumberField(splayWidth, { splayWidth = it }, "Splay width (dp)")
                NumberField(lineWidth, { lineWidth = it }, "Drawn line width (dp)")
                NumberField(stationDiameter, { stationDiameter = it }, "Station size (dp)")
                NumberField(stationLabel, { stationLabel = it }, "Station name size (sp)")
                NumberField(legend, { legend = it }, "Scale bar and compass size (sp)")
                NumberField(symbol, { symbol = it }, "Symbol size (dp)")
                NumberField(text, { text = it }, "Text tool size (sp)")

                HorizontalDivider()

                // `pref_delete_path_fragments`. The engine has done this since the sketch was
                // ported and nothing ever passed it anything but the default, so the app had the
                // behaviour and not the choice.
                Toggle(
                    title = "Rub out part of a line",
                    detail =
                        "The eraser takes the bit of a wall under your finger and leaves both " +
                            "ends. Turn it off to delete the whole stroke instead.",
                    checked = fragments,
                    onCheckedChange = { fragments = it },
                )

                // `pref_legacy_cross_sections`, which this port did not have because it did not
                // have the frame the setting turns off.
                Toggle(
                    title = "Plain cross-sections",
                    detail =
                        "Draw a cross-section as bare splays with a dashed line to its station, " +
                            "the way the app used to. No frame, no drag bar, and no tapping one " +
                            "open to draw inside it.",
                    checked = legacySections,
                    onCheckedChange = { legacySections = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        preferences.copy(
                            deletePathFragments = fragments,
                            legacyCrossSections = legacySections,
                            sketchStyle =
                                styleFrom(
                                    style,
                                    legWidth,
                                    splayWidth,
                                    lineWidth,
                                    stationDiameter,
                                    stationLabel,
                                    legend,
                                    symbol,
                                    text,
                                ),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The eight typed values as sizes, each falling back to what it was rather than to the default.
 *
 * These are text fields, so every one of them passes through every prefix of a number on the way
 * to one — and through the empty string, when somebody clears the box to retype it. Falling back
 * to the *default* there would quietly reset a surveyor's chosen line width to the app's the
 * moment they touched a different field and pressed Save.
 */
internal fun styleFrom(
    current: SketchStyle,
    legWidth: String,
    splayWidth: String,
    lineWidth: String,
    stationDiameter: String,
    stationLabel: String,
    legend: String,
    symbol: String,
    text: String,
): SketchStyle =
    SketchStyle(
        legWidthDp = SketchStyle.size(legWidth, current.legWidthDp),
        splayWidthDp = SketchStyle.size(splayWidth, current.splayWidthDp),
        sketchLineWidthDp = SketchStyle.size(lineWidth, current.sketchLineWidthDp),
        stationDiameterDp = SketchStyle.size(stationDiameter, current.stationDiameterDp),
        stationLabelSizeSp = SketchStyle.size(stationLabel, current.stationLabelSizeSp),
        legendSizeSp = SketchStyle.size(legend, current.legendSizeSp),
        symbolSizeDp = SketchStyle.size(symbol, current.symbolSizeDp),
        textSizeSp = SketchStyle.size(text, current.textSizeSp),
    )
