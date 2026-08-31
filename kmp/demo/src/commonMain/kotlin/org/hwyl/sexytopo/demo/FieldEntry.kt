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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.Lrud
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.survey.DegreesMinutesSeconds
import org.hwyl.sexytopo.shared.survey.InputMode

/**
 * Typing a reading in by hand.
 *
 * This exists because of a hard platform limit rather than a design preference: **iOS Safari has no
 * Web Bluetooth**, and the browser build is the only one a caver can install on an iPhone today. So
 * on the platform this port is for, there is no way to hear from an instrument at all, and a
 * surveyor who wants to use it in a cave has to be able to read the DistoX display and type what it
 * says.
 *
 * It is not a downgrade from the Bluetooth path. The numbers go through exactly the same
 * [org.hwyl.sexytopo.shared.survey.SurveyUpdater] the radio would feed, so the triple-shot
 * promotion rule applies unchanged: enter the same leg three times within tolerance and it becomes
 * a station, just as it does underground with a real instrument.
 */
@Composable
fun ManualReadingDialog(
    inputMode: InputMode,
    onInputMode: (InputMode) -> Unit,
    onDismiss: () -> Unit,
    /** The reading, whether it is a splay, and the four optional passage measurements. */
    onAdd: (Leg, Boolean, List<String>) -> Unit,
    /** `pref_lrud_fields`: offer the passage size here rather than in a second dialog. */
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
            // Same reason as the station dialog: three numeric fields, the input-mode buttons and
            // a line of explanation, opened with a keypad covering the bottom of the screen.
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
                // FlowRow and not Row. Three chips filled a phone-width dialog exactly, and a
                // Row clips what does not fit rather than wrapping it — so the fourth mode would
                // have been off the edge of the card with nothing to say it was there. That is
                // findings 30 and 34 a third time: a container that cannot grow, given another row.
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
                // `pref_lrud_fields`, off by default as upstream has it. On, the passage size
                // is booked in the same dialog as the leg — which for a compass-and-tape survey
                // is the whole workflow: stand at the station, measure the four walls, shoot on.
                // Doing it in two dialogs means going back to a station you have already left.
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
                                label = { Text(side.name.take(1)) },
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
                // A splay takes no passage size with it, as upstream has it: `addSplay` goes
                // through the dialog that has no LRUD fields at all. A splay *is* a wall
                // measurement, so booking four more against it would be recording the same thing
                // twice from the same place.
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
 * Lifted out of the field bar's `onAdd` lambda for the reason the port keeps rediscovering: a rule
 * that lives inside a composable is a rule nothing can test. And there is a rule in here worth
 * testing, because it is the kind that is wrong silently.
 *
 * ## Which station the passage was measured at
 *
 * [measuredFrom] is read *before* the leg goes in. A reading that promotes moves the active
 * station to the far end of the shot, so a passage size read afterwards would be attached to the
 * station the surveyor has just created and not the one they are standing at — putting the walls
 * of this chamber around the next one. Nothing in the numbers afterwards says so: they are
 * ordinary splays either way, on a station that really exists, at a bearing that really was
 * measured. It would come out as a drawing that is subtly the wrong shape.
 *
 * Upstream does the same thing more visibly, shuffling `survey.setActiveStation` back to the
 * from-station around its own LRUD calls and forward again afterwards.
 *
 * @return how many passage splays were added, which is what a test can assert on.
 */
internal fun addTypedReading(
    survey: Survey,
    leg: Leg,
    asSplay: Boolean,
    lrud: List<String>,
    inputMode: InputMode,
    settings: SurveySettings,
    onStationCreated: () -> Unit = {},
): Int {
    val measuredFrom = survey.activeStation
    if (asSplay) {
        // A splay is wall detail, taken where you stand. There is no far end to have stood at, so
        // the input mode does not apply to one.
        SurveyBuilder.addSplay(survey, survey.activeStation, leg)
    } else if (SurveyUpdater.update(survey, leg, inputMode, settings)) {
        onStationCreated()
    }
    return addLruds(survey, measuredFrom, lrud)
}

/**
 * Which of the field bar's two buttons are on it.
 *
 * A value with a function rather than two conditions written inline, because one of them is a
 * data-safety rule and the other is a preference, and a rule that lives inside a composable is a
 * rule nothing can test.
 */
internal data class FieldControls(
    /** *Add reading*: `pref_manual_controls`, which the Android app applies to its own two FABs. */
    val manualEntry: Boolean,
    /** *Simulate*: this port's own, and never over a real instrument. See finding 58. */
    val simulator: Boolean,
) {
    companion object {
        fun of(preferences: AppPreferences, attachedInstrument: InstrumentProfile?) =
            FieldControls(
                manualEntry = preferences.manualControls,
                // The whole of the rule, and the reason for it: `SurveySession.takeReading`
                // *detaches whatever is attached* and emits a fabricated shot into the live
                // survey. Pressed with a BRIC on the tripod that is two harms at once — a made-up
                // leg indistinguishable from a real one afterwards, and an instrument silently
                // disconnected while the surveyor goes on shooting. The button exists to show the
                // app working with no instrument in the room, so it belongs only there.
                simulator = attachedInstrument == null,
            )
    }
}

/**
 * How the surveyor wants angles typed: `pref_deg_mins_secs` and `pref_inc_deg_mins_secs`.
 *
 * A composition local rather than a parameter threaded down. The reading fields are reached
 * through four layers of dialog — the table row's action menu, the leg dialog, the edit dialog —
 * and none of those layers is about angles; adding a parameter to each would be four signatures
 * whose only job is to carry a value past. The alternative shape, a defaulted parameter at the
 * bottom, is the one finding 53 is about.
 *
 * Provided once, in [App], from the saved preferences. The default here is decimal, which is what
 * a test rendering these fields on their own should get.
 */
val LocalAngleEntry = compositionLocalOf { AngleEntry() }

/** The two preferences, as one value to provide. */
data class AngleEntry(
    val azimuthInDms: Boolean = false,
    val inclinationInDms: Boolean = false,
)

/**
 * The three numbers that make a shot, laid out the way a phone wants them.
 *
 * Shared by the add and edit dialogs so a fix to either reaches both — and there is one fix here
 * that matters more than it looks. Inclination is signed, and **no mobile numeric keypad offers a
 * minus sign**: iOS `decimalPad` has digits and a decimal point and nothing else, and Android's
 * numeric IME is no better. Without the +/- button beside the field, half of every survey — every
 * downward shot — would be untypable on the phone this port exists for.
 *
 * [KeyboardType.Decimal] rather than [KeyboardType.Number] for the same class of reason: `Number`
 * maps to iOS `numberPad`, which has no decimal point either, so `4.2` could not be entered.
 */
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
    // `pref_deg_mins_secs` and `pref_inc_deg_mins_secs`, which are two preferences upstream and
    // stay two here: a sighting compass is graduated in minutes and a clinometer often is not.
    val angleEntry = LocalAngleEntry.current
    val azimuthInDms = angleEntry.azimuthInDms
    val inclinationInDms = angleEntry.inclinationInDms

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (azimuthInDms) {
            ReadingField(distance, onDistance, "Distance (m)")
            DmsFields("Azimuth", azimuth, onAzimuth, signed = false)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingField(distance, onDistance, "Distance (m)")
                ReadingField(azimuth, onAzimuth, "Azimuth (\u00b0)")
            }
        }
        if (inclinationInDms) {
            DmsFields("Inclination", inclination, onInclination, signed = true, lastImeAction)
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReadingField(inclination, onInclination, "Inclination (\u00b0)", lastImeAction)
                OutlinedButton(onClick = { onInclination(withSignFlipped(inclination)) }) {
                    Text("+/-")
                }
            }
        }
    }
}

