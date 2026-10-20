package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.SurvexConstants
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.SurvexTherionWriter
import org.hwyl.sexytopo.shared.io.export.ThconfigExporter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.export.formatAzimuth
import org.hwyl.sexytopo.shared.io.export.formatDistance
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.io.export.formatInclination
import org.hwyl.sexytopo.shared.io.imports.EspecParser
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export fidelity. These files are the interchange with Therion and Survex, and a survey that
 * exports differently from the Android app is a survey that disagrees with the surveyor's notes.
 */
class ExportTest {

    private fun simpleSurvey(): Survey {
        val survey = Survey("Test")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(1.5f, 180f, 0f))
        return survey
    }

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
        // SurvexTherionUtil.formatInclination is plain "%.2f".
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
                // No `*extend` lines any more: the directions go in a .espec beside the file.
                "*end Test\n"

        assertEquals(expected, SurvexExporter.export(simpleSurvey(), createdOn = "2026-08-29"))
    }

    @Test
    fun survexNamesSplaysWithTwoDots() {
        val output = SurvexExporter.export(simpleSurvey(), createdOn = "x")
        assertTrue(output.contains("2\t..\t"), "Survex writes an absent destination as '..'")
    }

    @Test
    fun therionOutputIsExact() {
        val expected =
            "encoding utf-8\n" +
                "survey Test\n" +
                "# Created with SexyTopo on 2026-08-29\n" +
                "\n" +
                // No scraps named, and the blank line the original leaves around them regardless.
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

    /**
     * The scrap files, without which Therion draws a centreline and no cave.
     *
     * `ThExporter` puts the `input` lines between the creation comment and the centreline block,
     * and appends its two newlines whether or not there were any — so the file with scraps is the
     * file without them plus exactly these lines, and nothing else moves.
     */
    @Test
    fun therionPullsInTheScrapFilesItIsGiven() {
        val withScraps =
            TherionExporter.export(
                simpleSurvey(),
                createdOn = "2026-08-29",
                th2Files = listOf("Test.plan.th2", "Test.ee.th2"),
            )
        val without = TherionExporter.export(simpleSurvey(), createdOn = "2026-08-29")

        assertTrue(withScraps.contains("input \"Test.plan.th2\"\ninput \"Test.ee.th2\"\n"))
        assertEquals(
            without,
            withScraps.replace("input \"Test.plan.th2\"\ninput \"Test.ee.th2\"\n", ""),
            "naming the scraps moved something else in the file",
        )
    }

    /**
     * Therion compiles a *project*, not a `.th`, and this is the project file. Byte for byte
     * against `ThconfigExporter`, including the two commented-out layout lines: the second is
     * commented out in the original because with it on Therion can fail to compile the survey.
     */
    @Test
    fun theThconfigIsExact() {
        val expected =
            "encoding utf-8\n" +
                "\n" +
                "layout local\n" +
                "  debug off\n" +
                "  # map-header 0 0 off\n" +
                "  # symbol-hide group cave-centreline\n" +
                "endlayout\n" +
                "\n" +
                "source \"Test.th\"\n" +
                "\n" +
                "export model -fmt survex -o \"Test-th.3d\"\n" +
                "\n" +
                "export map -proj plan -layout local -o \"Test-plan.pdf\"\n" +
                "\n" +
                "export map -proj extended -layout local -o \"Test-ee.pdf\""

        assertEquals(expected, ThconfigExporter.export(simpleSurvey()))
    }

    @Test
    fun theThconfigNamesTheSurveyEverywhereItHasTo() {
        // Four references to the survey's name, and a project whose source line disagreed with the
        // file beside it would fail to build with an error about a missing file rather than
        // anything a surveyor could act on.
        val output = ThconfigExporter.export(Survey("Swildons"))

        assertTrue(output.contains("source \"Swildons.th\""))
        assertTrue(output.contains("\"Swildons-th.3d\""))
        assertTrue(output.contains("\"Swildons-plan.pdf\""))
        assertTrue(output.contains("\"Swildons-ee.pdf\""))
    }

    @Test
    fun therionNamesSplaysWithAHyphenAndUsesNoCommandChar() {
        val output = TherionExporter.export(simpleSurvey(), createdOn = "x")
        assertTrue(output.contains("2\t-\t"), "Therion writes an absent destination as '-'")
        assertTrue(output.contains("\ndata normal "), "Therion has no leading * on commands")
    }

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

    private fun promotedSurvey(): Survey {
        val survey = Survey("P")
        val destination = Station("2")
        val precursors =
            arrayOf(Leg(5.0f, 90f, 10f), Leg(5.01f, 90.3f, 10.2f), Leg(4.98f, 89.6f, 10.1f))
        val leg = Leg(5f, 90f, 10f, destination, precursors)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)
        return survey
    }

    /**
     * Therion cannot average repeated readings itself yet, so it still gets this app's average,
     * with the readings it came from kept as comment lines so the file holds what was observed.
     * Each of those now ends in a tab like every other data line, since upstream's tidy writes
     * them through the same `formatDataLine`.
     */
    @Test
    fun therionKeepsPromotedReadingsAsComments() {
        val output = TherionExporter.export(promotedSurvey(), createdOn = "x")
        assertTrue(output.contains("\n1\t2\t5.000\t90.00\t10.00\t\n"), "the average; was:\n$output")
        assertTrue(output.contains("#1\t2\t5.000\t90.00\t10.00\t\n"), "first precursor, commented")
        assertTrue(output.contains("#1\t2\t5.010\t90.30\t10.20\t\n"), "second precursor")
        assertTrue(output.contains("#1\t2\t4.980\t89.60\t10.10\t\n"), "third precursor")
    }

    /**
     * Survex averages parallel legs in its own adjustment, so it gets the readings as taken and no
     * average at all — averaging twice would weight this app's arithmetic over Survex's.
     */
    @Test
    fun survexGetsTheRawReadingsAndNoAverage() {
        val output = SurvexExporter.export(promotedSurvey(), createdOn = "x")
        val legLines = output.lines().filter { it.startsWith("1\t2\t") }
        assertEquals(
            listOf(
                "1\t2\t5.000\t90.00\t10.00\t",
                "1\t2\t5.010\t90.30\t10.20\t",
                "1\t2\t4.980\t89.60\t10.10\t",
            ),
            legLines,
        )
        assertTrue(!output.contains(";1\t2"), "no commented copies; was:\n$output")
    }

    /** `testBasicExportWithPromotedLegs`: three legs of three readings each, all written out. */
    @Test
    fun survexWritesEveryReadingOfASurveyTakenInRepeats() {
        val content = SurvexExporter.export(TestSurveys.createStraightNorthThroughRepeats())

        assertTrue(!content.contains(";1\t2"))
        assertEquals(3, content.lines().count { it.startsWith("1\t2\t5.000\t0.00\t0.00") })
    }

    @Test
    fun aPromotedLegsCommentGoesOnItsFirstReading() {
        val survey = TestSurveys.createStraightNorthThroughRepeats()
        survey.origin.getConnectedOnwardLegs().first().comment = "Big Sandy Chamber"

        val lines = SurvexExporter.export(survey).lines().filter { it.startsWith("1\t2") }

        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("Big Sandy Chamber"))
        assertTrue(!lines[1].contains("Big Sandy Chamber"))
        assertTrue(!lines[2].contains("Big Sandy Chamber"))
    }

    @Test
    fun aPromotedLegsCommentJoinsItsFirstReadingsComment() {
        val survey = Survey()
        val first = Leg(5f, 0f, 0f).also { it.comment = "cold draught" }
        val promoted =
            Leg.upgradeSplayToConnectedLeg(first, Station("2"), arrayOf(first, Leg(5f, 0f, 0f)))
        promoted.comment = "Big Sandy Chamber"
        survey.origin.addOnwardLeg(promoted)

        assertTrue(SurvexExporter.export(survey).contains("Big Sandy Chamber :: cold draught"))
    }

    /** `ThExporterTest`'s check that the switch is Survex's alone. */
    @Test
    fun therionStillAveragesASurveyTakenInRepeats() {
        val centreline =
            SurvexTherionWriter.centrelineData(
                TestSurveys.createStraightNorthThroughRepeats(),
                SurveyFormat.THERION,
            )

        assertTrue(centreline.contains("1\t2\t5.000\t0.00\t0.00"))
        assertEquals(3, centreline.lines().count { it.startsWith("#1\t2\t5.000\t0.00\t0.00") })
    }

    @Test
    fun theTripDateIsWrittenWithDots() {
        for (format in SurveyFormat.entries) {
            val survey = TestSurveys.createStraightNorth()
            survey.trip = Trip(SurveyDate(1970, 1, 1))

            val metadata = SurvexTherionWriter.metadata(survey, format)

            assertTrue(metadata.contains("${format.commandChar}date 1970.01.01"), "on $format")
            assertTrue(!metadata.contains("1970-01-01"), "on $format")
        }
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

        val output = SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.THERION)
        assertEquals("extend start 1\nextend left 3\n", output)
    }

    @Test
    fun aVerticalLegIsExtendedWithBothStationNames() {
        // Vertical does not propagate, so it names the leg rather than the subtree.
        val survey = Survey("V")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 60f))
        survey.getStationByName("2")!!.extendedElevationDirection =
            ExtendedElevationDirection.VERTICAL

        val output = SurvexTherionWriter.extendedElevationExtensions(survey, SurveyFormat.THERION)
        assertEquals("extend start 1\nextend vertical 1 2\n", output)
    }

    // The .espec, from SurvexExporterTest. createStraightNorth is 1 -> 2 -> 3 -> 4, with 4 active.

    @Test
    fun theSvxHasNoExtendCommands() {
        val survey = TestSurveys.createStraightNorth()
        assertTrue(!SurvexExporter.export(survey).contains("*extend"))

        // Not even when there is a direction to say, which is what used to make the file invalid.
        survey.activeStation.extendedElevationDirection = ExtendedElevationDirection.LEFT
        assertTrue(!SurvexExporter.export(survey).contains("*extend"))
    }

    @Test
    fun theEspecStartsAtTheOrigin() {
        val survey = TestSurveys.createStraightNorth()
        assertTrue(SurvexExporter.espec(survey).contains("*start ${survey.origin.name}"))
    }

    @Test
    fun theEspecTurnsAStationLeft() {
        val survey = TestSurveys.createStraightNorth()
        survey.activeStation.extendedElevationDirection = ExtendedElevationDirection.LEFT

        assertTrue(SurvexExporter.espec(survey).contains("*eleft ${survey.activeStation.name}"))
    }

    @Test
    fun theEspecTurnsAStationBackRight() {
        val survey = TestSurveys.createStraightNorth()
        survey.getStationByName("2")!!.extendedElevationDirection = ExtendedElevationDirection.LEFT
        survey.getStationByName("3")!!.extendedElevationDirection = ExtendedElevationDirection.LEFT
        survey.activeStation.extendedElevationDirection = ExtendedElevationDirection.RIGHT

        assertTrue(SurvexExporter.espec(survey).contains("*eright ${survey.activeStation.name}"))
    }

    @Test
    fun theEspecLeavesOutStationsThatDoNotChange() {
        val survey = TestSurveys.createStraightNorth()

        val espec = SurvexExporter.espec(survey)

        assertTrue(espec.contains("*start ${survey.origin.name}"))
        assertTrue(!espec.contains("*eleft") && !espec.contains("*eright"), "was:\n$espec")
    }

    @Test
    fun theEspecCommentsOutAVerticalLegAndNamesBothEnds() {
        val survey = TestSurveys.createStraightNorth()
        survey.activeStation.extendedElevationDirection = ExtendedElevationDirection.VERTICAL

        val espec = SurvexExporter.espec(survey)

        assertTrue(espec.contains("; *evertical 3 4\n"), "was:\n$espec")
        assertTrue(!espec.replace("; *evertical", "").contains("*evertical"), "was:\n$espec")
    }

    /** A pitch does not change what the passage below it inherits. */
    @Test
    fun aVerticalLegLeavesTheInheritedDirectionAlone() {
        val survey = TestSurveys.createStraightNorth()
        survey.getStationByName("2")!!.extendedElevationDirection = ExtendedElevationDirection.LEFT
        survey.getStationByName("3")!!.extendedElevationDirection =
            ExtendedElevationDirection.VERTICAL
        // Station 4 stays at the default, right, which is a change from 2's left.

        assertTrue(SurvexExporter.espec(survey).contains("*eright 4"))
    }

    @Test
    fun anEspecIsOnlyNeededWhenADirectionIsNotTheDefault() {
        assertTrue(!SurvexExporter.hasNonDefaultDirections(TestSurveys.createStraightNorth()))

        val notDefault = listOf(ExtendedElevationDirection.LEFT, ExtendedElevationDirection.VERTICAL)
        for (direction in notDefault) {
            val survey = TestSurveys.createStraightNorth()
            survey.activeStation.extendedElevationDirection = direction
            assertTrue(SurvexExporter.hasNonDefaultDirections(survey), "$direction")
        }

        val leftOrigin = TestSurveys.createStraightNorth()
        leftOrigin.origin.extendedElevationDirection = ExtendedElevationDirection.LEFT
        assertTrue(SurvexExporter.hasNonDefaultDirections(leftOrigin))
    }

    @Test
    fun theEspecFileIsCalledEspec() {
        assertEquals("espec", SurvexConstants.ESPEC_EXTENSION)
    }

    /**
     * A survey that sets off to the left says so for its origin. `*extend start` never did, which
     * is why a left-hand survey used to come back right.
     */
    @Test
    fun aLeftOriginIsMarked() {
        val survey = TestSurveys.createStraightNorth()
        SurveyUpdater.setExtendedElevationDirectionOfSubtree(
            survey.origin,
            ExtendedElevationDirection.LEFT,
        )

        assertEquals("*start 1\n*eleft 1\n", SurvexExporter.espec(survey))
    }

    @Test
    fun anAllLeftSurveyRoundTripsThroughTheEspec() {
        val exported = TestSurveys.createStraightNorth()
        SurveyUpdater.setExtendedElevationDirectionOfSubtree(
            exported.origin,
            ExtendedElevationDirection.LEFT,
        )

        val imported = TestSurveys.createStraightNorth()
        EspecParser.apply(SurvexExporter.espec(exported), imported)

        for (station in imported.getAllStations()) {
            assertEquals(
                ExtendedElevationDirection.LEFT,
                station.extendedElevationDirection,
                "station ${station.name}",
            )
        }
    }

    /**
     * The Java dereferences a missing station for an origin set vertical and throws; there is no
     * leg into the origin to name, so this leaves it out instead.
     */
    @Test
    fun aVerticalOriginDoesNotBreakTheEspec() {
        val survey = TestSurveys.createStraightNorth()
        survey.origin.extendedElevationDirection = ExtendedElevationDirection.VERTICAL

        assertEquals("*start 1\n", SurvexExporter.espec(survey))
    }

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
                // No `*extend` lines any more: the directions go in a .espec beside the file.
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
        assertTrue(!output.contains("copyright"), "was:\n$output")
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
