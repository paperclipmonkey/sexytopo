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
    var manualControls by remember { mutableStateOf(preferences.manualControls) }
    var lrudFields by remember { mutableStateOf(preferences.lrudFields) }
    var azimuthInDms by remember { mutableStateOf(preferences.azimuthInDms) }
    var inclinationInDms by remember { mutableStateOf(preferences.inclinationInDms) }
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

                // Greyed out while the switch above is off, rather than hidden — which is what
                // `android:dependency` actually does: `Preference.setDependency` *disables* the
                // dependent, it does not remove it. Hiding it was both less faithful and worse
                // here for a reason worth writing down: a dialog that changes height when you
                // touch a control in it moves every other control in it, and `field.mjs` finds
                // these by measurement. A check that turned this on and then failed to turn it
                // off again passed anyway, because it only asserted the turning on.
                NumberField(
                    reconnectWindow,
                    { reconnectWindow = it },
                    "Give up after (minutes)",
                    enabled = autoReconnect,
                )
                Text(
                    "Counted from the first failure, not the last — so an instrument left " +
                        "behind at the last station stops costing battery on the way out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                azimuthInDms,
                                inclinationInDms,
                                manualControls,
                                lrudFields,
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
    azimuthInDms: Boolean,
    inclinationInDms: Boolean,
    manualControls: Boolean,
    lrudFields: Boolean,
): AppPreferences =
    current.copy(
        buzzOnNewStation = buzzOnNewStation,
        hotCorners = hotCorners,
        twoFingerMove = twoFingerMove,
        autoReconnect = autoReconnect,
        autoReconnectWindowMinutes =
            autoReconnectWindow.trim().toIntOrNull()?.coerceIn(0, AppPreferencesStore.MAX_WINDOW_MINUTES)
                ?: current.autoReconnectWindowMinutes,
        azimuthInDms = azimuthInDms,
        inclinationInDms = inclinationInDms,
        manualControls = manualControls,
        lrudFields = lrudFields,
    )

/**
 * A labelled switch with a line of explanation, as the settings rows above it already are.
 *
 * [enabled] greys the row out rather than removing it, which is what `android:dependency` does on
 * an Android preference screen and is the right behaviour for the same reason: a setting that
 * disappears when its parent is turned off is a setting the surveyor cannot find again to work out
 * why it did nothing. It also keeps the dialog one height, which the browser checks rely on.
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
    Row(verticalAlignment = Alignment.CenterVertically) {
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
