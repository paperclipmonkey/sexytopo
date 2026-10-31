package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Line
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A placement found without touching any station, and how much to trust it.
 *
 * [slideMetres] is how far the scan could be moved along its own length without fitting
 * noticeably worse. In a straight featureless passage that is large, and says the scan's position
 * along the passage is a guess, though its LRUDs are still good; at a junction or a bend it is
 * small. [legsThroughRock] is the share of the centerline inside the scan's reach that ran through
 * scanned rock: near zero for a good fit, and well above it where the old survey is simply wrong.
 */
class FittedResult(
    val placement: ScanPlacement,
    val cost: Float,
    val slideMetres: Float,
    val legsThroughRock: Float,
)

/**
 * Placing a scan on an old centerline that has no surviving station marks.
 *
 * Two facts make it possible. The phone's own path is inside the passage, because the surveyor
 * walked it; and the survey legs are inside the passage too, because the surveyors who shot them
 * stood in it. So a good placement puts the phone's path close to the centerline, and puts no leg
 * through rock the scan saw. Neither alone is enough: the first allows a scan to sit in the right
 * passage turned the wrong way round a bend, the second is satisfied by a scan floating in empty
 * space. Together, with a rough start, they find it.
 *
 * The start is the surveyor's pin ("this scan begins near station 23") and ARKit's heading, which
 * underground may be out by tens of degrees. The search is a grid, coarse and then fine, around
 * that start: a few thousand candidates, each cheap, because the scan's points are bucketed once in
 * the scan's own frame and the centerline is moved into that frame rather than the reverse.
 *
 * The match is deliberately one-way. The scan may see far more than the survey did, alcoves and
 * side passages and avens, and none of that counts against a placement; only centerline inside the
 * scan's reach is asked about.
 */
object FittedPlacement {

    /** How far either side of the prior heading to look. */
    const val HEADING_RANGE_DEGREES = 45f

    /** How far from the pin to look, horizontally. */
    const val SEARCH_RADIUS_METRES = 6f

    /** A phone's path further than this from any leg costs no more: it may be in a side passage. */
    const val PATH_DISTANCE_CAP_METRES = 4f

    /** Rock this close to a leg means the leg goes through it. */
    const val ROCK_CLEARANCE_METRES = 0.25f

    /**
     * Legs touch rock at the stations, which are on walls and floors: so close to a station a leg
     * meeting rock is expected, and is not counted.
     */
    const val STATION_ALLOWANCE_METRES = 1f

    /** How heavily a leg through rock counts against the phone's path being off the centerline. */
    const val ROCK_WEIGHT = 4f

    private const val LEG_SAMPLE_METRES = 0.25f
    private const val PATH_SAMPLES = 200
    private const val SLIDE_TOLERANCE = 1.1f

