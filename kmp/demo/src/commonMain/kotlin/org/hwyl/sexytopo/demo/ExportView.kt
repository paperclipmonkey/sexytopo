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
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The interchange formats a caver actually cares about.
 *
 * [extension] is not decoration: Survex will not open a file that is not named `.svx`, and a
 * surveyor who has to rename four downloads on a laptop after every trip will stop using the app.
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    /**
     * Whether there is one of these file per *projection* rather than one per survey.
     *
     * The plan and the extended elevation are different drawings, so they are different files, and
     * the Android app tells them apart in the name: `Name.plan.th2` and `Name.ee.th2`,
     * `SexyTopoConstants.PLAN_SUFFIX` and `EE_SUFFIX` through `DoubleSketchFileExporter`. This
     * port exported both under one name until that was noticed, so saving the elevation quietly
     * overwrote the plan — and the two are indistinguishable once written, being the same format
     * of the same cave.
     */
    val perProjection: Boolean = false,
    /**
     * Whether this is one of the files a Therion project is made of.
     *
     * Which matters for two reasons: these are the formats the `pref_therion_*` preferences name,
     * and they are the ones whose *Options* button offers them.
     */
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
 * Shows what the survey exports as.
 *
 * This is the interop claim made visible: the Survex and Therion output is produced by the ported
 * emitter, whose golden tests assert it byte for byte against what the Android app writes, and the
 * JSON is the app's own native format — the same bytes the Android app would read back.
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
    // The zip is not per-format - it is the whole survey either way - so unlike the three above
    // this is not keyed on the chips: a surveyor who shares the survey and then looks at the
    // Therion preview should still be able to see where it went.
    var sharedTo by remember { mutableStateOf<String?>(null) }
    var shareFailed by remember { mutableStateOf(false) }
    var choosingSvgOptions by remember { mutableStateOf(false) }
    var choosingTherionOptions by remember { mutableStateOf(false) }

    // Today, from the device's own clock, because these files date the trip. The shared exporters
    // take the date as a parameter rather than reading a clock - that is what makes their golden
    // tests possible - so this is where it has to come from.
    val today = remember(survey, revision) { todayIso() }

    val text =
        remember(survey, revision, format, today, projection, svgOptions, therionOptions) {
            exportText(survey, format, projection, today, svgOptions, therionOptions)
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
        // Wrapped rather than scrolled sideways, which is what this was.
        //
        // Eight formats do not fit across a phone, and a row that scrolls hides four of them behind
        // a gesture that does not work: a drag beginning on a chip is taken by the chip, moves the
        // row about thirty pixels and stops. A finger lands on a chip far more often than in the
        // gap between two, so half the export formats were, in practice, unreachable. Three rows of
        // chips cost some height on a screen whose main content scrolls anyway.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
                    val where =
                        saveTextFile(
                            fileNameFor(survey, format, projection, therionOptions),
                            text,
                        )
                    // The companions go too, or the export is the same data loss the *importer*
                    // had until recently: a survey is four files and its own format cannot be
                    // written as one of them.
                    val companions = companionFiles(survey, format)
                    val companionsSaved =
                        companions.all { (name, body) -> saveTextFile(name, body) != null }
                    savedTo = if (where != null && companionsSaved) where else null
                    saveFailed = where == null || !companionsSaved
                },
            ) { Text(if (companionFiles(survey, format).isEmpty()) "Save file" else "Save files") }
            // Only on the format it is about. The Android app asks these thirteen questions in a
            // dialog *on the way out* - tap SVG, answer, then the file is written - which is one
            // tap more than anybody wants when they already know the answers. Here the preview
            // below redraws as they are changed, so the dialog is worth opening only when the
            // drawing is wrong, and the export itself stays a single button.
            if (format == ExportFormat.SVG) {
                TextButton(onClick = { choosingSvgOptions = true }) { Text("Options") }
            }
            // Offered on all four Therion formats, because the names these set are shared between
            // them: a `.th2` names its `.xvi` and a `.thconfig` names both, so a surveyor who has
            // just changed the plan suffix while looking at the `.th2` needs the `.thconfig` to
            // follow — and would otherwise have to guess which screen owned the setting.
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
                            companionFiles(survey, format).map { it.first })
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

        // The whole survey as one file, which is what handing it to somebody actually means.
        //
        // `SurveyZipSharer` does this in the Android app and this port had only the receiving half:
        // it would read a survey somebody had unzipped and could not make the zip, so giving a
        // survey to a caving partner meant three files and a hope they arrived together.
        //
        // Its own row, below the format actions rather than among them, because it is not one of
        // them: the buttons above write whatever format the chips are set to, and this one always
        // writes this app's own three files, which is what the other end knows how to import.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Beside the button rather than above it. A `Row` aligns to the top by default, which
            // puts a line of `bodySmall` at the top of a forty-dp button and leaves it floating
            // over the label instead of reading as the same line.
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
 * escape the directory it is written into however the survey was named — plus, for the three
 * formats that are one file per drawing, which drawing it is.
 */
