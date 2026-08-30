package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.Lrud
import org.hwyl.sexytopo.shared.survey.LrudMode
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * Naming a station, and saying what is there.
 *
 * Stations arrive as "1", "2", "3" and that is fine for a straight passage. It stops being fine at
 * the first junction: a surveyor writes "sump" or "AV12" in their notebook, and a survey where that
 * name exists only on paper is one that cannot be tied to the next trip's. The comment is the same
 * argument a sentence later — "continues, too tight for me" is the difference between a lead
 * somebody goes back for and one nobody remembers.
 *
 * The extended-elevation direction is here because it belongs to a station and nowhere else. In an
 * extended elevation the passage is unrolled onto a line, and at a junction the surveyor has to say
 * which way the branch unrolls; SexyTopo's own UI puts that choice on the station for exactly this
 * reason. It propagates onward from here, so setting it on the junction sets it for the branch.
 */
@Composable
fun StationActionsDialog(
    survey: Survey,
    station: Station,
    onDismiss: () -> Unit,
    onEdited: () -> Unit,
) {
    var name by remember(station) { mutableStateOf(station.name) }
    var comment by remember(station) { mutableStateOf(station.comment) }
    var direction by remember(station) { mutableStateOf(station.extendedElevationDirection) }
    val lrud = remember(station) { mutableStateListOf("", "", "", "") }

    val problem = renameProblem(survey, station, name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Station ${station.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment") },
                    placeholder = { Text("Continues, too tight") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Passage size", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Four tape measurements instead of four instrument shots. They become ordinary " +
                        "splays, so the cross-section is drawn from them like any other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((index, side) in Lrud.entries.withIndex()) {
                        OutlinedTextField(
                            value = lrud[index],
                            onValueChange = { lrud[index] = it },
                            label = { Text(side.name.take(1)) },
                            singleLine = true,
                            keyboardOptions =
                                KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Text(
                    "In the extended elevation, this station's passage unrolls:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in ExtendedElevationDirection.entries) {
                        FilterChip(
                            selected = direction == option,
                            onClick = { direction = option },
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    applyStationEdit(survey, station, name, comment, direction)
                    addLruds(survey, station, lrud.toList())
                    onEdited()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Why the typed name cannot be used, or null if it can.
 *
 * The uniqueness check is deliberately made here rather than left to
 * [SurveyUpdater.renameStation], which throws — a surveyor who types a name that is already taken
 * should be told, not crashed at. It is also made on the *sanitised* name: [Station] strips
 * newlines when storing one, and the ported `renameStation` checks the raw string, so a name that
 * differs from an existing one only by a newline passes its check and then collides. Checking what
 * will actually be stored closes that off without changing the ported behaviour underneath.
 */
internal fun renameProblem(survey: Survey, station: Station, typed: String): String? {
    val wanted = sanitiseStationName(typed)
    return when {
        wanted.isEmpty() -> "A station needs a name"
        wanted == station.name -> null
        survey.getStationByName(wanted) != null -> "There is already a station called $wanted"
        else -> null
    }
}

/** What [Station] will keep of a typed name. */
internal fun sanitiseStationName(typed: String): String =
    typed.filterNot { it in Station.FORBIDDEN_CHARS }.trim()

internal fun applyStationEdit(
    survey: Survey,
    station: Station,
    name: String,
    comment: String,
    direction: ExtendedElevationDirection,
) {
    val wanted = sanitiseStationName(name)
    // renameStation rejects a rename to the name the station already has, so only call it when
    // something actually changed.
    if (wanted.isNotEmpty() && wanted != station.name) {
        SurveyUpdater.renameStation(survey, station, wanted)
    }
    station.comment = comment
    station.extendedElevationDirection = direction
    survey.isSaved = false
}

/**
 * Turns typed passage dimensions into splays.
 *
 * The traditional way of recording passage size when there is a tape and no instrument: four
 * numbers per station rather than four full compass-and-clino shots, with the app inventing the
 * directions. Left and right go square to the passage — [LrudMode.SURVEY], which bisects the
 * corner at a bend, and is what most cavers mean by a left-hand wall measurement — while up and
 * down are vertical.
 *
 * They become ordinary splays, which is what makes cross-sections and the exporters' passage
 * dimensions work on a hand-booked survey exactly as on an instrument-fed one.
 *
 * A blank or unreadable field adds nothing, and a zero is skipped too: [Leg] rejects a
 * non-positive distance by throwing, and "0" is what somebody types for a wall they are standing
 * against.
 */
internal fun addLruds(survey: Survey, station: Station, distances: List<String>): Int {
    var added = 0
    for ((index, side) in Lrud.entries.withIndex()) {
        val distance = distances.getOrNull(index)?.trim()?.replace(',', '.')?.toFloatOrNull()
        if (distance == null || distance <= 0f) continue
        SurveyBuilder.addSplay(
            survey,
            station,
            side.createSplay(survey, station, LrudMode.DEFAULT, distance),
        )
        added++
    }
    return added
}
