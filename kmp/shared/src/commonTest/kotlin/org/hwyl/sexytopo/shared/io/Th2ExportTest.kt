package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.Th2Exporter
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drawing as a Therion scrap file.
 *
 * A `.th2` carries the parts of a survey Therion understands as *data* — stations by name, the
 * anchors that tie a cross-section scrap to its station, labels and symbols as Therion points —
 * and positions the `.xvi` tracing image behind them. Between them the two files are how a survey
 * drawn on a phone becomes a survey drawn up in the tool most cavers use.
 */
class Th2ExportTest {

    private fun cave(): Survey {
        val survey = Survey("Swildons Hole")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        return survey
    }

    private fun exportOf(
        survey: Survey,
        projection: Projection2D = Projection2D.PLAN,
        options: Th2Exporter.Options = Th2Exporter.Options.DEFAULT,
    ): String {
        val frame = SvgExporter.exportFrame(survey, projection).scale(SvgExporter.SCALE.toFloat())
        return Th2Exporter.export(
            survey = survey,
            projection = projection,
            innerFrame = frame,
            outerFrame = SvgExporter.addBorder(SvgExporter.exportFrame(survey, projection))
                .scale(SvgExporter.SCALE.toFloat()),
            scale = SvgExporter.SCALE.toFloat(),
            options = options,
        )
    }

    @Test
    fun theFileIsAScrapWithAnEncodingHeader() {
        val th2 = exportOf(cave())

        assertTrue(th2.startsWith("encoding utf-8"))
        assertContains(th2, "scrap Swildons-Hole-plan -projection plan")
        assertContains(th2, "endscrap")
    }

    /** An extended elevation is a different projection and a different scrap name. */
    @Test
    fun anExtendedElevationSaysSo() {
        val th2 = exportOf(cave(), Projection2D.EXTENDED_ELEVATION)

        assertContains(th2, "scrap Swildons-Hole-ee -projection extended")
    }

    /**
     * A name with spaces in it becomes a legal scrap name.
     *
     * Ported from `TextTools.intelligentlySanitise`, joining character and all: a hyphen normally,
     * but an underscore if the name already uses one, so a name written `Swildons_Hole` does not
     * come back half-hyphenated.
     */
    @Test
    fun aSurveyNameIsSanitisedTheWayTheAppDoes() {
        assertEquals("Swildons-Hole", Th2Exporter.sanitise("Swildons Hole"))
        assertEquals("Swildons_Hole_Two", Th2Exporter.sanitise("Swildons_Hole Two"))
        assertEquals("a-b-c", Th2Exporter.sanitise("a b:c"))
    }

    /** Stations go in by name, which is what ties the traced drawing to the survey. */
    @Test
    fun everyStationIsAPointWithItsName() {
        val th2 = exportOf(cave())

        for (name in listOf("1", "2", "3")) {
            assertContains(th2, "station -name $name")
        }
    }

