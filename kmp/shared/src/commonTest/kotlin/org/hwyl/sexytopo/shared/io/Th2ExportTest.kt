package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.Th2Exporter
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.math.abs
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

    /**
     * A big cave is drawn up as several scraps, and the app makes the empty ones.
     *
     * `TherionExportOptions` in the Android app carries four settings that are not among its ten
     * `pref_therion_*` preferences: they are asked for in a dialog on the way out of every export.
     * The port had the ten and none of these, so it always wrote one scrap with the stations in it.
     *
     * Extra scraps are *empty* on purpose. They carry the header, the projection and the copyright
     * line and nothing else, because the drawing that goes in them has not been done yet — this is
     * the app making the containers so a surveyor does not hand-type `scrap` headers and get a plan
     * scrap into an elevation.
     */
    @Test
    fun aDrawingCanBeWrittenAsSeveralScraps() {
        val th2 = exportOf(cave(), options = Th2Exporter.Options(scrapCount = 3))

        assertEquals(3, Regex("^scrap ", RegexOption.MULTILINE).findAll(th2).count())
        assertContains(th2, "scrap Swildons-Hole-plan1 -projection plan")
        assertContains(th2, "scrap Swildons-Hole-plan2 -projection plan")
        assertContains(th2, "scrap Swildons-Hole-plan3 -projection plan")
    }

    /** And only the first one has anything in it. */
    @Test
    fun onlyTheFirstScrapCarriesTheDrawing() {
        val survey = cave()
        survey.planSketch.addTextDetail(Coord2D(1f, 1f), "sump", 1f, Colour.BLACK)

        val th2 = exportOf(survey, options = Th2Exporter.Options(scrapCount = 2))

        val scraps = th2.split(Regex("(?m)^scrap ")).drop(1)
        assertEquals(2, scraps.size)
        assertContains(scraps[0], "point")
        assertContains(scraps[0], "sump")
        assertFalse(scraps[1].contains("point"), "the second scrap should be empty: ${scraps[1]}")
    }

    /**
     * One scrap is named as it always was, so an existing project does not move under a surveyor.
     *
     * The suffix becomes a *pattern* only once there is more than one: this is the case that would
     * otherwise rename `Name-plan` to `Name-plan1` for everybody who never asked for scraps.
     */
    @Test
    fun oneScrapKeepsThePlainNameItAlwaysHad() {
        assertContains(exportOf(cave()), "scrap Swildons-Hole-plan -projection plan")
    }

    /** `#` takes the number and `##` pads it, as the Java's `formatScrapName` does. */
    @Test
    fun theSuffixIsAPatternOnceThereIsMoreThanOneScrap() {
        assertEquals("Cave-plan-1", Th2Exporter.scrapName("Cave", "-plan-#", 1, 2))
        assertEquals("Cave-plan-02", Th2Exporter.scrapName("Cave", "-plan-##", 2, 12))
        assertEquals("Cave-plan3", Th2Exporter.scrapName("Cave", "-plan", 3, 4))
        assertEquals("Cave-plan", Th2Exporter.scrapName("Cave", "-plan", 1, 1))
        // A placeholder in a single scrap is still expanded — somebody who wrote `#` meant it.
        assertEquals("Cave-plan-1", Th2Exporter.scrapName("Cave", "-plan-#", 1, 1))
    }

    /**
     * The stations can be left out, so they can live in a scrap of their own.
     *
     * Which is a real Therion habit rather than a curiosity: with the stations in their own scrap,
     * re-exporting after a correction to the centreline does not overwrite a drawing somebody has
     * spent an evening on.
     */
    @Test
    fun theStationsCanBeLeftOutOfTheFirstScrap() {
        val withThem = exportOf(cave(), options = Th2Exporter.Options(stationsInFirstScrap = true))
        val without = exportOf(cave(), options = Th2Exporter.Options(stationsInFirstScrap = false))

        assertContains(withThem, "-name 1")
        assertFalse(without.contains("-name 1"), "the stations were written anyway: $without")
    }

    /** And the cross-section anchors go with them, because they are written at their station. */
    @Test
    fun theCrossSectionAnchorsFollowTheStations() {
        val survey = withCrossSection()

        val without =
            exportOf(survey, options = Th2Exporter.Options(stationsInFirstScrap = false))

        assertFalse(
            without.contains("point.*section".toRegex()),
            "a section anchor was left with no station to hang it on: $without",
        )
        // The section's own scrap is still written: it is the drawing, not the anchor.
        assertContains(without, "-projection none")
    }

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

    // Cross-sections on the elevation, from `Th2ExporterTest` in upstream 1.13.0, and how the
    // options bear on them, from `Th2ExporterPreferencesTest`. The preferences are the
    // `Th2Exporter.Options` they are read into, so there is nothing here to stand in for them.

    // point <x> <y> section -scrap <name>
    private val sectionPoint = Regex("""^point (\S+) (\S+) section -scrap (\S+)""")

    // scrap <name> -projection none -scale [0 0 <x> <y> 0 0 10.00 10.00 m]
    private val sectionScrap = Regex("""^scrap (\S+) -projection none -scale \[0 0 (\S+) (\S+) """)

    /** The regular expression's groups for every line of the th2 it matches. */
    private fun match(th2: String, pattern: Regex): List<List<String>> =
        th2.lines().mapNotNull { pattern.find(it.trimStart())?.groupValues?.drop(1) }

    /** The names of the section scraps the main scrap points at, sorted. */
    private fun sectionPointNames(th2: String): List<String> =
        match(th2, sectionPoint).map { it[2] }.sorted()

    /** The names of the scraps drawn with no projection, as sections are, sorted. */
    private fun sectionScrapNames(th2: String): List<String> =
        match(th2, sectionScrap).map { it[0] }.sorted()

    private fun List<String>.anyEndsWith(ending: String) = any { it.endsWith(ending) }

    private val scale = SvgExporter.SCALE.toFloat()

    @Test
    fun theElevationHasAnExtendedScrapAndAScrapForEachSection() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val th2 = exportOf(survey, Projection2D.EXTENDED_ELEVATION)

        assertEquals(1, th2.split("-projection extended").size - 1)
        assertEquals(2, sectionScrapNames(th2).size)
    }

    @Test
    fun theElevationsSectionsArePointedAtByTheMainScrap() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val th2 = exportOf(survey, Projection2D.EXTENDED_ELEVATION)

        val pointedAt = sectionPointNames(th2)
        assertEquals(2, pointedAt.size)
        assertTrue(pointedAt.anyEndsWith("EEX1"))
        assertTrue(pointedAt.anyEndsWith("EEX3"))
        // ...and every one of them has the scrap it points at
        assertEquals(pointedAt, sectionScrapNames(th2))
    }

    @Test
    fun theElevationsSectionPointsAreWhereTheSectionsAre() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val th2 = exportOf(survey, Projection2D.EXTENDED_ELEVATION)

        for ((x, y, name) in match(th2, sectionPoint)) {
            // The sections are at (2, 4) and (12, 4), and Therion's y runs the other way
            val expectedX = if (name.endsWith("EEX1")) 2 * scale else 12 * scale
            assertTrue(abs(expectedX - x.toFloat()) < 0.01f, "$name at $x")
            assertTrue(abs(-4 * scale - y.toFloat()) < 0.01f, "$name at $y")
        }
    }

    @Test
    fun aHorizontalSectionHasANoProjectionScrapLikeAVerticalOne() {
        // Therion has one kind of section scrap, and anything with no projection is one. Station
        // 1 has a vertical section on the elevation and station 3 a horizontal one.
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val scraps = sectionScrapNames(exportOf(survey, Projection2D.EXTENDED_ELEVATION))

        assertTrue(scraps.anyEndsWith("EEX1"))
        assertTrue(scraps.anyEndsWith("EEX3"))
    }

    @Test
    fun planAndElevationSectionScrapsHaveDifferentNames() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        val plan = sectionScrapNames(exportOf(survey, Projection2D.PLAN))
        val elevation = sectionScrapNames(exportOf(survey, Projection2D.EXTENDED_ELEVATION))

        // Stations 1 and 3 have sections on both, and Therion needs every scrap named differently
        assertEquals(2, plan.size)
        assertEquals(2, elevation.size)
        for (name in plan) assertFalse(name in elevation, name)
    }

    @Test
    fun eachExportHasOnlyItsOwnSketchsSections() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        survey.elevationSketch.addCrossSection(
            CrossSection.horizontal(survey.getStationByName("2")!!),
            Coord2D(7f, 4f),
        )

        val plan = sectionPointNames(exportOf(survey, Projection2D.PLAN))
        val elevation = sectionPointNames(exportOf(survey, Projection2D.EXTENDED_ELEVATION))

        assertTrue(elevation.anyEndsWith("EEX2"))
        assertEquals(3, elevation.size)
        assertEquals(2, plan.size)
        assertFalse(plan.anyEndsWith("2"))
    }

    @Test
    fun sectionScrapsAreScaledByTheCrossSectionScale() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()

        for (crossSectionScale in listOf(1f, 2f, 3f)) {
            survey.elevationSketch.crossSectionScale = crossSectionScale

            val th2 = exportOf(survey, Projection2D.EXTENDED_ELEVATION)

            for ((_, x, y) in match(th2, sectionScrap)) {
                // The scrap is 10m across at the cross-section scale
                val expected = 10 * scale * crossSectionScale
                assertTrue(abs(expected - x.toFloat()) < 0.01f, "$x at $crossSectionScale")
                assertTrue(abs(expected - y.toFloat()) < 0.01f, "$y at $crossSectionScale")
            }
        }
    }

    @Test
    fun theElevationsSectionNamesFollowTheElevationSuffix() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        val options = Th2Exporter.Options(elevationCrossSectionSuffix = "SEC-#")

        val elevation = sectionPointNames(exportOf(survey, Projection2D.EXTENDED_ELEVATION, options))
        val plan = sectionPointNames(exportOf(survey, Projection2D.PLAN, options))

        assertTrue(elevation.anyEndsWith("SEC-1"))
        assertTrue(elevation.anyEndsWith("SEC-3"))
        // The plan has its own suffix, which hasn't been changed
        assertTrue(plan.anyEndsWith("PX1"))
    }

    @Test
    fun thePlansSectionNamesFollowThePlanSuffix() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        val options = Th2Exporter.Options(planCrossSectionSuffix = "S#")

        val plan = sectionPointNames(exportOf(survey, Projection2D.PLAN, options))
        val elevation = sectionPointNames(exportOf(survey, Projection2D.EXTENDED_ELEVATION, options))

        assertTrue(plan.anyEndsWith("S1"))
        assertTrue(elevation.anyEndsWith("EEX1"))
    }

    @Test
    fun noSectionsAreExportedWhenTheOptionIsOff() {
        val survey = TestSurveys.createWithCrossSectionsInPlanAndElevation()
        val options = Th2Exporter.Options(crossSections = false)

        for (projection in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
            val th2 = exportOf(survey, projection, options)

            assertTrue(sectionPointNames(th2).isEmpty(), projection.name)
            assertTrue(sectionScrapNames(th2).isEmpty(), projection.name)
        }
    }
}
