package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip

/**
 * Who was on the trip, when, and with what.
 *
 * Every exporter in the port already knows how to write this — Therion and Survex `*team` and
 * `*date` lines, Compass's `SURVEY DATE` and `SURVEY TEAM` headers, PocketTopo's date row — and
 * until now there was no way to put anything in it, so every file this app produced went out
 * anonymous and, in Compass's case, dated by whatever the caller passed rather than by the trip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripDetailsDialog(
    survey: Survey,
    today: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val existing = survey.trip

    var date by remember { mutableStateOf((existing?.surveyDate ?: SurveyDate.parseOrNull(today))?.toString() ?: today) }
    // Seeded from the trip already on the survey, not from scratch: building a fresh Trip on Save
    // used to silently reset both fields to their class defaults.
    var explorationDateLinked by remember { mutableStateOf(existing?.explorationDateLinked ?: true) }
    var explorationDate by remember {
        mutableStateOf(existing?.explorationDate?.toString() ?: date)
    }
    var instrument by remember { mutableStateOf(existing?.instrument ?: "") }
    var comments by remember { mutableStateOf(existing?.comments ?: "") }
    var copyrightHolder by remember { mutableStateOf(existing?.copyrightHolder ?: "") }
    var licence by remember { mutableStateOf(existing?.licence ?: "") }
    // Until chosen, Save stays disabled: a blank licence is a perfectly good answer, but it has
    // to be chosen rather than defaulted into.
    var isLicenceChosen by remember { mutableStateOf(existing?.licence?.isNotEmpty() == true) }

    // A list rather than a single string, because the exporters emit one line per person with
    // their roles, and flattening it here would throw that away.
    val team = remember { mutableStateListOf<Trip.TeamEntry>().also { it.addAll(existing?.team.orEmpty()) } }
    var newMember by remember { mutableStateOf("") }

    val dateProblem = if (SurveyDate.parseOrNull(date) == null) "Dates are yyyy-mm-dd" else null
    val explorationDateProblem =
        if (!explorationDateLinked && SurveyDate.parseOrNull(explorationDate) == null) {
            "Dates are yyyy-mm-dd"
        } else {
            null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.actionTrip) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(Strings.tripSurveyDateLabel) },
                    singleLine = true,
                    isError = dateProblem != null,
                    supportingText = dateProblem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                // `exploration_date_linked_checkbox`, `exploration_date_field` and
                // `clear_exploration_date_button` from `activity_trip.xml`.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = explorationDateLinked,
                        onCheckedChange = { linked ->
                            explorationDateLinked = linked
                            if (!linked) explorationDate = date
                        },
                    )
                    Text(Strings.tripSameAsSurveyDate)
                }
                if (!explorationDateLinked) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = explorationDate,
                            onValueChange = { explorationDate = it },
                            label = { Text(Strings.tripExplorationDateLabel) },
                            singleLine = true,
                            isError = explorationDateProblem != null,
                            supportingText = explorationDateProblem?.let { { Text(it) } },
                            modifier = Modifier.weight(1f),
                        )
                        // `clear_exploration_date_button`: back to "not recorded", not to linked.
                        TextButton(onClick = { explorationDate = "" }) { Text("Clear") }
                    }
                }

                Text(Strings.tripTeam, style = MaterialTheme.typography.titleSmall)
                for ((index, member) in team.withIndex()) {
                    TeamMemberRow(
                        member = member,
                        onChange = { team[index] = it },
                        onRemove = { team.removeAt(index) },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // `trip_dialog_add_to_team_name_hint` and `trip_dialog_name_required`:
                    // `TeamMemberForm.performValidation` refuses a blank name, and says so.
                    OutlinedTextField(
                        value = newMember,
                        onValueChange = { newMember = it },
                        label = { Text(Strings.tripAddToTeamNameHint) },
                        singleLine = true,
                        isError = newMember.isNotEmpty() && newMember.isBlank(),
                        supportingText = {
                            if (newMember.isNotEmpty() && newMember.isBlank()) {
                                Text(Strings.tripNameRequired)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newMember.isNotBlank(),
                        onClick = {
                            team.add(Trip.TeamEntry(newMember.trim()))
                            newMember = ""
                        },
                    ) { Text(Strings.add) }
                }

                OutlinedTextField(
                    value = instrument,
                    onValueChange = { instrument = it },
                    label = { Text(Strings.tripInstrumentLabel) },
                    placeholder = { Text("DistoX2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text(Strings.tripComments) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                OutlinedTextField(
                    value = copyrightHolder,
                    onValueChange = { copyrightHolder = it },
                    label = { Text(Strings.tripCopyrightLabel) },
                    placeholder = { Text("Your caving club") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = licence,
                    onValueChange = {
                        licence = it
                        // Clearing the field by hand does not un-answer the question: an empty
                        // field is the unanswered state, and "No licence" is its own chip.
                        if (it.isNotBlank()) isLicenceChosen = true
                    },
                    label = { Text(Strings.tripLicenceLabel) },
                    placeholder = { Text("CC-BY-SA-4.0") },
                    singleLine = true,
                    isError = !isLicenceChosen,
                    supportingText = if (!isLicenceChosen) {
                        {
                            Text(
                                "Pick a licence before saving, or \"No licence\" if you " +
                                    "explicitly don't want one",
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = isLicenceChosen && licence.isBlank(),
                        onClick = {
                            licence = ""
                            isLicenceChosen = true
                        },
                        label = { Text("No licence") },
                    )
                    for (option in Licence.entries) {
                        FilterChip(
                            selected = licence.trim() == option.licenceName,
                            onClick = {
                                licence = option.licenceName
                                isLicenceChosen = true
                            },
                            label = {
                                Text(
                                    if (option == Licence.RECOMMENDED) {
                                        "${option.licenceName} (Recommended)"
                                    } else {
                                        option.licenceName
                                    },
                                )
                            },
                        )
                    }
                }
                // Only the licences above have a summary; free text, an unfamiliar licence from an
                // imported survey, and an untouched field all show nothing.
                val chosenLicence = Licence.forName(licence.trim())
                val licenceSummary = when {
                    chosenLicence != null -> chosenLicence.summaryPrefix + chosenLicence.summary
                    licence.isBlank() && isLicenceChosen -> Licence.NONE_SUMMARY
                    else -> null
                }
                if (licenceSummary != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(licenceSummary, style = MaterialTheme.typography.bodySmall)
                        if (chosenLicence?.hasUrl == true) {
                            Text(chosenLicence.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dateProblem == null && explorationDateProblem == null && isLicenceChosen,
                onClick = {
                    survey.trip =
                        tripFrom(
                            date = date,
                            explorationDateLinked = explorationDateLinked,
                            explorationDate = explorationDate,
                            team = team.toList(),
                            instrument = instrument,
                            comments = comments,
                            copyrightHolder = copyrightHolder,
                            licence = licence,
                        )
                    survey.isSaved = false
                    onSaved()
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamMemberRow(
    member: Trip.TeamEntry,
    onChange: (Trip.TeamEntry) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(member.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        // FlowRow, not Row: four chips do not fit across a phone, and a Row silently squeezes
        // the last one until its label wraps into an unreadable vertical column of letters.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (role in Trip.Role.entries) {
                FilterChip(
                    selected = role in member.roles,
                    onClick = {
                        // Roles are a list, not one value: on a two-person trip the same caver is
                        // usually on both the book and the instruments.
                        val roles =
                            if (role in member.roles) member.roles - role else member.roles + role
                        onChange(member.copy(roles = roles))
                    },
                    label = { Text(labelFor(role)) },
                )
            }
        }
    }
}

/**
 * `trip_role_book` and its three siblings, which say what each role *is* rather than abbreviating
 * it: a caver who has not filled this screen in before does not know what "Book" or "Dog" means.
 */
