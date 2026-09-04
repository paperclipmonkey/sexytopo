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
import androidx.compose.ui.platform.testTag
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
        title = { Text(Strings.settingsManualDataEntryTitle) },
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
                    title = Strings.settingsManualControlsTitle,
                    detail = Strings.settingsManualControlsSummary,
                    checked = manualControls,
                    onCheckedChange = { manualControls = it },
                )

                // `preferences_manual_data_entry.xml` declares no `android:dependency` between
                // these two, and the LRUD fields reach more than the hand-typed reading dialog —
                // the station menu's own passage-size face uses them too. Greying it out with
                // *Manual Data Controls* off was this port's invention, and a wrong one.
                Toggle(
                    title = Strings.settingsLrudFieldsTitle,
                    detail = Strings.settingsLrudFieldsSummary,
                    checked = lrudFields,
                    onCheckedChange = { lrudFields = it },
                )

                // `pref_lrud_direction`: the Android app reads this key but declares it nowhere,
                // so the choice exists in its code with no way to reach it.
                //
                // A switch and not two chips, deliberately: `field.mjs` finds switches in this
                // dialog by scanning pixels, and a chip pair here would be miscounted as one,
                // silently toggling the wrong setting.
                Toggle(
                    title = Strings.settingsLrudDirectionTitle,
                    detail =
                        Strings.settingsLrudDirectionSummary + ". Off is " +
                            Strings.lrudDirectionSurvey + "; on is " +
                            Strings.lrudDirectionShot + ".",
                    checked = lrudMode == LrudMode.SHOT,
                    onCheckedChange = { lrudMode = if (it) LrudMode.SHOT else LrudMode.SURVEY },
                )

                Toggle(
                    title = Strings.settingsAzimuthDmsTitle,
                    detail = Strings.settingsAzimuthDmsSummary,
                    checked = azimuthInDms,
                    onCheckedChange = { azimuthInDms = it },
                )

                Toggle(
                    title = Strings.settingsInclinationDmsTitle,
                    detail = Strings.settingsInclinationDmsSummary,
                    checked = inclinationInDms,
                    onCheckedChange = { inclinationInDms = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(SETTINGS_SAVE),
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
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
