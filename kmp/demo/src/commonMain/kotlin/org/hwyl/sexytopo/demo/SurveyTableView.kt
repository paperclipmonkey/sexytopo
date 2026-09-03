package org.hwyl.sexytopo.demo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.LrudMode
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.asBacksight

/**
 * The survey table — the other half of the Android app's main UI, alongside the sketch.
 *
 * The interesting part is not the layout but [asTakenRows]: a leg shot backwards is stored pointing
 * the other way, and both the table and every exporter must show the reading *as the surveyor took
 * it*, or the numbers will not match their notes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SurveyTableView(
    survey: Survey,
    revision: Int,
    modifier: Modifier = Modifier,
    /** Which palette the rows and the active-station highlight are drawn from. */
    darkMode: Boolean = false,
    /** `pref_lrud_fields`, passed to the edit dialog the way the app passes it to `LegDialogs`. */
    lrudFields: Boolean = false,
    /** `pref_lrud_direction`, likewise. */
    lrudMode: LrudMode = LrudMode.DEFAULT,
    onEdited: () -> Unit = {},
    /** Read-only when false, which is what the demo cave wants. */
    editable: Boolean = false,
    /** Opening a station's own menu, from a tap on a From or To cell. Null disables it. */
    onStation: ((Station) -> Unit)? = null,
    /**
     * A station to scroll to, from `action_jump_to_table` on the sketch's own menu.
     *
     * The *first* row that mentions it: a station appears twice in this table, once as a To and
     * again as the From of everything leaving it, and the arriving leg is the one being looked for.
     */
    scrollTo: String? = null,
    /** Called once the scroll has happened, so the request is not repeated on every recomposition. */
    onScrolled: () -> Unit = {},
) {
    val rows = remember(survey, revision) { asTakenRows(survey) }
    val listState = rememberLazyListState()

    LaunchedEffect(scrollTo, rows) {
        val wanted = scrollTo ?: return@LaunchedEffect
        // Not found is still done: a station can be renamed or deleted between the menu opening
        // and this running, and a request left standing would scroll on the next unrelated edit.
        rowIndexFor(rows, wanted)?.let { listState.scrollToItem(it) }
        onScrolled()
    }

    // `syncWithSurvey`: with nothing asked for, the table opens on the latest reading. The working
    // end of a survey is the bottom of this table, and it is the only part anybody opens it for.
    LaunchedEffect(survey) {
        if (scrollTo == null && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    // A tap edits the reading and a long press opens the leg's menu, which is what
    // `TableActivity.onRowClick` and `onRowLongClick` do. Two states rather than one, since the
    // Android app reaches two different dialogs from the same row.
    var editingRow by remember(revision) { mutableStateOf<SurveyTableRow?>(null) }
    var menuRow by remember(revision) { mutableStateOf<SurveyTableRow?>(null) }

    editingRow?.let { row ->
        EditLegDialog(
            row = row,
            survey = survey,
            lrudFields = lrudFields,
            lrudMode = lrudMode,
            onDismiss = { editingRow = null },
            onSave = { edited ->
                SurveyUpdater.editLeg(survey, row.leg, edited)
                editingRow = null
                onEdited()
            },
        )
    }

    menuRow?.let { row ->
        LegActionsDialog(
            survey = survey,
            row = row,
            onDismiss = { menuRow = null },
            onEdited = {
                menuRow = null
                onEdited()
            },
        )
    }

    // One scroll state for the header and every row, so the whole table moves together: five
    // fixed-width columns plus padding run wider than a small phone, and a header that scrolls
    // independently of the rows would drift out of alignment with the numbers under it.
    val columns = rememberScrollState()

    val active = survey.activeStation

    Column(modifier.fillMaxSize()) {
        // `@style/HeaderRow`: `headerBackground` with white bold text over it, not a Material
        // surface tint — and the dark green of `values-night` at night.
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (darkMode) {
                        SexyTopoColours.panelBackgroundNight
                    } else {
                        SexyTopoColours.panelBackground
                    },
                )
                .horizontalScroll(columns)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            HeaderCell(Strings.tableHeadFrom, FROM_WIDTH)
            HeaderCell(Strings.tableHeadTo, TO_WIDTH)
            HeaderCell(Strings.tableHeadDistance, DISTANCE_WIDTH)
            HeaderCell(Strings.tableHeadAzimuth, AZIMUTH_WIDTH)
            HeaderCell(Strings.tableHeadInclination, INCLINATION_WIDTH)
        }
        HorizontalDivider()

        if (rows.isEmpty()) {
            // `syncWithSurvey` toasts `no_data`; a toast is not something this port has.
            Text(
                Strings.noData,
                Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = cellInk(lit = false, darkMode = darkMode),
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(rows) { index, row ->
                // `onBindViewHolder`: even rows on `tableBackground`, odd on `tableBackgroundAlt`.
                val stripe =
                    if (index % 2 == 0) {
                        if (darkMode) SexyTopoColours.tableBackgroundNight else SexyTopoColours.tableBackground
                    } else {
                        if (darkMode) {
                            SexyTopoColours.tableBackgroundAltNight
                        } else {
                            SexyTopoColours.tableBackgroundAlt
                        }
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(stripe)
                        .then(
                            if (editable) {
                                // Long press opens the leg's menu; a tap edits the reading.
                                Modifier.combinedClickable(
                                    onClick = { editingRow = row },
                                    onLongClick = { menuRow = row },
                                )
                            } else {
                                Modifier
                            },
                        )
                        .horizontalScroll(columns)
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                ) {
                    // A press on a station's name is about the station; anywhere else on the row
                    // is about the reading.
                    StationCell(
                        row.fromShown,
                        row.fromStationShown,
                        active,
                        row.isSplay,
                        editable,
                        darkMode,
                        FROM_WIDTH,
                        onStation,
                        onEdit = { editingRow = row },
                    )
                    StationCell(
                        row.toShown,
                        row.toStationShown,
                        active,
                        row.isSplay,
                        editable,
                        darkMode,
                        TO_WIDTH,
                        onStation,
                        onEdit = { editingRow = row },
                    )
                    Cell(row.distanceShown, DISTANCE_WIDTH, row.isSplay, darkMode)
                    Cell(row.azimuth, AZIMUTH_WIDTH, row.isSplay, darkMode)
                    Cell(row.inclination, INCLINATION_WIDTH, row.isSplay, darkMode)
                }
            }
        }
    }
}

/**
 * The five columns of `table_row.xml`, whose `layout_weight="1"` shares the width evenly. Fixed
 * widths here instead, because this table scrolls sideways rather than squeezing: a bearing that
 * has been ellipsised to `12…` is worse than one you have to scroll to.
 */
private val FROM_WIDTH = 64.dp

private val TO_WIDTH = 64.dp

private val DISTANCE_WIDTH = 92.dp

private val AZIMUTH_WIDTH = 84.dp

private val INCLINATION_WIDTH = 92.dp

/**
 * Which row to scroll to for a named station, or null if the table has none.
 *
 * The origin is an accidental exception: it has no arriving leg, so the first row mentioning it is
 * the first leg *out* of it, which is the only row it has.
 */
internal fun rowIndexFor(rows: List<SurveyTableRow>, station: String): Int? =
    rows.indexOfFirst { it.to == station || it.from == station }.takeIf { it >= 0 }

/** `@style/HeaderText`: white, bold, centred. */
@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        Modifier.width(width),
        style = MaterialTheme.typography.bodyMedium,
        color = SexyTopoColours.onPanel,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

/**
 * A From or To cell: the station's name, and a way into its menu. Inert on a splay.
 *
 * The active station is lit in `tableHighlight` with `tableHighlightText` over it, which is how a
 * surveyor finds the working end of a table forty rows long.
 */
@Composable
private fun StationCell(
    text: String,
    station: Station?,
    active: Station?,
    isSplay: Boolean,
    editable: Boolean,
    darkMode: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onStation: ((Station) -> Unit)?,
    onEdit: () -> Unit,
) {
    val handler = if (editable && station != null) onStation else null
    val lit = station != null && station === active

    Box(
        Modifier
            .width(width)
            .then(
                if (lit) {
                    Modifier.background(
                        if (darkMode) {
                            SexyTopoColours.tableHighlightNight
                        } else {
                            SexyTopoColours.tableHighlight
                        },
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (handler == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = onEdit,
                        onLongClick = { handler(station!!) },
                    )
                },
            ),
    ) {
        Text(
            text,
            Modifier.fillMaxWidth(),
            fontSize = CELL_TEXT_SP.sp,
            style = MaterialTheme.typography.bodySmall,
            // `onBindViewHolder`: station names centred, bold on a full leg and plain on a splay.
            textAlign = TextAlign.Center,
            fontWeight = if (isSplay) FontWeight.Normal else FontWeight.Bold,
            color = cellInk(lit, darkMode),
        )
    }
}

/** A number: right-aligned, as `onBindViewHolder` sets the gravity on the three numeric columns. */
@Composable
private fun Cell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    isSplay: Boolean,
    darkMode: Boolean,
) {
    Text(
        text,
        Modifier.width(width),
        fontSize = CELL_TEXT_SP.sp,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.End,
        fontWeight = if (isSplay) FontWeight.Normal else FontWeight.Bold,
        color = cellInk(lit = false, darkMode = darkMode),
    )
}

/** `bodyTextColor`, or `tableHighlightText` where the active station is lit under it. */
private fun cellInk(lit: Boolean, darkMode: Boolean) =
    when {
        lit -> SexyTopoColours.tableHighlightText
        darkMode -> SexyTopoColours.bodyTextNight
        else -> SexyTopoColours.bodyText
    }

/** `@style/BodyText`'s own `textSize`. */
private const val CELL_TEXT_SP = 14

/** One row of the table, already formatted. */
class SurveyTableRow(
    val from: String,
    val to: String,
    val distance: String,
    val azimuth: String,
    val inclination: String,
    /** The station the leg hangs off, needed to delete or move it. */
    val fromStation: Station,
    /** The leg itself, as stored - not the as-taken reading shown in the row. */
    val leg: Leg,
    /** Whether the station shown in the From column carries a comment. Splays never do. */
    val fromHasComment: Boolean = false,
    /** Whether the station shown in the To column carries a comment. Splays never do. */
    val toHasComment: Boolean = false,
    /**
     * The station the From column shows, which for a backsight is the far end — the one the
     * reading was taken standing at.
     */
    val fromStationShown: Station = fromStation,
    /** The station the To column shows, or null on a splay, which arrives nowhere. */
    val toStationShown: Station? = null,
) {
    val isSplay: Boolean get() = !leg.hasDestination()

    // A splay's From is left alone even when the station it hangs off has a comment: only a full
    // leg's stations are marked.
    val fromShown: String get() = if (fromHasComment) "$from $COMMENT_MARKER" else from
    val toShown: String get() = if (toHasComment) "$to $COMMENT_MARKER" else to
    val distanceShown: String
        get() = if (leg.hasComment()) "$COMMENT_MARKER $distance" else distance
}

/**
 * Flattens the survey tree into table rows in chronological order, normalising backwards shots: a
 * leg with `wasShotBackwards` is stored attached in the opposite direction from the one it was
 * read, so from/to are swapped and the leg is converted to its backsight before being displayed.
 *
 * Chronological, as `toChronoListOfSurveyListEntries` is, and not tree order: the surveyor's own
 * notebook runs in the order the shots were taken, and a table that runs down one branch and back
 * up cannot be read against it. The two orders agree until somebody surveys a side passage and
 * comes back to push the main one.
 */
fun asTakenRows(survey: Survey): List<SurveyTableRow> {
    val rows = mutableListOf<SurveyTableRow>()

    for (station in survey.getAllStations()) {
        for (leg in station.onwardLegs) {
            rows.add(rowFor(station, leg))
        }
    }

    val chronological = survey.getAllLegsInChronoOrder()
    return rows.sortedBy { chronological.indexOf(it.leg) }
}

/**
 * The row for the leg that made [station], or null for the origin, which no leg made.
 *
 * The station menu's incoming-leg actions are already written against a table row, so building one
 * here keeps one implementation of "what a leg looks like once normalised".
 */
fun incomingLegRow(survey: Survey, station: Station): SurveyTableRow? {
    val leg = survey.getReferringLeg(station) ?: return null
    val from = survey.getOriginatingStation(leg) ?: return null
    return rowFor(from, leg)
}

internal fun rowFor(from: Station, leg: Leg): SurveyTableRow {
    val (fromStation, toStation) =
        if (leg.wasShotBackwards) leg.destination to from else from to leg.destination
    val reading = if (leg.wasShotBackwards) leg.asBacksight() else leg

    return SurveyTableRow(
        fromStation = from,
        leg = leg,
        from = fromStation.name,
        to = if (leg.hasDestination()) toStation.name else SPLAY,
        // Same precision as the Android app's TableCol formats: %.3f, %.2f, %+.2f.
        distance = formatFixed(reading.distance, 3),
        azimuth = formatFixed(reading.azimuth, 2),
        inclination = formatFixed(reading.inclination, 2, alwaysSigned = true),
        fromHasComment = leg.hasDestination() && fromStation.hasComment(),
        toHasComment = leg.hasDestination() && toStation.hasComment(),
        fromStationShown = fromStation,
        toStationShown = if (leg.hasDestination()) toStation else null,
    )
}

/** `Survey.NULL_STATION`, whose name is what the app puts in the To column of a splay. */
private const val SPLAY = "-"

/** The dagger the Android app puts beside anything carrying a comment. `SexyTopoConstants.COMMENT_MARKER`. */
const val COMMENT_MARKER = "†"

/**
 * Fixed-decimal formatting, because commonMain has no `String.format`. Rounds half away from zero
 * on the magnitude, matching Java's `Formatter` HALF_UP.
 */
fun formatFixed(value: Float, decimalPlaces: Int, alwaysSigned: Boolean = false): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"

    var scale = 1L
    repeat(decimalPlaces) { scale *= 10 }

    val magnitude = kotlin.math.abs(value.toDouble())
    // floor(x + 0.5), NOT kotlin.math.round: Kotlin's round is ties-to-even (it maps to
    // Math.rint), whereas Java's Formatter — and so every number the Android app has ever
    // written to a Therion or Survex file — is HALF_UP. round(2.5) would give 2, not 3.
    val scaled = kotlin.math.floor(magnitude * scale + 0.5).toLong()
    val whole = scaled / scale
    val fraction = scaled % scale

    val sign =
        when {
            value < 0 -> "-"
            alwaysSigned -> "+"
            else -> ""
        }

    return if (decimalPlaces == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${fraction.toString().padStart(decimalPlaces, '0')}"
    }
}
