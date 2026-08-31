package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import org.hwyl.sexytopo.shared.survey.LrudMode

/**
 * What a reading looks like when it is typed rather than radioed: `preferences_manual_data_entry`.
 *
 * Its own dialog because that is the shape `preferences_main.xml` has — *Manual data entry* is one
 * of its eight screens — and because this port had merged it into *Surveying* and the merge
 * outgrew a phone. Eleven settings in one scrolling card is not a screen anybody can use with cold
 * hands, and the browser checks proved it in their own way: they find the switches by scanning
 * pixels, the dialog grew past the point where all of them fit on a 420-by-900 screen at once, and
 * "the last switch but five" silently became a switch that was not on screen at all.
 *
 * ## Who this screen is for
 *
 * The surveyor with a compass, a clinometer and a tape, and no DistoX in the party. Every setting
 * here is about that: whether the app offers a hand-typed reading at all, whether it asks for the
 * passage size in the same breath, which bearing the walls are measured square to, and whether the
 * angles are typed as decimals or as the degrees and minutes a sighting compass is graduated in.
 */
@Composable
fun ManualEntrySettingsDialog(
    preferences: AppPreferences,
    onDismiss: () -> Unit,
    onSave: (AppPreferences) -> Unit,
) {
    var manualControls by remember { mutableStateOf(preferences.manualControls) }
    var lrudFields by remember { mutableStateOf(preferences.lrudFields) }
    var lrudMode by remember { mutableStateOf(preferences.lrudMode) }
    var azimuthInDms by remember { mutableStateOf(preferences.azimuthInDms) }
    var inclinationInDms by remember { mutableStateOf(preferences.inclinationInDms) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual entry") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "For a survey booked with a compass, a clinometer and a tape rather than " +
                        "read off an instrument.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // `pref_manual_controls`. The Android app applies it to the two floating buttons
                // on its table view; the same control here is *Add reading* on the field bar.
                Toggle(
                    title = "Offer a reading typed by hand",
                    detail =
                        "How a DistoX's display gets into the survey when the radio will not " +
                            "play. Worth turning off once an instrument is talking, for the room " +
                            "it gives back.",
                    checked = manualControls,
                    onCheckedChange = { manualControls = it },
                )

                // `pref_lrud_fields`. Disabled rather than hidden when there is no hand-typed
                // reading to put them in — `android:dependency`'s own behaviour, and the right
                // one: a row that vanishes is a row nobody can find again to work out why their
                // setting did nothing.
                Toggle(
                    title = "Book passage size with the reading",
                    detail =
                        "Four tape measurements beside the leg, taken where you are standing. " +
                            "For a compass-and-tape survey that is the whole station in one " +
                            "dialog instead of going back to one you have already left.",
                    checked = lrudFields,
                    onCheckedChange = { lrudFields = it },
                    enabled = manualControls,
                )

                // `pref_lrud_direction`, which is here at all because the Android app **reads
                // this key and declares it nowhere**: `getLrudMode` has a caller and no entry in
                // any `preferences_*.xml`, so on Android the choice exists in the code and nobody
                // can make it. See the note in the README.
                //
                // A switch and not two chips, and that was not the first attempt. Chips are the
                // better shape for a choice between two conventions — but the browser checks find
                // the switches in this dialog by scanning a band of pixels around x=320, and the
                // second chip's right edge lands *inside* that band at seventeen columns against a
                // threshold of ten. It would have been counted as a switch, shifting every
                // negative index in `field.mjs` by one and quietly turning off whichever setting
                // the checks meant to touch. That failure has happened on this branch before; it
                // took four hundred lines to surface last time. A switch is also what every other
                // row here is.
                Toggle(
                    title = "Measure walls square to the next leg",
                    detail =
                        "Off, the default, takes them square to the passage — bisecting the " +
                            "corner at a bend, which is what most cavers mean by a left-hand " +
                            "wall. On uses the leg you are about to shoot instead.",
                    checked = lrudMode == LrudMode.SHOT,
                    onCheckedChange = { lrudMode = if (it) LrudMode.SHOT else LrudMode.SURVEY },
                )

                // `pref_deg_mins_secs` and `pref_inc_deg_mins_secs`, from the Android app's
                // *Manual data entry* screen. They are here, with the tolerances, because both
                // answer the same question — what a reading looks like when it is not coming off
                // a DistoX — and because the surveyor who loosens the tolerances for a compass
                // and tape is the same surveyor who needs these.
                Toggle(
                    title = "Type bearings in minutes",
                    detail =
                        "A sighting compass is graduated in minutes, so it reads 123\u00b0 30\u2032 " +
                            "and not 123.5. Converting that in your head at every station is how " +
                            "a survey acquires errors nobody can find afterwards.",
                    checked = azimuthInDms,
                    onCheckedChange = { azimuthInDms = it },
                )

                Toggle(
                    title = "Type inclinations in minutes",
                    detail =
                        "Its own switch, as upstream: plenty of clinometers read in degrees while " +
                            "the compass beside them reads in minutes.",
                    checked = inclinationInDms,
                    onCheckedChange = { inclinationInDms = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // A `copy` of what came in, for the reason written on `preferencesFrom`: this
                    // screen shows five of the app's preferences and building a fresh object from
                    // five values would reset every other one to its default.
                    onSave(
                        preferences.copy(
                            manualControls = manualControls,
                            lrudFields = lrudFields,
                            lrudMode = lrudMode,
                            azimuthInDms = azimuthInDms,
                            inclinationInDms = inclinationInDms,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
