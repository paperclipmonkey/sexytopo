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
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
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
                // With no trip there is no copyright line and no metadata block, but the newlines
                // that follow them are unconditional in the original, so the blank lines remain.
                "\n" +
                "\n" +
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
                // Where the `input "....th2"` lines go; the port has no .th2 exporter yet, but the
                // blank line the original leaves around them is kept.
                "\n" +
                "\n" +
                "centreline\n" +
                // No trip, so no copyright line and no metadata - but metadata's trailing newline
                // is unconditional.
                "\n" +
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

    // -------------------------------------------------------------------------------------
    // Trip metadata
    // -------------------------------------------------------------------------------------

    /** A fully filled-in trip, of the kind a club would actually publish from. */
    private fun documentedSurvey(): Survey {
        val survey = simpleSurvey()
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.instrument = "DistoX2"
        trip.copyrightHolder = "Some Caving Club"
        trip.licence = "CC-BY-SA-4.0"
        trip.comments = "Wet.\nVery wet."
        trip.team =
            listOf(
                Trip.TeamEntry("Alice", listOf(Trip.Role.BOOK, Trip.Role.INSTRUMENTS)),
                Trip.TeamEntry("Bob", listOf(Trip.Role.DOG, Trip.Role.EXPLORATION)),
                Trip.TeamEntry("Carol", emptyList()),
            )
        survey.trip = trip
        return survey
    }

    @Test
    fun survexEmitsTheWholeTripBlock() {
        val expected =
            "*begin Test\n" +
                "; Created with SexyTopo on 2026-08-29\n" +
                "*copyright 2026 \"Some Caving Club\" ;\"CC-BY-SA-4.0\"\n" +
                "\n" +
                "*date 2026.08.29\n" +
                "*instrument insts \"DistoX2\"\n" +
                // Survex has one team list, so an explorer is just another role on it. Carol has
                // no roles at all and is left out entirely.
                "*team \"Alice\" notes instruments\n" +
                "*team \"Bob\" assistant explorer\n" +
                "\n" +
                // No explicit exploration date, and the trip says it is linked to the survey date.
                "*date explored 2026.08.29\n" +
                "\n" +
                // No space after the comment char here, unlike the "Created with" line above -
                // the original is inconsistent about it and the file has to match.
                ";Comment from SexyTopo trip information\n" +
                ";Wet.\n" +
                ";Very wet.\n" +
                "\n" +
                "*data normal from to tape compass clino ignoreall\n" +
                "1\t2\t5.000\t90.00\t10.00\t\n" +
                "2\t..\t1.500\t180.00\t0.00\t\n" +
                "\n" +
                "\n" +
                "*extend start 1\n" +
                "*end Test\n"

        assertEquals(expected, SurvexExporter.export(documentedSurvey(), createdOn = "2026-08-29"))
    }

    @Test
    fun therionSplitsTheTeamFromTheExplorers() {
        val output = TherionExporter.export(documentedSurvey(), createdOn = "2026-08-29")

        assertTrue(output.contains("\nteam \"Alice\" notes instruments\n"), "was:\n$output")
        // Bob explored and held the tape, so he appears on both lists - but the exploration role
        // is stripped from the team line, because Therion says that with explo-team instead.
        assertTrue(output.contains("\nteam \"Bob\" assistant\n"), "was:\n$output")
        assertTrue(output.contains("\nexplo-team \"Bob\"\n"), "was:\n$output")
        assertTrue(!output.contains("explorer"), "Therion has no explorer role; was:\n$output")

        assertTrue(output.contains("\ncopyright 2026 \"Some Caving Club\" #\"CC-BY-SA-4.0\"\n"))
        assertTrue(output.contains("\nexplo-date 2026.08.29\n"), "was:\n$output")
        assertTrue(output.contains("\n#Comment from SexyTopo trip information\n"))
    }

    /** Somebody whose only role was exploring is not on the survey team at all. */
    @Test
    fun anExplorerOnlyMemberIsLeftOffTherionsTeamLine() {
        val survey = simpleSurvey()
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.team = listOf(Trip.TeamEntry("Dave", listOf(Trip.Role.EXPLORATION)))
        survey.trip = trip

        val output = TherionExporter.export(survey, createdOn = "x")
        assertTrue(!output.contains("team \"Dave\" \n"), "no empty role list; was:\n$output")
        assertTrue(!output.contains("\nteam \"Dave\""), "Dave did not survey; was:\n$output")
        assertTrue(output.contains("explo-team \"Dave\"\n"), "but he did explore; was:\n$output")

        // Survex has no such split, so there he is simply on the team as an explorer.
        assertTrue(SurvexExporter.export(survey, createdOn = "x").contains("*team \"Dave\" explorer\n"))
    }

    /**
     * A blank field is written commented-out rather than omitted. That is deliberate in the
     * original: the exported file doubles as a form, so somebody editing it afterwards can see the
     * slot and fill it in.
     */
    @Test
    fun blankTripFieldsAreWrittenAsCommentedPlaceholders() {
        val survey = simpleSurvey()
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.explorationDateLinked = false
        survey.trip = trip

        val output = SurvexExporter.export(survey, createdOn = "x")
        assertTrue(output.contains("\n;*instrument insts \"\"\n"), "was:\n$output")
        assertTrue(output.contains("\n;*date explored \n"), "was:\n$output")
        // Neither a copyright holder nor a licence, so no copyright line at all.
        assertTrue(!output.contains("copyright"), "was:\n$output")
        // And no comment block, since there are no comments.
        assertTrue(!output.contains("Comment from SexyTopo"), "was:\n$output")
    }

    @Test
    fun anUnlinkedExplorationDateIsWrittenAsItself() {
        val survey = simpleSurvey()
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.explorationDateLinked = false
        trip.explorationDate = SurveyDate(1998, 12, 1)
        survey.trip = trip

        assertTrue(
            SurvexExporter.export(survey, createdOn = "x").contains("*date explored 1998.12.01\n"),
            "the passage was found long before it was surveyed",
        )
    }

    /** A licence with no copyright holder still gets a line, with an empty pair of quotes. */
    @Test
    fun aLicenceWithoutAHolderStillWritesTheLine() {
        val survey = simpleSurvey()
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.licence = "CC0-1.0"
        survey.trip = trip

        val output = SurvexExporter.export(survey, createdOn = "x")
        assertTrue(output.contains("*copyright 2026 \"\" ;\"CC0-1.0\"\n"), "was:\n$output")
    }

    @Test
    fun aSurveyWithNoTripEmitsNoMetadataAtAll() {
        val output = SurvexExporter.export(simpleSurvey(), createdOn = "x")
        assertTrue(!output.contains("*date"), "was:\n$output")
        assertTrue(!output.contains("instrument"), "was:\n$output")
        assertTrue(!output.contains("team"), "was:\n$output")
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
