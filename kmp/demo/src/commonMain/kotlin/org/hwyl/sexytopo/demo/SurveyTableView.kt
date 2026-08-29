package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
fun SurveyTableView(survey: Survey, revision: Int, modifier: Modifier = Modifier) {
    val rows = remember(survey, revision) { asTakenRows(survey) }

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

        LazyColumn(Modifier.fillMaxSize()) {
            items(rows) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                    Cell(row.from, 64.dp)
                    Cell(row.to, 64.dp)
                    Cell(row.distance, 92.dp)
                    Cell(row.azimuth, 84.dp)
                    Cell(row.inclination, 92.dp)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        Modifier.width(width),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
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
)

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

private fun rowFor(from: Station, leg: Leg): SurveyTableRow {
    val (fromName, toName, reading) =
        if (leg.wasShotBackwards) {
            Triple(leg.destination.name, from.name, leg.asBacksight())
        } else {
            Triple(from.name, if (leg.hasDestination()) leg.destination.name else SPLAY, leg)
        }

    return SurveyTableRow(
        from = fromName,
        to = toName,
        // Same precision as the Android app's TableCol formats: %.3f, %.2f, %+.2f.
        distance = formatFixed(reading.distance, 3),
        azimuth = formatFixed(reading.azimuth, 2),
        inclination = formatFixed(reading.inclination, 2, alwaysSigned = true),
    )
}

private const val SPLAY = "–"

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
