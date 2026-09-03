package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.calibration.CalibrationChoice
import org.hwyl.sexytopo.shared.calibration.CalibrationPositions
import org.hwyl.sexytopo.shared.calibration.CalibrationQuality
import org.hwyl.sexytopo.shared.calibration.CalibrationResult
import org.hwyl.sexytopo.shared.io.export.formatFixed

/**
 * Calibrating the instrument.
 *
 * An uncalibrated DistoX can be several degrees out, and because a survey is a chain of bearings
 * the error accumulates along the passage. The positions are a checklist rather than a
 * validation — the instrument does not report which way it was pointing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationDialog(state: DemoState, onDismiss: () -> Unit) {
    val session = state.session
    val run = session.calibration
    var result by remember { mutableStateOf<CalibrationResult?>(null) }
    var written by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Read from saved preferences rather than held as local state: a chip that reset every time
    // the dialog opened would cost a surveyor fifty-six shots' worth of forgetting.
    val algorithm = state.preferences.calibrationAlgorithm
    val nonLinear = algorithm.useNonLinearity(session.profile)

    val revision = session.calibrationRevision

    // Covers both ways the run can change: the buttons here, and a reading arriving from an
    // instrument while nobody is touching the screen.
    LaunchedEffect(revision) { state.noteCalibrationChanged() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Point the instrument as listed and shoot. Fifty-six shots: fourteen " +
                        "directions, each rolled through four positions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "${run.count} of ${CalibrationPositions.REQUIRED} readings",
                    style = MaterialTheme.typography.titleSmall,
                )

                Text(
                    run.next?.let { "Next: $it" } ?: "Enough readings — solve when ready",
                    style = MaterialTheme.typography.bodyMedium,
                )

                run.last?.let { reading ->
                    Text(
                        "Last: G ${reading.gx}, ${reading.gy}, ${reading.gz}   " +
                            "M ${reading.mx}, ${reading.my}, ${reading.mz}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                HorizontalDivider()

                // Said before Start, not after: worth knowing before carrying a target board
                // into a cave. A BRIC is calibrated on the device, from its own menu.
                if (!session.canCalibrate) {
                    Text(
                        session.profile?.name?.let {
                            "$it cannot be calibrated from the app. It is calibrated on the " +
                                "instrument itself, from its own menu."
                        } ?: "Connect an instrument to calibrate it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // FlowRow, not Row: a Row squeezes the last button into a column of letters.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !session.calibrating,
                        onClick = {
                            result = null
                            written = 0
                            problem =
                                if (session.startCalibration()) {
                                    null
                                } else {
                                    "This instrument cannot be calibrated from the app"
                                }
                        },
                    ) { Text("Start") }
                    TextButton(
                        enabled = session.calibrating,
                        onClick = { session.stopCalibration() },
                    ) { Text("Stop") }
                    TextButton(
                        enabled = session.calibrating,
                        onClick = { session.simulateCalibrationReading() },
                    ) { Text("Shot") }
                    TextButton(
                        enabled = run.count > 0,
                        onClick = { session.deleteLastCalibrationReading() },
                    ) { Text("Undo") }
                    TextButton(
                        enabled = run.count > 0,
                        onClick = {
                            session.clearCalibration()
                            result = null
                            written = 0
                        },
                    ) { Text("Clear") }
                }

                HorizontalDivider()

                // Solving is offered below 56, unlike the Android app: a surveyor who lost the
                // light after 40 shots is better served by a partial calibration than none.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (choice in CalibrationChoice.entries) {
                        FilterChip(
                            selected = algorithm == choice,
                            onClick = {
                                state.updatePreferences(
                                    state.preferences.copy(calibrationAlgorithm = choice),
                                )
                            },
                            label = { Text(choice.label) },
                        )
                    }
                }
                if (algorithm == CalibrationChoice.AUTO) {
                    Text(
                        session.profile?.let {
                            "${it.name}: " +
                                if (nonLinear) "non-linear" else "linear"
                        } ?: "Nothing attached, so linear.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(
                    enabled = run.canSolve,
                    onClick = {
                        problem = null
                        result =
                            runCatching { run.solve(nonLinear) }
                                .onFailure { problem = it.message ?: "the fit failed" }
                                .getOrNull()
                    },
                ) {
                    Text(if (run.isComplete) "Solve" else "Solve (${run.count} shots)")
                }

                result?.let { fitted ->
                    val quality = run.assess(fitted)
                    Text(
                        "Error ${formatFixed(fitted.delta, 2)} " +
                            "(good is ${formatFixed(CalibrationPositions.MAX_ERROR.toFloat(), 2)} " +
                            "or less), ${fitted.iterations} iterations",
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (quality == CalibrationQuality.GOOD) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                    Text(
                        describe(quality, run.count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A fit that never settled is not offered: its coefficients are wherever the
                    // solver happened to stop, and writing them would be worse than uncalibrated.
                    TextButton(
                        enabled = quality != CalibrationQuality.DID_NOT_SETTLE,
                        onClick = { written = session.writeCalibration(fitted) },
                    ) { Text("Write to instrument") }
                    if (written > 0) {
                        Text(
                            "$written coefficient blocks written",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (session.calibrating) session.stopCalibration()
                    onDismiss()
                },
            ) { Text("Close") }
        },
    )
}

/** What the assessment means, in a sentence a surveyor can act on. */
internal fun describe(quality: CalibrationQuality, readings: Int): String =
    when (quality) {
        CalibrationQuality.GOOD ->
            if (readings >= CalibrationPositions.REQUIRED) {
                "A good fit. Write it to the instrument and re-shoot a known leg to check."
            } else {
                "A good fit, but from a partial set — the positions are what make it meaningful, " +
                    "so finish the 56 before trusting it underground."
            }

        CalibrationQuality.POOR ->
            "A poor fit. Usually a position repeated, or the instrument moved during a shot."

        CalibrationQuality.DID_NOT_SETTLE ->
            "The fit never settled, which means the readings do not describe one instrument. " +
                "Clear and start again."
    }
