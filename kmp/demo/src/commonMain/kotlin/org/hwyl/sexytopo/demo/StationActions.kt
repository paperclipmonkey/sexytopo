package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
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
 * Which of the station's fields a dialog is about.
 *
 * `context_station.xml` has a row each for renaming and commenting, and each opens a dialog with
 * one field in it. This port had them as one dialog with everything on it, which is fewer taps and
 * not what the app does — so it is one composable with three faces instead, sharing a single save
 * path so the three cannot disagree about what a rename does to the survey.
 */
enum class StationFields { NAME, COMMENT, PASSAGE }

/** What the boxes of the station's own dialogs answer to. */
const val STATION_NAME_FIELD: String = "station-name"
const val STATION_COMMENT_FIELD: String = "station-comment"

/** One of the four passage measurements, by the side of the passage it is. */
fun passageFieldTag(side: Lrud): String = "station-passage-${side.name.lowercase()}"

/**
 * Naming a station, and saying what is there.
 *
 * [StationFields.PASSAGE] is the one face with no counterpart upstream: the Android app books
 * passage measurements while adding a leg and never afterwards, and being able to add them to a
 * junction you have already walked past is what lets a cross-section be drawn from a hand-booked
 * survey.
 */
@Composable
fun StationActionsDialog(
    survey: Survey,
    station: Station,
    fields: StationFields,
    onDismiss: () -> Unit,
    onEdited: () -> Unit,
    /** Which bearing left and right go square to: `pref_lrud_direction`. */
    lrudMode: LrudMode = LrudMode.DEFAULT,
) {
    var name by remember(station) { mutableStateOf(station.name) }
    var comment by remember(station) { mutableStateOf(station.comment) }
    var direction by remember(station) { mutableStateOf(station.extendedElevationDirection) }
    val lrud = remember(station) { mutableStateListOf("", "", "", "") }

    val problem = if (fields == StationFields.NAME) renameProblem(survey, station, name) else null
    val focus = rememberOpeningFocus()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (fields) {
                    StationFields.NAME -> Strings.manualRenameStationTitle
                    StationFields.COMMENT -> Strings.menuComment
                    StationFields.PASSAGE -> Strings.settingsLrudFieldsTitle
                },
            )
        },
        text = {
            // Scrollable because the passage face is four fields wide and opens with the keyboard
            // up, taking a third of a phone screen. A scroll container that fits reports the
            // height of its content, so this changes nothing where it doesn't.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (fields) {
                    StationFields.NAME ->
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(Strings.manualRenameStationHint) },
                            singleLine = true,
                            isError = problem != null,
                            modifier =
                                Modifier.fillMaxWidth().testTag(STATION_NAME_FIELD).then(focus),
                        )

                    StationFields.COMMENT ->
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            label = { Text(Strings.manualEditStationComment) },
                            placeholder = { Text("Continues, too tight") },
                            modifier =
                                Modifier.fillMaxWidth().testTag(STATION_COMMENT_FIELD).then(focus),
                        )

                    StationFields.PASSAGE -> {
                        Text(
                            "Four tape measurements instead of four instrument shots. They " +
                                "become ordinary splays, so the cross-section is drawn from them " +
                                "like any other.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((index, side) in Lrud.entries.withIndex()) {
                                OutlinedTextField(
                                    value = lrud[index],
                                    onValueChange = { lrud[index] = it },
                                    label = { Text(lrudLabel(side)) },
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier =
                                        Modifier.weight(1f).testTag(passageFieldTag(side)),
                                )
                            }
                        }
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
                    if (fields == StationFields.PASSAGE) {
                        addLruds(survey, station, lrud.toList(), lrudMode)
                    }
                    onEdited()
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/** `manual_edit_left` and its three siblings: the field labels, not the enum's initials. */
private fun lrudLabel(side: Lrud): String =
    when (side) {
        Lrud.LEFT -> Strings.manualEditLeft
        Lrud.RIGHT -> Strings.manualEditRight
        Lrud.UP -> Strings.manualEditUp
        Lrud.DOWN -> Strings.manualEditDown
    }

