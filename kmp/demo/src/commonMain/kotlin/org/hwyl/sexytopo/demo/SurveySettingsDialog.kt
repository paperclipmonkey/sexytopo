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
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * `action_settings_survey`, which in the Android app is `openSurveySettingsDialog`: a number for
 * each of this survey's two sketches, belonging to the survey rather than to the app.
 *
 * Cross-section scale is how much bigger than life a cross-section is drawn on its sketch. Plan
 * and elevation each keep their own, since sections on the two can need drawing at different
 * sizes, and each is saved in its own sketch file, so a cave drawn at 4x opens at 4x on the other
 * app too.
 */
@Composable
fun SurveySettingsDialog(
    survey: Survey,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var planScale by remember { mutableStateOf(survey.planSketch.crossSectionScale.toString()) }
    var elevationScale by
        remember { mutableStateOf(survey.elevationSketch.crossSectionScale.toString()) }
    val planParsed = parseCrossSectionScale(planScale)
    val elevationParsed = parseCrossSectionScale(elevationScale)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.settingsSurveyTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    planScale,
                    { planScale = it },
                    Strings.settingsSurveyPlanCrossSectionScale,
                )
                NumberField(
                    elevationScale,
                    { elevationScale = it },
                    Strings.settingsSurveyElevationCrossSectionScale,
                )
                Text(
                    "How much bigger than life a cross-section is drawn on each sketch. A " +
                        "passage two metres across is a few pixels at the zoom a whole cave fits at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = planParsed != null && elevationParsed != null,
                onClick = {
                    applyCrossSectionScales(survey, planParsed, elevationParsed)
                    onSaved()
                },
            ) { Text(Strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/** A typed scale, or null if it is not a positive number. A comma is taken as the decimal point. */
internal fun parseCrossSectionScale(typed: String): Float? =
    typed.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }

/**
 * `applyCrossSectionScale`, once for each sketch: a sketch whose scale is unchanged is left
 * alone, so opening the dialog and saving it does not mark an untouched sketch as edited.
 *
 * @return whether either sketch changed.
 */
internal fun applyCrossSectionScales(survey: Survey, plan: Float?, elevation: Float?): Boolean {
    var changed = false
    fun apply(sketch: Sketch, scale: Float?) {
        if (scale == null || scale == sketch.crossSectionScale) return
        sketch.crossSectionScale = scale
        changed = true
    }
    apply(survey.planSketch, plan)
    apply(survey.elevationSketch, elevation)
    if (changed) survey.isSaved = false
    return changed
}
