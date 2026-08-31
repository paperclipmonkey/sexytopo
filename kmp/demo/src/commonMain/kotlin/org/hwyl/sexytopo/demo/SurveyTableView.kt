package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.asBacksight

/**
 * The survey table — the other half of the Android app's main UI, alongside the sketch.
 *
 * The interesting part is not the layout but [asTakenRows]: a leg shot backwards is stored pointing
 * the other way, and both the table and every exporter must show the reading *as the surveyor took
 * it*, or the numbers will not match their notes.
 */
@Composable
fun SurveyTableView(
    survey: Survey,
    revision: Int,
    modifier: Modifier = Modifier,
    onEdited: () -> Unit = {},
    /** Read-only when false, which is what the demo cave wants. */
    editable: Boolean = false,
    /**
     * Opening a station's own menu, which in the Android app is what a tap on a From or To cell
     * does — `table_station_selected.xml` rather than `context_leg.xml`. Null leaves those cells
     * behaving like the rest of the row.
     */
    onStation: ((Station) -> Unit)? = null,
    /**
     * A station to scroll to, from `action_jump_to_table` on the sketch's own menu.
     *
     * The *first* row that mentions it, which is the leg that arrives at it — a station appears
     * twice in this table, once as a To and again as the From of everything leaving it, and the
     * arriving leg is the one a surveyor who tapped it on the drawing is looking for.
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
    var chosen by remember(revision) { mutableStateOf<SurveyTableRow?>(null) }

    chosen?.let { row ->
        LegActionsDialog(
            survey = survey,
            row = row,
            onDismiss = { chosen = null },
            onEdited = {
                chosen = null
                onEdited()
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            HeaderCell("From", 64.dp)
            HeaderCell("To", 64.dp)
            HeaderCell("Distance", 92.dp)
            HeaderCell("Azimuth", 84.dp)
            HeaderCell("Inclination", 92.dp)
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (editable) {
                                Modifier.clickable { chosen = row }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                ) {
                    // A tap on a station's name is about the station; a tap anywhere else on the
                    // row is about the reading. Ported from `TableActivity.onCellClicked`, which
                    // asks the same question of the column that was hit.
                    StationCell(row.fromShown, row.fromStationShown, editable, onStation)
                    StationCell(row.toShown, row.toStationShown, editable, onStation)
                    Cell(row.distanceShown, 92.dp)
                    Cell(row.azimuth, 84.dp)
                    Cell(row.inclination, 92.dp)
                }
            }
        }
    }
}

/**
 * Which row to scroll to for a named station, or null if the table has none.
 *
 * The *first* row mentioning it, which is the leg that arrives at it. A station appears in this
 * table more than once — once as the To of the leg that reached it, then as the From of everything
 * leaving it — and someone who tapped it on the drawing is looking for the reading that made it.
 * Its own row is also the one with its splays under it, so landing there shows the passage
 * measurements as well.
 *
 * The origin is the exception and is right by accident: it has no arriving leg, so the first row
 * mentioning it is the first leg *out* of it, which is the only row it has.
 */
internal fun rowIndexFor(rows: List<SurveyTableRow>, station: String): Int? =
    rows.indexOfFirst { it.to == station || it.from == station }.takeIf { it >= 0 }

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        Modifier.width(width),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * A From or To cell: the station's name, and a way into its menu.
 *
 * Inert on a splay, whose To column is a dash rather than a station, and on the demo cave, where
 * nothing is editable.
 */
@Composable
private fun StationCell(
    text: String,
    station: Station?,
    editable: Boolean,
    onStation: ((Station) -> Unit)?,
) {
    val handler = if (editable && station != null) onStation else null
    Text(
        text,
        Modifier
            .width(64.dp)
            .then(if (handler == null) Modifier else Modifier.clickable { handler(station!!) }),
        fontSize = 12.sp,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, Modifier.width(width), fontSize = 12.sp, style = MaterialTheme.typography.bodySmall)
}

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
     * reading was taken standing at. `TableActivity` works this out with
     * `(col == FROM) ^ leg.wasShotBackwards()`, and getting it the wrong way round would open the
     * menu for the station at the other end of the leg.
     */
    val fromStationShown: Station = fromStation,
    /** The station the To column shows, or null on a splay, which arrives nowhere. */
    val toStationShown: Station? = null,
) {
    val isSplay: Boolean get() = !leg.hasDestination()

    // Ported from `TableRowAdapter.onBindViewHolder`, which marks three cells and no others: a
    // full leg's From and To when *that station* has a comment, with the dagger trailing; and the
    // distance when the *leg* has one, with the dagger leading. A splay's From is left alone even
    // when the station it hangs off has a comment, because the Java tests `isFullLeg` first.
    val fromShown: String get() = if (fromHasComment) "$from $COMMENT_MARKER" else from
    val toShown: String get() = if (toHasComment) "$to $COMMENT_MARKER" else to
    val distanceShown: String
        get() = if (leg.hasComment()) "$COMMENT_MARKER $distance" else distance
}

/**
 * Flattens the survey tree into table rows in chronological order, normalising backwards shots.
 *
 * Ported from `GraphToListTranslator.toAsTakenReading`: a leg with `wasShotBackwards` is stored
 * with the reading taken at the far station but attached in the opposite direction, so from/to are
 * swapped and the leg is converted to its backsight before being displayed.
 */
fun asTakenRows(survey: Survey): List<SurveyTableRow> {
    val rows = mutableListOf<SurveyTableRow>()

    for (station in survey.getAllStations()) {
        for (leg in station.onwardLegs) {
            rows.add(rowFor(station, leg))
        }
    }
    return rows
}

/**
 * The row for the leg that made [station], or null for the origin, which no leg made.
 *
 * The station menu on the sketch offers the incoming leg's actions — edit, reverse, comment,
 * delete — and those are already written against a table row, because that is where they were
 * first reachable from. Building the row here rather than duplicating the actions keeps one
 * implementation of "what a leg looks like once a backwards shot has been normalised".
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

private const val SPLAY = "–"

/**
 * The dagger the Android app puts beside anything carrying a comment.
 *
 * `SexyTopoConstants.COMMENT_MARKER`. It is the only sign in the table that a comment exists at
 * all, so a surveyor who wrote "sump, do not follow" against a leg can see it without opening
 * anything.
 */
const val COMMENT_MARKER = "†"

/**
 * Fixed-decimal formatting, because commonMain has no `String.format`.
 *
 * Rounds half away from zero on the magnitude, matching Java's `Formatter` HALF_UP. This will move
 * to the shared module alongside the exporters, which need exactly the same behaviour to reproduce
 * Therion and Survex output byte for byte.
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
