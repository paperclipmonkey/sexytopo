package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.survey.Leg

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
    onDismiss: () -> Unit,
    onAdd: (Leg, Boolean) -> Unit,
) {
    var distance by remember { mutableStateOf("") }
    var azimuth by remember { mutableStateOf("") }
    var inclination by remember { mutableStateOf("") }

    val parsed = parseReading(distance, azimuth, inclination)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a reading") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadingFields(
                    distance = distance,
                    onDistance = { distance = it },
                    azimuth = azimuth,
                    onAzimuth = { azimuth = it },
                    inclination = inclination,
                    onInclination = { inclination = it },
                    lastImeAction = ImeAction.Done,
                )
                Text(
                    parsed.problem
                        ?: "Three agreeing readings make a station. A single one is kept as a splay.",
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
                    onClick = { parsed.leg?.let { onAdd(it, true) } },
                ) { Text("Add splay") }
                TextButton(
                    enabled = parsed.leg != null,
                    onClick = { parsed.leg?.let { onAdd(it, false) } },
                ) { Text("Add leg") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingField(distance, onDistance, "Distance (m)")
            ReadingField(azimuth, onAzimuth, "Azimuth (\u00b0)")
        }
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = Modifier.width(140.dp),
    )
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
