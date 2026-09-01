package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the exported files are called.
 *
 * A name is not decoration here. Survex will not open a file that is not named `.svx`; Therion
 * finds a scrap by the name the `.th` gives it; and two drawings written under one name are one
 * drawing, silently.
 */
class ExportNamingTest {

    private val survey = Survey("Swildons")

    @Test
    fun aWholeSurveyFormatIsNamedAfterTheSurvey() {
        assertEquals("Swildons.svx", fileNameFor(survey, ExportFormat.SURVEX))
        assertEquals("Swildons.th", fileNameFor(survey, ExportFormat.THERION))
        assertEquals("Swildons.thconfig", fileNameFor(survey, ExportFormat.THCONFIG))
        assertEquals("Swildons.data.json", fileNameFor(survey, ExportFormat.NATIVE))
    }

    /**
     * A survey exported in this app's own format has to be the whole survey.
     *
     * `NATIVE` wrote `Swildons.data.json` and nothing else, which hands somebody a centreline and
     * keeps the drawing — the same loss the *importer* had at the other end, where it read the
     * data file and never looked for the sketches beside it. Fixing one end and not the other
     * would have left this app able to read a complete survey and unable to write one.
     */
    @Test
    fun theNativeExportIsTheWholeSurvey() {
        val written =
            listOf(fileNameFor(survey, ExportFormat.NATIVE)) +
                companionFiles(survey, ExportFormat.NATIVE).map { it.first }
        assertEquals(
            listOf(
                "Swildons.data.json",
                "Swildons.metadata.json",
                "Swildons.plan.json",
                "Swildons.ext-elevation.json",
            ),
            written,
            "the native export is not the four-file survey the importer expects",
        )
    }

    /** And no other format grows files it never had. */
    @Test
    fun everyOtherFormatIsStillOneFile() {
        for (format in ExportFormat.entries.filterNot { it == ExportFormat.NATIVE }) {
            assertEquals(
                emptyList(),
                companionFiles(survey, format).map { it.first },
                "$format grew companion files",
            )
        }
    }

    /**
     * The bug this was written for: the plan and the elevation were both `Swildons.th2`, so
     * exporting one after the other left a surveyor with a single file and no way to tell which
     * drawing was in it. `DoubleSketchFileExporter` puts `PLAN_SUFFIX` or `EE_SUFFIX` in the name.
     */
    @Test
    fun aDrawingFormatSaysWhichDrawingItIs() {
        for (format in ExportFormat.entries.filter { it.perProjection }) {
            val plan = fileNameFor(survey, format, Projection2D.PLAN)
            val elevation = fileNameFor(survey, format, Projection2D.EXTENDED_ELEVATION)

            assertTrue(plan.endsWith(".plan.${format.extension}"), "the plan came out as $plan")
            assertTrue(elevation.endsWith(".ee.${format.extension}"), "got $elevation")
        }
    }

    @Test
    fun theThreeDrawingFormatsAreTheDrawingFormats() {
        // .svg, .xvi and .th2 — everything else in the list describes the whole survey, and
        // marking one of those per-projection would produce two files that were byte for byte the
        // same under two names.
        assertEquals(
            setOf(ExportFormat.SVG, ExportFormat.XVI, ExportFormat.TH2),
            ExportFormat.entries.filter { it.perProjection }.toSet(),
        )
    }

    /**
     * The one place a filename crosses from one file into another: the `.th` names its scraps, and
     * the `.th2` names the tracing image it is meant to be drawn over. If either disagrees with
     * what this screen actually saves, Therion reports a missing file.
     */
    @Test
    fun theProjectionsThatCanBeDrawnAreTheOnesTheProjectFileNames() {
        val drawable = Projection2D.entries.filter { it.isDrawable }

        assertEquals(listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION), drawable)
        assertEquals(
            listOf("Swildons.plan.th2", "Swildons.ee.th2"),
            drawable.map { fileNameFor(survey, ExportFormat.TH2, it) },
        )
    }
}
