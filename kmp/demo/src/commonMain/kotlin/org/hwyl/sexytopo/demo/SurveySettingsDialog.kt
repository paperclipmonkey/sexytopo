package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.sketch.Sketch

/**
 * `action_settings_survey`, which in the Android app is `openSurveySettingsDialog`: one number,
 * belonging to this survey's plan rather than to the app.
 *
 * Cross-section scale is how much bigger than life a cross-section is drawn on the plan. It is a
 * property of the sketch, saved with the survey, so a cave drawn at 4x opens at 4x on the other
 * app too.
 */
@Composable
fun SurveySettingsDialog(
    sketch: Sketch,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var scale by remember { mutableStateOf(sketch.crossSectionScale.toString()) }
    val parsed = scale.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.settingsSurveyTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(scale, { scale = it }, Strings.settingsSurveyCrossSectionScale)
                Text(
                    "How much bigger than life a cross-section is drawn on the plan. A passage " +
                        "two metres across is a few pixels at the zoom a whole cave fits at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    parsed?.let { sketch.crossSectionScale = it }
                    onSaved()
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
