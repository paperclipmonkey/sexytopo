package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.LrudMode
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * What a long press on a station offers, ported from `res/menu/context_station.xml` and the
 * visibility rules `ContextMenuManager` applies to it.
 *
 * Kept as a list of values rather than built inline in the dialog, so the rules can be tested.
 */
enum class StationAction(val label: String) {
    /** `action_set_active_station`: where the next leg will hang off. */
    MAKE_ACTIVE(Strings.menuSetActiveStation),

    /** `action_comment`. */
    COMMENT(Strings.menuComment),

    /** `action_rename_station`. */
    RENAME(Strings.menuRenameStation),

    /**
     * This port's own, and the one row here the Android app has no counterpart for: four tape
     * measurements, which upstream can only be booked while adding a leg. Kept because it is what
     * lets a cross-section be drawn from a hand-booked survey somebody has already left.
     */
    PASSAGE_SIZE(Strings.settingsLrudFieldsTitle),

    /** `action_xsection_create`, plan only. */
    CROSS_SECTION_CREATE(Strings.menuCrossSectionCreate),

    /** `action_xsection_edit`, plan only, and only where there is one. */
    CROSS_SECTION_EDIT(Strings.menuCrossSectionEdit),

    /** `action_xsection_set_direction`: swings an existing section round its station. */
    CROSS_SECTION_SET_DIRECTION(Strings.menuCrossSectionSetDirection),

    /** `action_xsection_delete`, likewise. */
    CROSS_SECTION_DELETE(Strings.menuCrossSectionDelete),

    /** `action_direction_left`, in the `menu_elevation` submenu — extended elevation only. */
    DIRECTION_LEFT(Strings.menuDrawLeft),

    /** `action_direction_right`. */
    DIRECTION_RIGHT(Strings.menuDrawRight),

    /** `action_direction_vertical`. */
    DIRECTION_VERTICAL(Strings.menuDrawVertical),

    /** The `menu_leg` submenu: edit, reverse, comment or delete the shot that made this station. */
    INCOMING_LEG(Strings.menuIncomingLeg),

    /** `action_jump_to_table`, hidden in the view it would jump to. */
    SHOW_IN_TABLE(Strings.menuJumpToTable),

    /** `action_jump_to_plan`, likewise. */
    SHOW_IN_PLAN(Strings.menuJumpToPlan),

    /** `action_jump_to_elevation`, likewise. */
    SHOW_IN_ELEVATION(Strings.menuJumpToElevation),

    /** `action_delete_station`, which takes the passage beyond it too. */
    DELETE(Strings.menuDeleteStation),
    ;
}

/**
 * The actions [station] offers, in the order the Android menu lists them.
 *
 * The origin has no incoming leg, and `SurveyUpdater.deleteStation` is a no-op on it — a menu item
 * that silently does nothing is worse than one that is not there.
 */
fun stationActionsFor(
    survey: Survey,
    station: Station,
    projection: Projection2D,
    sketch: Sketch?,
    /**
     * Whether this menu was opened from the table rather than from the drawing: the Android app
     * has two station menus, and cross-sections belong to the sketch's only.
     */
    fromTable: Boolean = false,
    /**
     * `pref_legacy_cross_sections`. `configureMenuVisibility` hides *Edit Sketch* when it is on,
     * because the editor the row opens is the thing that preference turns off.
     */
    legacyCrossSections: Boolean = false,
): List<StationAction> {
    val actions = mutableListOf<StationAction>()

    if (survey.activeStation !== station) actions += StationAction.MAKE_ACTIVE
    actions += StationAction.COMMENT
    actions += StationAction.RENAME
    actions += StationAction.PASSAGE_SIZE

    // `menu_xsection`, which `ViewContext.PLAN` is the only context to show.
    if (!fromTable && projection == Projection2D.PLAN) {
        if (crossSectionAt(sketch, station) == null) {
            actions += StationAction.CROSS_SECTION_CREATE
        } else {
            // `configureMenuVisibility` greys these out rather than hiding them; here an action
            // that cannot work is left out, as everywhere else in this port.
            if (!legacyCrossSections) actions += StationAction.CROSS_SECTION_EDIT
            actions += StationAction.CROSS_SECTION_SET_DIRECTION
            actions += StationAction.CROSS_SECTION_DELETE
        }
    }

    // `menu_elevation`, which `ViewContext.EXTENDED_ELEVATION` is the only context to show.
    if (!fromTable && projection == Projection2D.EXTENDED_ELEVATION) {
        actions += StationAction.DIRECTION_LEFT
        actions += StationAction.DIRECTION_RIGHT
        actions += StationAction.DIRECTION_VERTICAL
    }

    if (survey.getReferringLeg(station) != null) actions += StationAction.INCOMING_LEG

    // `menu_navigate`, less the view this menu was opened in — `ViewContext` hides that one.
    if (!fromTable) actions += StationAction.SHOW_IN_TABLE
    if (fromTable || projection != Projection2D.PLAN) actions += StationAction.SHOW_IN_PLAN
    if (fromTable || projection != Projection2D.EXTENDED_ELEVATION) {
        actions += StationAction.SHOW_IN_ELEVATION
    }

    if (!survey.isOrigin(station)) actions += StationAction.DELETE

    return actions
}

