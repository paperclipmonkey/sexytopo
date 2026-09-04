package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm

/**
 * `preferences_instruments.xml`: how close two readings have to be before the app calls them the
 * same shot, and what to do when the instrument drops out.
 *
 * This exists because the defaults assume a DistoX. `maxAngleDelta` is 1.7 degrees, which a laser
 * instrument beats comfortably and a hand-held compass does not come close to, so on a training
 * trip with a compass and tape, three readings of the same leg never agree and nothing is ever
 * promoted to a station.
 *
 * Only the tolerances the selected algorithm actually reads are shown: offering all four at once
 * invites somebody to loosen the one their algorithm ignores and conclude the setting is broken.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstrumentSettingsDialog(
    settings: SurveySettings,
    preferences: AppPreferences,
    onDismiss: () -> Unit,
    onSave: (SurveySettings, AppPreferences) -> Unit,
) {
    var algorithm by remember { mutableStateOf(settings.legAmalgamationAlgorithm) }
    var distance by remember { mutableStateOf(settings.maxDistanceDelta.toString()) }
    var angle by remember { mutableStateOf(settings.maxAngleDelta.toString()) }
    var endpoint by remember { mutableStateOf(settings.maxEndpointDelta.toString()) }
    var pairwise by remember { mutableStateOf(settings.maxPairwiseError.toString()) }
    var repeats by remember { mutableStateOf(settings.numberOfRepeatsForNewStation.toString()) }
    var autoReconnect by remember { mutableStateOf(preferences.autoReconnect) }
    var developerMode by remember { mutableStateOf(preferences.developerMode) }
    var reconnectWindow by
        remember { mutableStateOf(preferences.autoReconnectWindowMinutes.toString()) }

    val edited =
        settingsFrom(algorithm, distance, angle, endpoint, pairwise, repeats, settings)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.settingsInstrumentsTitle) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "How close repeated readings have to be before they make a station. The " +
                        "defaults suit a DistoX; a compass and tape needs looser ones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    Strings.settingsAmalgamationTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (option in LegAmalgamationAlgorithm.entries) {
                        FilterChip(
                            selected = algorithm == option,
                            onClick = { algorithm = option },
                            label = { Text(labelFor(option)) },
                        )
                    }
                }
                Text(
                    describe(algorithm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (algorithm) {
                    LegAmalgamationAlgorithm.ANGULAR -> {
                        NumberField(distance, { distance = it }, Strings.settingsMaxDistanceDeltaTitle)
                        NumberField(angle, { angle = it }, Strings.settingsMaxAngleDeltaTitle)
                    }
                    LegAmalgamationAlgorithm.CARTESIAN ->
                        NumberField(endpoint, { endpoint = it }, Strings.settingsMaxEndpointDeltaTitle)
                    LegAmalgamationAlgorithm.PAIRWISE ->
                        NumberField(pairwise, { pairwise = it }, Strings.settingsMaxPairwiseErrorTitle)
                }

                NumberField(repeats, { repeats = it }, READINGS_TO_MAKE_A_STATION)

                HorizontalDivider()

                // `pref_auto_reconnect` and `pref_auto_reconnect_window`.
                Toggle(
                    title = Strings.settingsAutoReconnectTitle,
                    detail = Strings.settingsAutoReconnectSummary,
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it },
                )

                // Greyed out while the switch above is off, rather than hidden - see the note on
                // [Toggle]. It also keeps the dialog's height constant, which `field.mjs` relies
                // on to find controls by measurement.
                NumberField(
                    reconnectWindow,
                    { reconnectWindow = it },
                    Strings.settingsAutoReconnectWindowTitle,
                    enabled = autoReconnect,
                )
                Text(
                    Strings.settingsAutoReconnectWindowSummary +
                        " — counted from the first failure, not the last, so an instrument left " +
                        "behind at the last station stops costing battery on the way out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // `pref_developer_mode`. Here it is one diagnostic — see
                // `SurveySession.traceFrames`: whether the bytes are arriving at all, which is the
                // one thing you cannot work out afterwards.
                Toggle(
                    title = Strings.settingsDeveloperModeTitle,
                    detail = Strings.settingsDeveloperModeSummary,
                    checked = developerMode,
                    onCheckedChange = { developerMode = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(SETTINGS_SAVE),
                enabled = edited != null,
                onClick = {
                    edited?.let {
                        onSave(
                            it,
                            preferencesFrom(
                                preferences,
                                autoReconnect,
                                reconnectWindow,
                                developerMode,
                            ),
                        )
                    }
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/**
 * The preferences this screen describes, on top of the ones it does not.
 *
 * A `copy` of what came in rather than a fresh [AppPreferences], and the difference is a silent
 * data loss: this dialog shows three of the app's preferences, and building a new object from three
 * values resets every other one to its default. *Follow the survey* is set from the drawing menu,
 * so a surveyor who turned it on halfway down a passage and then loosened their tolerances would
 * find it off again with nothing on screen to say so.
 */
