package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.imports.PocketTopoTxtImporter
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading PocketTopo's text export.
 *
 * The fixture is `PocketTopoTxtImporterTest.FAKE_TEXT` from the Android app's own test suite, so
 * the three assertions it makes are made here too, on three targets rather than one.
 */
class PocketTopoTxtImportTest {

    private val fakeText =
        listOf(
            "TRIP",
            "DATE 2005-07-01 ",
            "DECLINATION     0.00",
            "DATA",
            "1.0\t\t193.78\t0.41\t9.118\t>",
            "1.0\t\t328.51\t14.60\t4.709\t>",
            "",
            "PLAN",
            "STATIONS",
            "0.000\t0.000\t1.0",
            "-10.255\t1.283\t1.1",
            "SHOTS",
            "1.597\t-1.073\t9.846\t1.700",
            "9.846\t1.700\t12.401\t0.728",
            "POLYLINE BROWN",
            "4.980\t-55.180",
            "POLYLINE RED",
            "3.780\t-48.580",
            "",
            "ELEVATION",
            "STATIONS",
            "0.000\t0.000\t1.0",
            "10.335\t0.789\t1.1",
            "SHOTS",
            "22.419\t2.357\t31.121\t10.880",
            "31.121\t10.880\t33.233\t17.211",
            "POLYLINE BLUE",
            "70.600\t-23.300",
            "70.800\t-23.300",
        ).joinToString("\n")

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ---------------------------------------------------------------------------------------
    // The three the Android app asserts itself
    // ---------------------------------------------------------------------------------------

    @Test
    fun aSectionIsItsHeaderUpToTheNextBlankLine() {
        assertEquals(
            "1.0\t\t193.78\t0.41\t9.118\t>\n1.0\t\t328.51\t14.60\t4.709\t>",
            PocketTopoTxtImporter.section(fakeText, "DATA"),
        )
    }

    @Test
    fun aSubSectionRunsUntilTheNextAllCapitalsLine() {
        val elevation = assertNotNull(PocketTopoTxtImporter.section(fakeText, "ELEVATION"))
        assertEquals(
            "0.000\t0.000\t1.0\n10.335\t0.789\t1.1",
            PocketTopoTxtImporter.namedSubSection(elevation, "STATIONS"),
        )
    }

    @Test
    fun aPolylineBecomesAStrokeOfTheRightColour() {
        val plan = assertNotNull(PocketTopoTxtImporter.section(fakeText, "PLAN"))
        val paths = PocketTopoTxtImporter.parsePolylines(plan, Coord2D.ORIGIN)

        val brown = assertNotNull(paths.firstOrNull { it.colour == Colour.BROWN })
        assertClose(4.980f, brown.path[0].x)
        // Negated: PocketTopo draws with y increasing upwards and this app increases downwards.
        assertClose(55.180f, brown.path[0].y)
    }

    // ---------------------------------------------------------------------------------------
    // The rest of the file
    // ---------------------------------------------------------------------------------------

    @Test
    fun theCentrelineComesIn() {
        val survey = PocketTopoTxtImporter.read(fakeText, "Fake")

        // The origin takes the name the first row shot from.
        assertEquals("1.0", survey.origin.name)
        // Both rows have an empty destination column, so both are splays off it.
        assertEquals(2, survey.origin.onwardLegs.size)
        assertTrue(survey.origin.onwardLegs.none { it.hasDestination() })

        val first = survey.origin.onwardLegs[0]
        assertClose(9.118f, first.distance)
        assertClose(193.78f, first.azimuth)
        assertClose(0.41f, first.inclination)
    }

    @Test
    fun aRowWithAFarEndMakesAStationWithThatName() {
        val text =
            listOf("DATA", "1.0\t2.0\t90.00\t0.00\t10.000\t>", "", "PLAN", "STATIONS", "")
                .joinToString("\n")

        val survey = PocketTopoTxtImporter.read(text, "Fake")

        assertEquals(2, survey.getAllStations().size)
        assertNotNull(survey.getStationByName("2.0"))
    }

    @Test
    fun bothDrawingsComeIn() {
        val survey = PocketTopoTxtImporter.read(fakeText, "Fake")

        assertEquals(2, survey.planSketch.pathDetails.size)
        assertEquals(1, survey.elevationSketch.pathDetails.size)
        // The elevation's single polyline has two points, so it is a line rather than a dot.
        assertEquals(2, survey.elevationSketch.pathDetails[0].path.size)
    }

    /**
     * PocketTopo draws in whatever coordinates its own layout used. The plan's station list puts
     * the origin at (0, 0), so nothing shifts; a file that put it elsewhere would shift everything.
     */
    @Test
    fun theDrawingIsShiftedOntoTheOriginStation() {
        val plan = assertNotNull(PocketTopoTxtImporter.section(fakeText, "PLAN"))
        val stations = PocketTopoTxtImporter.namedSubSection(plan, "STATIONS").lines()

        assertEquals(Coord2D(0f, 0f), PocketTopoTxtImporter.offsetForNamedStation(stations, "1.0"))
        assertEquals(
            Coord2D(-10.255f, 1.283f),
            PocketTopoTxtImporter.offsetForNamedStation(stations, "1.1"),
        )
        assertNull(PocketTopoTxtImporter.offsetForNamedStation(stations, "not a station"))
    }

