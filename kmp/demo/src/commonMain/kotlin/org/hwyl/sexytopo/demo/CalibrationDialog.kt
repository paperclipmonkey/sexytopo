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
import org.hwyl.sexytopo.shared.calibration.CalibrationPositions
import org.hwyl.sexytopo.shared.calibration.CalibrationQuality
import org.hwyl.sexytopo.shared.calibration.CalibrationResult
import org.hwyl.sexytopo.shared.io.export.formatFixed

/**
 * Calibrating the instrument.
 *
 * Ported from `DistoXCalibrationActivity`. This is the last of the big screens to have a
 * counterpart here, and the one whose absence mattered most in the field: an uncalibrated DistoX
 * can be several degrees out, a survey is a chain of bearings, and the error accumulates along the
 * passage. A cave surveyed on an uncalibrated instrument comes back not quite the same shape as the
 * cave, and nothing in the numbers says so.
 *
 * Everything under this screen was ported and tested long before anything could reach it: Beat
 * Heeb's solver against the Android app's own two 56-shot datasets — asserting the iteration counts
 * as well as the errors, on the JVM, Kotlin/Wasm *and* Kotlin/Native — the packet decoders that
 * turn frames into readings, and the memory-write commands that put the answer back on the device.
 * What was missing was the fifty lines that ask the instrument to start.
 *
 * The workflow is the app's: put the instrument into calibration mode, shoot the 56 positions it
 * lists, solve, and write the coefficients back. The positions are a checklist rather than a
 * validation — the instrument does not report which way it was pointing, so neither app can know.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationDialog(state: DemoState, onDismiss: () -> Unit) {
    val session = state.session
    val run = session.calibration
    var result by remember { mutableStateOf<CalibrationResult?>(null) }
    var written by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Linear by default, and the Android app's own comment says why: "linear probably safer as
    // default". The non-linear variant fits three extra accelerometer coefficients.
    var nonLinear by remember { mutableStateOf(false) }


    // Read so this recomposes as readings arrive.
    val revision = session.calibrationRevision

    // Saved on every change, keyed on that same counter so it covers every way the run can change
    // — the buttons here, and a reading arriving from an instrument while nobody is touching the
    // screen, which is the case that actually matters.
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

                // FlowRow, not Row: five buttons do not fit across a phone, and a Row squeezes
                // the last one into a column of single letters rather than wrapping it.
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
                                    // FCL exposes no calibration commands at all.
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

                // Solving is offered from the solver's own minimum rather than from 56, with the
                // shortfall said plainly. The Android app refuses below 56; this reports instead,
                // because a surveyor who has taken 40 shots and lost the light is better served by
                // a calibration they know is partial than by none.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !nonLinear,
                        onClick = { nonLinear = false },
                        label = { Text("Linear") },
                    )
                    FilterChip(
                        selected = nonLinear,
                        onClick = { nonLinear = true },
                        label = { Text("Non-linear") },
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
                    // A poor fit is still offered: it is the surveyor's instrument, and a poor
                    // calibration they know about beats the factory one they do not. A fit that
                    // never settled is not, because it is not a calibration at all — its
                    // coefficients are wherever the solver happened to stop, and writing them
                    // would leave the instrument worse than uncalibrated.
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

                // No connection log here, unlike the instrument screen. It would say the same
                // thing as the count above it, and — because the dialog is centred — every line
                // it grew by moved the buttons a few pixels up under the surveyor's thumb.
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
