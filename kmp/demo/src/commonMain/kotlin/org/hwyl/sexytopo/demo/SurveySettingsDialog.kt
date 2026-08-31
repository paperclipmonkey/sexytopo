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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm

/**
 * How close two readings have to be before the app calls them the same shot.
 *
 * This exists because the defaults assume a DistoX. `maxAngleDelta` is 1.7 degrees, which a laser
 * instrument beats comfortably and a hand-held compass does not come close to — so on a training
 * trip with a compass and tape, three readings of the same leg never agree, nothing is ever
 * promoted to a station, and the survey fills up with splays while the surveyor has no idea why.
 * Every one of these values was already ported and tested; they were simply hard-wired to
 * [SurveySettings.DEFAULT], which made the app usable with one class of instrument only.
 *
 * Only the tolerances the selected algorithm actually reads are shown. Offering all four at once
 * invites somebody to loosen the one their algorithm ignores and conclude the setting is broken.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurveySettingsDialog(
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
    var buzz by remember { mutableStateOf(preferences.buzzOnNewStation) }
    var hotCorners by remember { mutableStateOf(preferences.hotCorners) }
    var twoFingerMove by remember { mutableStateOf(preferences.twoFingerMove) }
    var autoReconnect by remember { mutableStateOf(preferences.autoReconnect) }
    var reconnectWindow by
        remember { mutableStateOf(preferences.autoReconnectWindowMinutes.toString()) }

    val edited =
        settingsFrom(algorithm, distance, angle, endpoint, pairwise, repeats, settings)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Surveying") },
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
                        NumberField(distance, { distance = it }, "Distance spread (m)")
                        NumberField(angle, { angle = it }, "Angle spread (°)")
                    }
                    LegAmalgamationAlgorithm.CARTESIAN ->
                        NumberField(endpoint, { endpoint = it }, "Endpoint gap (m)")
                    LegAmalgamationAlgorithm.PAIRWISE ->
                        NumberField(pairwise, { pairwise = it }, "Relative error")
                }

                NumberField(repeats, { repeats = it }, "Readings to make a station")

                HorizontalDivider()

                // `pref_vibrate_on_new_station`, which lives in the Android app's general
                // preferences. It is here because this port has one settings screen, and because
                // this is the setting somebody reaches for the moment they take the phone
                // underground rather than looking at it on a desk.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Buzz when a station is made", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (canBuzz()) {
                                "So you can look at the rock instead of the phone."
                            } else {
                                "This device cannot vibrate."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = buzz && canBuzz(),
                        enabled = canBuzz(),
                        onCheckedChange = {
                            buzz = it
                            // Feel it as you turn it on, which is the only way to know the phone
                            // is actually going to do it.
                            if (it) buzz()
                        },
                    )
                }

                HorizontalDivider()

                // `pref_hot_corners` and `pref_two_finger_movement`. Both are about the same
                // problem — moving the drawing while drawing it — so they belong together, and
                // both keep the Android app's own defaults.
                Toggle(
                    title = "Corners pan the sketch",
                    detail =
                        "A touch in any corner moves the drawing instead of marking it, so you " +
                            "can pan without putting the pencil down.",
                    checked = hotCorners,
                    onCheckedChange = { hotCorners = it },
                )

                Toggle(
                    title = "Two fingers pan the sketch",
                    detail =
                        "Off by default: a hand holding the phone rests a second finger on the " +
                            "glass more often than it means to. Pinch to zoom works either way.",
                    checked = twoFingerMove,
                    onCheckedChange = { twoFingerMove = it },
                )

                HorizontalDivider()

                // `pref_auto_reconnect` and `pref_auto_reconnect_window`, which the Android app
                // keeps on this same preference screen — `preferences_instruments.xml`, below the
                // tolerances — so this is where they belong here too.
                Toggle(
                    title = "Chase a lost instrument",
                    detail =
                        "A cave breaks Bluetooth constantly: you walk round a corner with the " +
                            "phone, the instrument sleeps, a cold battery sags. Off by default, " +
                            "as on Android.",
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it },
                )

                // Only while it is on, which is what `android:dependency` does on the Android
                // screen: a number with nothing reading it is a setting that looks broken.
                if (autoReconnect) {
                    NumberField(
                        reconnectWindow,
                        { reconnectWindow = it },
                        "Give up after (minutes)",
                    )
                    Text(
                        "Counted from the first failure, not the last — so an instrument left " +
                            "behind at the last station stops costing battery on the way out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = edited != null,
                onClick = {
                    edited?.let {
                        onSave(
                            it,
                            preferencesFrom(
                                preferences,
                                buzz,
                                hotCorners,
                                twoFingerMove,
                                autoReconnect,
                                reconnectWindow,
                            ),
                        )
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    buzzOnNewStation: Boolean,
    hotCorners: Boolean,
    twoFingerMove: Boolean,
    autoReconnect: Boolean,
    /**
     * As typed. Rubbish keeps the value that was there rather than resetting it to the default —
     * a half-typed number should not be able to change a setting the surveyor was not editing,
     * and the field is only on screen at all while the toggle above it is on.
     */
    autoReconnectWindow: String,
): AppPreferences =
    current.copy(
        buzzOnNewStation = buzzOnNewStation,
        hotCorners = hotCorners,
        twoFingerMove = twoFingerMove,
        autoReconnect = autoReconnect,
        autoReconnectWindowMinutes =
            autoReconnectWindow.trim().toIntOrNull()?.coerceIn(0, AppPreferencesStore.MAX_WINDOW_MINUTES)
                ?: current.autoReconnectWindowMinutes,
    )

/** A labelled switch with a line of explanation, as the settings rows above it already are. */
@Composable
internal fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
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

/** What each strategy actually compares, in the terms a surveyor thinks in. */
internal fun describe(algorithm: LegAmalgamationAlgorithm): String =
    when (algorithm) {
        LegAmalgamationAlgorithm.ANGULAR ->
            "Compares distance and angles separately. The app's default."
        LegAmalgamationAlgorithm.CARTESIAN ->
            "Compares where each reading puts the far end. Forgiving of a long shot's bearing."
        LegAmalgamationAlgorithm.PAIRWISE ->
            "Compares endpoints as a fraction of leg length, so short shots are held tighter."
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
