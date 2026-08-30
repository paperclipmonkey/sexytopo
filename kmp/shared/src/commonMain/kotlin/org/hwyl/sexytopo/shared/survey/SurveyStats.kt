package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.math.Space3DTransformer
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * How big the cave is: the numbers a caver puts on a drawing and in a trip report.
 *
 * Ported from `control/util/SurveyStats`, whose own comment apologises for being written before
 * Java had lambdas. The arithmetic is unchanged; only the loops are gone.
 *
 * Two of these are load-bearing for the SVG legend — [totalLength] and [heightRange] are the
 * "L: 120 m, H: 14 m" line under the title — and the rest come with them because they are the same
 * five lines and the app shows them on its own statistics screen.
 */
object SurveyStats {

    /**
     * The surveyed length in metres: connecting legs only.
     *
     * Splays are excluded because they are wall shots, not passage. Note this is the *shot* length
     * rather than the horizontal length, which is what every cave survey means by "length".
     */
    fun totalLength(survey: Survey): Float =
        survey.getAllLegs().filter { it.hasDestination() }.sumOf { it.distance.toDouble() }.toFloat()

    /**
     * The longest shot, splays included — as in the original, which does not filter here even
     * though [totalLength] does.
     */
    fun longestLeg(survey: Survey): Float =
        survey.getAllLegs().maxOfOrNull { it.distance } ?: 0f

    fun shortestLeg(survey: Survey): Float =
        survey.getAllLegs().minOfOrNull { it.distance } ?: 0f

    /**
     * How many stations the survey has, *minus one*.
     *
     * The original's own arithmetic: the origin is not a station anybody surveyed to, so a survey
     * with only its origin has counted nothing. Reproduced rather than corrected, because the
     * number the app has always shown is this one.
     */
    fun numberOfStations(survey: Survey): Int = survey.getAllStations().size - 1

    /**
     * How much cave hangs off one station — the counts the app shows for a branch.
     *
     * The Java reaches these through static `Survey.getAllStations(origin)` and
     * `Survey.getAllLegs(origin)` overloads that this port does not carry, so the subtree walk is
     * here instead. It is the same walk: a survey is a tree, so following the connected onward legs
     * from a station reaches its branch and nothing else.
     */
    fun numberOfStationsUnder(origin: Station): Int = stationsUnder(origin).size

    fun numberOfLegsUnder(origin: Station): Int = legsUnder(origin).size

    fun numberOfFullLegsUnder(station: Station): Int =
        legsUnder(station).count { it.hasDestination() }

    fun numberOfSplaysUnder(station: Station): Int =
        legsUnder(station).count { !it.hasDestination() }

    /** Iterative for the same reason `Survey.getAllStations` is: a passage is a chain. */
    private fun stationsUnder(origin: Station): List<Station> =
        buildList {
            val pending = ArrayDeque<Station>()
            pending.addLast(origin)
            while (pending.isNotEmpty()) {
                val station = pending.removeLast()
                add(station)
                for (leg in station.getConnectedOnwardLegs().asReversed()) {
                    pending.addLast(leg.destination)
                }
            }
        }

    private fun legsUnder(origin: Station): List<Leg> =
        stationsUnder(origin).flatMap { it.onwardLegs }

    /** The vertical range in metres: how deep the cave is, top station to bottom station. */
    fun heightRange(survey: Survey): Float {
        val (bottom, top) = heightRangeOf(survey)
        return top - bottom
    }

    /**
     * The lowest and highest station heights, or `0f to 0f` for a survey with at most one station.
     *
     * The original returns a two-element array and the same zero pair for the degenerate case;
     * a single station has no range, and reporting one from `Float.MAX_VALUE` and `Float.MIN_VALUE`
     * would be worse than reporting none.
     */
    fun heightRangeOf(survey: Survey): Pair<Float, Float> {
        val heights = Space3DTransformer().transformTo3D(survey).stationMap.values.map { it.z }
        if (heights.size <= 1) return 0f to 0f
        return heights.min() to heights.max()
    }
}
