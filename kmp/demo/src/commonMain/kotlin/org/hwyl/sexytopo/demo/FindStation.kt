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
 * Finding a station by name and putting the view on it — `action_find_station`, which the Android
 * app offers from its tools menu through `StationSelectorDialog`.
 *
 * A survey of any size does not fit on a phone screen, and the two things a surveyor does with
 * somebody else's cave are "where is AV12" and "take me back to where I stopped". Without this the
 * only answer is to pinch out until the whole cave is on screen, find the numeral, and pinch back
 * in — which on a 420-pixel screen means the labels are too small to read at exactly the zoom where
 * the whole cave fits.
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
        title = { Text("Find a station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Name") },
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
                    // A list rather than an autocomplete dropdown, because a surveyor often does
                    // not know the name — they know it is one of the ones near the sump. Showing
                    // the candidates is the useful half of the Android field's autocomplete.
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The name, plus the comment when there is one — which is usually what somebody is looking for. */
internal fun describe(station: Station): String =
    if (station.comment.isBlank()) station.name else "${station.name} — ${station.comment}"

/**
 * The stations whose name or comment contains [query], case-insensitively, in survey order.
 *
 * The comment is searched as well as the name, and that is the point of the feature rather than a
 * flourish: stations are called "1", "2", "3", and what the surveyor remembers is "the one where
 * the draught was", which is what they wrote in the comment.
 *
 * An empty query lists everything, so opening the dialog on a small survey shows the whole cave
 * without typing.
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
 * Where [station] sits in [projection], or null if the survey does not place it.
 *
 * Null rather than a default, because it is reachable: the map is keyed on station *identity* — see
 * the note in `Space` about `Station` having no `hashCode` — so a station renamed or deleted
 * between the dialog listing it and a row being tapped is simply not in it, and centring the view
 * on the origin instead would be a lie about where it went.
 */
fun stationPositionIn(survey: Survey, projection: Projection2D, station: Station): Coord2D? =
    projection.project(survey).stationMap[station]

/**
 * "Delete the last leg", with the leg named — `buttonDeleteLastLeg`, which the Android app runs
 * without asking.
 *
 * Asking is the one deliberate difference. The sketch has an undo stack; the *survey* does not, in
 * the app or here, so this is the only action on the drawing menu that cannot be taken back. On
 * Android it sits between "Centre view" and a row of display toggles, which is a bad neighbourhood
 * for something irreversible — and the reading it names is exactly what tells a surveyor whether
 * the app means the shot they think it means.
 */
@Composable
fun DeleteLastLegDialog(survey: Survey, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val leg = remember(survey, survey.getAllLegsInChronoOrder().size) { lastLegDescription(survey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (leg == null) "Nothing to delete" else "Delete the last leg?") },
        text = {
            Text(
                leg
                    ?: "This survey has no legs yet, so there is nothing to take back.",
            )
        },
        confirmButton = {
            if (leg != null) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (leg == null) "Close" else "Cancel") }
        },
    )
}

/**
 * The last leg recorded, in the surveyor's own terms, or null if there is none.
 *
 * Chronological order rather than tree order: "the last leg" means the last one *taken*, which is
 * what `Survey.undoAddLeg` pops, and on a survey with several branches that is not the last one in
 * any walk of the tree.
 */
fun lastLegDescription(survey: Survey): String? {
    val leg = survey.getAllLegsInChronoOrder().lastOrNull() ?: return null
    val from = survey.getOriginatingStation(leg)?.name ?: "?"
    val to = if (leg.hasDestination()) leg.destination.name else "a splay"
    // The shared formatters the table and every exporter use, so the numbers here read exactly as
    // they do in the row this deletes — HALF_UP rather than Kotlin's ties-to-even, which is
    // finding 3 and the reason these exist at all.
    val reading =
        "${formatDistance(leg.distance)} m, ${formatAzimuth(leg.azimuth)}°, " +
            "${formatInclination(leg.inclination)}°"
    return "$from to $to — $reading"
}