    @Test
    fun anOffsetMovesTheWholeStroke() {
        val plan = assertNotNull(PocketTopoTxtImporter.section(fakeText, "PLAN"))
        val paths = PocketTopoTxtImporter.parsePolylines(plan, Coord2D(1f, 2f))

        val brown = assertNotNull(paths.firstOrNull { it.colour == Colour.BROWN })
        assertClose(4.980f - 1f, brown.path[0].x)
        assertClose(-(-55.180f - 2f), brown.path[0].y)
    }

    @Test
    fun americanGreyIsTheSameColourAsBritishGrey() {
        assertEquals(Colour.GREY, PocketTopoTxtImporter.interpretColour("GRAY"))
        assertEquals(Colour.GREY, PocketTopoTxtImporter.interpretColour("GREY"))
        // Anything the app has no brush for draws black rather than refusing the file.
        assertEquals(Colour.BLACK, PocketTopoTxtImporter.interpretColour("CHARTREUSE"))
        assertEquals(Colour.BLACK, PocketTopoTxtImporter.interpretColour(""))
    }

    // ---------------------------------------------------------------------------------------
    // The four crashes this port does not reproduce
    // ---------------------------------------------------------------------------------------

    /**
     * `getSection` calls `matcher.find()` without checking it matched and then `matcher.group(1)`,
     * so a file exported before anything was drawn takes the app down.
     */
    @Test
    fun aFileWithNoDrawingImportsRatherThanThrowing() {
        val text = listOf("DATA", "1.0\t\t90.00\t0.00\t10.000\t>").joinToString("\n")

        val survey = PocketTopoTxtImporter.read(text, "Fake")

        assertEquals(1, survey.origin.onwardLegs.size)
        assertEquals(0, survey.planSketch.pathDetails.size)
        assertEquals(0, survey.elevationSketch.pathDetails.size)
    }

    /** The Java guards on `fields.length < 3` and then reads `fields[3]` and `fields[4]`. */
    @Test
    fun aShortDataRowIsSkippedRatherThanThrowing() {
        val text =
            listOf(
                "DATA",
                "1.0\t\t90.00",
                "1.0\t\t90.00\t0.00\t10.000\t>",
                "1.0\t\tnot\ta\tnumber",
            ).joinToString("\n")

        val survey = PocketTopoTxtImporter.read(text, "Fake")

        assertEquals(1, survey.origin.onwardLegs.size)
    }

    /** `getOffsetForNamedStation` reads `tokens[2]` with no length check. */
    @Test
    fun aShortStationLineIsSkippedRatherThanThrowing() {
        val lines = listOf("0.000", "0.000\t0.000\t1.0")
        assertEquals(Coord2D(0f, 0f), PocketTopoTxtImporter.offsetForNamedStation(lines, "1.0"))
    }

    /**
     * When the origin is not in the station list the Java falls back to the far end of the first
     * leg — and then calls `minus` on an offset it has just failed to find.
     */
    @Test
    fun anUnanchoredDrawingLandsAtTheOriginRatherThanThrowing() {
        val text =
            listOf(
                "DATA",
                "1.0\t2.0\t90.00\t0.00\t10.000\t>",
                "",
                "PLAN",
                "STATIONS",
                // Neither station named here.
                "5.000\t5.000\t9.9",
                "POLYLINE RED",
                "1.000\t1.000",
            ).joinToString("\n")

        val survey = PocketTopoTxtImporter.read(text, "Fake")

        assertEquals(1, survey.planSketch.pathDetails.size)
        val point = survey.planSketch.pathDetails[0].path[0]
        assertClose(1f, point.x)
        assertClose(-1f, point.y)
    }

    /** A row shot from a station that does not exist yet loses that shot, not the whole cave. */
    @Test
    fun aRowFromAnUnknownStationIsSkipped() {
        val text =
            listOf(
                "DATA",
                "1.0\t2.0\t90.00\t0.00\t10.000\t>",
                "9.9\t\t45.00\t0.00\t5.000\t>",
                "2.0\t\t45.00\t0.00\t5.000\t>",
            ).joinToString("\n")

        val survey = PocketTopoTxtImporter.read(text, "Fake")

        assertEquals(2, survey.getAllStations().size)
        assertEquals(1, assertNotNull(survey.getStationByName("2.0")).onwardLegs.size)
    }

    /**
     * Three similar splays off one station are a passage wall measured carefully, not a leg taken
     * three times. The Java's text importer hands them to `SurveyUpdater.update`, whose triple-shot
     * rule promotes them into a station that is not in the file — auto-named, with the rest of the
     * import hanging off it. Its own binary importer avoids `SurveyUpdater` for this reason.
     */
    @Test
    fun repeatedSplaysDoNotInventAStation() {
        val text =
            listOf(
                "DATA",
                "1.0\t\t90.00\t0.00\t3.000\t>",
                "1.0\t\t90.10\t0.10\t3.010\t>",
                "1.0\t\t89.90\t-0.10\t2.990\t>",
            ).joinToString("\n")

        val survey = PocketTopoTxtImporter.read(text, "Fake")

        assertEquals(1, survey.getAllStations().size, "an extra station appeared from nowhere")
        assertEquals(3, survey.origin.onwardLegs.size)
        assertTrue(survey.origin.onwardLegs.none { it.hasDestination() })
    }

    @Test
    fun anEmptyFileIsAnEmptySurveyRatherThanAThrow() {
        val survey = PocketTopoTxtImporter.read("", "Nothing")
        assertEquals(1, survey.getAllStations().size)
        assertEquals(0, survey.planSketch.pathDetails.size)
    }
}
