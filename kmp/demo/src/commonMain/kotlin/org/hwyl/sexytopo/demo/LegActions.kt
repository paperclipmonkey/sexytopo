package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * What can be done to a leg once it is in the survey.
 *
 * Every action is a call into the ported [SurveyUpdater]: deleting a leg takes its subtree with
 * it, promoting a splay names a new station and renumbers from there, and reversing swaps the
 * shot's direction rather than negating its numbers.
 */
enum class LegAction(private val legLabel: String, private val splayLabel: String = legLabel) {
    EDIT("Edit reading"),
    COMMENT("Leg comment", "Splay comment"),
    REVERSE("Reverse the shot"),
    UPGRADE("Make it a station"),
    DOWNGRADE("Make it a splay"),
    PROMOTE("Add it to the leg above"),
    MOVE("Hang it off another station…"),
    DELETE("Delete"),
    ;

    fun label(isSplay: Boolean): String = if (isSplay) splayLabel else legLabel
}

/** Which actions this row can actually take, in the order the dialog offers them. */
fun legActionsFor(survey: Survey, row: SurveyTableRow): List<LegAction> = buildList {
    add(LegAction.EDIT)
    add(LegAction.COMMENT)
    if (row.isSplay) {
        add(LegAction.UPGRADE)
        if (SurveyUpdater.canPromoteToAboveLeg(survey, row.leg)) add(LegAction.PROMOTE)
    } else {
        add(LegAction.REVERSE)
        if (SurveyUpdater.canDowngradeLeg(row.leg)) add(LegAction.DOWNGRADE)
    }
    if (legMoveTargets(survey, row).isNotEmpty()) add(LegAction.MOVE)
    add(LegAction.DELETE)
}

/**
 * The stations [row]'s shot could be re-hung on, filtered by [query] the way *Find a station* is.
 *
 * Two stations are excluded: the one the leg already hangs off, and, for a connecting leg, the
 * station it leads to and everything below that — `moveLeg` re-parents without checking, so
 * hanging a leg inside its own subtree would make a cycle and the next traversal never returns.
 */
fun legMoveTargets(survey: Survey, row: SurveyTableRow, query: String = ""): List<Station> {
    val leg = row.leg
    val current = survey.getOriginatingStation(leg) ?: return emptyList()
    return stationsMatching(survey, query).filter { candidate ->
        candidate !== current &&
            !(leg.hasDestination() && Survey.isInSubtree(leg.destination, candidate))
    }
}

/**
 * Writes a comment onto a leg, and remembers that the survey has changed — which the Android
 * app's own comment dialogs fail to do, silently losing a comment-only edit.
 */
internal fun applyLegComment(survey: Survey, leg: Leg, comment: String) {
    leg.comment = comment
    survey.isSaved = false
}

