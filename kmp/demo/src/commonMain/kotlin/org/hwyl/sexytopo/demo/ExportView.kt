package org.hwyl.sexytopo.demo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.io.MetadataJson
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.store.SurveyZip
import org.hwyl.sexytopo.shared.io.export.CompassExporter
import org.hwyl.sexytopo.shared.io.export.PocketTopoExporter
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.Th2Exporter
import org.hwyl.sexytopo.shared.io.export.ThconfigExporter
import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.io.store.SurveyFileType
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The interchange formats a caver actually cares about. [extension] is not decoration: Survex
 * will not open a file that is not named `.svx`.
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    /**
     * Whether there is one of these file per *projection* rather than one per survey — the plan
     * and the extended elevation are different drawings, named `Name.plan.th2` / `Name.ee.th2`.
     */
    val perProjection: Boolean = false,
    /** Whether this is one of the files a Therion project is made of. */
    val isTherion: Boolean = false,
) {
    SURVEX("Survex .svx", "svx"),
    THERION("Therion .th", "th", isTherion = true),
    THCONFIG("Therion .thconfig", "thconfig", isTherion = true),
    SVG("Drawing .svg", "svg", perProjection = true),
    XVI("Tracing .xvi", "xvi", perProjection = true, isTherion = true),
    TH2("Therion .th2", "th2", perProjection = true, isTherion = true),
    COMPASS("Compass .dat", "dat"),
    POCKET_TOPO("PocketTopo .txt", "txt"),
    NATIVE("SexyTopo JSON", "data.json"),
}

/** `PLAN_SUFFIX` and `EE_SUFFIX`, which is what goes in a filename before the extension. */
internal val Projection2D.fileSuffix: String
    get() = if (this == Projection2D.PLAN) "plan" else "ee"

/**
 * Shows what the survey exports as: the Survex and Therion output is produced by the ported
 * emitter, whose golden tests assert it byte for byte against what the Android app writes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportView(
    survey: Survey,
    revision: Int,
    modifier: Modifier = Modifier,
    projection: Projection2D = Projection2D.PLAN,
    svgOptions: SvgExporter.Options = SvgExporter.Options.DEFAULT,
    onSvgOptionsChange: (SvgExporter.Options) -> Unit = {},
    therionOptions: TherionExport = TherionExport.DEFAULT,
    onTherionOptionsChange: (TherionExport) -> Unit = {},
) {
    var format by remember { mutableStateOf(ExportFormat.SURVEX) }
    var copied by remember(format) { mutableStateOf(false) }
    var savedTo by remember(format) { mutableStateOf<String?>(null) }
    var saveFailed by remember(format) { mutableStateOf(false) }
    // Not keyed on the chips, unlike the three above: the zip is the whole survey either way.
    var sharedTo by remember { mutableStateOf<String?>(null) }
    var shareFailed by remember { mutableStateOf(false) }
    var choosingSvgOptions by remember { mutableStateOf(false) }
    var choosingTherionOptions by remember { mutableStateOf(false) }

    // The shared exporters take the date as a parameter, which is what makes their golden tests
    // possible.
    val today = remember(survey, revision) { todayIso() }

    val text =
        remember(survey, revision, format, today, projection, svgOptions, therionOptions) {
            exportText(survey, format, projection, today, svgOptions, therionOptions)
        }

    // Remembered rather than recomputed where it is used: writing a Therion project means
    // emitting five more files, and the button's own label asks whether there are any.
    val companions =
        remember(survey, revision, format, today, projection, therionOptions) {
            companionFiles(survey, format, projection, today, therionOptions)
        }

    if (choosingTherionOptions) {
        TherionExportDialog(
            survey = survey.name,
            options = therionOptions,
            onDismiss = { choosingTherionOptions = false },
            onSave = {
                onTherionOptionsChange(it)
                choosingTherionOptions = false
            },
        )
    }

    if (choosingSvgOptions) {
        SvgExportDialog(
            options = svgOptions,
            onDismiss = { choosingSvgOptions = false },
            onSave = {
                onSvgOptionsChange(it)
                choosingSvgOptions = false
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        // Wrapped rather than scrolled sideways: a drag beginning on a chip is taken by the
        // chip rather than scrolling the row.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (f in ExportFormat.entries) {
                FilterChip(format == f, { format = f }, { Text(f.label) })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { copied = copyToClipboard(text) }) {
                Text(if (copied) "Copied" else "Copy")
            }
            TextButton(
                onClick = {
                    val where =
                        saveTextFile(
                            fileNameFor(survey, format, projection, therionOptions),
                            text,
                        )
                    val companionsSaved =
                        companions.all { (name, body) -> saveTextFile(name, body) != null }
                    savedTo = if (where != null && companionsSaved) where else null
                    saveFailed = where == null || !companionsSaved
                },
            ) { Text(if (companions.isEmpty()) "Save file" else "Save files") }
            // The preview below redraws as options are changed, so the export itself stays a
            // single button.
            if (format == ExportFormat.SVG) {
                TextButton(onClick = { choosingSvgOptions = true }) { Text("Options") }
            }
            if (format.isTherion) {
                TextButton(onClick = { choosingTherionOptions = true }) { Text("Options") }
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    saveFailed -> "Could not save a file here"
                    savedTo != null -> "Saved to $savedTo"
                    else ->
                        (listOf(fileNameFor(survey, format, projection, therionOptions)) +
                            companions.map { it.first })
                            .joinToString(", ")
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

        // Its own row rather than among the format actions: this always writes this app's own
        // three files regardless of which chip is selected.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    val where =
                        saveBinaryFile(
                            SurveyZip.fileNameFor(survey),
                            SurveyZip.archive(survey),
                        )
                    sharedTo = where
                    shareFailed = where == null
                },
            ) { Text("Share survey") }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    shareFailed -> "Could not save a file here"
                    sharedTo != null -> "Saved to $sharedTo"
                    else -> SurveyZip.fileNameFor(survey)
                },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (shareFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

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
 * What a format's file says, as a function of the survey rather than of the screen.
 *
 * Lifted out of the composable it lived inside: a throw inside a `remember` block is a throw
 * *inside a composition*, which on the web means no error and no blank page — the last frame
 * simply stays up and the app looks frozen.
 */
