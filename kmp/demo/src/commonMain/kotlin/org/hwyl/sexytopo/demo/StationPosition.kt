package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.model.survey.StationFix
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Where one station is in the world, taken off a receiver or typed off a map.
 *
 * A survey is a frame floating in space until somebody says where one station of it is. Both
 * formats this app exports have had the mechanism for decades — see `StationFix` for what is
 * written and `SurvexTherionWriter.georeference` for how — and this is the screen that collects
 * it: stand at the entrance, put the phone down, and wait.
 *
 * ## Why the best reading rather than the last
 *
 * A receiver does not converge on an answer; it wanders towards one. The first reading out of a
 * cold phone can be a hundred metres out and the same spot two minutes later reads to five, but
 * the sequence is not monotonic — a bad one arrives after a good one all the time, as the
 * satellites in view change. Taking whatever happened to be on screen when Save was pressed would
 * throw away the good readings for a late bad one. So this keeps the best it has seen, shows both
 * when they differ, and saves the best.
 *
 * "Best" is the smallest claimed accuracy, which is the receiver's own opinion of itself and not
 * an independent one. It is the only opinion available, and it is a good deal better than the
 * alternative of trusting the newest.
 *
 * ## Why typing it in is a first-class way to use this screen
 *
 * Because it is usually the more accurate one. Cave entrances are in wooded valleys, under cliffs
 * and down shakeholes — which is to say in the places a satellite receiver is worst — and most
 * caves worth surveying already have a grid reference in a club's records or a registry, taken
 * with more care than a phone can manage. A screen that only offered the receiver would be a
 * screen that insisted on the worse number.
 */