@Composable
fun LegActionsDialog(
    survey: Survey,
    row: SurveyTableRow,
    onDismiss: () -> Unit,
    onEdited: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }

    when {
        moving ->
            MoveLegDialog(
                survey = survey,
                row = row,
                onDismiss = { moving = false },
                onMove = { station ->
                    SurveyUpdater.moveLeg(survey, row.leg, station)
                    onEdited()
                },
            )

        commenting ->
            LegCommentDialog(
                row = row,
                onDismiss = { commenting = false },
                onSave = { comment ->
                    applyLegComment(survey, row.leg, comment)
                    onEdited()
                },
            )

        editing ->
            EditLegDialog(
                row = row,
                onDismiss = { editing = false },
                onSave = { edited ->
                    SurveyUpdater.editLeg(survey, row.leg, edited)
                    onEdited()
                },
            )

        confirmingDelete ->
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Delete this ${if (row.isSplay) "splay" else "leg"}?") },
                text = {
                    Text(
                        if (row.isSplay) {
                            "${row.from} → ${row.distance} m"
                        } else {
                            "${row.from} → ${row.to}. Anything surveyed beyond it goes too."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        SurveyUpdater.deleteLeg(survey, row.fromStation, row.leg)
                        onEdited()
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                },
            )

        else ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (row.isSplay) "Splay from ${row.from}" else "${row.from} → ${row.to}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${row.distance} m   ${row.azimuth}°   ${row.inclination}°",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!row.isSplay) {
                            Text(
                                "Everything surveyed beyond this leg moves with it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (row.leg.hasComment()) {
                            Text(
                                "$COMMENT_MARKER ${row.leg.comment}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        for (action in legActionsFor(survey, row)) {
                            TextButton(
                                onClick = {
                                    when (action) {
                                        LegAction.EDIT -> editing = true
                                        LegAction.COMMENT -> commenting = true
                                        LegAction.DELETE -> confirmingDelete = true
                                        LegAction.UPGRADE -> {
                                            SurveyUpdater.upgradeSplay(survey, row.leg)
                                            onEdited()
                                        }
                                        // Addressed by the station the leg arrives at, not by
                                        // the leg, since that survives the leg object being
                                        // replaced.
                                        LegAction.REVERSE -> {
                                            SurveyUpdater.reverseLeg(survey, row.leg.destination)
                                            onEdited()
                                        }
                                        LegAction.DOWNGRADE -> {
                                            SurveyUpdater.downgradeLeg(survey, row.leg)
                                            onEdited()
                                        }
                                        LegAction.PROMOTE -> {
                                            SurveyUpdater.promoteToAboveLeg(survey, row.leg)
                                            onEdited()
                                        }
                                        LegAction.MOVE -> moving = true
                                    }
                                },
                            ) { Text(action.label(row.isSplay)) }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
    }
}

/**
 * Correcting a reading that was taken or typed wrongly. The fields hold the **as-taken** reading;
 * saving puts it back into the orientation the survey stores, so a backsight stays a backsight.
 */
@Composable
private fun EditLegDialog(row: SurveyTableRow, onDismiss: () -> Unit, onSave: (Leg) -> Unit) {
    var distance by remember { mutableStateOf(row.distance) }
    var azimuth by remember { mutableStateOf(row.azimuth) }
    var inclination by remember { mutableStateOf(row.inclination) }

    val parsed = parseReading(distance, azimuth, inclination)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit reading") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReadingFields(
                    distance = distance,
                    onDistance = { distance = it },
                    azimuth = azimuth,
                    onAzimuth = { azimuth = it },
                    inclination = inclination,
                    onInclination = { inclination = it },
                )
                parsed.problem?.let {
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
                enabled = parsed.leg != null,
                onClick = { parsed.leg?.let { onSave(inOrientationOf(row.leg, it)) } },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Re-dresses a freshly parsed reading as a replacement for [original].
 *
 * [parseReading] only produces a bare splay, so three things must be carried across or the edit
 * quietly destroys them: the destination station (whose loss would silently delete everything
 * surveyed beyond this leg), the comment, and the backwards flag with its 180-degree turn.
 * `promotedFrom` is deliberately *not* carried across: those constituent readings no longer
 * average to what was typed.
 */
internal fun inOrientationOf(original: Leg, edited: Leg): Leg {
    val replacement =
        if (original.wasShotBackwards) {
            Leg(
                edited.distance,
                adjustAngle(edited.azimuth, 180f),
                -edited.inclination,
                original.destination,
                wasShotBackwards = true,
            )
        } else {
            Leg(
                edited.distance,
                edited.azimuth,
                edited.inclination,
                original.destination,
            )
        }
    replacement.comment = original.comment
    return replacement
}

@Composable
private fun LegCommentDialog(
    row: SurveyTableRow,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var comment by remember(row) { mutableStateOf(row.leg.comment) }
    val focus = rememberOpeningFocus()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LegAction.COMMENT.label(row.isSplay)) },
        text = {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("Comment") },
                placeholder = { Text("Sump; do not follow") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(comment) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick the station this shot should have come off, from [legMoveTargets] so no cycle is offered. */
@Composable
private fun MoveLegDialog(
    survey: Survey,
    row: SurveyTableRow,
    onDismiss: () -> Unit,
    onMove: (Station) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val targets = remember(survey, row, query) { legMoveTargets(survey, row, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hang it off which station?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (row.isSplay) {
                        "The splay moves; nothing else does."
                    } else {
                        "Everything surveyed beyond ${row.to} comes with it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (targets.isEmpty()) {
                    Text(
                        "No station is called that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(targets, key = { it.name }) { station ->
                            TextButton(
                                onClick = { onMove(station) },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
