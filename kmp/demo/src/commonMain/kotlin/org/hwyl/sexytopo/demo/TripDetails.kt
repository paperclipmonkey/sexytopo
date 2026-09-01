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
 * This is the block that turns a set of numbers into a survey somebody can publish. Every exporter
 * in the port already knows how to write it — Therion and Survex `*team` and `*date` lines,
 * Compass's `SURVEY DATE` and `SURVEY TEAM` headers, PocketTopo's date row — and until now there
 * was no way to put anything in it, so every file this app produced went out anonymous and, in
 * Compass's case, dated by whatever the caller passed rather than by the trip.
 *
 * It matters most for exactly the case a training weekend is: several people, several trips, one
 * cave. A survey that does not say who made it cannot be checked against anybody's notebook.
 */
@Composable
fun TripDetailsDialog(
    survey: Survey,
    today: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val existing = survey.trip

    var date by remember { mutableStateOf((existing?.surveyDate ?: SurveyDate.parseOrNull(today))?.toString() ?: today) }
    // The two-flag encoding Trip itself keeps: linked means "explored the day it was surveyed",
    // in which case explorationDate is never even read - see Trip.hasExplorationDate. Seeded from
    // the trip already on the survey, not from scratch, which is the whole fix: the dialog used to
    // build a brand-new Trip on Save and silently reset both fields to their class defaults, so an
    // exploration date read in from an imported file did not survive opening this dialog at all.
    var explorationDateLinked by remember { mutableStateOf(existing?.explorationDateLinked ?: true) }
    var explorationDate by remember {
        mutableStateOf(existing?.explorationDate?.toString() ?: date)
    }
    var instrument by remember { mutableStateOf(existing?.instrument ?: "") }
    var comments by remember { mutableStateOf(existing?.comments ?: "") }
    var copyrightHolder by remember { mutableStateOf(existing?.copyrightHolder ?: "") }
    var licence by remember { mutableStateOf(existing?.licence ?: "") }

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
        title = { Text("Trip details") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Survey date") },
                    singleLine = true,
                    isError = dateProblem != null,
                    supportingText = dateProblem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                // `exploration_date_linked_checkbox`, `exploration_date_field` and
                // `clear_exploration_date_button` from `activity_trip.xml`: the passage a survey
                // records is often found on an earlier trip, and a training weekend where several
                // people book the same lead on different days is exactly when that difference is
                // worth keeping separate from *when this particular set of numbers was measured*.
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
                    Text("Explored on the day it was surveyed")
                }
                if (!explorationDateLinked) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = explorationDate,
                            onValueChange = { explorationDate = it },
                            label = { Text("Exploration date") },
                            singleLine = true,
                            isError = explorationDateProblem != null,
                            supportingText = explorationDateProblem?.let { { Text(it) } },
                            modifier = Modifier.weight(1f),
                        )
                        // `clear_exploration_date_button`: back to "not recorded" rather than back
                        // to linked - a surveyor who unlinked the date because they do not know it
                        // yet should not have to re-discover the checkbox to say so.
                        TextButton(onClick = { explorationDate = "" }) { Text("Clear") }
                    }
                }

                Text("Team", style = MaterialTheme.typography.titleSmall)
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
                    OutlinedTextField(
                        value = newMember,
                        onValueChange = { newMember = it },
                        label = { Text("Add someone") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newMember.isNotBlank(),
                        onClick = {
                            team.add(Trip.TeamEntry(newMember.trim()))
                            newMember = ""
                        },
                    ) { Text("Add") }
                }

                OutlinedTextField(
                    value = instrument,
                    onValueChange = { instrument = it },
                    label = { Text("Instrument") },
                    placeholder = { Text("DistoX2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text("Trip comments") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                )
                OutlinedTextField(
                    value = copyrightHolder,
                    onValueChange = { copyrightHolder = it },
                    label = { Text("Copyright holder") },
                    placeholder = { Text("Your caving club") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = licence,
                    onValueChange = { licence = it },
                    label = { Text("Licence") },
                    placeholder = { Text("CC-BY-SA-4.0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dateProblem == null && explorationDateProblem == null,
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
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

/** Sentence case rather than the file format's shouting. */
internal fun labelFor(role: Trip.Role): String =
    when (role) {
        Trip.Role.BOOK -> "Book"
        Trip.Role.INSTRUMENTS -> "Instruments"
        Trip.Role.DOG -> "Dog"
        Trip.Role.EXPLORATION -> "Explo"
    }

/**
 * Builds the [Trip] the dialog describes.
 *
 * Blank people are dropped rather than written: an empty name would emit a `*team ""` line that
 * Therion accepts and no human can read.
 *
 * [explorationDate] is parsed only when [explorationDateLinked] is false — when it is true,
 * [Trip.hasExplorationDate] never reads the field at all, so a blank or half-typed box left behind
 * from before the surveyor re-linked it must not block Save, matching the dialog's own
 * `explorationDateProblem`, which is null in exactly that case for the same reason.
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
