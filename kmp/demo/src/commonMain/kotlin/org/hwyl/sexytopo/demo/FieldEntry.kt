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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingField(
                        value = distance,
                        onValueChange = { distance = it },
                        label = "Distance (m)",
                        modifier = Modifier.weight(1f),
                    )
                    ReadingField(
                        value = azimuth,
                        onValueChange = { azimuth = it },
                        label = "Azimuth (°)",
                        modifier = Modifier.weight(1f),
                    )
                }
                ReadingField(
                    value = inclination,
                    onValueChange = { inclination = it },
                    label = "Inclination (°)",
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun ReadingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        // A decimal keypad, and one that offers a minus sign - inclination is signed and a
        // surveyor should not have to hunt for it on a phone keyboard in the wet.
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        modifier = modifier.width(140.dp),
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
