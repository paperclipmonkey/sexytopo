package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.Lrud
import org.hwyl.sexytopo.shared.survey.LrudMode
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.StationNamer
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.survey.DegreesMinutesSeconds
import org.hwyl.sexytopo.shared.survey.InputMode

/**
 * Typing a reading in by hand.
 *
 * **iOS Safari has no Web Bluetooth**, so on the platform this port is for there is no way to
 * hear from an instrument at all. The numbers go through the same [SurveyUpdater] the radio
 * would feed, so the triple-shot promotion rule applies unchanged.
 */
@Composable
fun ManualReadingDialog(
    inputMode: InputMode,
    onInputMode: (InputMode) -> Unit,
    onDismiss: () -> Unit,
    /** The reading, whether it is a splay, and the four optional passage measurements. */
    onAdd: (Leg, Boolean, List<String>) -> Unit,
    lrudFields: Boolean = false,
) {
    var distance by remember { mutableStateOf("") }
    var azimuth by remember { mutableStateOf("") }
    var inclination by remember { mutableStateOf("") }
    val lrud = remember { mutableStateListOf("", "", "", "") }

    val parsed = parseReading(distance, azimuth, inclination)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a reading") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReadingFields(
                    distance = distance,
                    onDistance = { distance = it },
                    azimuth = azimuth,
                    onAzimuth = { azimuth = it },
                    inclination = inclination,
                    onInclination = { inclination = it },
                    lastImeAction = ImeAction.Done,
                )
                // FlowRow, not Row: a Row would clip the fourth chip rather than wrap it.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (mode in OFFERED_MODES) {
                        FilterChip(
                            selected = inputMode == mode,
                            onClick = { onInputMode(mode) },
                            label = { Text(labelFor(mode)) },
                        )
                    }
                }
                if (lrudFields) {
                    HorizontalDivider()
                    Text(
                        "Passage size here, taken where you are standing. Left and right go " +
                            "square to the passage; up and down are vertical. Leave any of them " +
                            "blank.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((index, side) in Lrud.entries.withIndex()) {
                            OutlinedTextField(
                                value = lrud[index],
                                onValueChange = { lrud[index] = it },
                                label = { Text(lrudEntryLabel(side)) },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Text(
                    parsed.problem ?: promotionRuleFor(inputMode),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (parsed.problem != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = parsed.leg != null,
                    onClick = { parsed.leg?.let { onAdd(it, true, emptyList()) } },
                ) { Text("Add splay") }
                TextButton(
                    enabled = parsed.leg != null,
                    onClick = { parsed.leg?.let { onAdd(it, false, lrud.toList()) } },
                ) { Text("Add leg") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What a hand-typed reading does to the survey.
 *
 * [measuredFrom] is read *before* the leg goes in: a reading that promotes moves the active
 * station to the far end of the shot, so a passage size read afterwards would attach to the
 * wrong station. Upstream does the same thing, shuffling the active station around its LRUD
 * calls.
 *
 * @return how many passage splays were added.
 */
internal fun addTypedReading(
    survey: Survey,
    leg: Leg,
    asSplay: Boolean,
    lrud: List<String>,
    inputMode: InputMode,
    settings: SurveySettings,
    lrudMode: LrudMode = LrudMode.DEFAULT,
    onStationCreated: () -> Unit = {},
): Int {
    val measuredFrom = survey.activeStation
    if (asSplay) {
        SurveyBuilder.addSplay(survey, survey.activeStation, leg)
    } else if (SurveyUpdater.update(survey, leg, inputMode, settings)) {
        onStationCreated()
    }
    return addLruds(survey, measuredFrom, lrud, lrudMode)
}

/**
 * *Add a leg* and *Add a splay*, from the Tools menu — distinct from *Add a reading* on the
 * field bar, which holds a typed reading to the instrument's rules. These write down what a
 * surveyor already knows: a leg from a paper book, or a join onto a station somebody else
 * surveyed.
 */
@Composable
fun AddLegDialog(
    survey: Survey,
    asSplay: Boolean,
    onDismiss: () -> Unit,
    onAdd: (AddedLeg) -> Unit,
    lrudFields: Boolean = false,
) {
    var distance by remember { mutableStateOf("") }
    var azimuth by remember { mutableStateOf("") }
    var inclination by remember { mutableStateOf("") }
    var toName by remember {
        mutableStateOf(
            if (asSplay) "" else StationNamer.generateNextStationName(survey, survey.activeStation),
        )
    }
    var toComment by remember { mutableStateOf("") }
    // `editFromStation`, pre-filled with the active station as the Android form pre-fills it.
    var fromName by remember { mutableStateOf(survey.activeStation.name) }
    var legComment by remember { mutableStateOf("") }
    val lrud = remember { mutableStateListOf("", "", "", "") }

    val parsed = parseReading(distance, azimuth, inclination)
    // Refused here rather than at export time: a station quietly renamed on the way out is one
    // nobody can match back to their notes.
    val nameProblem = if (asSplay) null else newStationNameProblem(toName)
    // `manual_edit_from_station_error`: the leg has to hang off a station that exists.
    val fromProblem =
        if (survey.getStationByName(fromName.trim()) == null) {
            Strings.manualEditFromStationError
        } else {
            null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        // `manual_add_station_title` / `manual_add_splay_title`, as `LegDialogs` titles them.
        title = {
            Text(
                if (asSplay) Strings.manualAddSplayTitle else Strings.manualAddStationTitle,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (asSplay) {
                        "As written down. No repeats and no agreement needed."
                    } else {
                        "As written down. The station is made straight away — no repeats and no " +
                            "agreement needed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // `editFromStation`, which the Android form puts above the reading: the leg does
                // not have to hang off the active station, and a party booking a side passage
                // from a junction they have already passed needs to say so.
                OutlinedTextField(
                    value = fromName,
                    onValueChange = { fromName = it },
                    label = { Text(Strings.manualEditFromStation) },
                    singleLine = true,
                    isError = fromProblem != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                ReadingFields(
                    distance = distance,
                    onDistance = { distance = it },
                    azimuth = azimuth,
                    onAzimuth = { azimuth = it },
                    inclination = inclination,
                    onInclination = { inclination = it },
                    lastImeAction = ImeAction.Next,
                )
                if (!asSplay) {
                    OutlinedTextField(
                        value = toName,
                        onValueChange = { toName = it },
                        label = { Text(Strings.manualEditToStation) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = toComment,
                        onValueChange = { toComment = it },
                        label = { Text(Strings.manualEditStationComment) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // `editLegComment`.
                OutlinedTextField(
                    value = legComment,
                    onValueChange = { legComment = it },
                    label = {
                        Text(
                            if (asSplay) {
                                Strings.menuCommentSplay
                            } else {
                                Strings.manualEditLegComment
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (lrudFields && !asSplay) {
                    HorizontalDivider()
                    Text(
                        "Passage size at ${fromName.trim()}, where you are standing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((index, side) in Lrud.entries.withIndex()) {
                            OutlinedTextField(
                                value = lrud[index],
                                onValueChange = { lrud[index] = it },
                                label = { Text(lrudEntryLabel(side)) },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                (parsed.problem ?: nameProblem ?: fromProblem)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.leg != null && nameProblem == null && fromProblem == null,
                onClick = {
                    parsed.leg?.let {
                        onAdd(
                            AddedLeg(it, toName, toComment, lrud.toList(), fromName, legComment),
                        )
                    }
                },
            ) { Text(Strings.add) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/**
 * Everything the add-a-leg form collected, which is more than three numbers now that it carries
 * `leg_edit_dialog_unified.xml`'s station and comment fields too.
 */
data class AddedLeg(
    val leg: Leg,
    val toName: String,
    val toComment: String,
    val lrud: List<String>,
    val fromName: String,
    val legComment: String,
)

/**
 * `manual_edit_left` and its three siblings, as the Android form labels its four LRUD fields.
 */
internal fun lrudEntryLabel(side: Lrud): String =
    when (side) {
        Lrud.LEFT -> Strings.manualEditLeft
        Lrud.RIGHT -> Strings.manualEditRight
        Lrud.UP -> Strings.manualEditUp
        Lrud.DOWN -> Strings.manualEditDown
    }

/**
 * Adds a leg outright, making the station at the far end of it.
 *
 * Different from [addTypedReading]: that one behaves like the instrument (three agreeing
 * readings make a station); this makes one immediately, with the name already known.
 */
internal fun addLegOutright(
    survey: Survey,
    leg: Leg,
    asSplay: Boolean,
    toName: String = "",
    toComment: String = "",
    lrud: List<String> = emptyList(),
    lrudMode: LrudMode = LrudMode.DEFAULT,
    /**
     * `editFromStation`: which station the leg hangs off. Blank, or a name no station has, means
     * the active one — which is where the Android form's field is pre-filled from.
     */
    fromName: String = "",
    /** `editLegComment`, written onto the leg itself. */
    legComment: String = "",
): Int {
    val from = survey.getStationByName(fromName.trim()) ?: survey.activeStation
    if (legComment.isNotBlank()) leg.comment = legComment.trim()
    if (asSplay) {
        SurveyBuilder.addSplay(survey, from, leg)
    } else {
        // Sanitised as `applyStationEdit` does: a name differing from an existing one only by a
        // newline would otherwise pass the uniqueness check below and collide once stored.
        val wanted = sanitiseStationName(toName)
        val name =
            if (wanted.isEmpty()) {
                StationNamer.generateNextStationName(survey, from)
            } else {
                StationNamer.advanceNumberIfNotUnique(survey, wanted)
            }
        val station = Station(name)
        if (toComment.isNotBlank()) station.comment = toComment.trim()
        SurveyBuilder.addLegFromStation(survey, from, Leg.upgradeSplayToConnectedLeg(leg, station))
    }
    return addLruds(survey, from, lrud, lrudMode)
}

/** Which of the field bar's two buttons are on it. */
internal data class FieldControls(
    val manualEntry: Boolean,
    /**
     * *Simulate*: gated behind `pref_developer_mode`, the same way Android gates its own
     * test-instrument menu item. Safari has no Web Bluetooth at all, so this is the only way
     * anybody sees instrument-driven surveying work there.
     */
    val simulator: Boolean,
) {
    companion object {
        fun of(preferences: AppPreferences, attachedInstrument: InstrumentProfile?) =
            FieldControls(
                manualEntry = preferences.manualControls,
                // `SurveySession.takeReading` detaches whatever is attached and emits a
                // fabricated shot — pressed with a real instrument connected, that silently
                // disconnects it and adds a made-up leg indistinguishable from a real one.
                simulator = attachedInstrument == null && preferences.developerMode,
            )
    }
}

/**
 * How the surveyor wants angles typed. A composition local rather than a parameter threaded
 * down four dialogs deep, none of which is otherwise about angles.
 */
val LocalAngleEntry = compositionLocalOf { AngleEntry() }

data class AngleEntry(
    val azimuthInDms: Boolean = false,
    val inclinationInDms: Boolean = false,
)

/**
 * The three numbers that make a shot, laid out the way a phone wants them.
 *
 * Inclination is signed, and **no mobile numeric keypad offers a minus sign** — iOS `decimalPad`
 * has digits and a decimal point and nothing else. Without the +/- button beside the field, every
 * downward shot would be untypable. [KeyboardType.Decimal] rather than [KeyboardType.Number] for
 * the same reason: `Number` maps to iOS `numberPad`, which has no decimal point either.
 */
/** What the three reading boxes answer to, wherever a dialog lays them out. */
const val READING_DISTANCE: String = "reading-distance"
const val READING_AZIMUTH: String = "reading-azimuth"
const val READING_INCLINATION: String = "reading-inclination"

@Composable
fun ReadingFields(
    distance: String,
    onDistance: (String) -> Unit,
    azimuth: String,
    onAzimuth: (String) -> Unit,
    inclination: String,
    onInclination: (String) -> Unit,
    lastImeAction: ImeAction = ImeAction.Done,
) {
    val angleEntry = LocalAngleEntry.current
    val azimuthInDms = angleEntry.azimuthInDms
    val inclinationInDms = angleEntry.inclinationInDms

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (azimuthInDms) {
            ReadingField(distance, onDistance, Strings.manualEditDistance, tag = READING_DISTANCE)
            DmsFields(Strings.manualEditAzimuthDms, azimuth, onAzimuth, signed = false)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingField(
                    distance,
                    onDistance,
                    Strings.manualEditDistance,
                    tag = READING_DISTANCE,
                )
                ReadingField(azimuth, onAzimuth, Strings.manualEditAzimuth, tag = READING_AZIMUTH)
            }
        }
        if (inclinationInDms) {
            DmsFields(
                Strings.manualEditInclinationDms,
                inclination,
                onInclination,
                signed = true,
                lastImeAction,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReadingField(
                    inclination,
                    onInclination,
                    Strings.manualEditInclination,
                    lastImeAction,
                    tag = READING_INCLINATION,
                )
                OutlinedButton(onClick = { onInclination(withSignFlipped(inclination)) }) {
                    Text("+/-")
                }
            }
        }
    }
}

/**
 * One angle as three boxes, reporting a decimal upward. Seeded from whatever came in and never
 * re-seeded, since only this composable ever writes the incoming value. The +/- button flips the
 * *degrees* box textually, so `-0` survives and 0° 30′ down is enterable at all.
 */
private val DMS_FIELD_WIDTH = 74.dp

@Composable
private fun DmsFields(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    signed: Boolean,
    lastImeAction: ImeAction = ImeAction.Done,
) {
    val initial = remember(Unit) { value.trim().toFloatOrNull()?.let(DegreesMinutesSeconds::of) }
    var degrees by remember { mutableStateOf(initial?.degreesText ?: "") }
    var minutes by remember { mutableStateOf(initial?.minutesText ?: "") }
    var seconds by remember { mutableStateOf(initial?.secondsText ?: "") }

    fun report() {
        val decimal = DegreesMinutesSeconds.toDecimal(degrees, minutes, seconds)
        onValue(decimal?.toString() ?: "")
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // `FlowRow`, not `Row`: a `Row` clips what doesn't fit rather than wrapping it. Measured
        // on a 420-pixel screen where the card's content is 270 wide: three 74dp boxes and two
        // gaps fit, and the button wraps below if anything stops fitting.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ReadingField(degrees, { degrees = it; report() }, "\u00b0", width = DMS_FIELD_WIDTH)
            ReadingField(minutes, { minutes = it; report() }, "\u2032", width = DMS_FIELD_WIDTH)
            ReadingField(
                seconds,
                { seconds = it; report() },
                "\u2033",
                if (signed) ImeAction.Next else lastImeAction,
                width = DMS_FIELD_WIDTH,
            )
            if (signed) {
                OutlinedButton(onClick = { degrees = withSignFlipped(degrees); report() }) {
                    Text("+/-")
                }
            }
        }
    }
}

/**
 * Textual rather than numeric on purpose: round-tripping "4.20" through a Float to negate it
 * would rewrite it as "-4.2" under the surveyor's cursor.
 */
fun withSignFlipped(value: String): String {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> "-"
        trimmed == "-" -> ""
        trimmed.startsWith("-") -> trimmed.removePrefix("-")
        trimmed.startsWith("+") -> "-" + trimmed.removePrefix("+")
        else -> "-$trimmed"
    }
}

@Composable
private fun ReadingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction = ImeAction.Next,
    width: Dp = 140.dp,
    /**
     * A name for the box, for a screen reader and for anything driving the app through one. The
     * label is drawn beside it and read out already; this is what survives the label being
     * reworded, and what tells the three boxes apart wherever they are laid out.
     */
    tag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = Modifier.width(width).then(if (tag == null) Modifier else Modifier.testTag(tag)),
    )
}

/**
 * `strings.xml` calls `CALIBRATION_CHECK` **Splays Only**: it stops readings promoting to
 * stations at all, which is an ordinary thing to want with no instrument in sight.
 */
val OFFERED_MODES =
    listOf(InputMode.FORWARD, InputMode.BACKWARD, InputMode.COMBO, InputMode.CALIBRATION_CHECK)

/** `input_mode_group`'s own four labels, shown on the field bar and in the overflow menu alike. */
fun labelFor(mode: InputMode): String =
    when (mode) {
        InputMode.FORWARD -> Strings.actionInputModeForward
        InputMode.BACKWARD -> Strings.actionInputModeBackward
        InputMode.COMBO -> Strings.actionInputModeCombo
        InputMode.CALIBRATION_CHECK -> Strings.actionInputModeCalCheck
    }

fun promotionRuleFor(mode: InputMode): String =
    when (mode) {
        InputMode.FORWARD ->
            "Three agreeing readings make a station. A single one is kept as a splay."
        InputMode.BACKWARD ->
            "Shots taken from the far station, looking back. Three agreeing ones make a station."
        InputMode.COMBO ->
            "A foresight then a backsight down the same leg makes a station; so do three repeats."
        InputMode.CALIBRATION_CHECK ->
            "Every reading is kept as a splay. Nothing promotes to a station in this mode."
    }

data class ParsedReading(val leg: Leg?, val problem: String?)

/**
 * Turns three typed strings into a [Leg], refusing anything [Leg] itself would refuse.
 *
 * The bounds are asked of `Leg.isDistanceLegal` and its two siblings rather than restated here,
 * which they had been — and the restatement was wrong three ways. It refused a distance of zero,
 * which the model allows; it refused the theodolite inclinations of 270 to 360 that
 * `isInclinationLegal` accepts, so a survey booked with a theodolite could not be typed in; and it
 * *accepted* an azimuth of exactly 360, which `Leg` rejects — so typing it threw out of the
 * dialog's own composition. A reading a surveyor can write down is a reading this app can take.
 *
 * Commas are accepted as decimal points, since a phone keyboard in a European locale offers one.
 */
fun parseReading(distance: String, azimuth: String, inclination: String): ParsedReading {
    if (distance.isBlank() || azimuth.isBlank() || inclination.isBlank()) {
        return ParsedReading(null, null)
    }
    val d = distance.trim().replace(',', '.').toFloatOrNull()
    val a = azimuth.trim().replace(',', '.').toFloatOrNull()
    val i = inclination.trim().replace(',', '.').toFloatOrNull()

    return when {
        d == null || a == null || i == null -> ParsedReading(null, "Numbers only")
        !Leg.isDistanceLegal(d) -> ParsedReading(null, Strings.manualEditDistanceError)
        !Leg.isAzimuthLegal(a) -> ParsedReading(null, Strings.manualEditAzimuthError)
        !Leg.isInclinationLegal(i) -> ParsedReading(null, Strings.manualEditInclinationError)
        else -> ParsedReading(Leg(d, a, i), null)
    }
}
