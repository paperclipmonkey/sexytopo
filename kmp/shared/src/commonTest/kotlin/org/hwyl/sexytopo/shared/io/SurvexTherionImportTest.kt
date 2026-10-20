package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.SurvexTherionWriter
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.imports.SurveyImporter
import org.hwyl.sexytopo.shared.io.imports.SurvexTherionImporter
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection.Companion.DEFAULT
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection.LEFT
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.TestSurveys
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a Survex or Therion file back into a survey.
 *
 * The strongest check available is a round trip, because this port already exports both formats
 * byte-identically against the Android app's own goldens: whatever the exporter writes, the
 * importer has to read back into the same cave. The rest of these use hand-written files, because a
 * colleague's `.svx` is not something SexyTopo wrote and is the other reason to have an importer.
 */
class SurvexTherionImportTest {

    private fun cave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, -3f))
        SurveyBuilder.addSplay(survey, survey.getStationByName("2")!!, Leg(2f, 45f, 0f))
        return survey
    }

    /** A Survex survey is the `.svx` and, since upstream moved the directions out, its `.espec`. */
    private fun roundTrip(survey: Survey, format: SurveyFormat): Survey =
        when (format) {
            SurveyFormat.SURVEX ->
                SurveyImporter.read(
                    SurvexExporter.export(survey),
                    format,
                    survey.name,
                    espec = SurvexExporter.espec(survey),
                )
            SurveyFormat.THERION ->
                SurveyImporter.read(TherionExporter.export(survey), format, survey.name)
        }

    @Test
    fun aSurveyExportedAsSurvexComesBackTheSameShape() {
        val original = cave()

        val imported = roundTrip(original, SurveyFormat.SURVEX)

        assertEquals(
            original.getAllStations().map { it.name }.sorted(),
            imported.getAllStations().map { it.name }.sorted(),
        )
        val legs = imported.getAllLegs()
        assertEquals(3, legs.size)
        assertEquals(2, legs.count { it.hasDestination() })
        assertEquals(1, legs.count { !it.hasDestination() }, "the splay did not survive")
    }

    @Test
    fun aSurveyExportedAsTherionComesBackTheSameShape() {
        val imported = roundTrip(cave(), SurveyFormat.THERION)

        assertEquals(3, imported.getAllStations().size)
        assertEquals(1, imported.getAllLegs().count { !it.hasDestination() })
    }

    @Test
    fun theReadingsThemselvesSurvive() {
        val imported = roundTrip(cave(), SurveyFormat.SURVEX)

        val first = imported.getAllLegs().first { it.hasDestination() }
        assertEquals(10f, first.distance, 0.001f)
        assertEquals(0f, first.azimuth, 0.001f)
        assertEquals(0f, first.inclination, 0.001f)

        val downhill = imported.getAllLegs().first { it.inclination < 0 }
        assertEquals(5f, downhill.distance, 0.001f)
        assertEquals(90f, downhill.azimuth, 0.001f)
        assertEquals(-3f, downhill.inclination, 0.001f)
    }

    /**
     * A station comment written into the passage block comes back onto its station.
     *
     * It is written in one place and read from another, which is exactly the sort of pairing that
     * only a round trip catches.
     */
    @Test
    fun aStationCommentSurvivesTheRoundTrip() {
        val original = cave()
        original.getStationByName("2")!!.comment = "junction"

        val imported = roundTrip(original, SurveyFormat.SURVEX)

        assertEquals("junction", imported.getStationByName("2")!!.comment)
    }

    /**
     * Left and right carry down the passage, and both formats keep them.
     *
     * Therion through its `extend` lines, Survex through the `.espec` beside the `.svx` — which is
     * upstream's answer to the gap this test was first written for, when the Java's `SurvexImporter`
     * read no directions at all.
     */
    @Test
    fun anExtendedElevationDirectionSurvivesTheRoundTrip() {
        val original = cave()
        original.getStationByName("2")!!.extendedElevationDirection = ExtendedElevationDirection.LEFT

        for (format in SurveyFormat.entries) {
            val imported = roundTrip(original, format)
            assertEquals(
                ExtendedElevationDirection.LEFT,
                imported.getStationByName("2")!!.extendedElevationDirection,
                "direction lost on the $format round trip",
            )
        }
    }

    /**
     * A pitch applies to the one leg it names and nowhere else, on either format - the same
     * distinction [ExtendedElevationDirection.propagates] draws in the model.
     */
    @Test
    fun aVerticalDirectionAppliesOnlyToItsOwnLegOnEitherFormat() {
        val original = cave()
        original.getStationByName("2")!!.extendedElevationDirection =
            ExtendedElevationDirection.VERTICAL

        for (format in SurveyFormat.entries) {
            val imported = roundTrip(original, format)
            assertEquals(
                ExtendedElevationDirection.VERTICAL,
                imported.getStationByName("2")!!.extendedElevationDirection,
                "the pitch itself, on $format",
            )
            assertEquals(
                ExtendedElevationDirection.DEFAULT,
                imported.origin.extendedElevationDirection,
                "the station above a pitch should not inherit it, on $format",
            )
        }
    }

    /**
     * A Survex file with `*extend` lines in it: hand-written, or exported by this app before the
     * directions moved to a `.espec`. The Java's `SurvexImporter` skips every `*` line, so there is
     * no "faithful to the original" answer here, only "does the thing Survex's own `*extend`
     * command means actually happen."
     */
    @Test
    fun aHandWrittenSurvexExtendLineIsRead() {
        val handWritten =
            """
            *begin Swildons
            *data normal from to length compass clino
            1 2 10.0 0.0 0.0
            *extend left 2
            *end Swildons
            """.trimIndent()

        val survey = SurveyImporter.read(handWritten, SurveyFormat.SURVEX, "Swildons")

        assertEquals(
            ExtendedElevationDirection.LEFT,
            survey.getStationByName("2")!!.extendedElevationDirection,
        )
    }

    /** `extend start <station>` is a no-op marker, not a direction called "start". */
    @Test
    fun anExtendStartLineIsNotMistakenForADirection() {
        val handWritten =
            """
            *begin Swildons
            *data normal from to length compass clino
            1 2 10.0 0.0 0.0
            *extend start 1
            *end Swildons
            """.trimIndent()

        val survey = SurveyImporter.read(handWritten, SurveyFormat.SURVEX, "Swildons")

        assertEquals(ExtendedElevationDirection.DEFAULT, survey.origin.extendedElevationDirection)
    }

    @Test
    fun theTripSurvivesTheRoundTrip() {
        val original = cave()
        original.trip =
            Trip(SurveyDate(2026, 4, 12)).also {
                it.team =
                    listOf(
                        Trip.TeamEntry("Lizzie Waterworth", listOf(Trip.Role.BOOK)),
                        Trip.TeamEntry("A N Other", listOf(Trip.Role.INSTRUMENTS)),
                    )
                it.instrument = "DistoX2"
                it.copyrightHolder = "Wessex Cave Club"
                it.licence = "CC BY 4.0"
            }

        val trip = roundTrip(original, SurveyFormat.SURVEX).trip!!

        assertEquals(SurveyDate(2026, 4, 12), trip.surveyDate)
        assertEquals("DistoX2", trip.instrument)
        assertEquals("Wessex Cave Club", trip.copyrightHolder)
        assertEquals("CC BY 4.0", trip.licence)
        assertEquals(listOf("Lizzie Waterworth", "A N Other"), trip.team.map { it.name })
        assertEquals(listOf(Trip.Role.BOOK), trip.team.first().roles)
    }

    /** Therion writes explorers on their own `explo-team` line rather than as a `team` role. */
    @Test
    fun anExplorerSurvivesTheTherionRoundTrip() {
        val original = cave()
        original.trip =
            Trip(SurveyDate(2026, 4, 12)).also {
                it.team = listOf(Trip.TeamEntry("Finder", listOf(Trip.Role.EXPLORATION)))
            }

        val trip = roundTrip(original, SurveyFormat.THERION).trip!!

        assertEquals("Finder", trip.team.single().name)
        assertTrue(Trip.Role.EXPLORATION in trip.team.single().roles)
    }

    /**
     * The repeated readings a station was promoted from come back with it.
     *
     * Losing them turns three readings into one and discards the evidence a surveyor would use to
     * check a leg that looks wrong.
     */
    @Test
    fun thePromotedReadingsComeBack() {
        val survey = Survey("T")
        // Three agreeing shots: the engine promotes them and keeps the precursors.
        repeat(3) { SurveyUpdater.update(survey, Leg(10f, 0f, 0f)) }
        val exported = SurvexExporter.export(survey)

        val imported = SurveyImporter.read(exported, SurveyFormat.SURVEX, "T")

        val promoted = imported.getAllLegs().first { it.hasDestination() }
        assertTrue(
            promoted.promotedFrom.isNotEmpty(),
            "the readings behind the station were lost:\n$exported",
        )
    }

    private val handWritten =
        """
        *begin swildons
        *date 2026.04.12
        *team "A Caver" notes instruments
        *data normal from to tape compass clino
        1 2 10.00 0.00 0.00
        2 3 5.00 90.00 -3.00
        2 .. 2.00 45.00 0.00 ; wall
        *end swildons
        """.trimIndent()

    @Test
    fun aHandWrittenSurvexFileReads() {
        val survey = SurveyImporter.read(handWritten, SurveyFormat.SURVEX, "Swildons")

        assertEquals(3, survey.getAllStations().size)
        assertEquals("1", survey.origin.name)
        assertEquals(2, survey.getAllLegs().count { it.hasDestination() })
        assertEquals("A Caver", survey.trip!!.team.single().name)
        assertEquals(
            listOf(Trip.Role.BOOK, Trip.Role.INSTRUMENTS),
            survey.trip!!.team.single().roles,
        )
    }

    /**
     * A trailing comment on a data line lands on the leg for a file with no version header.
     *
     * That is the modern convention, and the right assumption for a third-party file: somebody
     * writing `2 .. 2.00 45.00 0.00 ; wall` means that shot, not the station.
     */
    @Test
    fun aCommentOnAHandWrittenLineBelongsToTheLeg() {
        val survey = SurveyImporter.read(handWritten, SurveyFormat.SURVEX, "Swildons")

        val splay = survey.getAllLegs().first { !it.hasDestination() }
        assertEquals("wall", splay.comment)
    }

    /**
     * A file written by SexyTopo 1.11.2 or earlier meant the comment for the *station*.
     *
     * The version is read from a comment line only, so a cave whose name contains a version number
     * cannot be mistaken for a stamp.
     */
    @Test
    fun anOlderFileStillPutsTheCommentOnTheStation() {
        val old =
            """
            ; Created with SexyTopo 1.11.2 on 2020-01-01
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00 ; big chamber
            """.trimIndent()

        val survey = SurveyImporter.read(old, SurveyFormat.SURVEX, "T")

        assertEquals("big chamber", survey.getStationByName("2")!!.comment)
        assertEquals("", survey.getAllLegs().first().comment)
    }

    @Test
    fun theVersionIsReadFromCommentLinesOnly() {
        assertEquals(Triple(1, 11, 2), SurveyImporter.versionOf("; SexyTopo 1.11.2 here"))
        assertNull(SurveyImporter.versionOf("1 2 10.0 0.0 0.0 SexyTopo 1.11.2"))
        assertNull(SurveyImporter.versionOf("no version at all"))
        // No stamp means third-party, which is read the modern way.
        assertTrue(SurveyImporter.writtenWithLegComments("no version at all"))
        assertTrue(SurveyImporter.writtenWithLegComments("; SexyTopo 1.11.3"))
        assertTrue(!SurveyImporter.writtenWithLegComments("; SexyTopo 1.11.2"))
    }

    /**
     * A leg shot from the far end is recognised by *position*, not by station name.
     *
     * A leg naming a station never seen before in the from position was shot backwards. Station
     * numbers say nothing: `3 2 ...` after `1 2 ...` is a backsight from the new station 3.
     */
    @Test
    fun aBacksightIsRecognisedByWhichEndIsNew() {
        val text =
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            3 2 5.00 270.00 3.00
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.SURVEX, "T")

        val backsight = survey.getAllLegs().first { it.wasShotBackwards }
        assertEquals("3", backsight.destination.name)
        // Shot from 2 towards 3, so 2 is where it hangs.
        assertEquals(listOf("1", "2", "3"), survey.getAllStations().map { it.name }.sorted())
    }

    @Test
    fun aPassageRowIsNotMistakenForAShot() {
        val text =
            """
            *data passage station left right up down ignoreall
            2	-	-	-	-	junction
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.SURVEX, "T")

        assertEquals(1, survey.getAllLegs().size)
        assertEquals("junction", survey.getStationByName("2")!!.comment)
    }

    /**
     * Both comments on a station are kept, not one of them.
     *
     * A station can carry a passage-block comment and a leg-line one; they came from different
     * places and mean different things, so the original joins them rather than losing either.
     */
    @Test
    fun twoCommentsOnOneStationAreJoined() {
        val text =
            """
            ; Created with SexyTopo 1.11.2 on 2020-01-01
            *data passage station left right up down ignoreall
            2	-	-	-	-	from the passage block
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00 ; from the leg line
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.SURVEX, "T")

        assertEquals(
            "from the passage block :: from the leg line",
            survey.getStationByName("2")!!.comment,
        )
    }

    @Test
    fun aFileWithNoMetadataHasNoTrip() {
        val text =
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            """.trimIndent()

        assertNull(SurvexTherionImporter.parseMetadata(text, SurveyFormat.SURVEX))
        assertNull(SurveyImporter.read(text, SurveyFormat.SURVEX, "T").trip)
    }

    @Test
    fun inlinePromotedLegsAreRead() {
        val text =
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00 ; {from: 10.01 0.10 0.00, 9.99 359.90 0.10}
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.SURVEX, "T")

        val leg = survey.getAllLegs().single()
        assertEquals(2, leg.promotedFrom.size)
        assertEquals(10.01f, leg.promotedFrom.first().distance, 0.001f)
        // The instruction is stripped from the comment rather than left in it.
        assertEquals("", leg.comment)
    }

    @Test
    fun commentedPrecursorLinesAreRead() {
        val text =
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            ;1 2 10.01 0.10 0.00
            ;1 2 9.99 359.90 0.10
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.SURVEX, "T")

        assertEquals(2, survey.getAllLegs().single().promotedFrom.size)
    }

    @Test
    fun anUnrelatedCommentBelowALegIsNotAReading() {
        val text =
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            ; nothing to do with the leg above
            """.trimIndent()

        assertEquals(0, SurveyImporter.read(text, SurveyFormat.SURVEX, "T")
            .getAllLegs().single().promotedFrom.size)
    }

    /**
     * An illegal reading names its own line, the way the Java's
     * `catch (Exception exception) { throw new Exception("Error importing this line: " + line) }`
     * does - not just "could not read the file", which says nothing a surveyor could act on.
     */
    @Test
    fun anIllegalReadingNamesItsOwnLine() {
        val text =
            """
            *data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            2 3 -5.00 45.00 0.00
            """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            SurveyImporter.read(text, SurveyFormat.SURVEX, "T")
        }

        assertTrue(
            exception.message?.contains("2 3 -5.00 45.00 0.00") == true,
            "expected the offending line in the message, got: ${exception.message}",
        )
    }

    /**
     * `extend left 2` turns the passage beyond station 2 as well, as `TherionImporter` applies it.
     * Read inline, as this port first did, it reached station 2 alone, because the stations below
     * had not been read yet.
     */
    @Test
    fun anExtendLineCarriesDownThePassage() {
        val text =
            """
            centreline
            data normal from to tape compass clino
            1 2 10.00 0.00 0.00
            2 3 10.00 0.00 0.00
            3 4 10.00 0.00 0.00
            extend start 1
            extend left 2
            endcentreline
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.THERION, "T")

        assertEquals(DEFAULT, survey.origin.extendedElevationDirection)
        for (name in listOf("2", "3", "4")) {
            assertEquals(LEFT, survey.getStationByName(name)!!.extendedElevationDirection, name)
        }
    }

    // --- Splay names, from SurvexImporterTest and TherionImporterTest ---

    /**
     * Every anonymous-station token either format uses. A real leg follows each splay because the
     * origin is only set from a leg with two ends.
     */
    @Test
    fun everyAnonymousStationNameIsASplay() {
        for (token in listOf("..", ".", "...", "-")) {
            val survey = Survey()
            val text = "1\t$token\t1.0\t0.0\t0.0\n1\t2\t5.0\t0.0\t0.0"
            SurvexTherionImporter.parseCentreline(text, survey)

            assertNull(survey.getStationByName(token), "a station called $token")
            assertEquals(1, survey.origin.getUnconnectedOnwardLegs().size, token)
        }
    }

    @Test
    fun consecutiveAnonymousSplaysStaySeparate() {
        val text =
            "1\t..\t1.0\t0.0\t0.0\n" +
                "1\t..\t1.2\t90.0\t0.0\n" +
                "1\t..\t0.8\t180.0\t0.0\n" +
                "1\t2\t5.0\t0.0\t0.0\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey)

        assertEquals(2, survey.getAllStations().size)
        assertEquals(3, survey.origin.getUnconnectedOnwardLegs().size)
    }

    // --- Repeated readings on consecutive lines, from SurvexImporterTest ---

    private val threeReadings =
        "1\t2\t5.001\t0.02\t0.01\n" + "1\t2\t4.999\t359.98\t-0.01\n" + "1\t2\t5.000\t0.00\t0.02\n"

    @Test
    fun repeatedLinesBecomeOnePromotedLeg() {
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(threeReadings, survey, true)

        assertEquals(2, survey.getAllStations().size)
        assertEquals(3, survey.origin.getConnectedOnwardLegs().single().promotedFrom.size)
    }

    @Test
    fun repeatedLinesAreAveragedAsALiveSurveyWouldBe() {
        val expected =
            SurveyUpdater.averageLegs(
                listOf(Leg(5.001f, 0.02f, 0.01f), Leg(4.999f, 359.98f, -0.01f), Leg(5f, 0f, 0.02f)),
            )

        val survey = Survey()
        SurvexTherionImporter.parseCentreline(threeReadings, survey, true)
        val leg = survey.origin.getConnectedOnwardLegs().single()

        assertEquals(expected.distance, leg.distance, 0.001f)
        assertEquals(expected.azimuth, leg.azimuth, 0.001f)
        assertEquals(expected.inclination, leg.inclination, 0.001f)
    }

    /** The surveyor's own choice of algorithm, not the default, does the averaging. */
    @Test
    fun repeatedLinesAreAveragedWithTheChosenAlgorithm() {
        val settings = SurveySettings(legAmalgamationAlgorithm = LegAmalgamationAlgorithm.CARTESIAN)
        val readings =
            listOf(Leg(5.001f, 0.02f, 0.01f), Leg(4.999f, 359.98f, -0.01f), Leg(5f, 0f, 0.02f))

        val survey = Survey()
        SurvexTherionImporter.parseCentreline(threeReadings, survey, true, settings)

        assertEquals(
            SurveyUpdater.averageLegs(readings, settings).azimuth,
            survey.origin.getConnectedOnwardLegs().single().azimuth,
            0.0001f,
        )
    }

    @Test
    fun aSingleLineIsNotAGroup() {
        val survey = Survey()
        SurvexTherionImporter.parseCentreline("1\t2\t5.0\t0.0\t0.0", survey)

        assertTrue(!survey.origin.getConnectedOnwardLegs().single().wasPromoted())
    }

    @Test
    fun theFirstLinesCommentIsTheLegs() {
        val text = "1\t2\t5.001\t0.02\t0.01\tBig Sandy Chamber\n" + "1\t2\t4.999\t359.98\t-0.01\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey, true)

        assertEquals("Big Sandy Chamber", survey.origin.getConnectedOnwardLegs().single().comment)
    }

    @Test
    fun aLaterLinesCommentStaysOnItsReading() {
        val text = "1\t2\t5.001\t0.02\t0.01\n" + "1\t2\t4.999\t359.98\t-0.01\tcold draught\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey, true)
        val leg = survey.origin.getConnectedOnwardLegs().single()

        assertEquals("", leg.comment)
        assertEquals("cold draught", leg.promotedFrom[1].comment)
    }

    /** A file from 1.11.2 or earlier meant the comment for the new station, grouped or not. */
    @Test
    fun theFirstLinesCommentGoesToTheStationInAnOlderFile() {
        val text = "1\t2\t5.001\t0.02\t0.01\tBig Sandy Chamber\n" + "1\t2\t4.999\t359.98\t-0.01\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey, false)
        val leg = survey.origin.getConnectedOnwardLegs().single()

        assertEquals("Big Sandy Chamber", survey.getStationByName("2")!!.comment)
        assertEquals("", leg.comment)
        assertEquals(2, leg.promotedFrom.size)
    }

    @Test
    fun aLaterLinesCommentIsDroppedInAnOlderFile() {
        val text = "1\t2\t5.001\t0.02\t0.01\n" + "1\t2\t4.999\t359.98\t-0.01\tcold draught\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey, false)
        val leg = survey.origin.getConnectedOnwardLegs().single()

        assertEquals(2, leg.promotedFrom.size)
        assertEquals("", leg.promotedFrom[1].comment)
    }

    @Test
    fun repeatedBacksightsAreGroupedTheWayTheyWereShot() {
        val text =
            "1\t2\t5.0\t0.0\t0.0\n" +
                // Backward: 3 is new and 2 is not.
                "3\t2\t5.001\t180.02\t-0.01\n" +
                "3\t2\t4.999\t179.98\t0.01\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey, true)

        val two = survey.getStationByName("2")!!
        assertEquals(2, two.getConnectedOnwardLegs().single().promotedFrom.size)
    }

    @Test
    fun theLegAfterAGroupIsStillRead() {
        val text =
            "1\t2\t5.001\t0.02\t0.01\n" + "1\t2\t4.999\t359.98\t-0.01\n" + "2\t3\t5.0\t0.0\t0.0\n"
        val survey = Survey()
        SurvexTherionImporter.parseCentreline(text, survey, true)

        assertEquals(3, survey.getAllStations().size)
        assertTrue(survey.getStationByName("3") != null)
    }

    /** Not exported for Therion yet, but read for the day Therion can average them itself. */
    @Test
    fun therionReadsRepeatedLinesToo() {
        val text =
            """
            centreline
            data normal from to tape compass clino
            1	2	5.001	0.02	0.01
            1	2	4.999	359.98	-0.01
            endcentreline
            """.trimIndent()

        val survey = SurveyImporter.read(text, SurveyFormat.THERION, "T")

        assertEquals(2, survey.origin.getConnectedOnwardLegs().single().promotedFrom.size)
    }

    @Test
    fun aSurvexPromotedLegRoundTrips() {
        val original = TestSurveys.createStraightNorthThroughRepeats()
        val centreline = SurvexTherionWriter.centrelineData(original, SurveyFormat.SURVEX)

        val reimported = Survey()
        SurvexTherionImporter.parseCentreline(centreline, reimported, true)
        val leg = reimported.origin.getConnectedOnwardLegs().single()

        assertEquals(3, leg.promotedFrom.size)
        assertEquals(5f, leg.distance, 0.001f)
    }

    /**
     * Two things change on the way round, and upstream documents both as the price of writing raw
     * readings: the leg is averaged again with whichever algorithm is current, and the leg's comment
     * and its first reading's share one line, so they come back merged onto the leg.
     */
    @Test
    fun aSurvexPromotedLegRoundTripIsLossyInTheDocumentedWays() {
        val first = Leg(5.1f, 1f, 2f).also { it.comment = "cold draught" }
        val readings = arrayOf(first, Leg(4.9f, 359f, -1f), Leg(5f, 0.5f, 0f))
        val original = Survey()
        val promoted = Leg.upgradeSplayToConnectedLeg(Leg(5f, 0f, 0f), Station("2"), readings)
        promoted.comment = "Big Sandy Chamber"
        original.origin.addOnwardLeg(promoted)

        val centreline = SurvexTherionWriter.centrelineData(original, SurveyFormat.SURVEX)
        val reimported = Survey()
        SurvexTherionImporter.parseCentreline(centreline, reimported, true)
        val leg = reimported.origin.getConnectedOnwardLegs().single()

        val expected = SurveyUpdater.averageLegs(readings.toList())
        assertEquals(expected.distance, leg.distance, 0.001f)
        assertEquals(expected.azimuth, leg.azimuth, 0.001f)
        assertEquals(expected.inclination, leg.inclination, 0.001f)
        assertEquals("Big Sandy Chamber :: cold draught", leg.comment)
        assertTrue(!leg.promotedFrom[0].hasComment())
    }

    /**
     * A reading that is not a number is an error, and it names its own line — not the good line
     * above it that a run of repeated readings might have swept it up with.
     */
    @Test
    fun aBadLineAfterALegIsReportedAgainstItself() {
        val text = "1\t2\t5.0\t0.0\t0.0\n" + "1\t2\t5.0\tnorth\t0.0\n"

        val exception =
            assertFailsWith<IllegalArgumentException> {
                SurvexTherionImporter.parseCentreline(text, Survey(), true)
            }

        assertTrue(
            exception.message?.endsWith("1\t2\t5.0\tnorth\t0.0") == true,
            "got: ${exception.message}",
        )
    }

    /** Export writes dots; hyphens are read too, for a file another tool wrote or edited. */
    @Test
    fun aHyphenatedTripDateIsRead() {
        val survex = SurvexTherionImporter.parseMetadata("*date 2026-01-05\n", SurveyFormat.SURVEX)
        val therion = SurvexTherionImporter.parseMetadata("date 2026-01-05\n", SurveyFormat.THERION)

        assertEquals(SurveyDate(2026, 1, 5), survex!!.surveyDate)
        assertEquals(SurveyDate(2026, 1, 5), therion!!.surveyDate)
    }
}
