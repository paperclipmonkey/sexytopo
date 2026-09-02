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
 * Finding 88: a cross-section used to be a bare star of splay rays, offset from its station in one
 * fixed direction regardless of which way the passage ran, which on plenty of stations put it back
 * on top of the centreline it was meant to illustrate. These pin the fix down as behaviour.
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

    /** A cross-section's sub-sketch used to be left empty; every section here now carries a closed outline. */
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

            // More points than splay tips: hand-drawn wobble, not a connect-the-dots polygon.
            assertTrue(
                path.size > tips,
                "an outline traced by hand should have more points than the ${tips} tips it runs around",
            )
            assertEquals(path.first(), path.last(), "the outline should close back on itself")
        }
    }

    /**
     * A section has to land clear of the centreline, the wall lines, and every other section — not
     * just its own station, which the old fixed `(0, offset)` direction only achieved east-west.
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

    /** The old random selection could, and on plenty of seeds did, never touch a side branch. */
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
