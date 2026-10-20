package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.SymbolDetail
import org.hwyl.sexytopo.shared.model.sketch.TextDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawing as an XVI: Therion's own background-image format.
 *
 * An XVI is what a surveyor traces over in xtherion, and it is made of line segments and nothing
 * else — so a label has to be drawn as strokes and a symbol as a polyline, which is why this port
 * carries a stroke font and a second, simplified set of symbol shapes.
 *
 * There is no golden here, for the same reason there is none for the SVG: the Java walks a
 * `HashMap` of stations, so two exports of an unchanged survey come out in different orders.
 */
class XviExportTest {

    private fun cave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        return survey
    }

    private fun exportOf(survey: Survey, sketch: Sketch = survey.getSketch(Projection2D.PLAN)) =
        XviExporter.export(
            sketch = sketch,
            space = Projection2D.PLAN.project(survey),
            scale = 50f,
            gridFrame = Frame(0f, 500f, 0f, 500f),
        )

    @Test
    fun theFileHasTheFiveBlocksTherionExpects() {
        val xvi = exportOf(cave())

        for (command in
            listOf(
                "set XVIgrids",
                "set XVIstations",
                "set XVIshots",
                "set XVIsketchlines",
                "set XVIgrid",
            )) {
            assertContains(xvi, command)
        }
    }

    /**
     * Everything after a block's opening `{` and before the `}` that closes it.
     *
     * The rows inside are themselves `\t {…}`, so a naive `substringBefore("}")` stops at the end
     * of the first row — which is how the first version of these tests passed while reading one
     * station out of three.
     */
    private fun block(xvi: String, name: String): String =
        xvi.substringAfter("$name {\n").lineSequence().takeWhile { it != "}" }.joinToString("\n")

    @Test
    fun everyStationIsNamedAndPlaced() {
        val stations = block(exportOf(cave()), "set XVIstations")

        assertEquals(3, stations.lines().size)
        for (name in listOf("1", "2", "3")) {
            assertContains(stations, " $name}")
        }
    }

    /**
     * y is flipped exactly once, here.
     *
     * Survey space is y north-positive and Therion's canvas is y down. A second flip anywhere
     * upstream is invisible until a drawing comes out mirrored — and mirrored is not obviously
     * wrong when you are looking at a cave you have not surveyed before.
     */
    @Test
    fun northIsUpInTheSurveyAndDownInTheFile() {
        val survey = Survey("T")
        // Ten metres due north, which is y = -10 in sketch space.
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))

        val stations = block(exportOf(survey), "set XVIstations")

        assertContains(stations, "{0.00 0.00 1}")
        assertContains(stations, "{0.00 500.00 2}")
    }

    @Test
    fun aSketchStrokeIsWrittenWithItsColour() {
        val survey = cave()
        val sketch = survey.getSketch(Projection2D.PLAN)
        sketch.startNewPath(Coord2D(1f, 1f), Colour.BROWN).lineTo(Coord2D(2f, 2f))

        val lines = block(exportOf(survey), "set XVIsketchlines")

        assertContains(lines, "BROWN 50.00 -50.00 100.00 -100.00")
    }

    /**
     * A label becomes strokes, because an XVI cannot carry text.
     *
     * Upper-cased first: the glyph table has no lower case, and the original upper-cases before
     * looking anything up — so "sump" comes out as four letters rather than four unknown boxes.
     */
    @Test
    fun aLabelIsDrawnAsStrokes() {
        val label = TextDetail(Coord2D(0f, 0f), "sump", 1f, Colour.BLACK)

        val strokes = XviExporter.textAsPaths(label)

        // Four letters, each several segments, and every segment a two-point path.
        assertTrue(strokes.size > 8, "only ${strokes.size} strokes for four letters")
        assertTrue(strokes.all { it.path.size == 2 })
        assertTrue(strokes.all { it.colour == Colour.BLACK })
    }

    /** An unknown character gets a box rather than nothing, so a bad label is visibly bad. */
    @Test
    fun anUnknownCharacterIsDrawnAsABox() {
        val known = XviExporter.textAsPaths(TextDetail(Coord2D.ORIGIN, "A", 1f, Colour.BLACK))
        val unknown = XviExporter.textAsPaths(TextDetail(Coord2D.ORIGIN, "é", 1f, Colour.BLACK))

        assertTrue(unknown.isNotEmpty(), "an unknown character drew nothing at all")
        assertEquals(4, unknown.size, "the fallback glyph is a four-sided box")
        assertTrue(known.isNotEmpty())
    }

    @Test
    fun aMultiLineLabelStacksDownwards() {
        val one = XviExporter.textAsPaths(TextDetail(Coord2D.ORIGIN, "A", 1f, Colour.BLACK))
        val two = XviExporter.textAsPaths(TextDetail(Coord2D.ORIGIN, "A\nA", 1f, Colour.BLACK))

        assertEquals(one.size * 2, two.size)
        val firstLineY = two.take(one.size).flatMap { it.path }.map { it.y }.min()
        val secondLineY = two.drop(one.size).flatMap { it.path }.map { it.y }.min()
        assertTrue(secondLineY > firstLineY, "the second line was not below the first")
    }

    @Test
    fun aSymbolIsDrawnAsPolylines() {
        val stamp = SymbolDetail(Coord2D(5f, 5f), "entrance", 2f, 0f, Colour.RED)

        val paths = XviExporter.symbolAsPaths(stamp)

        assertEquals(1, paths.size, "the entrance triangle is one polyline")
        assertTrue(paths.single().colour == Colour.RED)
        // Centred on the stamp: every point is within the stamp's own size of where it was put.
        assertTrue(paths.single().path.all { getDistance(it, Coord2D(5f, 5f)) <= 2f })
    }

    /** A symbol from a newer version of the app contributes nothing rather than throwing. */
    @Test
    fun anUnknownSymbolIsSkipped() {
        val stamp = SymbolDetail(Coord2D.ORIGIN, "not-a-real-symbol", 1f, 0f, Colour.BLACK)

        assertTrue(XviExporter.symbolAsPaths(stamp).isEmpty())
    }

    @Test
    fun aimingASymbolRotatesIt() {
        val upright = SymbolDetail(Coord2D.ORIGIN, "water-flow", 1f, 0f, Colour.BLUE)
        val turned = SymbolDetail(Coord2D.ORIGIN, "water-flow", 1f, 90f, Colour.BLUE)

        val a = XviExporter.symbolAsPaths(upright).flatMap { it.path }
        val b = XviExporter.symbolAsPaths(turned).flatMap { it.path }

        assertEquals(a.size, b.size)
        assertTrue(a != b, "aiming the symbol did not move any of its strokes")
    }

    /**
     * A cross-section is drawn where it sits, with a connector back to its station.
     *
     * Without the connector a reader of the traced drawing has a passage profile floating in blank
     * space with nothing to say which station it belongs to.
     */
    @Test
    fun aCrossSectionIsPlacedAndJoinedToItsStation() {
        val survey = cave()
        val middle = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 45f, 0f))
        val sketch = survey.getSketch(Projection2D.PLAN)
        sketch.crossSectionDetails.add(
            org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail(
                Coord2D(20f, -5f),
                CrossSectioner.section(survey, middle),
            ),
        )

        val lines = block(exportOf(survey), "set XVIsketchlines")

        assertContains(lines, "connect")
    }

    @Test
    fun theGridIsAnOriginTwoVectorsAndTwoCounts() {
        val xvi = exportOf(cave())

        val grid = xvi.substringAfter("set XVIgrid {").substringBefore("}")

        assertEquals(8, grid.trim().split(" ").size)
        assertContains(grid, "50.0 0.0 0.0 50.0")
    }

    @Test
    fun theSameSurveyExportsIdenticallyEveryTime() {
        val survey = cave()
        assertEquals(exportOf(survey), exportOf(survey))
    }

    // Cross-sections on the elevation, from `XviExporterTest` in upstream 1.13.0

    // What `TestSurveys.createWithCrossSectionsInPlanAndElevation` gives every section: splays 2m
    // east, 1.5m west, 1m north, 1m up and 1m down. Each is listed as how far it goes across the
    // XVI's page and up it, in metres, so the up splay is (0, 1) and the north one, which a
    // vertical section facing north sees head on, is (0, 0).
    private val verticalSplays =
        listOf("2.00,0.00", "-1.50,0.00", "0.00,0.00", "0.00,1.00", "0.00,-1.00").sorted()
    private val horizontalSplays =
        listOf("2.00,0.00", "-1.50,0.00", "0.00,1.00", "0.00,0.00", "0.00,0.00").sorted()

    /** As `XviExporterTest.export`: a metre to a unit, framed as the export frames it. */
    private fun export(survey: Survey, projection: Projection2D): String =
        XviExporter.export(
            sketch = survey.getSketch(projection),
            space = projection.project(survey),
            scale = 1f,
            gridFrame = SvgExporter.exportFrame(survey, projection),
        )

    /** The entries of one of the XVI's lists, such as XVIshots, without their braces. */
    private fun entries(xvi: String, list: String): List<String> =
        block(xvi, "set $list").lines()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .map { it.substring(1, it.length - 1) }

    /** The connectors from each station to its section, as "x1 y1 x2 y2". */
    private fun connectors(xvi: String): List<String> =
        entries(xvi, "XVIsketchlines")
            .filter { it.startsWith("connect ") }
            .map { it.removePrefix("connect ") }

    /** The shots leaving a point, as how far across and up each goes, in metres, sorted. */
    private fun shotsFrom(xvi: String, x: Float, y: Float): List<String> =
        entries(xvi, "XVIshots")
            .map { entry -> entry.split(" ").map { it.toFloat() } }
            .filter { abs(it[0] - x) < 0.005f && abs(it[1] - y) < 0.005f }
            .map { "${tidy(it[2] - it[0])},${tidy(it[3] - it[1])}" }
            .sorted()

    // Rounded to hundredths, with negative zero made an ordinary one
    private fun tidy(value: Float): String =
        formatFixed(floor(value * 100.0 + 0.5) / 100.0 + 0.0, 2)

    @Test
    fun theElevationJoinsEachStationToItsSection() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val joined = connectors(export(survey, Projection2D.EXTENDED_ELEVATION)).sorted()

        // Station 1 is at the origin and 3 is 10m along, and the sections at (2, 4) and (12, 4)
        // are drawn 4m up, which is 4 the other way on the XVI's page
        assertEquals(listOf("0.00 0.00 2.00 -4.00", "10.00 0.00 12.00 -4.00").sorted(), joined)
    }

    @Test
    fun theElevationsSectionsHaveTheirStationsNameAtTheirPosition() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val stations = entries(export(survey, Projection2D.EXTENDED_ELEVATION), "XVIstations")

        assertContains(stations, "2.00 -4.00 1")
        assertContains(stations, "12.00 -4.00 3")
    }

    @Test
    fun aVerticalSectionOnTheElevationHasItsSplaysAcrossThePassage() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val xvi = export(survey, Projection2D.EXTENDED_ELEVATION)

        assertEquals(verticalSplays, shotsFrom(xvi, 2f, -4f))
    }

    @Test
    fun aHorizontalSectionHasItsSplaysLyingFlatWithNorthUp() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val xvi = export(survey, Projection2D.EXTENDED_ELEVATION)

        // The north splay goes up the page, and the up and down splays have nothing to show
        assertEquals(horizontalSplays, shotsFrom(xvi, 12f, -4f))
    }

    @Test
    fun theSameStationHasADifferentSectionOnThePlanAndTheElevation() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        // Station 3 has a vertical section on the plan, drawn at (2, 10)...
        assertEquals(verticalSplays, shotsFrom(export(survey, Projection2D.PLAN), 2f, -10f))
        // ...and a horizontal one on the elevation, drawn at (12, 4)
        assertEquals(
            horizontalSplays,
            shotsFrom(export(survey, Projection2D.EXTENDED_ELEVATION), 12f, -4f),
        )
    }

    @Test
    fun theElevationsSectionsAreDrawnAtItsCrossSectionScale() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        survey.elevationSketch.crossSectionScale = 2f

        val xvi = export(survey, Projection2D.EXTENDED_ELEVATION)

        assertEquals(
            listOf("4.00,0.00", "-3.00,0.00", "0.00,0.00", "0.00,2.00", "0.00,-2.00").sorted(),
            shotsFrom(xvi, 2f, -4f),
        )
        assertEquals(
            listOf("4.00,0.00", "-3.00,0.00", "0.00,2.00", "0.00,0.00", "0.00,0.00").sorted(),
            shotsFrom(xvi, 12f, -4f),
        )
    }

    @Test
    fun eachExportHasOnlyItsOwnSketchsSections() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        survey.elevationSketch.addCrossSection(
            CrossSection.horizontal(survey.getStationByName("2")!!),
            Coord2D(7f, 4f),
        )

        assertEquals(2, connectors(export(survey, Projection2D.PLAN)).size)
        assertEquals(3, connectors(export(survey, Projection2D.EXTENDED_ELEVATION)).size)
    }

    @Test
    fun whatIsDrawnInAnElevationSectionIsPlacedAndScaledWithIt() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        val elevation = survey.elevationSketch
        // Each gets a line from (5, 0) to (10, 0) in the section's own drawing
        for (name in listOf("1", "3")) {
            val station = survey.getStationByName(name)!!
            elevation.crossSectionDetails.single { it.station === station }.sketch.pathDetails.add(
                PathDetail(listOf(Coord2D(5f, 0f), Coord2D(10f, 0f)), Colour.BLACK),
            )
        }

        val xvi = export(survey, Projection2D.EXTENDED_ELEVATION)

        // The sections are at (2, 4) and (12, 4), which the XVI has 4 the other way up
        assertContains(xvi, "{BLACK 7.00 -4.00 12.00 -4.00}")
        assertContains(xvi, "{BLACK 17.00 -4.00 22.00 -4.00}")

        elevation.crossSectionScale = 2f
        val enlarged = export(survey, Projection2D.EXTENDED_ELEVATION)

        // At twice the size the drawing stretches away from where the section is
        assertContains(enlarged, "{BLACK 12.00 -4.00 22.00 -4.00}")
        assertContains(enlarged, "{BLACK 22.00 -4.00 32.00 -4.00}")
    }
}

private fun getDistance(a: Coord2D, b: Coord2D): Float =
    org.hwyl.sexytopo.shared.math.getDistance(a, b)
