package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.SvgLegend
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyStats
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strip under the drawing: what turns an exported plan into a survey somebody else can read.
 *
 * A drawing with no scale, no north and no date is a picture. Every caving club that accepts survey
 * data expects the other thing, so this is not decoration — and the Java lays it out with two dozen
 * arithmetic constants, which is exactly the sort of thing a port gets subtly wrong.
 */
class SvgLegendTest {

    private fun cave(): Survey {
        val survey = Survey("Swildons")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, 0f))
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 90f, 0f))
        val middle = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, middle, Leg(2f, 45f, 0f))
        return survey
    }

    private fun tripped(): Survey {
        val survey = cave()
        val trip = Trip(SurveyDate(2026, 4, 12))
        trip.team =
            listOf(
                Trip.TeamEntry("Lizzie Waterworth", listOf(Trip.Role.BOOK)),
                Trip.TeamEntry("A N Other", listOf(Trip.Role.INSTRUMENTS)),
            )
        trip.copyrightHolder = "Wessex Cave Club"
        trip.licence = "CC BY 4.0"
        survey.trip = trip
        return survey
    }

    // ------------------------------------------------------------------------------------
    // What reaches the file
    // ------------------------------------------------------------------------------------

    @Test
    fun theLegendCarriesEverythingASurveyNeedsToBeReadable() {
        val svg = SvgExporter.export(tripped())

        assertContains(svg, "<g id=\"legend\"")
        assertContains(svg, ">Swildons</text>")
        assertContains(svg, ">2026-04-12</text>")
        assertContains(svg, ">Surveyed By: Lizzie Waterworth, A N Other</text>")
        assertContains(svg, "<g id=\"scale-bar\"")
        assertContains(svg, "<g id=\"north-arrow\"")
        assertContains(svg, ">Surveyed with SexyTopo</text>")
    }

    @Test
    fun theLegendCanBeTurnedOffEntirely() {
        val svg = SvgExporter.export(tripped(), options = SvgExporter.Options(showLegend = false))

        assertFalse("<g id=\"legend\"" in svg)
        assertFalse("<g id=\"scale-bar\"" in svg)
    }

    /**
     * Each part is separately switchable, as in `SvgExportOptions` — a club that wants the drawing
     * without the tagline, or a survey whose team would rather not be named, gets that.
     */
    @Test
    fun eachPartOfTheLegendCanBeTurnedOffOnItsOwn() {
        val bare =
            SvgExporter.export(
                tripped(),
                options =
                    SvgExporter.Options(
                        showNorthArrow = false,
                        showScaleBar = false,
                        showTeam = false,
                        showCopyright = false,
                        showTagline = false,
                    ),
            )

        // Only the legend is inspected: the copyright also goes in the document's <desc>, which is
        // metadata rather than part of the drawing and is not what this option governs.
        val legend = bare.substringAfter("<g id=\"legend\"")
        assertContains(legend, ">Swildons</text>")
        assertFalse("north-arrow" in legend)
        assertFalse("scale-bar" in legend)
        assertFalse("Surveyed By" in legend)
        assertFalse("Wessex Cave Club" in legend)
        assertFalse("Surveyed with SexyTopo" in legend)
    }

    /**
     * North means nothing in an extended elevation — the passage is unrolled onto a line, so the
     * horizontal axis is distance along the cave rather than a compass direction. Drawing an arrow
     * there would be worse than drawing none.
     */
    @Test
    fun thereIsNoNorthArrowInAnExtendedElevation() {
        val plan = SvgExporter.export(tripped(), Projection2D.PLAN)
        val elevation = SvgExporter.export(tripped(), Projection2D.EXTENDED_ELEVATION)

        assertContains(plan, "north-arrow")
        assertFalse("north-arrow" in elevation)
        assertContains(elevation, "<g id=\"legend\"", message = "the rest of the legend still goes")
    }

    /**
     * The page grows to hold the legend, so it can never land on top of the cave.
     *
     * This is the whole reason the layout is computed before anything is written: the height in the
     * `svg` element and its viewBox both have to know how tall the strip turned out.
     */
    @Test
    fun thePageGrowsToMakeRoomForTheLegend() {
        // A long, flat passage: the drawing is wide and barely tall, so the legend strip is the
        // thing that decides how tall the page has to be. A squarer survey has enough border to
        // swallow it, which is fine and is why the assertion is made on this shape.
        val flat = Survey("Flat")
        SurveyBuilder.updateWithNewStation(flat, Leg(30f, 90f, 0f))

        val without =
            heightOf(SvgExporter.export(flat, options = SvgExporter.Options(showLegend = false)))
        val with = heightOf(SvgExporter.export(flat))

        assertTrue(with > without, "the page did not grow: $with is not more than $without")
    }

    /** The drawing does not move when the legend appears — only the page below it grows. */
    @Test
    fun theDrawingItselfIsUnmoved() {
        val flat = Survey("Flat")
        SurveyBuilder.updateWithNewStation(flat, Leg(30f, 90f, 0f))
        val without = SvgExporter.export(flat, options = SvgExporter.Options(showLegend = false))
        val with = SvgExporter.export(flat)

        val centreline = "<g id=\"centreline\">"
        assertEquals(
            without.substringAfter(centreline).substringBefore("</g>"),
            with.substringAfter(centreline).substringBefore("</g>"),
        )
    }

    private fun heightOf(svg: String): Double =
        svg.substringAfter(" height=\"").substringBefore('"').toDouble()

    // ------------------------------------------------------------------------------------
    // The lines themselves
    // ------------------------------------------------------------------------------------

    /** "© 2026 Wessex Cave Club — CC BY 4.0": the © and the year are added, the holder is a name. */
    @Test
    fun theCopyrightLineIsBuiltFromTheTripAndItsYear() {
        assertEquals(
            "© 2026 Wessex Cave Club — CC BY 4.0",
            SvgLegend.copyrightLine(tripped().trip),
        )
    }

    @Test
    fun eitherHalfOfTheCopyrightLineCanBeMissing() {
        val holderOnly = Trip(SurveyDate(2026, 4, 12)).also { it.copyrightHolder = "A Club" }
        val licenceOnly = Trip(SurveyDate(2026, 4, 12)).also { it.licence = "CC0" }

        assertEquals("© 2026 A Club", SvgLegend.copyrightLine(holderOnly))
        assertEquals("CC0", SvgLegend.copyrightLine(licenceOnly))
        assertEquals("", SvgLegend.copyrightLine(Trip(SurveyDate(2026, 4, 12))))
        assertEquals("", SvgLegend.copyrightLine(null))
    }

    /** A team member entered and then cleared should not leave a stray comma on the drawing. */
    @Test
    fun blankTeamNamesAreDropped() {
        val trip = Trip(SurveyDate(2026, 4, 12))
        trip.team =
            listOf(
                Trip.TeamEntry("Lizzie Waterworth"),
                Trip.TeamEntry("   "),
                Trip.TeamEntry("A N Other"),
            )

        assertEquals("Lizzie Waterworth, A N Other", SvgLegend.teamNames(trip))
        assertEquals("", SvgLegend.teamNames(null))
    }

    /**
     * "L: 20 m, H: 0 m": the surveyed length is connecting legs only, so the splay does not count.
     *
     * A survey whose length included its wall shots would be reporting a number three times too
     * large, and it is the number that goes in the trip report.
     */
    @Test
    fun theStatsLineCountsPassageAndNotWallShots() {
        assertEquals("L: 20 m, H: 0 m", SvgLegend.statsLine(cave()))
    }

    @Test
    fun theStatsLineRoundsHalfUpAsTheAndroidAppDoes() {
        val survey = Survey("T")
        // 2.5 m of passage: Kotlin's round() is ties-to-even and would give 2.
        SurveyBuilder.updateWithNewStation(survey, Leg(2.5f, 0f, 0f))

        assertEquals("L: 3 m, H: 0 m", SvgLegend.statsLine(survey))
    }

    @Test
    fun theHeightRangeIsTopStationToBottomStation() {
        val survey = Survey("T")
        SurveyBuilder.updateWithNewStation(survey, Leg(10f, 0f, -90f))
        SurveyBuilder.updateWithNewStation(survey, Leg(4f, 0f, 90f))

        assertEquals(10f, SurveyStats.heightRange(survey), 0.001f)
        assertEquals(-10f to 0f, SurveyStats.heightRangeOf(survey).let { it.first to it.second })
    }

    /** One station is not a range, and must not report one built out of sentinel values. */
    @Test
    fun aSurveyOfOneStationHasNoHeightRange() {
        assertEquals(0f, SurveyStats.heightRange(Survey("T")))
    }

    @Test
    fun theScaleBarLabelIsWholeMetresOrCentimetres() {
        assertEquals("10 m", SvgLegend.scaleBarLabel(10.0))
        assertEquals("1 m", SvgLegend.scaleBarLabel(1.0))
        assertEquals("50 cm", SvgLegend.scaleBarLabel(0.5))
        assertEquals("2 cm", SvgLegend.scaleBarLabel(0.02))
    }

    // ------------------------------------------------------------------------------------
    // The layout
    // ------------------------------------------------------------------------------------

    /**
     * The body lines run down the strip in order, evenly spaced, below the title.
     *
     * Every one of these numbers is the Java's, and none of them is derived from anything
     * measurable — so the only way a port keeps the two apps' legends comparable is to pin them.
     */
    @Test
    fun theLayoutIsTheAndroidAppsArithmetic() {
        val legend =
            SvgLegend(
                title = "Swildons",
                bodyLines = listOf("a", "b", "c"),
                barLengthInMetres = 10.0,
                scale = SvgExporter.SCALE,
                isPlan = true,
                showNorthArrow = true,
                showScaleBar = true,
                showTagline = true,
            )

        // topPadding 9 + titleFont 24.
        assertEquals(33.0, legend.titleY, 0.001)
        // + sectionGap 12, then lineGap 25.5 per line.
        assertEquals(listOf(70.5, 96.0, 121.5), legend.bodyYs)
        // + sectionGap 12 + taglineFont 11.25.
        assertEquals(144.75, legend.taglineY, 0.001)
        // + preScaleBarGap 18, then tickHeight 7.2 down to the baseline.
        assertEquals(162.75, legend.barTopY, 0.001)
        assertEquals(169.95, legend.barBaselineY, 0.001)
        assertEquals(500.0, legend.barLengthInPixels, 0.001)
        assertEquals(1, legend.strokeWidth)
    }

    /**
     * The arrow is given its own vertical room rather than being allowed to push the text about,
     * so a short legend beside a tall arrow still ends below the arrow.
     */
    @Test
    fun theNorthArrowReservesItsOwnHeight() {
        fun legend(withArrow: Boolean) =
            SvgLegend(
                title = "S",
                bodyLines = listOf("a"),
                barLengthInMetres = 1.0,
                scale = SvgExporter.SCALE,
                isPlan = true,
                showNorthArrow = withArrow,
                showScaleBar = true,
                showTagline = true,
            )

        val withArrow = legend(true)
        assertTrue(withArrow.totalHeight > legend(false).totalHeight)
        // arrowBottomY 144 + bodyFont 15 (the N beneath it) + bottomPadding 9.
        assertEquals(168.0, withArrow.totalHeight, 0.001)
    }

    /** A survey with nothing in it has no width to measure a scale bar against. */
    @Test
    fun aSurveyWithNoExtentGetsNoLegend() {
        val nothing =
            SvgLegend.of(
                Survey("T"),
                Projection2D.PLAN,
                org.hwyl.sexytopo.shared.model.common.Frame(0f, 0f, 0f, 0f),
                SvgExporter.SCALE,
                SvgExporter.Options.DEFAULT,
            )

        assertEquals(null, nothing)
    }
}