@Composable
fun StationPositionDialog(
    survey: Survey,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val existing = survey.fix

    var station by remember { mutableStateOf(existing?.station ?: survey.origin.name) }
    // Typing starts on where the survey already is: a screen that opened into an empty form would
    // lose the position the moment somebody opened it to look at it.
    var typing by remember { mutableStateOf(existing != null || whyNoPosition().isNotEmpty()) }
    var latitude by remember { mutableStateOf(existing?.latitude?.toString() ?: "") }
    var longitude by remember { mutableStateOf(existing?.longitude?.toString() ?: "") }
    var altitude by remember { mutableStateOf(existing?.altitude?.toString() ?: "") }

    val positioning by rememberPosition(enabled = !typing)

    // The best reading seen since this screen opened, and when it last improved: a receiver that
    // has stopped improving is one a surveyor can stop waiting for, and nothing else on the screen
    // says so.
    var best by remember { mutableStateOf<GpsReading?>(null) }
    var seconds by remember { mutableStateOf(0) }
    var improvedAt by remember { mutableStateOf(0) }

    LaunchedEffect(typing) {
        if (typing) return@LaunchedEffect
        while (true) {
            delay(ONE_SECOND)
            seconds++
        }
    }

    val reading = positioning.reading
    if (reading != null && isBetterThan(reading, best)) {
        best = reading
        improvedAt = seconds
    }

    // The station has to exist before anything is built out of it, and not merely before Save is
    // pressed: `StationFix` refuses to be a fix that names nothing, and this runs on every
    // keystroke — including the empty field somebody passes through on their way to typing
    // another name.
    val named = station.trim().takeIf { survey.getStationByName(it) != null }
    val stationProblem = if (named == null) Strings.positionNoSuchStation else null

    val typedLatitude = StationFix.parseLatitude(latitude)
    val typedLongitude = StationFix.parseLongitude(longitude)
    val typedAltitude = StationFix.parseAltitude(altitude)

    val fix =
        if (named == null) {
            null
        } else if (typing) {
            if (typedLatitude != null && typedLongitude != null && typedAltitude != null) {
                StationFix(
                    station = named,
                    latitude = typedLatitude,
                    longitude = typedLongitude,
                    altitude = typedAltitude,
                    source = StationFix.Source.ENTERED,
                )
            } else {
                null
            }
        } else {
            // A reading with no height is a position and not a fix, and the export needs three
            // coordinates. Rather than refuse it, the height falls to whatever has been typed —
            // which is how somebody takes the position off the phone and the height off the map.
            best?.let { measured ->
                val height = measured.altitude ?: typedAltitude
                height?.let {
                    StationFix(
                        station = named,
                        latitude = measured.latitude,
                        longitude = measured.longitude,
                        altitude = it,
                        horizontalAccuracy = measured.horizontalAccuracy,
                        verticalAccuracy = measured.verticalAccuracy,
                        source = StationFix.Source.MEASURED,
                    )
                }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.actionStationPosition) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = station,
                    onValueChange = { station = it },
                    label = { Text(Strings.positionStationLabel) },
                    singleLine = true,
                    isError = stationProblem != null,
                    supportingText = stationProblem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("position-station"),
                )

                if (typing) {
                    TypedPosition(
                        latitude = latitude,
                        onLatitude = { latitude = it },
                        longitude = longitude,
                        onLongitude = { longitude = it },
                        altitude = altitude,
                        onAltitude = { altitude = it },
                        latitudeOk = latitude.isEmpty() || typedLatitude != null,
                        longitudeOk = longitude.isEmpty() || typedLongitude != null,
                        altitudeOk = altitude.isEmpty() || typedAltitude != null,
                    )
                } else {
                    MeasuredPosition(
                        positioning = positioning,
                        best = best,
                        seconds = seconds,
                        settled = best != null && seconds - improvedAt >= SETTLED_SECONDS,
                        // Only asked for when the receiver has none, which is the browser's usual
                        // answer and iOS's answer indoors.
                        altitude = altitude,
                        onAltitude = { altitude = it },
                        altitudeOk = altitude.isEmpty() || typedAltitude != null,
                    )
                }

                Text(Strings.positionTips, style = MaterialTheme.typography.bodySmall)
                Text(Strings.positionExportNote, style = MaterialTheme.typography.bodySmall)

                if (whyNoPosition().isEmpty()) {
                    TextButton(
                        onClick = { typing = !typing },
                        modifier = Modifier.testTag("position-switch-source"),
                    ) {
                        Text(if (typing) Strings.positionUseTheReceiver else Strings.positionTypeItIn)
                    }
                }

                // Away from the two buttons below, on purpose: this throws away something that
                // cost a walk to the entrance, and the app has already learned once what happens
                // when a destructive action sits where a stray tap lands.
                if (existing != null) {
                    TextButton(
                        onClick = {
                            survey.fix = null
                            onSaved()
                        },
                        modifier = Modifier.testTag("position-remove"),
                    ) {
                        Text(Strings.positionRemove)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fix != null,
                onClick = {
                    survey.fix = fix
                    onSaved()
                },
                modifier =
                    Modifier
                        .semantics { contentDescription = Strings.save }
                        .testTag("position-save"),
            ) {
                Text(Strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/** What the receiver is saying, and how long it has been saying it. */
@Composable
private fun MeasuredPosition(
    positioning: Positioning,
    best: GpsReading?,
    seconds: Int,
    settled: Boolean,
    altitude: String,
    onAltitude: (String) -> Unit,
    altitudeOk: Boolean,
) {
    val reading = positioning.reading

    Text(
        text =
            when {
                best?.horizontalAccuracy != null -> Strings.positionGoodTo(best.horizontalAccuracy)
                best != null -> Strings.positionAccuracyUnknown
                else -> Strings.positionWaiting
            },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("position-accuracy"),
    )

    if (best != null) {
        Text(
            degreesAndMetres(best),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("position-reading"),
        )
    }

    // The current reading, only when it is not the one being kept: two numbers on the screen at
    // once need a reason, and "the last one was worse than the best one" is that reason.
    val current = reading?.horizontalAccuracy
    val kept = best?.horizontalAccuracy
    if (current != null && kept != null && current > kept) {
        Text(Strings.positionBestSoFar(kept), style = MaterialTheme.typography.bodySmall)
    }

    Text(Strings.positionWatchingFor(seconds), style = MaterialTheme.typography.bodySmall)

    if (settled) {
        Text(Strings.positionSettled, style = MaterialTheme.typography.bodySmall)
    }

    positioning.trouble?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("position-trouble"))
    }

    // Asked for only when the receiver cannot say, so that a phone reporting its own height does
    // not invite somebody to disagree with it.
    if (best != null && best.altitude == null) {
        OutlinedTextField(
            value = altitude,
            onValueChange = onAltitude,
            label = { Text(Strings.positionAltitudeLabel) },
            singleLine = true,
            isError = !altitudeOk,
            supportingText = { Text(if (altitudeOk) Strings.positionAltitudeHint else Strings.positionAltitudeProblem) },
            modifier = Modifier.fillMaxWidth().testTag("position-altitude"),
        )
    }
}

/** The three fields, for a position that came off a map rather than out of the sky. */
@Composable
private fun TypedPosition(
    latitude: String,
    onLatitude: (String) -> Unit,
    longitude: String,
    onLongitude: (String) -> Unit,
    altitude: String,
    onAltitude: (String) -> Unit,
    latitudeOk: Boolean,
    longitudeOk: Boolean,
    altitudeOk: Boolean,
) {
    OutlinedTextField(
        value = latitude,
        onValueChange = onLatitude,
        label = { Text(Strings.positionLatitudeLabel) },
        singleLine = true,
        isError = !latitudeOk,
        supportingText = { Text(if (latitudeOk) "" else Strings.positionDegreesProblem) },
        modifier = Modifier.fillMaxWidth().testTag("position-latitude"),
    )
    OutlinedTextField(
        value = longitude,
        onValueChange = onLongitude,
        label = { Text(Strings.positionLongitudeLabel) },
        singleLine = true,
        isError = !longitudeOk,
        supportingText = { Text(if (longitudeOk) "" else Strings.positionDegreesProblem) },
        modifier = Modifier.fillMaxWidth().testTag("position-longitude"),
    )
    OutlinedTextField(
        value = altitude,
        onValueChange = onAltitude,
        label = { Text(Strings.positionAltitudeLabel) },
        singleLine = true,
        isError = !altitudeOk,
        supportingText = { Text(if (altitudeOk) Strings.positionAltitudeHint else Strings.positionAltitudeProblem) },
        modifier = Modifier.fillMaxWidth().testTag("position-altitude"),
    )
}

/**
 * Whether a reading is worth keeping over the one already kept.
 *
 * A receiver that will not say how well it knows where it is loses to one that will, and beats
 * nothing at all. Both are ordinary: a browser often gives an accuracy and no height, and some
 * Android devices give a position with neither.
 */
private fun isBetterThan(reading: GpsReading, best: GpsReading?): Boolean {
    if (best == null) return true
    val kept = best.horizontalAccuracy ?: return true
    val offered = reading.horizontalAccuracy ?: return false
    return offered < kept
}

/**
 * A reading as a surveyor reads it, in the digits the file will get.
 *
 * `formatFixed` rather than anything of this screen's own, and not for the sake of one fewer
 * function: it is what the exporter uses, so the number somebody reads off the screen and the
 * number that lands in the Therion file are the same number, rounded once and in one place. Six
 * decimals of a degree is about a tenth of a metre — four more than a phone can justify, and the
 * point at which a coordinate stops being one a person can compare with another by eye.
 *
 * Latitude first here, and longitude first in the file. That looks like an inconsistency and is
 * the opposite of one: people write and read coordinates north-then-east, every mapping site takes
 * them that way round, and both survey formats take them the other way round. Following each
 * convention where it belongs is what stops somebody comparing this screen with their phone's map
 * app and thinking one of the two is wrong.
 */
private fun degreesAndMetres(reading: GpsReading): String {
    val height = reading.altitude?.let { ", ${formatFixed(it, 0)} m" } ?: ""
    return "${formatFixed(reading.latitude, DEGREE_PLACES)}, " +
        "${formatFixed(reading.longitude, DEGREE_PLACES)}$height"
}

private const val DEGREE_PLACES = 6

private const val ONE_SECOND = 1000L

/**
 * How long the best reading has to stand before the screen says it has settled.
 *
 * Long enough that a receiver still improving in steps does not get told it has stopped, short
 * enough to be useful to somebody standing in the rain.
 */
private const val SETTLED_SECONDS = 30
