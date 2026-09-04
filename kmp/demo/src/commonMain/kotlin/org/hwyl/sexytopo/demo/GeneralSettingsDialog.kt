package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * `preferences_general.xml`: theme, and whether the phone buzzes when a station is made.
 *
 * The Android screen has a third entry, `pref_orientation`, which calls
 * `setRequestedOrientation` — an Android activity API with no counterpart on iOS, the desktop or
 * the web, so it is left out rather than shown doing nothing.
 *
 * The theme applies as it is picked rather than on a Save button: comparing two themes means
 * looking at the drawing behind the dialog, and the Android app's own summary warns that changing
 * it restarts the app, which is a cost this port does not have to pass on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneralSettingsDialog(
    state: DemoState,
    onDismiss: () -> Unit,
) {
    var buzz by remember { mutableStateOf(state.preferences.buzzOnNewStation) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.settingsGeneralTitle) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    Strings.settingsThemeTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (theme in AppTheme.entries) {
                        FilterChip(
                            modifier = Modifier.testTag("theme-${theme.name.lowercase()}"),
                            selected = state.preferences.theme == theme,
                            onClick = {
                                state.updatePreferences(state.preferences.copy(theme = theme))
                            },
                            label = { Text(theme.label) },
                        )
                    }
                }

                HorizontalDivider()

                // `pref_vibrate_on_new_station`.
                Toggle(
                    title = Strings.settingsVibrateTitle,
                    detail =
                        if (canBuzz()) {
                            Strings.settingsVibrateSummary
                        } else {
                            "This device cannot vibrate."
                        },
                    checked = buzz && canBuzz(),
                    enabled = canBuzz(),
                    onCheckedChange = {
                        buzz = it
                        // Feel it as you turn it on, to know the phone actually will.
                        if (it) buzz()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    state.updatePreferences(state.preferences.copy(buzzOnNewStation = buzz))
                    onDismiss()
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
