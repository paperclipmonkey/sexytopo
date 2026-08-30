package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.formatFixedTrimmed
import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawing, as a file somebody can open.
 *
 * There is no golden here, and deliberately so: the Java's own output is not reproducible. It walks
 * a `HashMap` of stations and collects the symbols in use into a `HashSet`, so two exports of an
 * unchanged survey come out in different orders. This port writes everything in the survey's own
 * chronological order, which is what these tests pin.
 */
class SvgExportTest {

    private fun cave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        val middle = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 45f, 0f))
        return survey
    }

    private fun exportOf(survey: Survey): String = SvgExporter.export(survey)

    @Test
    fun theDocumentIsWellFormedAndSized() {
        val svg = exportOf(cave())

        assertTrue(svg.startsWith("<?xml"))
        assertContains(svg, "<svg ")
        assertContains(svg, "xmlns=\"http://www.w3.org/2000/svg\"")
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertEquals(countOf(svg, "<g "), countOf(svg, "</g>"), "unbalanced groups")
    }

    /** Every station, leg and splay reaches the file, named the way the survey names them. */
    @Test
    fun everythingSurveyedIsDrawn() {
        val svg = exportOf(cave())

        assertContains(svg, "id=\"1-2\"")
        assertContains(svg, "id=\"2-3\"")
        assertContains(svg, "id=\"2-Splay0\"")
        for (name in listOf("1", "2", "3")) assertContains(svg, "id=\"$name\"")
    }

    /** Two exports of an unchanged survey must be identical, or a diff is meaningless. */
    @Test
    fun theSameSurveyExportsIdenticallyEveryTime() {
        val survey = cave()
        assertEquals(exportOf(survey), exportOf(survey))
    }

    // -------------------------------------------------------------------------------------
    // The sketch
    // -------------------------------------------------------------------------------------

    @Test
    fun strokesLabelsAndSymbolsAreDrawn() {
        val survey = cave()
        val editor = SketchEditor(survey.planSketch)
        editor.activeColour = Colour.BLUE
        editor.startPath(Coord2D(0f, 0f))
        editor.extendPath(Coord2D(1f, 1f))
        editor.finishPath()
        editor.addText(Coord2D(2f, 2f), "sump", size = 0.5f)
        editor.addSymbol(Coord2D(3f, 3f), Symbol.BLOCKS.therionName, size = 0.5f)

        val svg = exportOf(survey)

        assertContains(svg, "<polyline points=")
        assertContains(svg, ">sump</text>")
        // The symbol is defined once and used once, by its Therion name.
        assertContains(svg, "<symbol id=\"blocks\"")
        assertContains(svg, "href=\"#blocks\"")
    }

    /** A directional symbol carries its bearing; an upright one must not carry a rotate at all. */
    @Test
    fun onlyDirectionalSymbolsAreRotated() {
        val survey = cave()
        val editor = SketchEditor(survey.planSketch)
        editor.addSymbol(Coord2D(1f, 1f), Symbol.WATER_FLOW.therionName, size = 0.5f, angle = 90f)
        editor.addSymbol(Coord2D(2f, 2f), Symbol.BLOCKS.therionName, size = 0.5f, angle = 90f)

        val svg = exportOf(survey)

        assertEquals(1, countOf(svg, "transform=\"rotate("), "wrong number of rotated symbols")
        assertTrue(Symbol.WATER_FLOW.isDirectional)
        assertTrue(!Symbol.BLOCKS.isDirectional)
    }

    @Test
    fun aCrossSectionIsDrawnWhereItWasPlaced() {
        val survey = cave()
        val middle = survey.getStationByName("2")!!
        SketchEditor(survey.planSketch)
            .addCrossSection(CrossSectioner.section(survey, middle), Coord2D(5f, 5f))

        assertContains(exportOf(survey), "id=\"x-section-2\"")
    }

    /** Text a surveyor typed goes into XML, so it has to be escaped or the file will not parse. */
    @Test
    fun textIsEscaped() {
        val survey = cave()
        SketchEditor(survey.planSketch).addText(Coord2D.ORIGIN, "tight <2m & wet", size = 0.5f)

        val svg = exportOf(survey)

        assertContains(svg, "tight &lt;2m &amp; wet")
        assertTrue("tight <2m" !in svg)
    }

    // -------------------------------------------------------------------------------------
    // Options
    // -------------------------------------------------------------------------------------

    @Test
    fun eachLayerCanBeTurnedOff() {
        val survey = cave()
        val bare =
            SvgExporter.export(
                survey,
                Projection2D.PLAN,
                SvgExporter.Options(
                    whiteBackground = false,
                    showGrid = false,
                    showSketch = false,
                    showCrossSections = false,
                    showCentreline = false,
                    showSplays = false,
                    showStations = false,
                ),
            )

        assertTrue("id=\"background\"" !in bare)
        assertTrue("id=\"grid\"" !in bare)
        assertTrue("id=\"1-2\"" !in bare)
        assertTrue("id=\"2-Splay0\"" !in bare)
        // Still a valid document with nothing in it, which is what "show nothing" should mean.
        assertContains(bare, "<svg ")
        assertEquals(countOf(bare, "<g "), countOf(bare, "</g>"))
    }

    // -------------------------------------------------------------------------------------
    // The frame and its arithmetic
    // -------------------------------------------------------------------------------------

    /** Padding by size band, then rounding out to whole metres, exactly as the Java does. */
    @Test
    fun theBorderIsPaddedByBandAndRoundedOut() {
        assertEquals(Frame(-1f, 6f, -1f, 6f), SvgExporter.addBorder(Frame(0f, 5f, 0f, 5f)))
        assertEquals(Frame(-5f, 25f, -5f, 25f), SvgExporter.addBorder(Frame(0f, 20f, 0f, 20f)))
        assertEquals(Frame(-10f, 110f, -10f, 110f), SvgExporter.addBorder(Frame(0f, 100f, 0f, 100f)))
    }

    /** 1, 2 or 5 times a power of ten, near an eighth of the width. */
    @Test
    fun theScaleBarPicksARoundNumber() {
        assertEquals(1.0, SvgExporter.scaleBarLength(10.0))
        assertEquals(2.0, SvgExporter.scaleBarLength(20.0))
        assertEquals(5.0, SvgExporter.scaleBarLength(50.0))
        assertEquals(10.0, SvgExporter.scaleBarLength(100.0))
        assertEquals(0.1, SvgExporter.scaleBarLength(1.0))
    }

    @Test
    fun aFrameIsTheUnionOfEverythingDrawnAndEverythingSurveyed() {
        val survey = cave()
        SketchEditor(survey.planSketch).addText(Coord2D(100f, 100f), "far away", size = 0.5f)

        val frame = SvgExporter.exportFrame(survey, Projection2D.PLAN)

        assertTrue(frame.right >= 100f, "the sketch did not widen the frame")
    }

    // -------------------------------------------------------------------------------------
    // Numbers
    // -------------------------------------------------------------------------------------

    /**
     * Exponent notation is invalid in a path and is rendered differently by the JVM and by
     * Kotlin/Wasm — a divergence this port has been bitten by before — so no coordinate may reach
     * the file through `Float.toString`.
     */
    @Test
    fun noCoordinateIsWrittenInExponentNotation() {
        val survey = cave()
        SketchEditor(survey.planSketch).addText(Coord2D(0.00001f, 200000f), "x", size = 0.5f)

        val quoted = Regex("\"[-0-9.eE+]+\"").findAll(exportOf(survey))
        for (match in quoted) {
            val number = match.value
            assertTrue(
                'e' !in number && 'E' !in number,
                "exponent notation reached the file: " + number,
            )
        }
    }

    /**
     * A deep shaft series in extended elevation is tall and narrow, and the grid spacing is chosen
     * from the width alone — so the horizontal lines are bounded by nothing at all. The Java has
     * the same shape and would draw as many as it takes to cross the height; here that showed up
     * as an out-of-memory error, and on a phone it would be an export that never finishes.
     */
    @Test
    fun aTallNarrowDrawingDoesNotRunTheGridAway() {
        val survey = Survey("Shaft")
        SurveyBuilder.updateWithNewStation(survey, Leg(200f, 0f, -89f))
        SketchEditor(survey.planSketch).addText(Coord2D(0.5f, 20000f), "deep", size = 0.5f)

        val svg = exportOf(survey)
        val lines = countOf(svg, "<line")

        assertTrue(lines < 500, "the grid ran away: " + lines + " lines")
        assertContains(svg, "</svg>")
    }

    @Test
    fun numbersLoseTheirTrailingZeros() {
        assertEquals("100", formatFixedTrimmed(100f, 3))
        assertEquals("1.5", formatFixedTrimmed(1.5f, 3))
        assertEquals("0", formatFixedTrimmed(-0.0001f, 3))
        assertEquals("-2.25", formatFixedTrimmed(-2.25f, 3))
    }

    private fun countOf(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}