internal fun exportText(
    survey: Survey,
    format: ExportFormat,
    projection: Projection2D,
    today: String,
    svgOptions: SvgExporter.Options = SvgExporter.Options.DEFAULT,
    therion: TherionExport = TherionExport.DEFAULT,
): String =
        when (format) {
            ExportFormat.SURVEX -> SurvexExporter.export(survey, createdOn = today)
            ExportFormat.THERION ->
                TherionExporter.export(
                    survey,
                    createdOn = today,
                    th2Files =
                        Projection2D.entries
                            .filter { it.isDrawable }
                            .map { fileNameFor(survey, ExportFormat.TH2, it, therion) },
                )
            ExportFormat.THCONFIG -> ThconfigExporter.export(survey)
            ExportFormat.SVG -> SvgExporter.export(survey, projection, svgOptions)
            ExportFormat.COMPASS ->
                CompassExporter.export(survey, fallbackDate = SurveyDate.parseOrNull(today))
            // Therion's own background-image format: the drawing as line segments, to trace over
            // in xtherion, at the same scale and frame as the SVG.
            ExportFormat.XVI ->
                XviExporter.export(
                    sketch = survey.getSketch(projection),
                    space = projection.project(survey),
                    scale = SvgExporter.SCALE.toFloat(),
                    gridFrame =
                        SvgExporter.addBorder(SvgExporter.exportFrame(survey, projection))
                            .scale(SvgExporter.SCALE.toFloat()),
                )

            // Names the .xvi it expects beside it: a .th2 alone has the stations and symbols but
            // not the passage walls, which live in the traced image.
            ExportFormat.TH2 ->
                Th2Exporter.export(
                    survey = survey,
                    projection = projection,
                    innerFrame =
                        SvgExporter.exportFrame(survey, projection)
                            .scale(SvgExporter.SCALE.toFloat()),
                    outerFrame =
                        SvgExporter.addBorder(SvgExporter.exportFrame(survey, projection))
                            .scale(SvgExporter.SCALE.toFloat()),
                    scale = SvgExporter.SCALE.toFloat(),
                    options =
                        therion.th2Options(
                            therion.xviReference(survey.name, projection),
                            projection,
                        ),
                )

            ExportFormat.POCKET_TOPO -> PocketTopoExporter.export(survey)
            ExportFormat.NATIVE -> SurveyJson.write(survey)
        }

