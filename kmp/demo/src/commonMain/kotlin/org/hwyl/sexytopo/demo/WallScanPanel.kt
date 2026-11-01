package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.scan.PlacementMode
import org.hwyl.sexytopo.shared.scan.ScanPlacement
import org.hwyl.sexytopo.shared.scan.SimulatedWallScan
import org.hwyl.sexytopo.shared.scan.SurveyFrame
import org.hwyl.sexytopo.shared.scan.WallScanApplier
import org.hwyl.sexytopo.shared.scan.WallScanRecord
import kotlin.math.roundToInt

/**
 * Tools → Wall scans: every scan kept with the survey, under the drawing, to place again, tidy or
 * delete.
 *
 * It takes the place of the field bar and the toolbar rather than covering the drawing, because
 * almost everything here is judged by looking at the drawing: whether a scan sits on its stations,
 * whether a wall follows the rock, whether a pen line has been smoothed too far. Every change is
 * drawn at once, and saved at once, as every other edit in this app is.
 *
 * What it changes is the scan's review, never its capture: removing a mark here can be undone by
 * restoring it, and putting a scan back on Automatic forgets every nudge.
 */
@Composable
fun WallScanPanel(state: DemoState, modifier: Modifier = Modifier) {
    // Read so the panel follows changes made to a record in place.
    @Suppress("UNUSED_EXPRESSION")
    state.scanRevision

    val scanner = rememberWallScanner { scan -> state.acceptScannedWallScan(scan) }
    LaunchedEffect(Unit) { state.recoverUnfinishedScan() }
    val scans = state.wallScans
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = scans.firstOrNull { it.id == selectedId } ?: scans.lastOrNull()
    var confirmingDelete by remember { mutableStateOf<WallScanRecord?>(null) }

    confirmingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(Strings.deleteWallScan) },
            text = { Text("Scan ${record.id} and everything it drew will be gone. Hand drawing stays.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.deleteWallScan(record)
                        confirmingDelete = null
                    },
                    modifier = Modifier.testTag("wall-scan-delete-confirm"),
                ) { Text(Strings.deleteWallScan) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text(Strings.cancel) } },
        )
    }

    Column(
        modifier
            .background(SexyTopoColours.canvasBackground)
            .testTag("wall-scan-panel"),
    ) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().background(SexyTopoColours.panelBackground).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Strings.reviewWallScans,
                color = SexyTopoColours.onPanel,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            if (scanner.available) {
                TextButton(onClick = {
                    if (state.readyToScan()) {
                        val survey = state.liveSurvey
                        scanner.scan(
                            survey.name,
                            survey.getAllStationsInChronoOrder().map { it.name },
                            survey.activeStation.name,
                        )
                    }
                }) { Text(Strings.scanWalls, color = SexyTopoColours.onPanel) }
            }
            TextButton(
                onClick = { state.reviewingScans = false },
                modifier = Modifier.testTag("wall-scan-close"),
            ) { Text(Strings.close, color = SexyTopoColours.onPanel) }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!scanner.available) {
                Text(Strings.noLidarHere, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { simulate(state, markStations = true) },
                        modifier = Modifier.testTag("simulate-scan"),
                    ) { Text(Strings.simulateWallScan) }
                    OutlinedButton(
                        onClick = { simulate(state, markStations = false) },
                        modifier = Modifier.testTag("simulate-scan-unmarked"),
                    ) { Text(SIMULATE_UNMARKED) }
                }
            }

            if (scans.isEmpty()) {
                Text(Strings.noWallScans, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (record in scans) {
                    FilterChip(
                        selected = record === selected,
                        onClick = { selectedId = record.id },
                        label = { Text("Scan ${record.id}") },
                        modifier = Modifier.testTag("wall-scan-${record.id}"),
                    )
                }
            }

            if (selected != null) ScanDetail(state, selected, state.scanRevision) { confirmingDelete = it }
        }
    }
}

