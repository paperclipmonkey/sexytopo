package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyStats

/**
 * How big the cave is.
 *
 * Ported from `StatsActivity`, minus its linked-surveys half — this port has no cross-survey links,
 * and a panel of zeroes claiming otherwise would be worse than not showing one.
 */
@Composable
fun StatsDialog(survey: Survey, revision: Int, onDismiss: () -> Unit) {
    // Read so the numbers follow the survey: it is mutated in place, so without this they would
    // freeze at whatever they were when the dialog first drew.
    @Suppress("UNUSED_EXPRESSION")
    revision

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(survey.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, value) in statsOf(survey)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.close) } },
    )
}

/**
 * The seven numbers `StatsActivity` shows, in its order and its words.
 *
 * Two of them reproduce arithmetic in the original that looks like a mistake and is not: the
 * station count is one less than the number of stations, because the origin is not somewhere
 * anybody surveyed *to*; and the longest and shortest legs count splays while the length does not.
 * The app has shown those numbers for years.
 *
 * The unit is in the label — *Length (m)* — and not on the number, which is how
 * `activity_stats.xml` lays it out; this port used to say *Depth* and put an *m* after every
 * figure, which reads fine and is a different app. The numbers themselves go through the same
 * two formats the activity uses: two decimals for a length, none for a count, and a comma every
 * three digits for both, since a kilometre of cave is a number with four figures before the point.
 */
internal fun statsOf(survey: Survey): List<Pair<String, String>> {
    val origin = survey.origin
    return listOf(
        Strings.statsLength to metres(SurveyStats.totalLength(survey)),
        Strings.statsVerticalRange to metres(SurveyStats.heightRange(survey)),
        Strings.statsNumberStations to count(SurveyStats.numberOfStations(survey)),
        Strings.statsNumberLegs to count(SurveyStats.numberOfFullLegsUnder(origin)),
        Strings.statsNumberSplays to count(SurveyStats.numberOfSplaysUnder(origin)),
        Strings.statsShortestLeg to metres(SurveyStats.shortestLeg(survey)),
        Strings.statsLongestLeg to metres(SurveyStats.longestLeg(survey)),
    )
}

/** `TextTools.formatTo2dpWithComma`. */
private fun metres(value: Float): String = withThousands(formatFixed(value, 2))

/** `TextTools.formatWithComma`. */
private fun count(value: Int): String = withThousands(value.toString())

/**
 * A comma every three digits of the integer part, leaving any decimals and a leading sign alone.
 * Written out rather than through a locale, because the app groups with a comma whatever the
 * phone's locale says and the four targets here do not agree on what a locale even is.
 */
internal fun withThousands(number: String): String {
    val sign = if (number.startsWith("-")) "-" else ""
    val unsigned = number.removePrefix("-")
    val whole = unsigned.substringBefore('.')
    val fraction = if ('.' in unsigned) "." + unsigned.substringAfter('.') else ""
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return sign + grouped + fraction
}
