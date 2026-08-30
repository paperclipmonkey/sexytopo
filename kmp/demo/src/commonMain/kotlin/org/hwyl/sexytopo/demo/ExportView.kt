package org.hwyl.sexytopo.demo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.hwyl.sexytopo.shared.io.export.CompassExporter
import org.hwyl.sexytopo.shared.io.export.PocketTopoExporter
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The interchange formats a caver actually cares about.
 *
 * [extension] is not decoration: Survex will not open a file that is not named `.svx`, and a
 * surveyor who has to rename four downloads on a laptop after every trip will stop using the app.
 */
enum class ExportFormat(val label: String, val extension: String) {
    SURVEX("Survex .svx", "svx"),
    THERION("Therion .th", "th"),
    SVG("Drawing .svg", "svg"),
    XVI("Tracing .xvi", "xvi"),
    COMPASS("Compass .dat", "dat"),
    POCKET_TOPO("PocketTopo .txt", "txt"),
    NATIVE("SexyTopo JSON", "data.json"),
}

/**
 * Shows what the survey exports as.
 *
 * This is the interop claim made visible: the Survex and Therion output is produced by the ported
 * emitter, whose golden tests assert it byte for byte against what the Android app writes, and the
 * JSON is the app's own native format — the same bytes the Android app would read back.
 */
@Composable
fun ExportView(
    survey: Survey,
    revision: Int,
    modifier: Modifier = Modifier,
    projection: Projection2D = Projection2D.PLAN,
) {
    var format by remember { mutableStateOf(ExportFormat.SURVEX) }
    var copied by remember(format) { mutableStateOf(false) }
    var savedTo by remember(format) { mutableStateOf<String?>(null) }
    var saveFailed by remember(format) { mutableStateOf(false) }

    // Today, from the device's own clock, because these files date the trip. The shared exporters
    // take the date as a parameter rather than reading a clock - that is what makes their golden
    // tests possible - so this is where it has to come from.
    val today = remember(survey, revision) { todayIso() }

    val text =
        remember(survey, revision, format, today, projection) {
            when (format) {
                ExportFormat.SURVEX -> SurvexExporter.export(survey, createdOn = today)
                ExportFormat.THERION -> TherionExporter.export(survey, createdOn = today)
                // The only export that is a picture. It follows the view the surveyor is looking
                // at, so exporting from the extended elevation gives the elevation drawing.
                ExportFormat.SVG -> SvgExporter.export(survey, projection)
                ExportFormat.COMPASS ->
                    CompassExporter.export(survey, fallbackDate = SurveyDate.parseOrNull(today))
                // Therion's own background-image format: the drawing as line segments, to trace
                // over in xtherion. The scale and frame are the SVG exporter's, so the two files
                // describe the same drawing at the same size.
                ExportFormat.XVI ->
                    XviExporter.export(
                        sketch = survey.getSketch(projection),
                        space = projection.project(survey),
                        scale = SvgExporter.SCALE.toFloat(),
                        gridFrame =
                            SvgExporter.addBorder(SvgExporter.exportFrame(survey, projection))
                                .scale(SvgExporter.SCALE.toFloat()),
                    )

                ExportFormat.POCKET_TOPO -> PocketTopoExporter.export(survey)
                ExportFormat.NATIVE -> SurveyJson.write(survey)
            }
        }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in ExportFormat.entries) {
                FilterChip(format == f, { format = f }, { Text(f.label) })
            }
        }

        // Getting off the phone. A survey that cannot leave the device it was recorded on is a
        // survey that has to be typed up again from a photograph of a screen. Two ways, because
        // neither covers everything: the clipboard reaches an email or a notes app, and only a
        // file reaches Therion.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { copied = copyToClipboard(text) }) {
                Text(if (copied) "Copied" else "Copy")
            }
            TextButton(
                onClick = {
                    val where = saveTextFile(fileNameFor(survey, format), text)
                    savedTo = where
                    saveFailed = where == null
                },
            ) { Text("Save file") }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    saveFailed -> "Could not save a file here"
                    savedTo != null -> "Saved to $savedTo"
                    else -> fileNameFor(survey, format)
                },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (saveFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        // Why the buttons above are worth pressing rather than trusting the app to remember.
        //
        // Below them rather than above, deliberately: it is a footnote to the way out, not a
        // banner over it - and it must not push the controls down, since it appears only on the
        // platform where they are most needed. Null on every native platform, where a saved file
        // stays saved and there is nothing to warn about.
        durabilityWarning()?.let { warning ->
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
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

/**
 * What the file is called.
 *
 * The survey's own name, which [Survey] has already stripped of path separators, so this cannot
 * escape the directory it is written into however the survey was named.
 */
internal fun fileNameFor(survey: Survey, format: ExportFormat): String =
    "${survey.name}.${format.extension}"
