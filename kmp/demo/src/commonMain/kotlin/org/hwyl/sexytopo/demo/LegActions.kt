package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.Lrud
import org.hwyl.sexytopo.shared.survey.LrudMode
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * What can be done to a leg once it is in the survey.
 *
 * Every action is a call into the ported [SurveyUpdater]: deleting a leg takes its subtree with
 * it, promoting a splay names a new station and renumbers from there, and reversing swaps the
 * shot's direction rather than negating its numbers.
 */
enum class LegAction(private val legLabel: String, private val splayLabel: String = legLabel) {
    /** `action_edit_leg`. */
    EDIT(Strings.menuEditLeg),

    /** `action_comment_leg`, whose title `configureMenuVisibility` swaps on a splay. */
    COMMENT(Strings.menuCommentLeg, Strings.menuCommentSplay),

    /** `action_reverse`, shown only on a full leg. */
    REVERSE(Strings.menuReverse),

    /** `action_upgrade_splay`, shown only on a splay. */
    UPGRADE(Strings.menuUpgradeSplay),

    /** `action_downgrade_leg`, shown only on a full leg with nothing beyond it. */
    DOWNGRADE(Strings.menuDowngradeLeg),

    /** `action_promote_to_above_leg`, shown only on a splay. */
    PROMOTE(Strings.menuPromoteToAboveLeg),

    /** `moveRow`, from the table's own menus rather than from `context_leg.xml`. */
    MOVE(Strings.menuMoveRow),

    /** `action_delete_leg`, whose title the table's splay menu gives as *Delete Splay*. */
    DELETE(Strings.menuDeleteLeg, Strings.menuDeleteSplay),
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
                survey = survey,
                onDismiss = { editing = false },
                onSave = { edited ->
                    SurveyUpdater.editLeg(survey, row.leg, edited)
                    onEdited()
                },
            )

        confirmingDelete ->
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = {
                    Text(
                        if (row.isSplay) {
                            "${Strings.menuDeleteSplay}?"
                        } else {
                            "${Strings.menuDeleteLeg} ${Strings.leg.lowercase()}?"
                        },
                    )
                },
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
                    }) { Text(Strings.delete) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text(Strings.cancel) }
                },
            )

        else ->
            AlertDialog(
                onDismissRequest = onDismiss,
                // `menu_context_title_leg` / `menu_context_title_splay`, which is what
                // `TableActivity.onRowLongClick` puts at the head of the Android menu.
                title = {
                    Text(
                        if (row.isSplay) {
                            Strings.splayTitle(row.from)
                        } else {
                            Strings.legTitle(row.from, row.to)
                        },
                    )
                },
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
                dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
            )
    }
}

/**
 * Correcting a reading that was taken or typed wrongly. The fields hold the **as-taken** reading;
 * saving puts it back into the orientation the survey stores, so a backsight stays a backsight.
 */