    /**
     * The best placement for a scan near [pin], or null when there is no centerline near it.
     *
     * [path] is the phone's track and [points] the rock it saw, both in the scan's own frame;
     * [pin] is roughly where in the survey the first point of [path] was.
     */
    fun fit(
        path: List<Coord3D>,
        points: List<Coord3D>,
        frame: SurveyFrame,
        pin: Coord3D,
        priorHeadingDegrees: Float,
    ): FittedResult? {
        if (path.isEmpty()) return null
        val reach = SEARCH_RADIUS_METRES + extent(path) + PATH_DISTANCE_CAP_METRES
        val legs = frame.legLines.filter { SurveyFrame.distanceToSegment(pin, it) <= reach }
        if (legs.isEmpty()) return null

        val pathSamples = thin(path, PATH_SAMPLES)
        val legSamples = sampleLegs(legs)
        val rock = Occupancy(points)
        val start = path.first()

        fun placementFor(heading: Float, east: Float, north: Float): ScanPlacement {
            // Turn about the start of the path, then put that start at the candidate position:
            // so the translation search is a search for where the path began, as the pin is.
            val turned = ScanPlacement(heading, Coord3D.ORIGIN).place(start)
            val provisional =
                ScanPlacement(heading, Coord3D(east - turned.x, north - turned.y, 0f))
            return ScanPlacement(heading, provisional.translation.withZ(heightOffset(provisional, pathSamples, legs)))
        }

        fun cost(placement: ScanPlacement): Pair<Float, Float> {
            val pathCost = pathCost(placement, pathSamples, legs)
            val throughRock = throughRock(placement, legSamples, rock)
            return (pathCost + ROCK_WEIGHT * throughRock) to throughRock
        }

        var best = Candidate(priorHeadingDegrees, pin.x, pin.y, Float.MAX_VALUE)
        for ((headingStep, positionStep, headingRange, radius) in listOf(
            Pass(5f, 1f, HEADING_RANGE_DEGREES, SEARCH_RADIUS_METRES),
            Pass(1f, 0.25f, 5f, 1f),
            Pass(0.25f, 0.05f, 1f, 0.25f),
        )) {
            val centre = best
            var heading = centre.heading - headingRange
            while (heading <= centre.heading + headingRange) {
                var east = centre.east - radius
                while (east <= centre.east + radius) {
                    var north = centre.north - radius
                    while (north <= centre.north + radius) {
                        val (value, _) = cost(placementFor(heading, east, north))
                        if (value < best.cost) best = Candidate(heading, east, north, value)
                        north += positionStep
                    }
                    east += positionStep
                }
                heading += headingStep
            }
        }

        val placement = placementFor(normalise(best.heading), best.east, best.north)
        val (finalCost, throughRock) = cost(placement)
        val slide = slide(placement, path, finalCost) { cost(it).first }
        return FittedResult(placement, finalCost, slide, throughRock)
    }

    private data class Pass(val headingStep: Float, val positionStep: Float, val headingRange: Float, val radius: Float)

    private class Candidate(val heading: Float, val east: Float, val north: Float, val cost: Float)

    /** Mean squared distance, capped, from the placed path to the nearest leg. */
    private fun pathCost(placement: ScanPlacement, path: List<Coord3D>, legs: List<Line<Coord3D>>): Float {
        var total = 0f
        for (point in path) {
            val placed = placement.place(point)
            val distance = legs.minOf { horizontalDistanceToSegment(placed, it) }
            val capped = minOf(distance, PATH_DISTANCE_CAP_METRES)
            total += capped * capped
        }
        return total / path.size
    }

    /**
     * The share of leg samples within the scan's reach that have rock right beside them.
     *
     * A sample is within reach when the scan saw anything at all within a few metres of it: a leg
     * the scan never got near says nothing either way.
     */
    private fun throughRock(placement: ScanPlacement, samples: List<Coord3D>, rock: Occupancy): Float {
        var covered = 0
        var blocked = 0
        for (sample in samples) {
            val inScan = placement.unplace(sample)
            if (!rock.reaches(inScan)) continue
            covered++
            if (rock.touches(inScan)) blocked++
        }
        return if (covered == 0) 0f else blocked.toFloat() / covered
    }

    /**
     * Where the scan saw rock, as two sets of occupied boxes: fine ones in three dimensions for
     * whether a leg passes through rock, coarse columns in plan for whether the scan reached a
     * place at all.
     *
     * Sets rather than the points themselves, because the search asks these two questions a few
     * hundred thousand times; walking the points near a sample each time would visit thousands of
     * them per question.
     */
    internal class Occupancy(points: List<Coord3D>) {
        private val fine = HashSet<Long>()
        private val coarse = HashSet<Long>()

        init {
            for (point in points) {
                fine.add(key(box(point.x, ROCK_CLEARANCE_METRES), box(point.y, ROCK_CLEARANCE_METRES), box(point.z, ROCK_CLEARANCE_METRES)))
                coarse.add(key(box(point.x, 1f), box(point.y, 1f), 0))
            }
        }

        /** Rock in the sample's own box or one next to it: within about the clearance. */
        fun touches(point: Coord3D): Boolean {
            val x = box(point.x, ROCK_CLEARANCE_METRES)
            val y = box(point.y, ROCK_CLEARANCE_METRES)
            val z = box(point.z, ROCK_CLEARANCE_METRES)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (key(x + dx, y + dy, z + dz) in fine) return true
            }
            return false
        }

