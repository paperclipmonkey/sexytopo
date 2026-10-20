package org.hwyl.sexytopo.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.calibration.CalibrationAnalysis
import org.hwyl.sexytopo.shared.calibration.CalibrationChoice
import org.hwyl.sexytopo.shared.calibration.CalibrationCoverage
import org.hwyl.sexytopo.shared.calibration.CalibrationPositions
import org.hwyl.sexytopo.shared.calibration.CalibrationQuality
import org.hwyl.sexytopo.shared.calibration.CalibrationResult
import org.hwyl.sexytopo.shared.calibration.CalibrationRun
import org.hwyl.sexytopo.shared.calibration.CalibrationSets
import org.hwyl.sexytopo.shared.calibration.CalibrationWrite
import org.hwyl.sexytopo.shared.calibration.ReadingDirection
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.model.sketch.Colour
import kotlin.math.roundToInt

/**
 * Calibrating the instrument.
 *
 * An uncalibrated DistoX can be several degrees out, and because a survey is a chain of bearings
 * the error accumulates along the passage.
 *
 * Laid out as `activity_calibration.xml` has been since 1.14.0, in its four cards — the mode, the
 * readings, the last reading and the result — and built around the readings themselves: each is
 * described by the way it was measured pointing rather than by its place in the guided sequence,
 * the readings card says how well the directions a calibration needs are covered, and any reading
 * can be looked at, replaced or deleted. The guided positions are still offered, as a suggestion.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationDialog(state: DemoState, onDismiss: () -> Unit) {
    val session = state.session
    val run = session.calibration
    val revision = session.calibrationRevision
    // Keyed on the revision, so a fit never outlives the readings it was made from: a reading
    // replaced or deleted after solving would otherwise leave a result, and an analysis, of a
    // calibration that no longer exists.
    var result by remember(revision) { mutableStateOf<CalibrationResult?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Where the readings list is open at, or null while it is closed.
    var listingFrom by remember { mutableStateOf<Int?>(null) }
    // Read from saved preferences rather than held as local state: a chip that reset every time
    // the dialog opened would cost a surveyor fifty-six shots' worth of forgetting.
    val algorithm = state.preferences.calibrationAlgorithm
    val nonLinear = algorithm.useNonLinearity(session.profile)

    val analysis = remember(result, revision) { result?.let(run::analyse) }
    val describer = remember(revision, analysis) { ReadingDescriber(run, analysis) }

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
                ModeSection(session, onStarted = { problem = it })
                HorizontalDivider()
                ReadingsSection(
                    run = run,
                    revision = revision,
                    onClear = {
                        session.clearCalibration()
                        result = null
                    },
                )
                HorizontalDivider()
                LastReadingSection(
                    run = run,
                    describer = describer,
                    onViewReadings = { listingFrom = run.lastIndex.coerceAtLeast(0) },
                    onDelete = { session.deleteLastCalibrationReading() },
                )
                HorizontalDivider()
                ResultSection(
                    state = state,
                    run = run,
                    algorithm = algorithm,
                    nonLinear = nonLinear,
                    result = result,
                    analysis = analysis,
                    describer = describer,
                    onSolve = {
                        problem = null
                        result =
                            runCatching { run.solve(nonLinear) }
                                .onFailure { problem = it.message ?: "the fit failed" }
                                .getOrNull()
                    },
                    onShowReading = { listingFrom = it },
                )

                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(CALIBRATION_CLOSE),
                onClick = {
                    if (session.calibrating) session.stopCalibration()
                    onDismiss()
                },
            ) { Text(Strings.close) }
        },
    )

    listingFrom?.let { start ->
        CalibrationReadingsDialog(
            session = session,
            describer = describer,
            startAt = start,
            onDismiss = { listingFrom = null },
        )
    }
}

/** `calibration_title_mode`: putting the instrument into calibration mode and out again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSection(session: SurveySession, onStarted: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(Strings.calibrationTitleMode)
        // Said before Start, not after: worth knowing before carrying a target board into a cave.
        // A BRIC is calibrated on the device, from its own menu.
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
                modifier = Modifier.testTag(CALIBRATION_START),
                onClick = {
                    onStarted(
                        if (session.startCalibration()) {
                            null
                        } else {
                            "This instrument cannot be calibrated from the app"
                        },
                    )
                },
            ) { Text(Strings.calibrationStart) }
            TextButton(
                enabled = session.calibrating,
                modifier = Modifier.testTag(CALIBRATION_STOP),
                onClick = { session.stopCalibration() },
            ) { Text(Strings.calibrationStop) }
            TextButton(
                enabled = session.calibrating,
                modifier = Modifier.testTag(CALIBRATION_SHOT),
                onClick = { session.simulateCalibrationReading() },
            ) { Text("Shot") }
        }
    }
}

/**
 * `calibration_title_readings`: how many readings, in how many sets, how well they cover the
 * directions a full calibration has, and where to point next.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingsSection(run: CalibrationRun, revision: Int, onClear: () -> Unit) {
    val coverage = remember(revision) { run.coverage() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(Strings.calibrationTitleReadings)
        Field(Strings.calibrationIndex, "${run.count}/${CalibrationPositions.REQUIRED}")
        Field(Strings.calibrationSets, Strings.calibrationSetsValue(coverage.setCount, coverage.completeSetCount))

        Text(Strings.calibrationCoverage, style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // `activity_calibration.xml`'s order, which is not the enum's.
            for ((label, pointing) in COVERAGE_ROWS) {
                val met = coverage.isTargetMet(pointing)
                Text(
                    "$label ${Strings.calibrationCoverageValue(
                        coverage.directionCount(pointing),
                        CalibrationCoverage.targetFor(pointing),
                    )}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (met) GOOD else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Text(Strings.calibrationNextStep, style = MaterialTheme.typography.labelMedium)
        val next = run.next
        Field(Strings.calibrationDirection, next?.direction?.label ?: Strings.notApplicable)
        Field(Strings.calibrationOrientation, next?.orientation?.label ?: Strings.notApplicable)

        TextButton(enabled = !run.isEmpty, onClick = onClear) { Text(Strings.clear) }
    }
}

/** `calibration_title_last_reading`: the reading most recently taken, and a way to the rest. */
@Composable
private fun LastReadingSection(
    run: CalibrationRun,
    describer: ReadingDescriber,
    onViewReadings: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(Strings.calibrationTitleLastReading, Modifier.weight(1f))
            TextButton(
                enabled = !run.isEmpty,
                modifier = Modifier.testTag(CALIBRATION_VIEW_READINGS),
                onClick = onViewReadings,
            ) { Text(Strings.calibrationViewReadings) }
        }
        val index = run.lastIndex
        if (index < 0) {
            Text(
                Strings.calibrationNoReadings,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CalibrationReadingCard(describer, index)
        }
        TextButton(
            enabled = !run.isEmpty,
            modifier = Modifier.testTag(CALIBRATION_DELETE_LAST),
            onClick = onDelete,
        ) { Text(Strings.calibrationDeleteLast) }
    }
}