/**
 * The cross-section drawn at [station], if there is one.
 *
 * By station identity rather than by name: a survey read from a file can hold two stations with the
 * same name, and the section belongs to the object the sketch was drawn against.
 */
fun crossSectionAt(sketch: Sketch?, station: Station): CrossSectionDetail? =
    sketch?.crossSectionDetails?.firstOrNull { it.station === station }

/**
 * The menu itself: long-press a station on the sketch and this is what comes up.
 *
 * A dialog rather than a menu anchored at the finger, unlike the Android app's: an anchored menu
 * with seven items and a submenu runs off the bottom of a small screen or opens upwards over the
 * thing that was long-pressed.
 */
@Composable
fun StationMenuDialog(
    survey: Survey,
    station: Station,
    projection: Projection2D,
    sketch: Sketch?,
    onDismiss: () -> Unit,
    onEdited: () -> Unit,
    onMakeActive: (Station) -> Unit,
    onOpenCrossSection: (CrossSectionDetail) -> Unit,
    onCreateCrossSection: (Station) -> Unit,
    onDeleteCrossSection: (CrossSectionDetail) -> Unit,
    /** See the parameter of the same name on [stationActionsFor]. */
    fromTable: Boolean = false,
    /** Take the surveyor to this station on a drawing. Only reached when [fromTable]. */
    onShowOn: (Station, Projection2D) -> Unit = { _, _ -> },
    /** Take them to its row in the table instead. Only reached when not [fromTable]. */
    onShowInTable: (Station) -> Unit = {},
    /** `action_xsection_set_direction`: swing an existing cross-section round its station. */
    onSetCrossSectionDirection: (Station) -> Unit = {},
    /** `pref_legacy_cross_sections`: passed to [stationActionsFor], which drops *Edit Sketch*. */
    legacyCrossSections: Boolean = false,
    /** Passed through to the passage-size fields: `pref_lrud_direction`. */
    lrudMode: LrudMode = LrudMode.DEFAULT,
) {
    var editing by remember(station) { mutableStateOf<StationFields?>(null) }
    var editingLeg by remember(station) { mutableStateOf(false) }
    var confirmingDelete by remember(station) { mutableStateOf(false) }

    val incoming = remember(survey, station) { incomingLegRow(survey, station) }

    val fields = editing
    when {
        fields != null ->
            StationActionsDialog(
                survey = survey,
                station = station,
                fields = fields,
                onDismiss = onDismiss,
                onEdited = onEdited,
                lrudMode = lrudMode,
            )

        editingLeg && incoming != null ->
            LegActionsDialog(
                survey = survey,
                row = incoming,
                onDismiss = onDismiss,
                onEdited = onEdited,
            )

        confirmingDelete ->
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("${Strings.menuDeleteStation} ${station.name}?") },
                text = {
                    Text(
                        "The leg that made it goes too, and so does everything surveyed beyond " +
                            "it. This cannot be undone.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            SurveyUpdater.deleteStation(survey, station)
                            onEdited()
                        },
                    ) { Text(Strings.delete) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text(Strings.cancel) }
                },
            )

        else ->
            AlertDialog(
                onDismissRequest = onDismiss,
                // `setStationTitle`: the station's own name, as the disabled first row of the
                // Android menu.
                title = { Text("${Strings.station} ${station.name}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (station.comment.isNotBlank()) {
                            Text(
                                station.comment,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        val actions =
                            stationActionsFor(
                                survey,
                                station,
                                projection,
                                sketch,
                                fromTable,
                                legacyCrossSections,
                            )
                        for (action in actions) {
                            // `setGroupDividerEnabled`: `group_station_delete` is its own group.
                            if (action == StationAction.DELETE) HorizontalDivider()
                            TextButton(
                                onClick = {
                                    when (action) {
                                        StationAction.MAKE_ACTIVE -> {
                                            onMakeActive(station)
                                            onDismiss()
                                        }
                                        StationAction.COMMENT -> editing = StationFields.COMMENT
                                        StationAction.RENAME -> editing = StationFields.NAME
                                        StationAction.PASSAGE_SIZE ->
                                            editing = StationFields.PASSAGE
                                        StationAction.CROSS_SECTION_CREATE -> {
                                            onCreateCrossSection(station)
                                            onDismiss()
                                        }
                                        StationAction.CROSS_SECTION_EDIT ->
                                            crossSectionAt(sketch, station)?.let {
                                                onOpenCrossSection(it)
                                                onDismiss()
                                            }
                                        StationAction.CROSS_SECTION_SET_DIRECTION -> {
                                            onSetCrossSectionDirection(station)
                                            onDismiss()
                                        }
                                        StationAction.CROSS_SECTION_DELETE ->
                                            crossSectionAt(sketch, station)?.let {
                                                onDeleteCrossSection(it)
                                                onEdited()
                                            }
                                        StationAction.DIRECTION_LEFT ->
                                            setDirection(
                                                survey,
                                                station,
                                                ExtendedElevationDirection.LEFT,
                                                onEdited,
                                            )
                                        StationAction.DIRECTION_RIGHT ->
                                            setDirection(
                                                survey,
                                                station,
                                                ExtendedElevationDirection.RIGHT,
                                                onEdited,
                                            )
                                        StationAction.DIRECTION_VERTICAL ->
                                            setDirection(
                                                survey,
                                                station,
                                                ExtendedElevationDirection.VERTICAL,
                                                onEdited,
                                            )
                                        StationAction.INCOMING_LEG -> editingLeg = true
                                        StationAction.DELETE -> confirmingDelete = true
                                        StationAction.SHOW_IN_PLAN -> {
                                            onShowOn(station, Projection2D.PLAN)
                                            onDismiss()
                                        }
                                        StationAction.SHOW_IN_ELEVATION -> {
                                            onShowOn(station, Projection2D.EXTENDED_ELEVATION)
                                            onDismiss()
                                        }
                                        StationAction.SHOW_IN_TABLE -> {
                                            onShowInTable(station)
                                            onDismiss()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // `group_direction` is `checkableBehavior="single"`, so the
                                    // three direction rows carry a mark and nothing else does.
                                    if (action in DIRECTION_ACTIONS) {
                                        CheckDot(directionOf(action) == station.extendedElevationDirection)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        action.label,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Start,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
    }
}

/** The three rows of `group_direction`, which are the only checkable ones on this menu. */
private val DIRECTION_ACTIONS =
    setOf(
        StationAction.DIRECTION_LEFT,
        StationAction.DIRECTION_RIGHT,
        StationAction.DIRECTION_VERTICAL,
    )

private fun directionOf(action: StationAction): ExtendedElevationDirection? =
    when (action) {
        StationAction.DIRECTION_LEFT -> ExtendedElevationDirection.LEFT
        StationAction.DIRECTION_RIGHT -> ExtendedElevationDirection.RIGHT
        StationAction.DIRECTION_VERTICAL -> ExtendedElevationDirection.VERTICAL
        else -> null
    }

/**
 * `onSetDirectionLeft` and its two siblings, which set the direction and rebuild the elevation.
 *
 * It propagates onward from here, so setting it on a junction sets it for the whole branch — which
 * is why one tap is worth a whole submenu.
 */
private fun setDirection(
    survey: Survey,
    station: Station,
    direction: ExtendedElevationDirection,
    onEdited: () -> Unit,
) {
    station.extendedElevationDirection = direction
    survey.isSaved = false
    onEdited()
}

/** The bearing a cross-section gets, which is a guess `CROSS_SECTION_SET_DIRECTION` overrules. */
fun sectionFor(survey: Survey, station: Station) = CrossSectioner.section(survey, station)

