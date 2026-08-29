package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.SurvexTherionWriter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.export.formatAzimuth
import org.hwyl.sexytopo.shared.io.export.formatDistance
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.io.export.formatInclination
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export fidelity. These files are the interchange with Therion and Survex, and a survey that
 * exports differently from the Android app is a survey that disagrees with the surveyor's notes.
 */
class ExportTest {

    /** 1 -> 2 with one splay hanging off 2. */
    private fun simpleSurvey(): Survey {
        val survey = Survey("Test")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(1.5f, 180f, 0f))
        return survey
    }

    // -------------------------------------------------------------------------------------
    // Number formatting — the part most likely to diverge silently
    // -------------------------------------------------------------------------------------

    @Test
    fun fieldsUseTheAndroidPrecision() {
        assertEquals("5.000", formatDistance(5f))
        assertEquals("12.346", formatDistance(12.3456f))
        assertEquals("90.00", formatAzimuth(90f))
        assertEquals("10.00", formatInclination(10f))
    }

    @Test
    fun inclinationIsNotSignedInExports() {
        // TableCol.INCLINATION is "%+.2f" for the on-screen table, but
        // SurvexTherionUtil.formatInclination is plain "%.2f". Writing a leading + into the file
        // would be a difference from the Android app's output.
        assertEquals("10.00", formatInclination(10f))
        assertEquals("-10.00", formatInclination(-10f))
        assertEquals("0.00", formatInclination(0f))
    }

    @Test
    fun roundingIsHalfUpNotTiesToEven() {
        // kotlin.math.round would give 2 here; Java's Formatter gives 3.
        assertEquals("3", formatFixed(2.5f, 0))
        assertEquals("2.5", formatFixed(2.45f, 1))
        assertEquals("-2.5", formatFixed(-2.45f, 1))
    }

    @Test
    fun decimalSeparatorIsAlwaysAPoint() {
        // The Java pins Locale.UK for exactly this reason: a comma-decimal locale would otherwise
        // write "1,50" into a file whose parser expects "1.50".
        assertTrue(formatDistance(1.5f).contains('.'))
        assertTrue(!formatDistance(1.5f).contains(','))
    }

    // -------------------------------------------------------------------------------------
    // Survex
    // -------------------------------------------------------------------------------------

    @Test
    fun survexOutputIsExact() {
        val expected =
            "*begin Test\n" +
                "; Created with SexyTopo on 2026-08-29\n" +
                "*data normal from to tape compass clino ignoreall\n" +
                "1\t2\t5.000\t90.00\t10.00\t\n" +
                "2\t..\t1.500\t180.00\t0.00\t\n" +
                "\n" +
                "\n" +
                "*extend start 1\n" +
                "*end Test\n"

        assertEquals(expected, SurvexExporter.export(simpleSurvey(), createdOn = "2026-08-29"))
    }

    @Test
    fun survexNamesSplaysWithTwoDots() {
        val output = SurvexExporter.export(simpleSurvey(), createdOn = "x")
        assertTrue(output.contains("2\t..\t"), "Survex writes an absent destination as '..'")
    }

    // -------------------------------------------------------------------------------------
    // Therion
    // -------------------------------------------------------------------------------------

    @Test
    fun therionOutputIsExact() {
        val expected =
            "encoding utf-8\n" +
                "survey Test\n" +
                "# Created with SexyTopo on 2026-08-29\n" +
                "\n" +
                "centreline\n" +
                "data normal from to tape compass clino ignoreall\n" +
                "1\t2\t5.000\t90.00\t10.00\t\n" +
                "2\t-\t1.500\t180.00\t0.00\t\n" +
                "\n" +
                "extend start 1\n" +
                "endcentreline\n" +
                "endsurvey\n"

        assertEquals(expected, TherionExporter.export(simpleSurvey(), createdOn = "2026-08-29"))
    }

    @Test
    fun therionNamesSplaysWithAHyphenAndUsesNoCommandChar() {
        val output = TherionExporter.export(simpleSurvey(), createdOn = "x")
        assertTrue(output.contains("2\t-\t"), "Therion writes an absent destination as '-'")
        assertTrue(output.contains("\ndata normal "), "Therion has no leading * on commands")
    }

    // -------------------------------------------------------------------------------------
    // Semantics that must survive export
    // -------------------------------------------------------------------------------------

    @Test
    fun aBackwardsShotIsExportedAsItWasTaken() {
        val survey = Survey("B")
        val destination = Station("2")
        val leg = Leg(5f, 90f, 10f, destination, wasShotBackwards = true)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)

        val output = SurvexExporter.export(survey, createdOn = "x")
        assertTrue(
            output.contains("2\t1\t5.000\t270.00\t-10.00\t"),
            "expected the as-taken reading 2 -> 1 reversed; got:\n$output",
        )
    }

    @Test
    fun promotedReadingsAreKeptAsComments() {
        // A promoted leg averages three readings; the originals are preserved as comment lines so
        // the exported file still contains what was actually observed.
        val survey = Survey("P")
        val destination = Station("2")
        val precursors =
            arrayOf(Leg(5.0f, 90f, 10f), Leg(5.01f, 90.3f, 10.2f), Leg(4.98f, 89.6f, 10.1f))
        val leg = Leg(5f, 90f, 10f, destination, precursors)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)

        val output = SurvexExporter.export(survey, createdOn = "x")
        assertTrue(output.contains(";1\t2\t5.000\t90.00\t10.00"), "first precursor, commented")
        assertTrue(output.contains(";1\t2\t5.010\t90.30\t10.20"), "second precursor")
        assertTrue(output.contains(";1\t2\t4.980\t89.60\t10.10"), "third precursor")
    }

    @Test
    fun stationCommentsBecomeAPassageBlock() {
        val survey = simpleSurvey()
        survey.origin.comment = "entrance under the boulder"

        val survex = SurvexExporter.export(survey, createdOn = "x")
        assertTrue(survex.contains("*data passage station left right up down ignoreall\n"))
        assertTrue(survex.contains("1\t-\t-\t-\t-\tentrance under the boulder\n"))

        val therion = TherionExporter.export(survey, createdOn = "x")
        assertTrue(therion.contains("data dimensions station left right up down ignoreall\n"))
    }

    @Test
    fun newlinesInCommentsAreFlattened() {
        val survey = simpleSurvey()
        survey.origin.comment = "line one\nline two"
        val output = SurvexExporter.export(survey, createdOn = "x")
        assertTrue(output.contains("line one\\nline two"), "a raw newline would break the format")
    }

    @Test
    fun extendCommandsFollowTheStationDirections() {
        val survey = Survey("E")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        survey.getStationByName("3")!!.extendedElevationDirection = ExtendedElevationDirection.LEFT

        val output = SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.SURVEX)
        assertEquals("*extend start 1\n*extend left 3\n", output)
    }

    @Test
    fun aVerticalLegIsExtendedWithBothStationNames() {
        // Vertical does not propagate, so it names the leg rather than the subtree.
        val survey = Survey("V")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 60f))
        survey.getStationByName("2")!!.extendedElevationDirection =
            ExtendedElevationDirection.VERTICAL

        val output = SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.SURVEX)
        assertEquals("*extend start 1\n*extend vertical 1 2\n", output)
    }

    @Test
    fun entriesComeOutInTheOrderTheInstrumentDeliveredThem() {
        val survey = Survey("C")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(1f, 90f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(6f, 10f, 0f))

        val entries = SurvexTherionWriter.chronologicalEntries(survey)
        val distances = entries.map { it.second.distance }
        assertEquals(listOf(5f, 1f, 6f), distances, "chronological, not tree order")
    }
}
