package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.SymbolDetail
import org.hwyl.sexytopo.shared.model.sketch.TextDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
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
 * There is no golden here for the same reason there is none for the SVG: the Java walks a
 * `HashMap` of stations, so two exports of an unchanged survey come out in different orders. This
 * port writes in the survey's own order, which is what these tests pin.
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

    /** Stations go in by name, which is what makes the traced drawing tie to the survey. */
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

    /** Each line of a multi-line label is drawn below the last. */
    @Test
    fun aMultiLineLabelStacksDownwards() {
        val one = XviExporter.textAsPaths(TextDetail(Coord2D.ORIGIN, "A", 1f, Colour.BLACK))
        val two = XviExporter.textAsPaths(TextDetail(Coord2D.ORIGIN, "A\nA", 1f, Colour.BLACK))

        assertEquals(one.size * 2, two.size)
        val firstLineY = two.take(one.size).flatMap { it.path }.map { it.y }.min()
        val secondLineY = two.drop(one.size).flatMap { it.path }.map { it.y }.min()
        assertTrue(secondLineY > firstLineY, "the second line was not below the first")
    }

    /** A symbol becomes its simplified polyline, centred and scaled where it was stamped. */
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

    /** Aiming a symbol turns its strokes, which is the whole point of a directional one. */
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

    /** The grid says where it starts, how big a square is, and how many there are. */
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
}

private fun getDistance(a: Coord2D, b: Coord2D): Float =
    org.hwyl.sexytopo.shared.math.getDistance(a, b)