/**
 * `calibration_title_result`: the algorithm, the fit, the readings least consistent with their
 * sets, and the write back to the instrument.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSection(
    state: DemoState,
    run: CalibrationRun,
    algorithm: CalibrationChoice,
    nonLinear: Boolean,
    result: CalibrationResult?,
    analysis: CalibrationAnalysis?,
    describer: ReadingDescriber,
    onSolve: () -> Unit,
    onShowReading: (Int) -> Unit,
) {
    val session = state.session
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(Strings.calibrationTitleResult)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (choice in CalibrationChoice.entries) {
                FilterChip(
                    selected = algorithm == choice,
                    onClick = {
                        state.updatePreferences(state.preferences.copy(calibrationAlgorithm = choice))
                    },
                    label = { Text(choice.label) },
                )
            }
        }
        if (algorithm == CalibrationChoice.AUTO) {
            val chosen =
                if (nonLinear) Strings.calibrationAlgorithmNonLinear else Strings.calibrationAlgorithmLinear
            Text(
                session.profile?.let { "${it.name}: $chosen" } ?: "Nothing attached, so linear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Solving is offered below 56, unlike the Android app: a surveyor who lost the light after
        // 40 shots is better served by a partial calibration than none. Never across a gap,
        // though, which is a reading the surveyor has said is wrong.
        TextButton(
            enabled = run.canSolve,
            modifier = Modifier.testTag(CALIBRATION_SOLVE),
            onClick = onSolve,
        ) {
            Text(if (run.isComplete) "Solve" else "Solve (${run.count} shots)")
        }

        val quality = result?.let(run::assess)
        Field(
            Strings.calibrationAssessment,
            result?.let { formatFixed(it.delta, 2) } ?: Strings.notApplicable,
            valueColour =
                when (quality) {
                    null -> MaterialTheme.colorScheme.onSurface
                    CalibrationQuality.GOOD -> GOOD
                    else -> BAD
                },
        )

        if (result != null && quality != null) {
            Text(
                "Good is ${formatFixed(CalibrationPositions.MAX_ERROR.toFloat(), 1)} or less; " +
                    "${result.iterations} iterations. " + describe(quality, run.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val worst = analysis?.leastConsistent().orEmpty()
        if (worst.isNotEmpty()) {
            Text(Strings.calibrationWorstReadings, style = MaterialTheme.typography.labelMedium)
            for (index in worst) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onShowReading(index) }
                        .testTag("$CALIBRATION_WORST_READING$index")
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        Strings.calibrationReadingLabel(index + 1, describer.position(index)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Strings.calibrationDegrees(formatFixed(analysis!!.errors[index], 1)),
                        style = MaterialTheme.typography.bodySmall,
                        color = BAD,
                    )
                }
            }
        }

        if (result != null && quality != null) {
            // A fit that never settled is not offered: its coefficients are wherever the solver
            // happened to stop, and writing them would be worse than uncalibrated.
            TextButton(
                enabled = quality != CalibrationQuality.DID_NOT_SETTLE,
                modifier = Modifier.testTag(CALIBRATION_WRITE),
                onClick = { session.writeCalibration(result) },
            ) { Text(Strings.calibrationUpdate) }
            session.calibrationWriteStatus?.let { status ->
                Text(
                    describe(status),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(CALIBRATION_WRITE_STATUS),
                    color =
                        when (status) {
                            CalibrationWrite.Status.CONFIRMED -> GOOD
                            CalibrationWrite.Status.REJECTED, CalibrationWrite.Status.FAILED -> BAD
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    }
}

/**
 * Every reading, as `requestViewReadings` lists them: tap one to replace it (keeping its place for a
 * retake) or delete it (closing up). The list stays open across several edits, as upstream's does.
 */