/**
 * One angle as three boxes, reporting a decimal upward.
 *
 * The three boxes are this composable's own state and the decimal is what leaves it, so the two
 * dialogs above go on holding one string per reading and parsing it the one way. What a surveyor
 * types and what the model stores are different shapes, and the conversion belongs at the edge.
 *
 * Seeded from whatever came in — blank for a new reading, the existing angle for an edit — and
 * never re-seeded, because the only thing that writes the incoming value is this composable.
 *
 * The +/- button is here for the same reason it is on the decimal field: **no mobile numeric
 * keypad has a minus key**. It flips the *degrees* box, which is where the direction lives, and it
 * does so textually — so `-0` survives, and 0° 30′ down is enterable at all. See finding 54.
 */
/** Narrow enough that three fit across a phone card with the gaps between them. */
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
            "$label (\u00b0 \u2032 \u2033)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // `FlowRow` and not `Row`, and the boxes are narrow. Three 140dp fields and a button are
        // wider than a phone card, and a `Row` **clips** what does not fit rather than wrapping it
        // — so the seconds box and the +/- button were simply not on screen, with nothing to say
        // they existed. That is findings 30 and 34 for the third time in this file: a container
        // that cannot grow, given another item. Measured on a 420-pixel screen, where the card's
        // content is 270 wide: three 74dp boxes and two gaps fit, and the button wraps below them
        // if anything ever stops fitting.
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
 * Flips the sign of a partly-typed number, leaving anything unparseable alone.
 *
 * Textual rather than numeric on purpose: the field is a string mid-edit, and round-tripping
 * "4.20" through a Float to negate it would rewrite it as "-4.2" under the surveyor's cursor.
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
    /** Two of these fit a phone card side by side; three of the degrees-and-minutes ones do. */
    width: Dp = 140.dp,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = Modifier.width(width),
    )
}

