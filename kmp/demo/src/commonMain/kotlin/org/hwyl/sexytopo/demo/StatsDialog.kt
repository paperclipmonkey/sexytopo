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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The seven numbers `StatsActivity` shows, in its order.
 *
 * Two of them reproduce arithmetic in the original that looks like a mistake and is not: the
 * station count is one less than the number of stations, because the origin is not somewhere
 * anybody surveyed *to*; and the longest and shortest legs count splays while the length does not.
 * The app has shown those numbers for years.
 */
internal fun statsOf(survey: Survey): List<Pair<String, String>> {
    val origin = survey.origin
    return listOf(
        "Length" to "${formatFixed(SurveyStats.totalLength(survey), 2)} m",
        "Depth" to "${formatFixed(SurveyStats.heightRange(survey), 2)} m",
        "Stations" to SurveyStats.numberOfStations(survey).toString(),
        "Legs" to SurveyStats.numberOfFullLegsUnder(origin).toString(),
        "Splays" to SurveyStats.numberOfSplaysUnder(origin).toString(),
        "Shortest leg" to "${formatFixed(SurveyStats.shortestLeg(survey), 2)} m",
        "Longest leg" to "${formatFixed(SurveyStats.longestLeg(survey), 2)} m",
    )
}