@Composable
private fun CalibrationReadingsDialog(
    session: SurveySession,
    describer: ReadingDescriber,
    startAt: Int,
    onDismiss: () -> Unit,
) {
    // An edit made from the options dialog redraws the list behind it because [describer] is
    // rebuilt on every revision of the readings, and so arrives here new.
    val run = describer.run
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startAt)
    var choosing by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.calibrationTitleReadings) },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(run.readings.size) { index ->
                    OutlinedCard(
                        onClick = { choosing = index },
                        modifier = Modifier.fillMaxWidth().testTag("$CALIBRATION_READING$index"),
                    ) {
                        Column(Modifier.padding(8.dp)) { CalibrationReadingContent(describer, index) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(CALIBRATION_READINGS_CLOSE),
                onClick = onDismiss,
            ) { Text(Strings.ok) }
        },
    )

    choosing?.let { index ->
        val isGap = run.readings.getOrNull(index) == null
        val close = { choosing = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(Strings.calibrationReadingTitle(index + 1)) },
            text = {
                Text(if (isGap) Strings.calibrationGapOptionsMessage else Strings.calibrationReadingOptionsMessage)
            },
            confirmButton = {
                if (!isGap) {
                    TextButton(
                        modifier = Modifier.testTag(CALIBRATION_REPLACE),
                        onClick = {
                            session.replaceCalibrationReading(index)
                            close()
                        },
                    ) { Text(Strings.calibrationReplace) }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = close) { Text(Strings.cancel) }
                    TextButton(
                        modifier = Modifier.testTag(CALIBRATION_DELETE_READING),
                        onClick = {
                            session.deleteCalibrationReading(index)
                            close()
                            if (run.isEmpty) onDismiss()
                        },
                    ) { Text(Strings.delete) }
                }
            },
        )
    }
}

