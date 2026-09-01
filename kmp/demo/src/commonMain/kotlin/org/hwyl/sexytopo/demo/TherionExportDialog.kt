package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.model.graph.Projection2D

/**
 * What a Therion export is called and what goes in it: `preferences_export_therion.xml`.
 *
 * ## Why a surveyor would open this
 *
 * A Therion project is not one file. This app writes a `.th` of centreline, a `.thconfig` that
 * builds it, and per drawing a `.th2` scrap and the `.xvi` background image the scrap is traced
 * over — and every one of them names the others. So the names are not a matter of taste: a
 * surveyor joining these files to a project that already holds twenty trips, laid out the way that
 * project lays things out, needs them to match, and a surveyor who keeps background images in an
 * `xvi/` folder needs the scrap to say so.
 *
 * That is also why this shows what the files will be called, live, above the boxes that decide it.
 * A suffix rule with three answers about one dot — `.plan` gives `Name.plan.th2`, `P` gives
 * `NameP.th2`, empty gives `Name.th2` — is far easier to check by looking than by reading.
 */
@Composable
fun TherionExportDialog(
    survey: String,
    options: TherionExport,
    onDismiss: () -> Unit,
    onSave: (TherionExport) -> Unit,
) {
    var planSuffix by remember { mutableStateOf(options.planSuffix) }
    var elevationSuffix by remember { mutableStateOf(options.elevationSuffix) }
    var xviFolder by remember { mutableStateOf(options.xviFolder) }
    var planScrap by remember { mutableStateOf(options.planScrapSuffix) }
    var elevationScrap by remember { mutableStateOf(options.elevationScrapSuffix) }
    var planSection by remember { mutableStateOf(options.planCrossSectionSuffix) }
    var elevationSection by remember { mutableStateOf(options.elevationCrossSectionSuffix) }
    var crossSections by remember { mutableStateOf(options.crossSections) }
    var symbols by remember { mutableStateOf(options.symbols) }
    var labels by remember { mutableStateOf(options.labels) }
    // Kept as text rather than as an Int, like every other number in these dialogs: a box that
    // rejects the empty string cannot be cleared to type a new value into.
    var planScraps by remember { mutableStateOf(options.planScrapCount.toString()) }
    var elevationScraps by remember { mutableStateOf(options.elevationScrapCount.toString()) }
    var stationsInPlan by remember { mutableStateOf(options.stationsInFirstPlanScrap) }
    var stationsInElevation by remember { mutableStateOf(options.stationsInFirstElevationScrap) }

    // Built from what is typed rather than from what is saved, so the names move as the boxes do.
    val preview =
        TherionExport(
            planSuffix = planSuffix,
            elevationSuffix = elevationSuffix,
            xviFolder = xviFolder,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Therion export") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "A Therion project is several files that name each other. These decide what " +
                        "they are called, so this app's files can join a project you already have.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The answer, above the question. See the note on this dialog.
                Text(
                    listOf(
                        preview.fileNameFor(survey, Projection2D.PLAN, "th2"),
                        preview.xviReference(survey, Projection2D.PLAN),
                        preview.fileNameFor(survey, Projection2D.EXTENDED_ELEVATION, "th2"),
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                SuffixField(planSuffix, { planSuffix = it }, "Plan file suffix")
                SuffixField(elevationSuffix, { elevationSuffix = it }, "Elevation file suffix")
                SuffixField(xviFolder, { xviFolder = it }, "Image folder")
                Text(
                    "The image folder is written into the scrap so xtherion can find the " +
                        "picture. The file itself is saved beside the others; move it in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Text(
                    "Names inside the files. A # in a cross-section name is where the station's " +
                        "name goes; ## and ### pad a numbered one, so station 7 sorts as 07.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SuffixField(planScrap, { planScrap = it }, "Plan scrap suffix")
                SuffixField(elevationScrap, { elevationScrap = it }, "Elevation scrap suffix")
                SuffixField(planSection, { planSection = it }, "Plan cross-section names")
                SuffixField(
                    elevationSection,
                    { elevationSection = it },
                    "Elevation cross-section names",
                )

                HorizontalDivider()

                Text(
                    "Splitting a drawing up. More than one scrap gives you the extra ones empty, " +
                        "named and ready to draw into — which is how a big cave gets drawn up, " +
                        "one scrap per chamber. Only the first holds anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberField(planScraps, { planScraps = it }, "Plan scraps")
                NumberField(elevationScraps, { elevationScraps = it }, "Elevation scraps")
                Toggle(
                    title = "Stations in the first plan scrap",
                    detail =
                        "Off puts them in a scrap of your own, so re-exporting after a " +
                            "correction does not overwrite a drawing you have worked on.",
                    checked = stationsInPlan,
                    onCheckedChange = { stationsInPlan = it },
                )
                Toggle(
                    title = "Stations in the first elevation scrap",
                    detail = "The same, for the extended elevation.",
                    checked = stationsInElevation,
                    onCheckedChange = { stationsInElevation = it },
                )

                HorizontalDivider()

                Toggle(
                    title = "Export cross-sections",
                    detail = "Each passage section as a scrap of its own, beside its station.",
                    checked = crossSections,
                    onCheckedChange = { crossSections = it },
                )
                Toggle(
                    title = "Export symbols",
                    detail = "Stamped symbols: entrances, chokes, water and the rest.",
                    checked = symbols,
                    onCheckedChange = { symbols = it },
                )
                Toggle(
                    title = "Export text",
                    detail = "Labels written onto the drawing.",
                    checked = labels,
                    onCheckedChange = { labels = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        options.copy(
                            planSuffix = planSuffix,
                            elevationSuffix = elevationSuffix,
                            xviFolder = xviFolder,
                            planScrapSuffix = planScrap,
                            elevationScrapSuffix = elevationScrap,
                            planCrossSectionSuffix = planSection,
                            elevationCrossSectionSuffix = elevationSection,
                            crossSections = crossSections,
                            symbols = symbols,
                            labels = labels,
                            // At least one, as the Java's `parseIntSafe` does: an empty box or
                            // "three" means the surveyor did not change it, and zero scraps is a
                            // th2 with no drawing in it at all.
                            planScrapCount = scrapsFrom(planScraps),
                            elevationScrapCount = scrapsFrom(elevationScraps),
                            stationsInFirstPlanScrap = stationsInPlan,
                            stationsInFirstElevationScrap = stationsInElevation,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * How many scraps a box asking for a number means.
 *
 * `Math.max(1, ...)` with a default of 1 on a parse failure, which is `TherionExporter.parseIntSafe`
 * exactly. Worth copying rather than improving on: zero scraps would be a `.th2` holding no drawing
 * at all, and a negative one is a loop that never runs.
 */
internal fun scrapsFrom(text: String): Int = (text.trim().toIntOrNull() ?: 1).coerceAtLeast(1)

/**
 * A one-line text box for something that goes in a filename.
 *
 * `singleLine` is not cosmetic here: these are written to the preferences file as `key=value`, one
 * to a line, so a newline in one would take the rest of the settings with it.
 */
@Composable
private fun SuffixField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
