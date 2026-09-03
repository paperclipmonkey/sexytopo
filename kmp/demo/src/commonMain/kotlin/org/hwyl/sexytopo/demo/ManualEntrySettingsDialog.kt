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
 * What a reading looks like when it is typed rather than radioed.
 *
 * For the surveyor with a compass, a clinometer and a tape, and no DistoX in the party.
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

                Toggle(
                    title = "Offer a reading typed by hand",
                    detail =
                        "How a DistoX's display gets into the survey when the radio will not " +
                            "play. Worth turning off once an instrument is talking, for the room " +
                            "it gives back.",
                    checked = manualControls,
                    onCheckedChange = { manualControls = it },
                )

                // Disabled rather than hidden when there's no hand-typed reading to put it in:
                // a row that vanishes is one nobody can find again to see why it did nothing.
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

                // `pref_lrud_direction`: the Android app reads this key but declares it nowhere,
                // so the choice exists in its code with no way to reach it.
                //
                // A switch and not two chips, deliberately: `field.mjs` finds switches in this
                // dialog by scanning pixels, and a chip pair here would be miscounted as one,
                // silently toggling the wrong setting.
                Toggle(
                    title = "Measure walls square to the next leg",
                    detail =
                        "Off, the default, takes them square to the passage — bisecting the " +
                            "corner at a bend, which is what most cavers mean by a left-hand " +
                            "wall. On uses the leg you are about to shoot instead.",
                    checked = lrudMode == LrudMode.SHOT,
                    onCheckedChange = { lrudMode = if (it) LrudMode.SHOT else LrudMode.SURVEY },
                )

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
                    // A `copy` of what came in: building a fresh object from these five values
                    // would reset every other preference to its default.
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