/** One reading in a card of its own, for the last-reading section. */
@Composable
private fun CalibrationReadingCard(describer: ReadingDescriber, index: Int) {
    Card(Modifier.fillMaxWidth().testTag(CALIBRATION_LAST_READING)) {
        Column(Modifier.padding(8.dp)) { CalibrationReadingContent(describer, index) }
    }
}

/**
 * `CalibrationReadingView`: a reading's number and the way it was measured pointing, its roll and
 * raw values, and how far it disagrees with the rest of its set once there is a fit to say.
 */
@Composable
private fun CalibrationReadingContent(describer: ReadingDescriber, index: Int) {
    val reading = describer.run.readings.getOrNull(index)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            Strings.calibrationReadingNumber(index + 1),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            if (reading != null) {
                val direction = describer.direction(index)
                Text(pointingLabel(direction.pointing), style = MaterialTheme.typography.titleSmall)
                Text(
                    describer.details(index),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        describer.error(index)?.let { error ->
            Text(
                Strings.calibrationDegrees(formatFixed(error, 1)),
                style = MaterialTheme.typography.titleSmall,
                color = if (error <= CalibrationAnalysis.GOOD_ERROR) GOOD else BAD,
            )
        }
    }

    if (reading == null) {
        Text(
            Strings.calibrationReadingDeleted,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    val direction = describer.direction(index)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        for ((label, value) in listOf(
            Strings.tableHeadInclination to direction.inclination,
            Strings.tableHeadAzimuth to direction.azimuth,
            Strings.calibrationRoll to direction.roll,
        )) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(Strings.calibrationDegrees("${value.roundToInt()}"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (!describer.isCalibrated) {
        Text(
            Strings.calibrationUncalibratedEstimate,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "${Strings.calibrationFieldG} ${reading.gx}, ${reading.gy}, ${reading.gz}   " +
            "${Strings.calibrationFieldM} ${reading.mx}, ${reading.my}, ${reading.mz}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

/**
 * What the screen says about each reading, from the fit when there is one and from the raw sensor
 * values when there is not. `DistoXCalibrationActivity.bindReading` and its helpers.
 */
internal class ReadingDescriber(val run: CalibrationRun, private val analysis: CalibrationAnalysis?) {

    private val setNumbers = run.setNumbers()

    /** Whether the directions shown come from a calibration rather than the raw values. */
    val isCalibrated: Boolean get() = analysis != null

    /** The way the reading at [index] was measured pointing. Not for a gap. */
    fun direction(index: Int): ReadingDirection =
        analysis?.directions?.getOrNull(index)
            ?: CalibrationSets.uncalibratedDirection(requireNotNull(run.readings[index]))

    /** Degrees from the rest of its set, or null before a fit or for a reading alone in its set. */
    fun error(index: Int): Double? = analysis?.errors?.getOrNull(index)?.takeUnless { it.isNaN() }

    /** "Face Up · Set 3", without the face when it was pointing straight up or down. */
    fun details(index: Int): String {
        val direction = direction(index)
        return listOfNotNull(
            faceLabel(direction.face).takeUnless { direction.pointing.isVertical },
            Strings.calibrationSetNumber(setNumbers[index]),
        ).joinToString(Strings.calibrationDetailSeparator)
    }

    /** "Horizontal, Face Up", for the least-consistent list. */
    fun position(index: Int): String {
        val direction = direction(index)
        val pointing = pointingLabel(direction.pointing)
        return if (direction.pointing.isVertical) {
            pointing
        } else {
            Strings.calibrationPosition(pointing, faceLabel(direction.face))
        }
    }
}

internal fun pointingLabel(pointing: ReadingDirection.Pointing): String =
    when (pointing) {
        ReadingDirection.Pointing.HORIZONTAL -> Strings.calibrationPointingHorizontal
        ReadingDirection.Pointing.UP -> Strings.directionUp
        ReadingDirection.Pointing.DOWN -> Strings.directionDown
        ReadingDirection.Pointing.CORNER_UP -> Strings.calibrationPointingCornerUp
        ReadingDirection.Pointing.CORNER_DOWN -> Strings.calibrationPointingCornerDown
    }

internal fun faceLabel(face: ReadingDirection.Face): String =
    when (face) {
        ReadingDirection.Face.UP -> Strings.orientationFaceUp
        ReadingDirection.Face.DOWN -> Strings.orientationFaceDown
        ReadingDirection.Face.SIDE -> Strings.calibrationFaceSide
    }

private val COVERAGE_ROWS =
    listOf(
        Strings.calibrationPointingHorizontal to ReadingDirection.Pointing.HORIZONTAL,
        Strings.directionUp to ReadingDirection.Pointing.UP,
        Strings.directionDown to ReadingDirection.Pointing.DOWN,
        Strings.calibrationCoverageCornersUp to ReadingDirection.Pointing.CORNER_UP,
        Strings.calibrationCoverageCornersDown to ReadingDirection.Pointing.CORNER_DOWN,
    )

/** The Android screen's own two colours for good and bad, `Colour.SEA_GREEN` and `Colour.RED`. */
private val GOOD = Color(Colour.SEA_GREEN.intValue)
private val BAD = Color(Colour.RED.intValue)

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    valueColour: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColour)
    }
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
            "A poor fit. Usually a position repeated, or the instrument moved during a shot; " +
                "the least consistent readings are the ones to retake."

        CalibrationQuality.DID_NOT_SETTLE ->
            "The fit never settled, which means the readings do not describe one instrument. " +
                "Clear and start again."
    }

/** How an upload went, in `strings.xml`'s words where it has them. */
internal fun describe(status: CalibrationWrite.Status): String =
    when (status) {
        CalibrationWrite.Status.SENT -> Strings.calibrationSentUnconfirmed
        CalibrationWrite.Status.AWAITING_CONFIRMATION -> Strings.calibrationWriting
        CalibrationWrite.Status.CONFIRMED -> Strings.calibrationSuccess
        CalibrationWrite.Status.REJECTED -> Strings.deviceDistoxCalibrationWriteRejected
        CalibrationWrite.Status.FAILED -> Strings.calibrationErrorUpdatingDevice
    }

/**
 * The calibration dialog's controls, by name, for the browser tests.
 *
 * `instrument.mjs` used to press these at fixed pixel positions, and the day the buttons took the
 * Android app's own wording — *Disto Cal Mode On* where this said *Start* — the row reflowed and
 * every position pressed something else. A name does not move.
 */
const val CALIBRATION_START: String = "calibration-start"
const val CALIBRATION_STOP: String = "calibration-stop"
const val CALIBRATION_SHOT: String = "calibration-shot"
const val CALIBRATION_SOLVE: String = "calibration-solve"
const val CALIBRATION_WRITE: String = "calibration-write"
const val CALIBRATION_WRITE_STATUS: String = "calibration-write-status"
const val CALIBRATION_CLOSE: String = "calibration-close"
const val CALIBRATION_VIEW_READINGS: String = "calibration-view-readings"
const val CALIBRATION_DELETE_LAST: String = "calibration-delete-last"
const val CALIBRATION_LAST_READING: String = "calibration-last-reading"

/** Followed by the reading's index, counting from nought. */
const val CALIBRATION_READING: String = "calibration-reading-"
const val CALIBRATION_WORST_READING: String = "calibration-worst-"
const val CALIBRATION_READINGS_CLOSE: String = "calibration-readings-close"
const val CALIBRATION_REPLACE: String = "calibration-replace"
const val CALIBRATION_DELETE_READING: String = "calibration-delete-reading"