internal fun labelFor(role: Trip.Role): String =
    when (role) {
        Trip.Role.BOOK -> Strings.tripRoleBook
        Trip.Role.INSTRUMENTS -> Strings.tripRoleInstruments
        Trip.Role.DOG -> Strings.tripRoleDog
        Trip.Role.EXPLORATION -> Strings.tripRoleExploration
    }

/**
 * Builds the [Trip] the dialog describes.
 *
 * Blank people are dropped rather than written: an empty name would emit a `*team ""` line that
 * Therion accepts and no human can read. [explorationDate] is parsed only when
 * [explorationDateLinked] is false, matching the dialog's own `explorationDateProblem`.
 */
internal fun tripFrom(
    date: String,
    team: List<Trip.TeamEntry>,
    instrument: String,
    comments: String,
    copyrightHolder: String,
    licence: String,
    explorationDateLinked: Boolean = true,
    explorationDate: String = "",
): Trip? {
    val surveyDate = SurveyDate.parseOrNull(date) ?: return null
    return Trip(surveyDate).also {
        it.explorationDateLinked = explorationDateLinked
        it.explorationDate = if (explorationDateLinked) null else SurveyDate.parseOrNull(explorationDate)
        it.team = team.filter { entry -> entry.name.isNotBlank() }
        it.instrument = instrument.trim()
        it.comments = comments.trim()
        it.copyrightHolder = copyrightHolder.trim()
        it.licence = licence.trim()
    }
}
