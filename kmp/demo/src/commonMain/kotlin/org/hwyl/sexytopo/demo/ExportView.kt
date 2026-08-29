package org.hwyl.sexytopo.demo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.model.survey.Survey

/** The interchange formats a caver actually cares about. */
enum class ExportFormat(val label: String) {
    SURVEX("Survex .svx"),
    THERION("Therion .th"),
    NATIVE("SexyTopo JSON"),
}

/**
 * Shows what the survey exports as.
 *
 * This is the interop claim made visible: the Survex and Therion output is produced by the ported
 * emitter, whose golden tests assert it byte for byte against what the Android app writes, and the
 * JSON is the app's own native format — the same bytes the Android app would read back.
 */
@Composable
fun ExportView(survey: Survey, revision: Int, modifier: Modifier = Modifier) {
    var format by remember { mutableStateOf(ExportFormat.SURVEX) }

    val text =
        remember(survey, revision, format) {
            when (format) {
                // A fixed date keeps the output reproducible; the real app stamps today's.
                ExportFormat.SURVEX -> SurvexExporter.export(survey, createdOn = "2026-08-29")
                ExportFormat.THERION -> TherionExporter.export(survey, createdOn = "2026-08-29")
                ExportFormat.NATIVE -> SurveyJson.write(survey)
            }
        }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in ExportFormat.entries) {
                FilterChip(format == f, { format = f }, { Text(f.label) })
            }
        }

        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
        )
    }
}
