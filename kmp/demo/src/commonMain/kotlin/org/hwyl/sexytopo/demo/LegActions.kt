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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * What can be done to a leg once it is in the survey.
 *
 * The reason this exists: a surveyor mistypes a number. Underground, with cold hands, reading a
 * display by head torch, they will — and without a way to fix it the app is a one-way funnel into
 * a wrong survey. The Android app puts these on a long-press context menu
 * (`ContextMenuManager`); here they are a tap on the table row, which is the same idea for a
 * screen with no long-press affordance.
 *
 * Every action is a call into the ported [SurveyUpdater], so the consequences are the app's own:
 * deleting a leg takes its subtree with it, promoting a splay names a new station and renumbers
 * from there, and reversing swaps the shot's direction rather than negating its numbers.
 */
enum class LegAction(private val legLabel: String, private val splayLabel: String = legLabel) {
    EDIT("Edit reading"),
    COMMENT("Leg comment", "Splay comment"),
    REVERSE("Reverse the shot"),
    UPGRADE("Make it a station"),
    DOWNGRADE("Make it a splay"),
    PROMOTE("Add it to the leg above"),
    DELETE("Delete"),
    ;

    fun label(isSplay: Boolean): String = if (isSplay) splayLabel else legLabel
}

/**
 * Which actions this row can actually take, in the order the dialog offers them.
 *
 * Ported from `ContextMenuManager.configureMenuVisibility`, with one divergence. The Java shows
 * *Downgrade to Splay* greyed out when the stations beyond it are in the way, and shows *Add to
 * Leg Above* always, answering an impossible tap with a toast. Both are left out here instead:
 * a dialog on a phone is a short column of buttons, and a button that cannot be pressed — or
 * one that can be pressed and does nothing but apologise — is worse than no button at all. The
 * warning above the buttons already says what a full leg is carrying behind it.
 */
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
    add(LegAction.DELETE)
}

/**
 * Writes a comment onto a leg, and remembers that the survey has changed.
 *
 * That second line is the whole reason this is a function. `SurveyEditorActivity`'s own leg and
 * station comment dialogs set the comment and broadcast an update, but never clear `isSaved` —
 * and `isSaved` is what decides whether leaving the survey writes it out. A comment typed with
 * nothing else changed is therefore lost on the Android app, silently, which is the worst way to
 * lose the note that says the passage ahead sumps.
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

    when {
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
                            // Only true of a connecting leg. Saying it over a splay, which has
                            // nothing beyond it, would teach a surveyor to distrust the warning
                            // exactly where it matters.
                            Text(
                                "Everything surveyed beyond this leg moves with it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (row.leg.hasComment()) {
                            // The table only shows a dagger, so this is where the note itself is
                            // read — and the surveyor deciding whether to delete a leg is exactly
                            // the person who wants to see what they wrote about it.
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
                                        // reverseLeg is addressed by the station the leg arrives
                                        // at, not by the leg, because that is the one thing that
                                        // survives the leg object being replaced.
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
 * Correcting a reading that was taken or typed wrongly.
 *
 * The fields hold the **as-taken** reading — what the table row shows, which for a leg shot
 * backwards is the far-end reading turned round. Saving puts it back into the orientation the
 * survey stores, so a backsight stays a backsight and the row reads the same way afterwards.
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
            // Scrollable for the same reason the station dialog is: three fields and a keypad on
            // a phone leave very little room, and a Compose dialog that does not fit is clipped
            // from the bottom, which is where Save is.
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
 * [parseReading] only ever produces a bare splay, so three things have to be carried across or the
 * edit quietly destroys them:
 *  - the destination station, whose loss would orphan — and [SurveyUpdater.editLeg] being a
 *    straight swap, silently delete — everything surveyed beyond this leg;
 *  - the comment;
 *  - the backwards flag, together with the 180-degree turn that puts the entered foresight back
 *    into the stored backsight orientation.
 *
 * `promotedFrom` is deliberately *not* carried across, matching the Android app's
 * `EditLegForm.getUpdatedLeg`: those constituent readings no longer average to what was typed, so
 * keeping them would make the leg claim a provenance it does not have.
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

/**
 * Writing a note against a leg — "sump", "boulder choke", "tape stretched over a rift".
 *
 * The comment travels: it is written into the survey's own JSON, and both the Survex and Therion
 * exporters put it on the line for that leg, so a note made underground reaches whoever draws the
 * cave up afterwards.
 */
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