/** One file of a Therion project: what it is called, and which chip and drawing it belongs to. */
private data class TherionFile(
    val format: ExportFormat,
    /** Null for the files there is one of per survey rather than per drawing. */
    val projection: Projection2D?,
    val name: String,
    val content: String,
)

/**
 * Every file a Therion project is made of, which is the only way it is any use.
 *
 * Therion compiles a project, and the files name each other: the `.thconfig` sources the `.th`,
 * the `.th` has an `input` line for each `.th2`, and each `.th2` names the `.xvi` it is traced
 * over. Write one of them on its own and Therion stops at the first name it cannot open —
 * `error -- C7.th [6] -- can't open file for input -- C7.ee.th2` — so the Android exporter
 * writes all six in one go, and so does this.
 */
private fun therionProject(
    survey: Survey,
    today: String,
    therion: TherionExport,
): List<TherionFile> = buildList {
    for (format in listOf(ExportFormat.THERION, ExportFormat.THCONFIG)) {
        add(
            TherionFile(
                format,
                null,
                fileNameFor(survey, format, Projection2D.PLAN, therion),
                exportText(survey, format, Projection2D.PLAN, today, therion = therion),
            ),
        )
    }
    for (projection in Projection2D.entries.filter { it.isDrawable }) {
        add(
            TherionFile(
                ExportFormat.TH2,
                projection,
                fileNameFor(survey, ExportFormat.TH2, projection, therion),
                exportText(survey, ExportFormat.TH2, projection, today, therion = therion),
            ),
        )
        add(
            TherionFile(
                ExportFormat.XVI,
                projection,
                // The path the .th2 names, folder and all, rather than the bare filename: a
                // tracing image the .th2 cannot find is a scrap with no passage walls in it.
                therion.xviReference(survey.name, projection),
                exportText(survey, ExportFormat.XVI, projection, today, therion = therion),
            ),
        )
    }
}

/**
 * The other files a format writes, besides the one shown on screen.
 *
 * Two formats have any. A SexyTopo survey is `Name.data.json` **and** its two sketches; and any
 * one Therion file is only useful alongside the rest of its project, so selecting any of the
 * four Therion chips writes the lot.
 */
internal fun companionFiles(
    survey: Survey,
    format: ExportFormat,
    projection: Projection2D = Projection2D.PLAN,
    today: String = "",
    therion: TherionExport = TherionExport.DEFAULT,
): List<Pair<String, String>> =
    when {
        format == ExportFormat.NATIVE ->
            listOf(
                SurveyFileType.METADATA.filenameFor(survey.name) to
                    MetadataJson.write(
                        survey,
                        SurveyStorage.DEFAULT_VERSION_NAME,
                        0,
                    ),
                SurveyFileType.PLAN_SKETCH.filenameFor(survey.name) to
                    SketchJson.write(survey.planSketch, survey.name),
                SurveyFileType.EXTENDED_ELEVATION_SKETCH.filenameFor(survey.name) to
                    SketchJson.write(survey.elevationSketch, survey.name),
            )
        format.isTherion ->
            therionProject(survey, today, therion)
                // All but the one on screen, which the caller writes itself.
                .filterNot {
                    it.format == format && (it.projection == null || it.projection == projection)
                }
                .map { it.name to it.content }
        else -> emptyList()
    }

/**
 * The three Therion formats take their suffix from the surveyor's own preference rather than
 * from [Projection2D.fileSuffix], since Therion's files refer to each other *by name*.
 */
internal fun fileNameFor(
    survey: Survey,
    format: ExportFormat,
    projection: Projection2D = Projection2D.PLAN,
    therion: TherionExport = TherionExport.DEFAULT,
): String =
    when {
        format.perProjection && format.isTherion ->
            therion.fileNameFor(survey.name, projection, format.extension)
        format.perProjection -> "${survey.name}.${projection.fileSuffix}.${format.extension}"
        else -> "${survey.name}.${format.extension}"
    }
