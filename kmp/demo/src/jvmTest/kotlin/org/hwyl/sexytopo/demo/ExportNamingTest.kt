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
