package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.io.export.formatAzimuth
import org.hwyl.sexytopo.shared.io.export.formatDistance
import org.hwyl.sexytopo.shared.io.export.formatInclination
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Finding a station by name and putting the view on it.
 *
 * Without this the only answer is to pinch out until the whole cave is on screen, find the
 * numeral, and pinch back in — at exactly the zoom where labels are too small to read.
 */
@Composable
fun FindStationDialog(
    survey: Survey,
    onDismiss: () -> Unit,
    onGo: (Station) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(survey, query) { stationsMatching(survey, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.actionFindStation) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    // Deliberately *not* given [rememberOpeningFocus]: this dialog is also a
                    // list, and focusing the box would raise the keyboard over it.
                    label = { Text(Strings.manualRenameStationHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matches.isEmpty()) {
                    Text(
                        "No station is called that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(matches, key = { it.name }) { station ->
                            TextButton(
                                onClick = { onGo(station) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    describe(station),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/** The name, plus the comment when there is one — which is usually what somebody is looking for. */
internal fun describe(station: Station): String =
    if (station.comment.isBlank()) station.name else "${station.name} — ${station.comment}"

/**
 * The stations whose name or comment contains [query], case-insensitively, in survey order.
 * Stations are called "1", "2", "3", and what a surveyor remembers is what they wrote in the
 * comment.
 */
fun stationsMatching(survey: Survey, query: String): List<Station> {
    val trimmed = query.trim()
    val all = survey.getAllStationsInChronoOrder()
    if (trimmed.isEmpty()) return all
    return all.filter {
        it.name.contains(trimmed, ignoreCase = true) ||
            it.comment.contains(trimmed, ignoreCase = true)
    }
}

/**
 * Where [station] sits in [projection], or null if the survey does not place it — reachable when
 * a station is renamed or deleted between the dialog listing it and a row being tapped.
 */
fun stationPositionIn(survey: Survey, projection: Projection2D, station: Station): Coord2D? =
    projection.project(survey).stationMap[station]

/**
 * `buttonDeleteLastLeg`, with the leg named. Asking is the one deliberate difference from Android:
 * the survey has no undo stack, so this is the only irreversible action on the menu.
 */
@Composable
fun DeleteLastLegDialog(survey: Survey, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val leg = remember(survey, survey.getAllLegsInChronoOrder().size) { lastLegDescription(survey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // `sketch_menu_delete_last_leg`, which is what the drawing menu row that opens this says.
        title = {
            Text(
                if (leg == null) "Nothing to delete" else "${Strings.sketchMenuDeleteLastLeg}?",
            )
        },
        text = {
            Text(
                leg
                    ?: "This survey has no legs yet, so there is nothing to take back.",
            )
        },
        confirmButton = {
            if (leg != null) {
                TextButton(onClick = onDelete) { Text(Strings.delete) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.cancel) }
        },
    )
}

/** Chronological order rather than tree order: "the last leg" means the last one *taken*. */
fun lastLegDescription(survey: Survey): String? {
    val leg = survey.getAllLegsInChronoOrder().lastOrNull() ?: return null
    val from = survey.getOriginatingStation(leg)?.name ?: "?"
    val to = if (leg.hasDestination()) leg.destination.name else "a splay"
    // The shared formatters the table and every exporter use — HALF_UP rather than Kotlin's
    // ties-to-even.
    val reading =
        "${formatDistance(leg.distance)} m, ${formatAzimuth(leg.azimuth)}°, " +
            "${formatInclination(leg.inclination)}°"
    return "$from to $to — $reading"
}