        /** Any rock seen within a few metres, in plan. */
        fun reaches(point: Coord3D): Boolean {
            val x = box(point.x, 1f)
            val y = box(point.y, 1f)
            val reach = COVERAGE_METRES.toInt()
            for (dx in -reach..reach) for (dy in -reach..reach) {
                if (key(x + dx, y + dy, 0) in coarse) return true
            }
            return false
        }

        private fun box(metres: Float, size: Float): Int = floor(metres / size).toInt()

        private fun key(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
    }

    private const val COVERAGE_METRES = 3f

    /**
     * The height that puts the phone's path, as near as can be, at the height of the legs beside
     * it: the median of the differences, so a stretch where the path runs under an aven or along a
     * different leg does not drag it.
     */
    private fun heightOffset(placement: ScanPlacement, path: List<Coord3D>, legs: List<Line<Coord3D>>): Float {
        val differences =
            path.map { point ->
                val placed = placement.place(point)
                val leg = legs.minBy { horizontalDistanceToSegment(placed, it) }
                val t = SurveyFrame.fractionAlong(placed, leg).coerceIn(0f, 1f)
                leg.start.z + t * (leg.end.z - leg.start.z) - placed.z
            }.sorted()
        return differences[differences.size / 2]
    }

    /**
     * How far the placement can be moved along the path's own direction while its cost stays
     * within a tenth of the best: the scan's uncertainty along the passage.
     */
    private fun slide(
        placement: ScanPlacement,
        path: List<Coord3D>,
        best: Float,
        cost: (ScanPlacement) -> Float,
    ): Float {
        val first = placement.place(path.first())
        val last = placement.place(path.last())
        val dx = last.x - first.x
        val dy = last.y - first.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.5f) return 0f
        val ux = dx / length
        val uy = dy / length
        val tolerance = maxOf(best * SLIDE_TOLERANCE, best + 0.01f)

        fun reach(sign: Float): Float {
            var shift = 0f
            while (shift < SEARCH_RADIUS_METRES) {
                val next = shift + 0.25f
                val moved =
                    ScanPlacement(
                        placement.headingDegrees,
                        Coord3D(
                            placement.translation.x + sign * ux * next,
                            placement.translation.y + sign * uy * next,
                            placement.translation.z,
                        ),
                    )
                if (cost(moved) > tolerance) break
                shift = next
            }
            return shift
        }
        return reach(1f) + reach(-1f)
    }

    private fun sampleLegs(legs: List<Line<Coord3D>>): List<Coord3D> {
        val samples = mutableListOf<Coord3D>()
        for (leg in legs) {
            val length = StationAnchoring.distance(leg.start, leg.end)
            if (length <= 2 * STATION_ALLOWANCE_METRES) continue
            var along = STATION_ALLOWANCE_METRES
            while (along <= length - STATION_ALLOWANCE_METRES) {
                val t = along / length
                samples.add(
                    Coord3D(
                        leg.start.x + t * (leg.end.x - leg.start.x),
                        leg.start.y + t * (leg.end.y - leg.start.y),
                        leg.start.z + t * (leg.end.z - leg.start.z),
                    ),
                )
                along += LEG_SAMPLE_METRES
            }
        }
        return samples
    }

    private fun horizontalDistanceToSegment(point: Coord3D, line: Line<Coord3D>): Float {
        val dx = line.end.x - line.start.x
        val dy = line.end.y - line.start.y
        val lengthSquared = dx * dx + dy * dy
        val t =
            if (lengthSquared == 0f) {
                0f
            } else {
                (((point.x - line.start.x) * dx + (point.y - line.start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            }
        val x = line.start.x + t * dx - point.x
        val y = line.start.y + t * dy - point.y
        return sqrt(x * x + y * y)
    }

    private fun extent(path: List<Coord3D>): Float {
        val start = path.first()
        return path.maxOf { sqrt((it.x - start.x) * (it.x - start.x) + (it.y - start.y) * (it.y - start.y)) }
    }

    private fun thin(points: List<Coord3D>, most: Int): List<Coord3D> {
        if (points.size <= most) return points
        val step = points.size.toFloat() / most
        return List(most) { points[(it * step).toInt()] }
    }

    private fun normalise(degrees: Float): Float = ((degrees % 360) + 360) % 360

    private fun Coord3D.withZ(z: Float): Coord3D = Coord3D(x, y, z)
}
