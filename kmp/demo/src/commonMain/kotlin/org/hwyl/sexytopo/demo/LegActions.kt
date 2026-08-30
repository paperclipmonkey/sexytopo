package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
@Composable
fun LegActionsDialog(
    survey: Survey,
    row: SurveyTableRow,
    onDismiss: () -> Unit,
    onEdited: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    when {
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
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { editing = true }) { Text("Edit reading") }
                        if (row.isSplay) {
                            TextButton(onClick = {
                                SurveyUpdater.upgradeSplay(survey, row.leg)
                                onEdited()
                            }) { Text("Make it a station") }
                        }
                        TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