/**
 * Why the typed name cannot be used, or null if it can.
 *
 * The uniqueness check is deliberately made here rather than left to
 * [SurveyUpdater.renameStation], which throws. It is also made on the *sanitised* name: [Station]
 * strips newlines when storing one, but the ported `renameStation` checks the raw string, so a
 * name differing from an existing one only by a newline would pass its check and then collide.
 */
internal fun renameProblem(survey: Survey, station: Station, typed: String): String? {
    val wanted = sanitiseStationName(typed)
    return when {
        wanted.isEmpty() -> "A station needs a name"
        wanted == station.name -> null
        survey.getStationByName(wanted) != null -> "There is already a station called $wanted"
        else -> exportProblem(wanted)
    }
}

/**
 * Characters that would make the survey files this app writes unreadable.
 *
 * The Android app's rename form only checks that a name is not blank, not `-`, and unique, so
 * *sump 2* is accepted there — and the Survex exporter writes station names into
 * whitespace-separated columns, so that leg comes out as
 *
 * ```
 * 1	sump 2	5.000	10.00	0.00
 * ```
 *
 * which is six fields where `*data normal from to tape compass clino` wants five. Survex will not
 * read it. A semicolon is worse than a space: it begins a comment in Survex, so the whole reading
 * is thrown away silently. Therion separates its columns the same way.
 *
 * The *model* still keeps whatever it is given, so a survey imported from Android with a name like
 * that is unchanged and still exports as badly — this app cannot fix somebody else's file by
 * refusing to open it. What it can do is stop making the problem going forward.
 */
private fun exportProblem(wanted: String): String? =
    when {
        wanted.any { it.isWhitespace() } ->
            "A station name cannot contain a space: Survex and Therion use spaces to separate " +
                "the columns, so the export would not read"
        ';' in wanted ->
            "A station name cannot contain a semicolon: it starts a comment in Survex, so the " +
                "readings on that line would be thrown away"
        else -> null
    }

/**
 * What is wrong with a name typed for a station that does not exist yet, or null.
 *
 * The other door into the same room as [renameProblem]: *Add a leg* lets a surveyor name the far
 * station. Two of [renameProblem]'s three rules do not apply here: a blank name is fine (it means
 * "call it whatever you would have called it"), and a name already in the survey is *not* refused,
 * because `advanceNumberIfNotUnique` moves it on — `2` becomes `3` — rather than losing a reading
 * somebody has just taken.
 */
internal fun newStationNameProblem(typed: String): String? {
    val wanted = sanitiseStationName(typed)
    return if (wanted.isEmpty()) null else exportProblem(wanted)
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
    // Through SurveyUpdater rather than by assignment: LEFT and RIGHT carry down the whole subtree
    // (`ExtendedElevationDirection.propagates`), so assigning the field directly would leave
    // everything past the junction going as it was - a drawing that is wrong and does not look
    // wrong. The guard avoids re-flooding the subtree on every unrelated edit.
    if (station.extendedElevationDirection != direction) {
        SurveyUpdater.setExtendedElevationDirection(survey, station, direction)
    }
    survey.isSaved = false
}

/**
 * Turns typed passage dimensions into splays.
 *
 * Left and right go square to whichever bearing [mode] names — [LrudMode.SURVEY] by default, which
 * bisects the corner at a bend — while up and down are vertical.
 *
 * A blank or unreadable field adds nothing, and a zero is skipped too: [Leg] rejects a non-positive
 * distance by throwing, and "0" is what somebody types for a wall they are standing against.
 */
internal fun addLruds(
    survey: Survey,
    station: Station,
    distances: List<String>,
    mode: LrudMode = LrudMode.DEFAULT,
): Int {
    var added = 0
    for ((index, side) in Lrud.entries.withIndex()) {
        val distance = distances.getOrNull(index)?.trim()?.replace(',', '.')?.toFloatOrNull()
        if (distance == null || distance <= 0f) continue
        SurveyBuilder.addSplay(
            survey,
            station,
            side.createSplay(survey, station, mode, distance),
        )
        added++
    }
    return added
}
