package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * What a long press on a station offers, ported from `res/menu/context_station.xml` and the
 * visibility rules `ContextMenuManager` applies to it.
 *
 * Kept as a list of values rather than built inline in the dialog so the rules can be tested: which
 * of these a station offers depends on whether it is the origin, whether it is already active,
 * which projection is showing and whether it already has a cross-section, and every one of those
 * has a wrong answer that is invisible until somebody taps it underground.
 */
enum class StationAction(val label: String) {
    /** `action_set_active_station`: where the next leg will hang off. */
    MAKE_ACTIVE("Start the next leg here"),

    /** `action_rename_station` + `action_comment` + the elevation direction, in one dialog. */
    EDIT("Name, comment and size…"),

    /** `action_xsection_create`, plan only. */
    CROSS_SECTION_CREATE("Draw a cross-section here"),

    /** `action_xsection_edit`, plan only, and only where there is one. */
    CROSS_SECTION_EDIT("Open this station's cross-section"),

    /** `action_xsection_delete`, likewise. */
    CROSS_SECTION_DELETE("Delete this station's cross-section"),

    /** The `menu_leg` submenu: edit, reverse, comment or delete the shot that made this station. */
    INCOMING_LEG("The leg that got here…"),

    /** `action_delete_station`, which takes the passage beyond it too. */
    DELETE("Delete this station"),

    /** `action_jump_to_plan`, offered from the table and not from the sketch. */
    SHOW_IN_PLAN("Show it on the plan"),

    /** `action_jump_to_elevation`, likewise. */
    SHOW_IN_ELEVATION("Show it in the elevation"),

    /** `action_jump_to_table`, offered from the sketch and not from the table. */
    SHOW_IN_TABLE("Show it in the table"),
    ;
}

/**
 * The actions [station] offers, in the order the Android menu lists them.
 *
 * The rules are the original's:
 *  - the active station cannot be made active again;
 *  - cross-sections belong to the plan, and `CrossSectionActivity` is reached from a section that
 *    exists, so create and edit/delete are mutually exclusive;
 *  - the origin has no incoming leg, and `SurveyUpdater.deleteStation` is a no-op on it — a menu
 *    item that silently does nothing is worse than one that is not there.
 */
fun stationActionsFor(
    survey: Survey,
    station: Station,
    projection: Projection2D,
    sketch: Sketch?,
    /**
     * Whether this menu was opened from the table rather than from the drawing.
     *
     * The Android app has two station menus, not one: `context_station.xml` for the sketch and
     * `table_station_selected.xml` for the table. What separates them is the `menu_navigate`
     * submenu — each offers the two views you are *not* looking at — and cross-sections, which are
     * a thing you draw and so belong to the sketch's menu only.
     */
    fromTable: Boolean = false,
): List<StationAction> {
    val actions = mutableListOf<StationAction>()

    if (survey.activeStation !== station) actions += StationAction.MAKE_ACTIVE
    if (fromTable) {
        actions += StationAction.SHOW_IN_PLAN
        actions += StationAction.SHOW_IN_ELEVATION
    } else {
        actions += StationAction.SHOW_IN_TABLE
    }
    actions += StationAction.EDIT

    if (!fromTable && projection == Projection2D.PLAN) {
        if (crossSectionAt(sketch, station) == null) {
            actions += StationAction.CROSS_SECTION_CREATE
        } else {
            actions += StationAction.CROSS_SECTION_EDIT
            actions += StationAction.CROSS_SECTION_DELETE
        }
    }

    if (survey.getReferringLeg(station) != null) actions += StationAction.INCOMING_LEG
    if (!survey.isOrigin(station)) actions += StationAction.DELETE

    return actions
}

/**
 * The cross-section drawn at [station], if there is one.
 *
 * By station identity rather than by name: a survey read from a file can hold two stations with the
 * same name (see the cycle finding), and the section belongs to the object the sketch was drawn
 * against.
 */