internal fun preferencesFrom(
    current: AppPreferences,
    autoReconnect: Boolean,
    /** As typed. Rubbish keeps the value that was there rather than resetting to the default. */
    autoReconnectWindow: String,
    developerMode: Boolean,
): AppPreferences =
    current.copy(
        autoReconnect = autoReconnect,
        autoReconnectWindowMinutes =
            autoReconnectWindow.trim().toIntOrNull()?.coerceIn(0, AppPreferencesStore.MAX_WINDOW_MINUTES)
                ?: current.autoReconnectWindowMinutes,
        developerMode = developerMode,
    )

/**
 * What every settings dialog's Save button answers to.
 *
 * Picking a theme repaints the card under the button, so finding it by the colour it is drawn on
 * stops working exactly when the theme is the thing being changed.
 */
const val SETTINGS_SAVE: String = "settings-save"

/**
 * A labelled switch with a line of explanation, as the settings rows above it already are.
 *
 * [enabled] greys the row out rather than removing it: a setting that disappears when its parent
 * is turned off is a setting the surveyor cannot find again to work out why it did nothing.
 */
@Composable
internal fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val dim = if (enabled) 1f else DISABLED_ALPHA
    // Named after the setting it is, because a switch that is off — or greyed out because the
    // device cannot do the thing — is not something a picture of the dialog can pick out.
    Row(
        Modifier.testTag(tagFor(title)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * `numberOfRepeatsForNewStation`, which the Android app has no settings entry for at all — it is
 * a shared-core value this port exposes because a compass-and-tape party needs it.
 */
private const val READINGS_TO_MAKE_A_STATION = "Readings to make a station"

/** Material's own disabled opacity, which is what the greyed-out rows above are drawn at. */
private const val DISABLED_ALPHA = 0.38f

@Composable
internal fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        // Decimal, not Number: iOS numberPad has no decimal point, and every value here has one.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun labelFor(algorithm: LegAmalgamationAlgorithm): String =
    when (algorithm) {
        LegAmalgamationAlgorithm.ANGULAR -> "Angular"
        LegAmalgamationAlgorithm.CARTESIAN -> "Cartesian"
        LegAmalgamationAlgorithm.PAIRWISE -> "Pairwise"
    }

/**
 * What each strategy actually compares — `settings_leg_amalgamation_algorithm_entries`, which the
 * Android app shows as the list entry itself. Here it is the caption under three short chips,
 * since a chip carrying a whole sentence is not a chip.
 */
internal fun describe(algorithm: LegAmalgamationAlgorithm): String =
    when (algorithm) {
        LegAmalgamationAlgorithm.ANGULAR -> Strings.amalgamationAngular
        LegAmalgamationAlgorithm.CARTESIAN -> Strings.amalgamationCartesian
        LegAmalgamationAlgorithm.PAIRWISE -> Strings.amalgamationPairwise
    }

/**
 * The settings the dialog describes, or null if a field cannot be read.
 *
 * Values the selected algorithm does not use are carried across from [current] untouched rather
 * than reset, so switching algorithm to look at it and switching back loses nothing.
 */
internal fun settingsFrom(
    algorithm: LegAmalgamationAlgorithm,
    distance: String,
    angle: String,
    endpoint: String,
    pairwise: String,
    repeats: String,
    current: SurveySettings,
): SurveySettings? {
    val distanceValue = distance.trim().replace(',', '.').toFloatOrNull() ?: return null
    val angleValue = angle.trim().replace(',', '.').toFloatOrNull() ?: return null
    val endpointValue = endpoint.trim().replace(',', '.').toFloatOrNull() ?: return null
    val pairwiseValue = pairwise.trim().replace(',', '.').toFloatOrNull() ?: return null
    val repeatsValue = repeats.trim().toIntOrNull() ?: return null

    // A negative tolerance would refuse every reading; zero repeats would promote on nothing at
    // all. Both are reachable by typing and neither is recoverable from without knowing why.
    if (distanceValue < 0f || angleValue < 0f || endpointValue < 0f || pairwiseValue < 0f) return null
    if (repeatsValue < 1) return null

    return current.copy(
        legAmalgamationAlgorithm = algorithm,
        maxDistanceDelta = distanceValue,
        maxAngleDelta = angleValue,
        maxEndpointDelta = endpointValue,
        maxPairwiseError = pairwiseValue,
        numberOfRepeatsForNewStation = repeatsValue,
    )
}
