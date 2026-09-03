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
 * has — `preferences_main.xml` lists Sketching and Instruments as separate screens.
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
    var hotCorners by remember { mutableStateOf(preferences.hotCorners) }
    var highlightLatest by remember { mutableStateOf(preferences.highlightLatestLeg) }
    var twoFingerMove by remember { mutableStateOf(preferences.twoFingerMove) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.settingsSketchingTitle) },
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

                // `pref_hot_corners`, `pref_delete_path_fragments`,
                // `pref_highlight_latest_leg`, `pref_two_finger_movement` and
                // `pref_legacy_cross_sections`, in `preferences_sketching.xml`'s own order.
                Toggle(
                    title = Strings.settingsHotCornersTitle,
                    detail = Strings.settingsHotCornersSummary,
                    checked = hotCorners,
                    onCheckedChange = { hotCorners = it },
                )

                Toggle(
                    title = Strings.settingsDeleteFragmentsTitle,
                    detail = Strings.settingsDeleteFragmentsSummary,
                    checked = fragments,
                    onCheckedChange = { fragments = it },
                )

                Toggle(
                    title = Strings.settingsHighlightLatestLegTitle,
                    detail = Strings.settingsHighlightLatestLegSummary,
                    checked = highlightLatest,
                    onCheckedChange = { highlightLatest = it },
                )

                Toggle(
                    title = Strings.settingsTwoFingerTitle,
                    detail = Strings.settingsTwoFingerSummary,
                    checked = twoFingerMove,
                    onCheckedChange = { twoFingerMove = it },
                )

                Toggle(
                    title = Strings.settingsLegacyCrossSectionsTitle,
                    detail = Strings.settingsLegacyCrossSectionsSummary,
                    checked = legacySections,
                    onCheckedChange = { legacySections = it },
                )

                HorizontalDivider()

                NumberField(text, { text = it }, Strings.settingsTextToolSizeTitle)
                NumberField(symbol, { symbol = it }, Strings.settingsSymbolSizeTitle)
                NumberField(lineWidth, { lineWidth = it }, Strings.settingsSketchLineWidthTitle)
                NumberField(legWidth, { legWidth = it }, Strings.settingsLegWidthTitle)
                NumberField(splayWidth, { splayWidth = it }, Strings.settingsSplayWidthTitle)
                NumberField(
                    stationDiameter,
                    { stationDiameter = it },
                    Strings.settingsStationDiameterTitle,
                )
                NumberField(
                    stationLabel,
                    { stationLabel = it },
                    Strings.settingsStationLabelSizeTitle,
                )
                NumberField(legend, { legend = it }, Strings.settingsLegendSizeTitle)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        preferences.copy(
                            deletePathFragments = fragments,
                            legacyCrossSections = legacySections,
                            hotCorners = hotCorners,
                            highlightLatestLeg = highlightLatest,
                            twoFingerMove = twoFingerMove,
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
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/**
 * The eight typed values as sizes, each falling back to what it was rather than to the default.
 *
 * A text field passes through the empty string while being cleared to retype; falling back to the
 * *default* there would quietly reset a surveyor's chosen value the moment they pressed Save.
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