fun crossSectionAt(sketch: Sketch?, station: Station): CrossSectionDetail? =
    sketch?.crossSectionDetails?.firstOrNull { it.station === station }

/**
 * The menu itself: long-press a station on the sketch and this is what comes up.
 *
 * Until this existed, the only station a surveyor could name, comment or measure was the *active*
 * one, through the chip on the field bar. That is fine while the survey is being pushed forward and
 * useless the moment somebody wants to go back and write "sump" on the junction they passed twenty
 * minutes ago — which is most of what a station's name is for.
 *
 * A dialog rather than a menu anchored at the finger, unlike the Android app's. On a 420-pixel
 * screen an anchored menu with seven items and a submenu either runs off the bottom or opens
 * upwards over the thing that was long-pressed; and every other action in this port — leg actions,
 * settings, trip details — is already a dialog, so this is the shape a user of it expects.
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
) {
    var editing by remember(station) { mutableStateOf(false) }
    var editingLeg by remember(station) { mutableStateOf(false) }
    var confirmingDelete by remember(station) { mutableStateOf(false) }

    val incoming = remember(survey, station) { incomingLegRow(survey, station) }

    when {
        editing ->
            StationActionsDialog(
                survey = survey,
                station = station,
                onDismiss = onDismiss,
                onEdited = onEdited,
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
                title = { Text("Delete station ${station.name}?") },
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
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                },
            )

        else ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Station ${station.name}") },
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
                            stationActionsFor(survey, station, projection, sketch, fromTable)
                        for (action in actions) {
                            if (action == StationAction.DELETE) HorizontalDivider()
                            TextButton(
                                onClick = {
                                    when (action) {
                                        StationAction.MAKE_ACTIVE -> {
                                            onMakeActive(station)
                                            onDismiss()
                                        }
                                        StationAction.EDIT -> editing = true
                                        StationAction.CROSS_SECTION_CREATE -> {
                                            onCreateCrossSection(station)
                                            onDismiss()
                                        }
                                        StationAction.CROSS_SECTION_EDIT ->
                                            crossSectionAt(sketch, station)?.let {
                                                onOpenCrossSection(it)
                                                onDismiss()
                                            }
                                        StationAction.CROSS_SECTION_DELETE ->
                                            crossSectionAt(sketch, station)?.let {
                                                onDeleteCrossSection(it)
                                                onEdited()
                                            }
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
                                Text(
                                    action.label,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
    }
}

/**
 * The bearing a cross-section created from this menu gets, and where it is drawn.
 *
 * Same call the position tool makes — `CrossSectioner.section` bisects the corner mid-passage,
 * follows the single leg at a dead end and falls back to north — so a section dropped from the menu
 * is the same section as one dropped by tapping, and can be overruled the same way.
 */
fun sectionFor(survey: Survey, station: Station) = CrossSectioner.section(survey, station)

/**
 * Where a cross-section created from the menu is drawn: beside the station, not on it.
 *
 * The position tool puts the section wherever the finger landed, which is how a surveyor keeps it
 * off the passage. From a menu there is no such point — the finger was on the station — so it is
 * offset by the app's own starting section size, far enough that it lands in the white space rather
 * than on top of the centreline it describes. *Move a cross-section* slides it from there.
 */
fun crossSectionPositionFor(
    survey: Survey,
    station: Station,
    projection: Projection2D,
): Coord2D? {
    val at = projection.project(survey).stationMap[station] ?: return null
    return at.add(CROSS_SECTION_MENU_OFFSET_METRES, -CROSS_SECTION_MENU_OFFSET_METRES)
}

/**
 * `SketchDefaults.CROSS_SECTION_STARTING_SIZE`-ish, in metres of cave rather than pixels: far
 * enough from the centreline to read as a separate drawing at the zoom a plan is usually at.
 */
private const val CROSS_SECTION_MENU_OFFSET_METRES = 3.0f
