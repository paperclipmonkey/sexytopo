package org.hwyl.sexytopo.shared.demo

import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Symbol
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

    private val stationPositions by lazy { Projection2D.PLAN.project(survey).stationMap }

    private fun symbolsNamed(symbol: Symbol) =
        plan.symbolDetails.filter { it.symbolName == symbol.therionName }

    /**
     * The demo used to carry four symbols, stamped at random stations, which showed the artwork
     * could be drawn and nothing else. A sketch is where a survey records what the centreline
     * cannot, so a demo cave with four decorations on it undersells the whole feature.
     */
    @Test
    fun theDemoCaveIsAnnotated() {
        assertTrue(
            plan.symbolDetails.size >= 12,
            "a demo of the sketching layer should be worth reading: ${plan.symbolDetails.size} symbols",
        )
    }

    /** One symbol to a station: two stamped on the same spot are one illegible smudge. */
    @Test
    fun noTwoSymbolsAreStampedOnTopOfEachOther() {
        val positions = plan.symbolDetails.map { it.position }
        assertEquals(
            positions.size,
            positions.toSet().size,
            "two symbols share a position, so one of them cannot be read",
        )

        for (detail in plan.symbolDetails) {
            val nearest = stationPositions.values.minOf { getDistance(it, detail.position) }
            assertTrue(
                nearest < 4f,
                "${detail.symbolName} is ${nearest}m from any station, which is not in a passage",
            )
        }
    }

    /** The one symbol a reader looks for first, and it has to be on the way in. */
    @Test
    fun theEntranceIsMarkedAtTheEntrance() {
        val entrances = symbolsNamed(Symbol.ENTRANCE)
        assertEquals(1, entrances.size, "a cave has one entrance symbol, on its entrance")

        val origin = stationPositions[survey.origin]!!
        val away = getDistance(entrances.first().position, origin)
        assertTrue(away < 3f, "the entrance symbol is ${away}m from the entrance station")
    }

    /**
     * A directional symbol with no bearing is the one that looks fine and means nothing: water
     * that runs due north, a passage that gets too tight in a direction nobody chose.
     */
    @Test
    fun theDirectionalSymbolsAreTurnedToTheirPassage() {
        val directional =
            plan.symbolDetails.filter { Symbol.byTherionName(it.symbolName)?.isDirectional == true }
        assertTrue(directional.size >= 4, "the cave should carry directional symbols at all")
        assertTrue(
            directional.count { it.angle != 0f } >= directional.size - 1,
            "the directional symbols were stamped upright rather than along their passage",
        )

        for (detail in plan.symbolDetails) {
            if (Symbol.byTherionName(detail.symbolName)?.isDirectional == false) {
                assertEquals(
                    0f,
                    detail.angle,
                    "${detail.symbolName} does not point anywhere, so it should not be turned",
                )
            }
        }
    }

    /** `colourForSymbol`: the app stamps water blue whatever colour is in the surveyor's hand. */
    @Test
    fun theWaterIsBlue() {
        val water = symbolsNamed(Symbol.WATER_FLOW)
        assertTrue(water.isNotEmpty(), "a stream passage should have its flow marked")
        for (detail in water) {
            assertEquals(Colour.BLUE, detail.colour, "a water symbol should be stamped blue")
        }
        for (detail in plan.symbolDetails - water.toSet()) {
            assertEquals(
                Colour.BLACK,
                detail.colour,
                "${detail.symbolName} should be the colour of the brush",
            )
        }
    }

    /** A passage that stops does so for a reason, and the sketch is where the reason is written. */
    @Test
    fun theEndsOfTheCaveSayWhyTheyAreTheEnds() {
        val leads =
            symbolsNamed(Symbol.TOO_TIGHT).size + symbolsNamed(Symbol.AIR_DRAUGHT).size
        assertTrue(leads >= 2, "no dead end in the cave says why it is one")

        val deadEnds =
            stationPositions.filterKeys {
                !survey.isOrigin(it) && it.getConnectedOnwardLegs().isEmpty()
            }
        for (name in listOf(Symbol.TOO_TIGHT, Symbol.AIR_DRAUGHT)) {
            for (detail in symbolsNamed(name)) {
                val away = deadEnds.values.minOf { getDistance(it, detail.position) }
                assertTrue(
                    away < 3f,
                    "a ${name.therionName} is ${away}m from any dead end, so it marks nothing",
                )
            }
        }
    }
}
