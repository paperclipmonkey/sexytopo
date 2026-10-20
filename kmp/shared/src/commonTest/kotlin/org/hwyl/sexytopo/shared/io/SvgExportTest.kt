package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.io.export.formatFixedTrimmed
import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun everythingSurveyedIsDrawn() {
        val svg = exportOf(cave())

        assertContains(svg, "id=\"1-2\"")
        assertContains(svg, "id=\"2-3\"")
        assertContains(svg, "id=\"2-Splay0\"")
        for (name in listOf("1", "2", "3")) assertContains(svg, "id=\"$name\"")
    }

    @Test
    fun theSameSurveyExportsIdenticallyEveryTime() {
        val survey = cave()
        assertEquals(exportOf(survey), exportOf(survey))
    }

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

        assertContains(exportOf(survey), "id=\"xs-2\"")
    }

    @Test
    fun textIsEscaped() {
        val survey = cave()
        SketchEditor(survey.planSketch).addText(Coord2D.ORIGIN, "tight <2m & wet", size = 0.5f)

        val svg = exportOf(survey)

        assertContains(svg, "tight &lt;2m &amp; wet")
        assertTrue("tight <2m" !in svg)
    }

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

    // Cross-sections on the elevation, from `SvgExporterTest` in upstream 1.13.0

    // What `TestSurveys.createWithCrossSectionsInPlanAndElevation` gives every section: splays 2m
    // east, 1.5m west, 1m north, 1m up and 1m down. Each is listed as how far it goes across the
    // page and down it, in metres, so the up splay is (0, -1).
    private val verticalSplays =
        listOf("2.00,0.00", "-1.50,0.00", "0.00,0.00", "0.00,-1.00", "0.00,1.00").sorted()
    private val horizontalSplays =
        listOf("2.00,0.00", "-1.50,0.00", "0.00,-1.00", "0.00,0.00", "0.00,0.00").sorted()

    private fun export(survey: Survey, projection: Projection2D): String =
        SvgExporter.export(survey, projection)

    /** The text of the group with this id, or null if there is none. */
    private fun group(svg: String, id: String): String? {
        val start = svg.indexOf("<g id=\"$id\"")
        if (start < 0) return null
        return svg.substring(start, svg.indexOf("</g>", start))
    }

    /** The numbers in each `points` of a polyline in [group] with the given [stroke]. */
    private fun polylines(group: String, stroke: (String) -> Boolean): List<List<Float>> =
        Regex("""<polyline points="([^"]*)" stroke="([^"]*)"""").findAll(group)
            .filter { stroke(it.groupValues[2]) }
            .map { match -> match.groupValues[1].split(' ', ',').map { it.toFloat() } }
            .toList()

    /** The splays drawn in a section's group, as (across, down) in metres, sorted. */
    private fun splays(svg: String, id: String): List<String> {
        val group = assertNotNull(group(svg, id), "$id should be in the export")
        return polylines(group) { it == "red" }
            .map { p ->
                val across = (p[2] - p[0]) / SvgExporter.SCALE
                val down = (p[3] - p[1]) / SvgExporter.SCALE
                "${tidy(across)},${tidy(down)}"
            }
            .sorted()
    }

    /** The two ends of the line drawn in a section: x1, y1, x2, y2. */
    private fun drawnLine(svg: String, id: String): List<Float> =
        polylines(assertNotNull(group(svg, id))) { it != "red" }.single().take(4)

    // Rounded to hundredths, with negative zero made an ordinary one
    private fun tidy(value: Float): String =
        formatFixed(floor(value * 100.0 + 0.5) / 100.0 + 0.0, 2)

    /** A horizontal line from (5, 0) to (10, 0) in a section's own drawing. */
    private fun drawOneHorizontalLine(sketch: Sketch) {
        sketch.pathDetails.add(PathDetail(listOf(Coord2D(5f, 0f), Coord2D(10f, 0f)), Colour.BLACK))
    }

    private fun assertLine(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) assertTrue(abs(e - a) < 0.01f, "$expected vs $actual")
    }

    @Test
    fun theElevationExportIncludesItsSections() {
        val svg = export(TestSurveys.createWithCrossSectionsInPlanAndElevation(), Projection2D.EXTENDED_ELEVATION)

        assertNotNull(group(svg, "cross-sections"))
        assertNotNull(group(svg, "xs-1"))
        assertNotNull(group(svg, "xs-3"))
    }

    @Test
    fun aVerticalSectionOnTheElevationIsDrawnAcrossThePassage() {
        val svg = export(TestSurveys.createWithCrossSectionsInPlanAndElevation(), Projection2D.EXTENDED_ELEVATION)

        assertEquals(verticalSplays, splays(svg, "xs-1"))
    }

    @Test
    fun aHorizontalSectionIsDrawnAsASliceWithNorthAtTheTop() {
        val svg = export(TestSurveys.createWithCrossSectionsInPlanAndElevation(), Projection2D.EXTENDED_ELEVATION)

        // The north splay goes up the page, and the up and down splays have nothing to show
        assertEquals(horizontalSplays, splays(svg, "xs-3"))
    }

    @Test
    fun theSameStationCanBeDrawnDifferentlyOnThePlanAndTheElevation() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        // Station 3 has a vertical section on the plan and a horizontal one on the elevation
        assertEquals(verticalSplays, splays(export(survey, Projection2D.PLAN), "xs-3"))
        assertEquals(
            horizontalSplays,
            splays(export(survey, Projection2D.EXTENDED_ELEVATION), "xs-3"),
        )
    }

    @Test
    fun eachExportHasOnlyItsOwnSketchsSections() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        survey.elevationSketch.addCrossSection(
            CrossSection.horizontal(survey.getStationByName("2")!!),
            Coord2D(7f, 4f),
        )

        assertNotNull(group(export(survey, Projection2D.EXTENDED_ELEVATION), "xs-2"))
        assertNull(group(export(survey, Projection2D.PLAN), "xs-2"))
    }

    @Test
    fun theElevationsSectionsAreLeftOutWhenTheOptionIsOff() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val svg =
            SvgExporter.export(
                survey,
                Projection2D.EXTENDED_ELEVATION,
                SvgExporter.Options(showCrossSections = false),
            )

        assertNull(group(svg, "cross-sections"))
        assertNull(group(svg, "xs-1"))
    }

    @Test
    fun theElevationsSectionsAreDrawnAtItsCrossSectionScale() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        survey.elevationSketch.crossSectionScale = 2f

        val svg = export(survey, Projection2D.EXTENDED_ELEVATION)

        assertEquals(
            listOf("4.00,0.00", "-3.00,0.00", "0.00,0.00", "0.00,-2.00", "0.00,2.00").sorted(),
            splays(svg, "xs-1"),
        )
        assertEquals(
            listOf("4.00,0.00", "-3.00,0.00", "0.00,-2.00", "0.00,0.00", "0.00,0.00").sorted(),
            splays(svg, "xs-3"),
        )
    }

    @Test
    fun enlargedSectionsStayInsideTheImage() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        // Large enough for the sections to reach beyond the margin the image leaves around its
        // frame, so that it is the frame that has to make room for them
        survey.elevationSketch.crossSectionScale = 6f

        val svg = export(survey, Projection2D.EXTENDED_ELEVATION)

        val box =
            Regex("""viewBox="([^"]*)"""").find(svg)!!.groupValues[1].split(' ').map { it.toFloat() }
        val (left, top) = box[0] to box[1]
        val (right, bottom) = (left + box[2]) to (top + box[3])
        for (id in listOf("xs-1", "xs-3")) {
            for (line in polylines(group(svg, id)!!) { true }) {
                for (k in 0 until 4 step 2) {
                    val (x, y) = line[k] to line[k + 1]
                    assertTrue(x in left..right && y in top..bottom, "$id point $x,$y")
                }
            }
        }
    }

    @Test
    fun whatIsDrawnInAnElevationSectionIsPlacedAndScaledWithIt() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        val elevation = survey.elevationSketch
        for (name in listOf("1", "3")) {
            val station = survey.getStationByName(name)!!
            drawOneHorizontalLine(elevation.crossSectionDetails.single { it.station === station }.sketch)
        }

        val svg = export(survey, Projection2D.EXTENDED_ELEVATION)

        // The sections are at (2, 4) and (12, 4), and a metre is 50 units
        assertLine(listOf(350f, 200f, 600f, 200f), drawnLine(svg, "xs-1"))
        assertLine(listOf(850f, 200f, 1100f, 200f), drawnLine(svg, "xs-3"))

        elevation.crossSectionScale = 2f
        val enlarged = export(survey, Projection2D.EXTENDED_ELEVATION)

        // At twice the size the drawing stretches away from where the section is
        assertLine(listOf(600f, 200f, 1100f, 200f), drawnLine(enlarged, "xs-1"))
        assertLine(listOf(1100f, 200f, 1600f, 200f), drawnLine(enlarged, "xs-3"))
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
