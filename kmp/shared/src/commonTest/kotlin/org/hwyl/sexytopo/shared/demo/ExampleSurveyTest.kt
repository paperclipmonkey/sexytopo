package org.hwyl.sexytopo.shared.demo

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Station
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

/**
 * The demo cave is the first thing a visitor with no survey of their own sees, and its whole job
 * is to sell the cross-section feature. See finding 88: a cross-section used to be a bare star of
 * splay rays, offset from its station in one fixed direction regardless of which way the passage
 * ran, which on plenty of stations put it right back on top of the centreline it was meant to
 * illustrate. These pin the fix down as behaviour rather than trusting the screenshot alone.
 */
class ExampleSurveyTest {

    private val survey = ExampleSurvey.create()
    private val plan = survey.getSketch(Projection2D.PLAN)

    /** Every cross-section this cave carries, with its footprint as actually drawn on the plan. */
    private data class DrawnSection(val detail: CrossSectionDetail, val footprint: Float)

    private val drawnSections: List<DrawnSection> by lazy {
        plan.crossSectionDetails.map { detail ->
            val reach = detail.crossSection.getProjection().legMap.values.maxOf { it.end.mag() }
            DrawnSection(detail, reach * plan.crossSectionScale)
        }
    }

    @Test
    fun theDemoCaveHasCrossSectionsWorthNoticing() {
        // The old one-in-five would place 4 on this cave; a visitor should not have to hunt.
        assertTrue(
            plan.crossSectionDetails.size >= 5,
            "expected a generous helping of cross-sections, found ${plan.crossSectionDetails.size}",
        )
    }

    /**
     * A cross-section's sub-sketch used to be left empty — `CrossSectioner.section` only ever
     * built the splay star, and nothing traced a wall around it. Every section here should now
     * carry a closed outline drawn around its own LRUD tips.
     */
    @Test
    fun everyCrossSectionHasAWallDrawnAroundItsLruds() {
        assertTrue(drawnSections.isNotEmpty(), "the demo cave should have cross-sections at all")

        for (drawn in drawnSections) {
            val sketch = drawn.detail.sketch
            assertEquals(
                1,
                sketch.pathDetails.size,
                "station ${drawn.detail.station.name} should carry exactly one outline",
            )
            val path = sketch.pathDetails.first().path
            val tips = drawn.detail.crossSection.getProjection().legMap.size

            // More points than splay tips: the outline is hand-drawn wobble between them
            // (freehandTo), not a bare connect-the-dots polygon.
            assertTrue(
                path.size > tips,
                "an outline traced by hand should have more points than the ${tips} tips it runs around",
            )
            // Closed: the traced wall comes back to where it started.
            assertEquals(path.first(), path.last(), "the outline should close back on itself")
        }
    }

    /**
     * The heart of finding 88: a section has to land clear of the centreline, the wall lines
     * either side of it, and every other section — not just clear of its own station, which the
     * old fixed `(0, offset)` direction only achieved for passages that happened to run east-west.
     */
    @Test
    fun crossSectionsAreClearOfTheRestOfTheSurvey() {
        val stationPositions = Projection2D.PLAN.project(survey).stationMap.values
        val wallPoints = plan.pathDetails.flatMap { it.path }

        for (drawn in drawnSections) {
            for (point in stationPositions) {
                assertClearOf(drawn, point, "a station")
            }
            for (point in wallPoints) {
                assertClearOf(drawn, point, "a wall line")
            }
        }

        for (i in drawnSections.indices) {
            for (j in i + 1 until drawnSections.size) {
                val a = drawnSections[i]
                val b = drawnSections[j]
                val separation = getDistance(a.detail.position, b.detail.position)
                val required = a.footprint + b.footprint
                assertTrue(
                    separation >= required,
                    "sections at ${a.detail.station.name} and ${b.detail.station.name} should " +
                        "not overlap: $separation apart, need $required",
                )
            }
        }
    }

    private fun assertClearOf(drawn: DrawnSection, point: Coord2D, what: String) {
        val distance = getDistance(drawn.detail.position, point)
        assertTrue(
            distance >= drawn.footprint,
            "the section at ${drawn.detail.station.name} (footprint ${drawn.footprint}) should " +
                "clear $what $distance away",
        )
    }

    /**
     * [ExampleSurvey.chooseSectionStations] in isolation: the old random selection could, and on
     * plenty of seeds did, land every cross-section on the entrance series and never touch one of
     * the cave's four side branches.
     */
    @Test
    fun chosenStationsAlwaysIncludeABranchWhenOneIsOffered() {
        val random = Random(1)
        val main = List(10) { Station("main$it") }
        val branch = List(3) { Station("branch$it") }
        val candidates = main + branch

        repeat(20) { seed ->
            val chosen =
                ExampleSurvey.chooseSectionStations(candidates, branch.toSet(), count = 4, Random(seed))
            assertTrue(
                chosen.any { it in branch },
                "seed $seed chose ${chosen.map { it.name }}, none of it on the branch",
            )
            assertEquals(4, chosen.size, "should still choose the full count requested")
        }
    }

    /** With nothing on a branch to offer, the pick still returns the count asked for. */
    @Test
    fun chosenStationsFallBackCleanlyWithNoBranchStations() {
        val candidates = List(6) { Station("s$it") }
        val chosen = ExampleSurvey.chooseSectionStations(candidates, emptySet(), count = 3, Random(7))
        assertEquals(3, chosen.size)
    }
}