@Composable
private fun ScanDetail(
    state: DemoState,
    record: WallScanRecord,
    // The record is changed in place, so the same instance comes back after every edit and Compose
    // would skip this; the revision is what says it has changed.
    revision: Int,
    onDelete: (WallScanRecord) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION")
    revision
    val result = record.result
    Text(
        result?.message ?: "Not placed yet.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("wall-scan-message"),
    )
    Text(
        "${record.scan.points.size} points of rock · ${record.scan.path.size} track points",
        style = MaterialTheme.typography.bodySmall,
        color = SexyTopoColours.legend,
    )
    trackingSummary(record)?.let { summary ->
        Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wall-scan-tracking"))
    }

    PlacementControls(state, record, revision)

    val touches = record.scan.touches
    if (touches.isNotEmpty()) {
        Heading(Strings.wallScanMarks)
        val residuals = result?.residuals?.toMap().orEmpty()
        for ((index, touch) in touches.withIndex()) {
            val removed = index in record.review.removedTouches
            val name = record.review.renamedTouches[index] ?: touch.stationName
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationPicker(state, name, enabled = !removed) { picked ->
                    if (picked == touch.stationName) {
                        record.review.renamedTouches.remove(index)
                    } else {
                        record.review.renamedTouches[index] = picked
                    }
                    state.rebuildWallScan(record)
                }
                Text(
                    when {
                        removed -> "not used"
                        residuals[name] != null -> "${centimetres(residuals.getValue(name))} out"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                RemoveToggle(removed) {
                    toggle(record.review.removedTouches, index)
                    state.rebuildWallScan(record)
                }
            }
        }
    }

    val lines = record.scan.penLines
    if (lines.isNotEmpty()) {
        Heading(Strings.wallScanLines)
        for ((index, line) in lines.withIndex()) {
            val removed = index in record.review.removedLines
            var amount by remember(record.id, index) {
                mutableFloatStateOf(record.review.lineSmoothing[index] ?: line.stroke.smoothing)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Line ${index + 1} · smooth ${(amount * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(150.dp),
                )
                Slider(
                    value = amount,
                    onValueChange = { amount = it },
                    // Only when the finger lifts: a rebuild walks the scan, and doing it for every
                    // pixel of a drag would stall the slider on a long one.
                    onValueChangeFinished = {
                        record.review.lineSmoothing[index] = amount
                        state.rebuildWallScan(record)
                    },
                    enabled = !removed,
                    modifier = Modifier.weight(1f).testTag("wall-scan-line-$index"),
                )
                RemoveToggle(removed) {
                    toggle(record.review.removedLines, index)
                    state.rebuildWallScan(record)
                }
            }
        }
    }

    val stamps = record.scan.stamps
    if (stamps.isNotEmpty()) {
        Heading(Strings.wallScanSymbols)
        for ((index, stamp) in stamps.withIndex()) {
            val removed = index in record.review.removedStamps
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stamp.symbol.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                RemoveToggle(removed) {
                    toggle(record.review.removedStamps, index)
                    state.rebuildWallScan(record)
                }
            }
        }
    }

    Heading(Strings.wallScanOutputs)
    val fromRock = result?.fromRockByDefault ?: false
    SwitchRow(Strings.wallScanWalls, record.review.generateWalls ?: fromRock, "wall-scan-walls") {
        record.review.generateWalls = it
        state.rebuildWallScan(record)
    }
    SwitchRow(Strings.wallScanLruds, record.review.addLruds ?: fromRock, "wall-scan-lruds") {
        record.review.addLruds = it
        state.rebuildWallScan(record)
    }
    SwitchRow(Strings.wallScanFans, record.review.splayFans, "wall-scan-fans") {
        record.review.splayFans = it
        state.rebuildWallScan(record)
    }
    SwitchRow(Strings.wallScanSections, record.review.crossSections, "wall-scan-sections") {
        record.review.crossSections = it
        state.rebuildWallScan(record)
    }

    Text(Strings.wallScanEditAfter, style = MaterialTheme.typography.bodySmall, color = SexyTopoColours.legend)

    OutlinedButton(onClick = { onDelete(record) }, modifier = Modifier.testTag("wall-scan-delete")) {
        Text(Strings.deleteWallScan)
    }
}

/**
 * Moving a scan by hand: turn it, shift it, then either keep it there or ask for a fit from
 * there. Every nudge starts from where the scan is drawn now, and the first one turns an
 * automatic placement into a hand one.
 */
@Composable
private fun PlacementControls(state: DemoState, record: WallScanRecord, revision: Int) {
    @Suppress("UNUSED_EXPRESSION")
    revision
    var step by remember { mutableFloatStateOf(0.1f) }
    val current = record.review.placement ?: record.result?.placement
    val mode = if (record.review.placement != null) PlacementMode.MANUAL else record.result?.mode

    Row(verticalAlignment = Alignment.CenterVertically) {
        Heading(Strings.wallScanPlacement)
        Spacer(Modifier.width(8.dp))
        Text(
            when (mode) {
                PlacementMode.ANCHORED -> "anchored to its marks"
                PlacementMode.FITTED -> "fitted to the centerline"
                PlacementMode.MANUAL -> "by hand"
                null -> "not placed"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }

    fun nudge(change: (ScanPlacement) -> ScanPlacement) {
        val from = current ?: startingPlacement(state, record)
        record.review.placement = change(from)
        state.rebuildWallScan(record)
    }

    val pivot = current?.let { placement -> middleOf(record)?.let { placement.place(it) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
        Text("Turn", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
        NudgeButton("-5°", "nudge-left-5") { nudge { it.turnedAbout(pivot ?: it.translation, -5f) } }
        NudgeButton("-1°", "nudge-left-1") { nudge { it.turnedAbout(pivot ?: it.translation, -1f) } }
        NudgeButton("+1°", "nudge-right-1") { nudge { it.turnedAbout(pivot ?: it.translation, 1f) } }
        NudgeButton("+5°", "nudge-right-5") { nudge { it.turnedAbout(pivot ?: it.translation, 5f) } }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
        NudgeButton("N", "nudge-north") { nudge { it.moved(Coord3D(0f, step, 0f)) } }
        NudgeButton("S", "nudge-south") { nudge { it.moved(Coord3D(0f, -step, 0f)) } }
        NudgeButton("E", "nudge-east") { nudge { it.moved(Coord3D(step, 0f, 0f)) } }
        NudgeButton("W", "nudge-west") { nudge { it.moved(Coord3D(-step, 0f, 0f)) } }
        NudgeButton("Up", "nudge-up") { nudge { it.moved(Coord3D(0f, 0f, step)) } }
        NudgeButton("Down", "nudge-down") { nudge { it.moved(Coord3D(0f, 0f, -step)) } }
        TextButton(onClick = { step = if (step < 0.5f) 1f else 0.1f }, modifier = Modifier.testTag("nudge-step")) {
            Text("${Strings.wallScanStep} ${if (step < 0.5f) "10 cm" else "1 m"}")
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                record.review.placement = null
                state.rebuildWallScan(record, refit = true)
            },
            enabled = record.review.placement != null,
            modifier = Modifier.testTag("placement-automatic"),
        ) { Text(Strings.wallScanAutomatic) }
        OutlinedButton(
            onClick = {
                record.review.placement = record.result?.placement
                state.rebuildWallScan(record)
            },
            enabled = record.review.placement == null && record.result?.placement != null,
            modifier = Modifier.testTag("placement-lock"),
        ) { Text(Strings.wallScanLock) }
        OutlinedButton(
            onClick = {
                if (WallScanApplier.refit(state.liveSurvey, record)) {
                    state.rebuildWallScan(record)
                } else {
                    state.note(Strings.wallScanNoFit)
                }
            },
            enabled = current != null,
            modifier = Modifier.testTag("placement-refit"),
        ) { Text(Strings.wallScanFitFromHere) }
    }
}

/**
 * Where a scan that could not be placed at all starts when it is first nudged: its track's start
 * on the active station, at whatever heading ARKit said.
 */
private fun startingPlacement(state: DemoState, record: WallScanRecord): ScanPlacement {
    val survey = state.liveSurvey
    val anchor = SurveyFrame(survey).stationPositions[survey.activeStation.name]
        ?: Coord3D.ORIGIN
    val start = record.scan.path.firstOrNull() ?: Coord3D.ORIGIN
    return ScanPlacement(0f, Coord3D(anchor.x - start.x, anchor.y - start.y, anchor.z - start.z))
}

/** The middle of the phone's track, in the scan's frame: what a hand turn turns about. */
private fun middleOf(record: WallScanRecord): Coord3D? {
    val path = record.scan.path.ifEmpty { record.scan.touches.map { it.inScan } }
    if (path.isEmpty()) return null
    return Coord3D(path.map { it.x }.average().toFloat(), path.map { it.y }.average().toFloat(), path.map { it.z }.average().toFloat())
}

private fun simulate(state: DemoState, markStations: Boolean) {
    val survey = state.liveSurvey
    val simulated =
        SimulatedWallScan.around(
            survey,
            survey.activeStation,
            SimulatedWallScan.Settings(markStations = markStations, seed = state.wallScans.size + 1),
        )
    if (simulated == null) {
        state.note(Strings.simulatedScanNeedsALeg)
        return
    }
    state.addWallScan(simulated.scan)
}

@Composable
private fun StationPicker(state: DemoState, name: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text(name, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (station in state.liveSurvey.getAllStationsInChronoOrder()) {
                DropdownMenuItem(
                    text = { Text(station.name) },
                    onClick = {
                        open = false
                        onPick(station.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun NudgeButton(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
}

@Composable
private fun RemoveToggle(removed: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(if (removed) Strings.wallScanRestore else Strings.wallScanRemove) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * How much of the scan was taken with tracking poor, or null when none was. Said as a share of the
 * walk because that is what a surveyor can picture: a scan a third of which was taken half-blind
 * is a scan to take again.
 */
internal fun trackingSummary(record: WallScanRecord): String? {
    val poor = record.scan.poorTracking.size
    val total = record.scan.path.size
    if (poor == 0 || total == 0) return null
    val percent = (poor * 100f / total).roundToInt()
    return "Tracking was poor for $percent% of the walk, which is left out when fitting."
}

private fun toggle(set: MutableSet<Int>, index: Int) {
    if (!set.remove(index)) set.add(index)
}

private fun centimetres(metres: Float): String = "${(metres * 100).roundToInt()} cm"

private const val SIMULATE_UNMARKED = "…with no marks"