/**
 * All four of `action_bar.xml`'s input modes.
 *
 * `CALIBRATION_CHECK` was left out of this port for a while, on the reading that it exists to hold
 * readings taken against a known baseline and so is useless on a build that cannot talk to a
 * DistoX. The app's own label says otherwise: `strings.xml` calls it **Splays Only**, and what it
 * does is stop readings promoting to stations at all. That is an ordinary thing to want with no
 * instrument in sight — a run of splays round a chamber, where three that happen to agree would
 * otherwise plant a station in the middle of the floor.
 */
val OFFERED_MODES =
    listOf(InputMode.FORWARD, InputMode.BACKWARD, InputMode.COMBO, InputMode.CALIBRATION_CHECK)

fun labelFor(mode: InputMode): String =
    when (mode) {
        InputMode.FORWARD -> "Forward"
        InputMode.BACKWARD -> "Backsight"
        InputMode.COMBO -> "Fore + back"
        InputMode.CALIBRATION_CHECK -> "Splays only"
    }

/** What it takes to make a station in this mode, which is the thing a surveyor needs to know. */
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

/** A reading, or the reason it is not one yet. */
data class ParsedReading(val leg: Leg?, val problem: String?)

/**
 * Turns three typed strings into a [Leg], refusing anything a real instrument could not produce.
 *
 * The bounds are the model's own: [Leg] rejects a negative distance, an azimuth outside 0-360 and
 * an inclination outside ±90 by throwing, and a dialog that let a surveyor type "400" and then
 * crashed would be worse than one that says so. Commas are accepted as decimal points, because a
 * phone keyboard in a European locale offers one and a surveyor should not have to notice.
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
        d <= 0f -> ParsedReading(null, "Distance must be more than zero")
        a < 0f || a > 360f -> ParsedReading(null, "Azimuth is 0 to 360")
        i < -90f || i > 90f -> ParsedReading(null, "Inclination is -90 to 90")
        else -> ParsedReading(Leg(d, a, i), null)
    }
}