@Composable
internal fun EditLegDialog(
    row: SurveyTableRow,
    onDismiss: () -> Unit,
    onSave: (Leg) -> Unit,
    /**
     * The survey the row belongs to. Null keeps the dialog to the three numbers, which is what a
     * caller with nothing to rename wants; given one, the station and comment fields of
     * `leg_edit_dialog_unified.xml` appear too and are applied on save.
     */
    survey: Survey? = null,
    /** `pref_lrud_fields`: whether the four passage measurements are shown, as in the app. */
    lrudFields: Boolean = false,
    lrudMode: LrudMode = LrudMode.DEFAULT,
) {
    var distance by remember { mutableStateOf(row.distance) }
    var azimuth by remember { mutableStateOf(row.azimuth) }
    var inclination by remember { mutableStateOf(row.inclination) }

    val from = row.fromStationShown
    val to = row.toStationShown

    var fromName by remember(row) { mutableStateOf(from.name) }
    var fromComment by remember(row) { mutableStateOf(from.comment) }
    var toName by remember(row) { mutableStateOf(to?.name ?: "") }
    var toComment by remember(row) { mutableStateOf(to?.comment ?: "") }
    var legComment by remember(row) { mutableStateOf(row.leg.comment) }
    val lrud = remember(row) { mutableStateListOf("", "", "", "") }

    val parsed = parseReading(distance, azimuth, inclination)
    val fromProblem = survey?.let { renameProblem(it, from, fromName) }
    val toProblem = if (survey != null && to != null) renameProblem(survey, to, toName) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        // `manual_edit_leg_title` / `manual_edit_splay_title`, as `LegDialogs.editLeg` titles it.
        title = {
            Text(
                if (row.isSplay) Strings.manualEditSplayTitle else Strings.manualEditLegTitle,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // `leg_edit_dialog_unified.xml`'s own order: the two stations and their comments,
                // then the reading, then the leg's comment.
                if (survey != null) {
                    OutlinedTextField(
                        value = fromName,
                        onValueChange = { fromName = it },
                        label = { Text(Strings.manualEditFromStation) },
                        singleLine = true,
                        isError = fromProblem != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = fromComment,
                        onValueChange = { fromComment = it },
                        label = { Text(Strings.manualEditStationComment) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (to != null) {
                        OutlinedTextField(
                            value = toName,
                            onValueChange = { toName = it },
                            label = { Text(Strings.manualEditToStation) },
                            singleLine = true,
                            isError = toProblem != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = toComment,
                            onValueChange = { toComment = it },
                            label = { Text(Strings.manualEditStationComment) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                ReadingFields(
                    distance = distance,
                    onDistance = { distance = it },
                    azimuth = azimuth,
                    onAzimuth = { azimuth = it },
                    inclination = inclination,
                    onInclination = { inclination = it },
                )

                if (survey != null) {
                    OutlinedTextField(
                        value = legComment,
                        onValueChange = { legComment = it },
                        label = {
                            Text(
                                if (row.isSplay) {
                                    Strings.menuCommentSplay
                                } else {
                                    Strings.manualEditLegComment
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (survey != null && lrudFields && to != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((index, side) in Lrud.entries.withIndex()) {
                            OutlinedTextField(
                                value = lrud[index],
                                onValueChange = { lrud[index] = it },
                                label = { Text(lrudFieldLabel(side)) },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                listOfNotNull(parsed.problem, fromProblem, toProblem).forEach {
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
                enabled = parsed.leg != null && fromProblem == null && toProblem == null,
                onClick = {
                    if (survey != null) {
                        // Before the reading: editLeg replaces the Leg object, and the comment
                        // would then be written onto one no longer in the survey.
                        applyStationEdit(
                            survey,
                            from,
                            fromName,
                            fromComment,
                            from.extendedElevationDirection,
                        )
                        to?.let {
                            applyStationEdit(
                                survey,
                                it,
                                toName,
                                toComment,
                                it.extendedElevationDirection,
                            )
                            if (lrudFields) addLruds(survey, it, lrud.toList(), lrudMode)
                        }
                    }
                    val edited = parsed.leg?.let { inOrientationOf(row.leg, it) }
                    if (edited != null) {
                        edited.comment = legComment
                        onSave(edited)
                    }
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/** `manual_edit_left` and its three siblings, as the with-LRUDs variant of the dialog labels them. */
private fun lrudFieldLabel(side: Lrud): String =
    when (side) {
        Lrud.LEFT -> Strings.manualEditLeft
        Lrud.RIGHT -> Strings.manualEditRight
        Lrud.UP -> Strings.manualEditUp
        Lrud.DOWN -> Strings.manualEditDown
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
                label = {
                    Text(
                        if (row.isSplay) {
                            Strings.menuCommentSplay
                        } else {
                            Strings.manualEditLegComment
                        },
                    )
                },
                placeholder = { Text("Sump; do not follow") },
                modifier = Modifier.fillMaxWidth().then(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(comment) }) { Text(Strings.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
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
        title = { Text(Strings.menuMoveRow) },
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
                    label = { Text(Strings.manualRenameStationHint) },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