/**
 * What a format's file says, as a function of the survey rather than of the screen.
 *
 * Lifted out of the composable it lived inside, and not as tidying-up. While it was an expression
 * in a `remember` block, the only way to learn what any of these exporters does with a given survey
 * was to run the app and look — and a throw in there is a throw *inside a composition*, which on
 * the web is finding 11: no error, no blank page, the last frame simply stays up and the app looks
 * frozen. An exporter that fails on some shape of survey is invisible exactly where it matters.
 *
 * Out here it can be handed an empty survey, or a one-station one, and asked whether it throws.
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
            // Naming both scraps, so a Therion project built from these files gets the
            // drawing and not just the centreline. The names are what this screen would save
            // them as, which is what makes them findable beside the .th.
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
            // The only export that is a picture. It follows the view the surveyor is looking
            // at, so exporting from the extended elevation gives the elevation drawing.
            ExportFormat.SVG -> SvgExporter.export(survey, projection, svgOptions)
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

            // The scrap file, naming the .xvi it expects beside it. Exporting the two
            // together is the point: a .th2 alone has the stations and the symbols but not
            // the passage walls, which live in the image the surveyor traces.
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
                    // The image reference carries the surveyor's xvi folder, because that is
                    // the half xtherion resolves: a scrap naming an image that is not where it
                    // says opens with a missing-file complaint and no background at all.
                    options = therion.th2Options(therion.xviReference(survey.name, projection)),
                )

            ExportFormat.POCKET_TOPO -> PocketTopoExporter.export(survey)
            ExportFormat.NATIVE -> SurveyJson.write(survey)
        }

/**
 * The other files a format writes, besides the one shown on screen.
 *
 * Only this app's own format has any, and it is the one that most needed them: a SexyTopo survey
 * is `Name.data.json` **and** its two sketches, so exporting the data file alone hands somebody a
 * centreline and keeps the drawing. That is exactly the loss the *importer* had — it read the data
 * file and ignored the sketches beside it — and fixing one end while leaving the other would mean
 * this app could read a complete survey and not write one.
 *
 * The preview stays the data file. The sketches are thousands of coordinates and there is nothing
 * to learn from looking at them; what matters is that they are written, and the row under the
 * button names all three.
 */
internal fun companionFiles(survey: Survey, format: ExportFormat): List<Pair<String, String>> =
    if (format == ExportFormat.NATIVE) {
        listOf(
            SurveyFileType.PLAN_SKETCH.filenameFor(survey.name) to
                SketchJson.write(survey.planSketch, survey.name),
            SurveyFileType.EXTENDED_ELEVATION_SKETCH.filenameFor(survey.name) to
                SketchJson.write(survey.elevationSketch, survey.name),
        )
    } else {
        emptyList()
    }

/**
 * What the file is called.
 *
 * The three Therion formats take their suffix from the surveyor's own preference rather than from
 * [Projection2D.fileSuffix], because Therion's files refer to each other *by name*: a `.th2` names
 * its `.xvi`, and a `.thconfig` names both. A surveyor whose other trips are laid out as `NameP`
 * and `NameE` has to be able to say so here, or this app's files will not join a project they
 * already have. The SVG keeps the app's own naming, because nothing refers to it.
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
