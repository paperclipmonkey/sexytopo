package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.io.imports.XviImporter
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a Therion tracing image, checked against the one thing that can check it: this app's own
 * writer.
 *
 * A round trip is the strongest check available here: export a drawing, read the file back, and the
 * strokes have to land where they started. Anything wrong with the scale, the sign of y, the token
 * order or the brace scanning shows up as coordinates that do not match, rather than as a file that
 * merely parses.
 */
class XviImportTest {

    private val scale = 50f

    private fun cave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        return survey
    }

    private fun exportOf(survey: Survey, sketch: Sketch) =
        XviExporter.export(
            sketch = sketch,
            space = Projection2D.PLAN.project(survey),
            scale = scale,
            gridFrame = Frame(0f, 500f, 0f, 500f),
        )

    private fun assertSamePoint(expected: Coord2D, actual: Coord2D, what: String) {
        // A tenth of a millimetre in survey metres: the file is written to a fixed number of
        // decimal places, so an exact comparison would be testing the formatter.
        assertTrue(
            abs(expected.x - actual.x) < 0.0001f && abs(expected.y - actual.y) < 0.0001f,
            "$what should be $expected and came back $actual",
        )
    }

    @Test
    fun aTracedDrawingComesBackWhereItWasDrawn() {
        val survey = cave()
        val sketch = Sketch()
        val wall =
            PathDetail(
                listOf(Coord2D(1f, -2f), Coord2D(3.5f, -4.25f), Coord2D(6f, -1f)),
                Colour.BLACK,
            )
        val water = PathDetail(listOf(Coord2D(-2f, 3f), Coord2D(-4f, 5f)), Colour.BLUE)
        sketch.pathDetails = mutableListOf(wall, water)

        val read = XviImporter.sketchFrom(exportOf(survey, sketch))

        assertEquals(2, read.pathDetails.size, "both strokes should come back")
        assertEquals(Colour.BLACK, read.pathDetails[0].colour)
        assertEquals(Colour.BLUE, read.pathDetails[1].colour, "the colour is written and read by name")
        assertEquals(3, read.pathDetails[0].path.size)
        for ((i, point) in wall.path.withIndex()) {
            assertSamePoint(point, read.pathDetails[0].path[i], "point $i of the wall")
        }
        for ((i, point) in water.path.withIndex()) {
            assertSamePoint(point, read.pathDetails[1].path[i], "point $i of the water")
        }
    }

    /**
     * The sign of y is the one that fails quietly.
     *
     * Therion's y runs up and this app's runs down, so both halves negate it. Getting that wrong in
     * one of them draws the cave mirrored about the centreline — which looks like a plausible cave,
     * which is why it needs asserting rather than eyeballing.
     */
    @Test
    fun theDrawingIsNotMirrored() {
        val survey = cave()
        val sketch = Sketch()
        sketch.pathDetails =
            mutableListOf(PathDetail(listOf(Coord2D(0f, -10f), Coord2D(0f, -20f)), Colour.BLACK))

        val read = XviImporter.sketchFrom(exportOf(survey, sketch))

        val points = read.pathDetails.single().path
        assertTrue(points[0].y < 0f && points[1].y < 0f, "y came back as $points, so it flipped")
        assertTrue(points[1].y < points[0].y, "the stroke came back reversed in y: $points")
    }

    /**
     * Symbols and labels arrive as strokes, because that is all the file says they are.
     *
     * Not a defect: an `.xvi` is a tracing image with no symbol vocabulary, so the exporter draws
     * them and the importer can only read what was drawn. Written down as a test so the lossiness
     * is a recorded decision rather than a surprise to whoever round-trips one.
     */
    @Test
    fun aStampComesBackAsTheStrokesItWasDrawnAs() {
        val survey = cave()
        val sketch = Sketch()
        sketch.symbolDetails =
            mutableListOf(
                org.hwyl.sexytopo.shared.model.sketch.SymbolDetail(
                    Coord2D(2f, -2f),
                    "entrance",
                    1f,
                    0f,
                    Colour.BLACK,
                ),
            )

        val read = XviImporter.sketchFrom(exportOf(survey, sketch))

        assertTrue(read.pathDetails.isNotEmpty(), "the stamp should arrive as strokes")
        assertTrue(read.symbolDetails.isEmpty(), "and not as a symbol, which the format cannot say")
    }

    // -----------------------------------------------------------------------------------------
    // The parsing itself, on files this app did not write
    // -----------------------------------------------------------------------------------------

    /** Nesting is why the block scan is a scan and not a regular expression. */
    @Test
    fun aBlockIsReadPastTheStrokesInsideIt() {
        val text = "set XVIsketchlines {\n{black 1 2 3 4}\n{blue 5 6 7 8}\n}\n"

        val block = XviImporter.blockContents(text, "set XVIsketchlines")

        assertEquals(2, XviImporter.entries(block!!).size, "stopping at the first } finds one stroke")
    }

    @Test
    fun aCrossSectionsConnectorComesBackAsALine() {
        val path = XviImporter.pathFrom(1f, "connect 0 0 10 20")

        assertEquals(Colour.BLACK, path!!.colour)
        assertSamePoint(Coord2D(0f, 0f), path.path[0], "the station end")
        assertSamePoint(Coord2D(10f, -20f), path.path[1], "the section end")
    }

    /**
     * A bad entry costs that entry, not the trip's drawing.
     *
     * The Android importer throws on any of these, which fails the whole import on one bad line.
     * This port skips them, as it does an unknown symbol in the exporter, and a survey that is
     * ninety-nine strokes and one bad one arrives as ninety-nine strokes.
     */
    @Test
    fun anEntryThisAppCannotReadIsSkippedRatherThanFatal() {
        // "chartreuse" would have passed: SexyTopo's palette is the 144-name CSS list, so an
        // unknown colour has to be a word that really is not one.
        assertNull(XviImporter.pathFrom(1f, "limestone 1 2"), "an unknown colour")
        assertNull(XviImporter.pathFrom(1f, "black 1 2 3"), "an odd coordinate left over")
        assertNull(XviImporter.pathFrom(1f, "black"), "a colour and no points")
        assertNull(XviImporter.pathFrom(1f, "connect 0 0 10"), "a connector missing an end")
        assertNull(XviImporter.pathFrom(1f, "black 1 two"), "a coordinate that is not a number")

        val sketch =
            XviImporter.sketchFrom(
                "set XVIgrid {0 0 1 0 0 1 10 10}\n" +
                    "set XVIsketchlines {\n{black 1 2 3 4}\n{limestone 9 9}\n{blue 5 6 7 8}\n}\n",
            )
        assertEquals(2, sketch.pathDetails.size, "the two readable strokes should still arrive")
    }

    /** A file with no grid line has no scale, and every coordinate would divide into nonsense. */
    @Test
    fun aFileWithNoUsableScaleReadsAsAnEmptyDrawing() {
        assertNull(XviImporter.scaleOf("set XVIsketchlines {\n{black 1 2 3 4}\n}\n"), "no grid")
        assertNull(XviImporter.scaleOf("set XVIgrid {0 0 1 0 0 0 10 10}"), "a zero scale")
        assertTrue(
            XviImporter.sketchFrom("set XVIsketchlines {\n{black 1 2 3 4}\n}\n")
                .pathDetails
                .isEmpty(),
        )
    }
}