    /** North is up in the survey and down on Therion's canvas: the flip happens once. */
    @Test
    fun northIsUpInTheSurveyAndDownInTheFile() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))

        val th2 = exportOf(survey)

        assertContains(th2, "point 0.0 0.0 station -name 1")
        assertContains(th2, "point 0.0 500.0 station -name 2")
    }

    // ------------------------------------------------------------------------------------
    // Cross-sections
    // ------------------------------------------------------------------------------------

    private fun withCrossSection(): Survey {
        val survey = cave()
        val middle = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 45f, 0f))
        survey.getSketch(Projection2D.PLAN).crossSectionDetails.add(
            CrossSectionDetail(Coord2D(20f, -5f), CrossSectioner.section(survey, middle)),
        )
        return survey
    }

    /**
     * A section gets a scrap of its own and an anchor pointing at it.
     *
     * The anchor is the load-bearing part: without it the section is a drawing on its own with
     * nothing saying where in the cave it was taken. The scrap it names has to exist further down
     * the file, or Therion reports a dangling reference.
     */
    @Test
    fun aCrossSectionIsAnchoredToItsStationAndGetsItsOwnScrap() {
        val th2 = exportOf(withCrossSection())

        assertContains(th2, "section -scrap Swildons-HolePX2")
        assertContains(th2, "scrap Swildons-HolePX2 -projection none -scale")
    }

    @Test
    fun crossSectionsCanBeLeftOut() {
        val th2 =
            exportOf(withCrossSection(), options = Th2Exporter.Options(crossSections = false))

        assertFalse("section -scrap" in th2)
        assertFalse("PX2" in th2)
    }

    /** `PX#` takes the station's name; `##` and `###` zero-pad a numeric one. */
    @Test
    fun theCrossSectionSuffixTakesTheStationName() {
        assertEquals("PX7", Th2Exporter.expandHashes("PX#", "7"))
        assertEquals("PX07", Th2Exporter.expandHashes("PX##", "7"))
        assertEquals("PX007", Th2Exporter.expandHashes("PX###", "7"))
        // A name that is not a number is substituted as it stands rather than padded.
        assertEquals("PXsump", Th2Exporter.expandHashes("PX##", "sump"))
        assertEquals("-plan", Th2Exporter.expandHashes("-plan", "7"))
    }

    // ------------------------------------------------------------------------------------
    // Sketch content
    // ------------------------------------------------------------------------------------

    @Test
    fun labelsAndSymbolsBecomeTherionPoints() {
        val survey = cave()
        val sketch = survey.getSketch(Projection2D.PLAN)
        sketch.addTextDetail(Coord2D(1f, 1f), "sump", 1f, Colour.BLACK)
        sketch.addSymbolDetail(Coord2D(2f, 2f), "blocks", 0.5f, 0f, Colour.BROWN)

        val th2 = exportOf(survey)

        assertContains(th2, "label -text \" sump \" -scale l")
        assertContains(th2, "blocks -scale s")
    }

    /** A directional symbol carries the bearing it was aimed at; an upright one does not. */
    @Test
    fun onlyDirectionalSymbolsCarryAnOrientation() {
        val survey = cave()
        val sketch = survey.getSketch(Projection2D.PLAN)
        sketch.addSymbolDetail(Coord2D(1f, 1f), "water-flow", 1f, 45f, Colour.BLUE)
        sketch.addSymbolDetail(Coord2D(2f, 2f), "blocks", 1f, 0f, Colour.BROWN)

        val th2 = exportOf(survey)

        assertContains(th2, "water-flow -scale l -orientation 45.0")
        assertEquals(1, th2.split("-orientation").size - 1)
    }

    @Test
    fun labelsAndSymbolsCanBeLeftOut() {
        val survey = cave()
        val sketch = survey.getSketch(Projection2D.PLAN)
        sketch.addTextDetail(Coord2D(1f, 1f), "sump", 1f, Colour.BLACK)
        sketch.addSymbolDetail(Coord2D(2f, 2f), "blocks", 1f, 0f, Colour.BROWN)

        val th2 = exportOf(survey, options = Th2Exporter.Options(labels = false, symbols = false))

        assertFalse("sump" in th2)
        assertFalse("blocks" in th2)
    }

    /** Therion has five point sizes; a size in metres picks one. */
    @Test
    fun aSizeInMetresBecomesOneOfTherionsFiveSizes() {
        assertEquals("xs", Th2Exporter.therionSize(0.4f))
        assertEquals("s", Th2Exporter.therionSize(0.5f))
        assertEquals("m", Th2Exporter.therionSize(0.7f))
        assertEquals("l", Th2Exporter.therionSize(1.1f))
        assertEquals("xl", Th2Exporter.therionSize(1.5f))
    }

    // ------------------------------------------------------------------------------------
    // The tracing image
    // ------------------------------------------------------------------------------------

    /**
     * The XVI block is written only when there is an XVI to write about.
     *
     * A `.th2` referring to an image that was never exported opens in xtherion with a missing-file
     * complaint and no background, which is worse than a scrap with no image at all.
     */
    @Test
    fun theTracingImageIsReferredToOnlyWhenThereIsOne() {
        assertFalse("##XTHERION##" in exportOf(cave()))

        val withImage =
            exportOf(cave(), options = Th2Exporter.Options(xviFileName = "Swildons-plan.xvi"))

        assertContains(withImage, "##XTHERION## xth_me_area_adjust")
        assertContains(withImage, "\"Swildons-plan.xvi\"")
        assertContains(withImage, "##XTHERION## xth_me_area_zoom_to 25")
    }

    /** The image is pinned to the origin station, by name, so it lines up with the scrap. */
    @Test
    fun theImageIsPinnedToTheOriginStation() {
        val withImage =
            exportOf(cave(), options = Th2Exporter.Options(xviFileName = "x.xvi"))

        assertContains(withImage, "xth_me_image_insert {0.0 1 1.0} {0.0 1}")
    }

    // ------------------------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------------------------

    /** The copyright sits on the line straight after the scrap header, as in the original. */
    @Test
    fun theCopyrightFollowsTheScrapLineImmediately() {
        val survey = cave()
        survey.trip =
            Trip(SurveyDate(2026, 4, 12)).also {
                it.copyrightHolder = "Wessex Cave Club"
                it.licence = "CC BY 4.0"
            }

        val th2 = exportOf(survey)

        val scrapLine = th2.lines().first { it.startsWith("scrap ") }
        val next = th2.lines()[th2.lines().indexOf(scrapLine) + 1]
        // Therion has no command prefix — its copyright is a bare `copyright` line, with the
        // licence following as a `#` comment. That is `SurveyFormat.THERION`, not a slip.
        assertTrue(next.startsWith("copyright 2026"), "copyright not on the next line: '$next'")
        assertContains(next, "Wessex Cave Club")
    }

    @Test
    fun theSameSurveyExportsIdenticallyEveryTime() {
        val survey = withCrossSection()
        assertEquals(exportOf(survey), exportOf(survey))
    }
}
