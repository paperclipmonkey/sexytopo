package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.PassageScan
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.Lrud
import org.hwyl.sexytopo.shared.survey.LrudMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far the walls are from a point in the passage, in each of the four directions an LRUD
 * records. Null where the scan saw too little in that direction to say.
 */
class WallDistances(val left: Float?, val right: Float?, val up: Float?, val down: Float?)

/**
 * Measuring a placed scan: the four LRUD distances at a station, and walls along each leg.
 *
 * Both are the same question asked at different places. A thin slice is taken through the scan,
 * square to the passage; in that slice, the rock in each of four wedges (left, right, up, down,
 * sixty degrees each) is collected, and the wall is taken at the eighth decile of its level or
 * plumb distances.
 * That is the reasoning `PassageScan` uses for cross-sections: not the farthest point, which is
 * whichever return went through a gap or a puddle, and not the middle, which draws the passage
 * inside its own walls.
 *
 * Points must already be placed, in survey coordinates: east, north, up from the survey's origin.
 */
object ScanProfiles {

    /** Half the thickness of the slice, either side of the plane. */
    const val SLAB_METRES = 0.25f

    /** Half the width of each wedge. */
    const val WEDGE_HALF_DEGREES = 30f

    const val WALL_PERCENTILE = 0.8f

    /** Fewer points than this in a wedge say nothing: one return is an artefact as often as rock. */
    const val MINIMUM_POINTS = 3

    /** A phone's LiDAR sees about five metres; anything much beyond that in a slice is noise. */
    const val MAXIMUM_RANGE_METRES = 8f

    /** How far apart generated wall points are along a leg. */
    const val WALL_STEP_METRES = 0.5f

    /**
     * The wall distances around [centre], looking along [bearingDegrees].
     *
     * [points] is searched through a [PointGrid] so that asking at every half metre of a long
     * survey does not walk every point of the scan each time.
     */
    fun distancesAt(points: PointGrid, centre: Coord3D, bearingDegrees: Float): WallDistances {
        val radians = bearingDegrees.toDouble() * PI / 180
        val sine = sin(radians).toFloat()
        val cosine = cos(radians).toFloat()

        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()
        val up = mutableListOf<Float>()
        val down = mutableListOf<Float>()

        points.forEachNear(centre.x, centre.y, MAXIMUM_RANGE_METRES) { point ->
            val dx = point.x - centre.x
            val dy = point.y - centre.y
            val along = dx * sine + dy * cosine
            if (abs(along) > SLAB_METRES) return@forEachNear
            val across = dx * cosine - dy * sine
            val height = point.z - centre.z
            val distance = sqrt(across * across + height * height)
            if (distance <= 0f || distance > MAXIMUM_RANGE_METRES) return@forEachNear

            // The wedge decides which wall a point is on; what is recorded is the distance an LRUD
            // means, level for left and right and plumb for up and down. Measured along the slant
            // instead, a flat wall would come out wider than it is by up to the wedge's secant.
            val angle = atan2(height, across) * 180 / PI.toFloat()
            when {
                abs(angle) <= WEDGE_HALF_DEGREES -> right.add(across)
                abs(angle - 90) <= WEDGE_HALF_DEGREES -> up.add(height)
                abs(angle + 90) <= WEDGE_HALF_DEGREES -> down.add(-height)
                abs(angle) >= 180 - WEDGE_HALF_DEGREES -> left.add(-across)
            }
        }
        return WallDistances(wall(left), wall(right), wall(up), wall(down))
    }

    /** The LRUD distances at [station], square to the passage as the cross-section sees it. */
    fun lrudAt(survey: Survey, frame: SurveyFrame, points: PointGrid, station: Station): WallDistances? {
        val centre = frame.stationPositions[station.name] ?: return null
        return distancesAt(points, centre, CrossSectioner.angleOfSection(survey, station))
    }

    /**
     * The distances as LRUD splays, ready to add to the station. A direction with no distance
     * gives no splay rather than a guessed one.
     */
    fun lrudSplays(survey: Survey, station: Station, distances: WallDistances): List<Leg> =
        listOfNotNull(
            distances.left?.let { Lrud.LEFT.createSplay(survey, station, LrudMode.SURVEY, it) },
            distances.right?.let { Lrud.RIGHT.createSplay(survey, station, LrudMode.SURVEY, it) },
            distances.up?.let { Lrud.UP.createSplay(survey, station, LrudMode.SURVEY, it) },
            distances.down?.let { Lrud.DOWN.createSplay(survey, station, LrudMode.SURVEY, it) },
        )

    /**
     * The left and right walls along every leg, as lines through the cave at the leg's height.
     *
     * Each leg is sampled every [WALL_STEP_METRES]; at each sample, the left and right wall
     * distances square to the leg give a wall point either side. A sample with no wall that side
     * breaks that side's line, so a stretch nobody scanned is a gap in the drawing rather than a
     * straight wall across it. Legs that are close to vertical have no left or right, and give
     * nothing.
     */
    fun wallsAlongLegs(frame: SurveyFrame, survey: Survey, points: PointGrid): List<List<Coord3D>> {
        val walls = mutableListOf<List<Coord3D>>()
        val legs = mutableListOf<Pair<Coord3D, Coord3D>>()
        for (leg in survey.getAllLegs()) {
            if (!leg.hasDestination()) continue
            val from = survey.getOriginatingStation(leg) ?: continue
            val start = frame.stationPositions[from.name] ?: continue
            val end = frame.stationPositions[leg.destination.name] ?: continue
            legs += start to end
        }
        for ((index, ends) in legs.withIndex()) {
            val (start, end) = ends
            val others = legs.filterIndexed { i, _ -> i != index }
            val dx = end.x - start.x
            val dy = end.y - start.y
            val horizontal = sqrt(dx * dx + dy * dy)
            if (horizontal < MINIMUM_HORIZONTAL_METRES) continue
            val bearing = (atan2(dx, dy) * 180 / PI.toFloat() + 360) % 360
            // Unit vector to the right of the direction of travel.
            val rightX = dy / horizontal
            val rightY = -dx / horizontal

            val leftLine = mutableListOf<Coord3D>()
            val rightLine = mutableListOf<Coord3D>()
            val samples = maxOf(1, floor(horizontal / WALL_STEP_METRES).toInt())
            for (i in 0..samples) {
                val t = i.toFloat() / samples
                val centre =
                    Coord3D(start.x + t * dx, start.y + t * dy, start.z + t * (end.z - start.z))
                val distances = distancesAt(points, centre, bearing)
                val left = distances.left?.let { offset(centre, rightX, rightY, -it) }
                val right = distances.right?.let { offset(centre, rightX, rightY, it) }
                extend(walls, leftLine, left?.takeUnless { insideAnother(it, distances.left, others) })
                extend(walls, rightLine, right?.takeUnless { insideAnother(it, distances.right, others) })
            }
            if (leftLine.size >= 2) walls.add(leftLine.toList())
            if (rightLine.size >= 2) walls.add(rightLine.toList())
        }
        return joinNearEnds(walls, ::planDistance)
    }

    /**
     * The roof and the floor along every leg, for the extended elevation: what the walls are to
     * the plan. A wall line drawn on the elevation would land on the centreline, since the
     * elevation unrolls the passage and keeps only distance along it and height; the roof and the
     * floor are what that view can show.
     *
     * Sampled as [wallsAlongLegs] samples, with the same gaps where nothing was scanned, and joined
     * over the stations where the lines from two legs nearly meet.
     */
    fun roofAndFloorAlongLegs(frame: SurveyFrame, survey: Survey, points: PointGrid): List<List<Coord3D>> {
        val lines = mutableListOf<List<Coord3D>>()
        for (leg in survey.getAllLegs()) {
            if (!leg.hasDestination()) continue
            val from = survey.getOriginatingStation(leg) ?: continue
            val start = frame.stationPositions[from.name] ?: continue
            val end = frame.stationPositions[leg.destination.name] ?: continue
            val dx = end.x - start.x
            val dy = end.y - start.y
            val horizontal = sqrt(dx * dx + dy * dy)
            if (horizontal < MINIMUM_HORIZONTAL_METRES) continue
            val bearing = (atan2(dx, dy) * 180 / PI.toFloat() + 360) % 360

            val roof = mutableListOf<Coord3D>()
            val floor = mutableListOf<Coord3D>()
            val samples = maxOf(1, floor(horizontal / WALL_STEP_METRES).toInt())
            for (i in 0..samples) {
                val t = i.toFloat() / samples
                val centre =
                    Coord3D(start.x + t * dx, start.y + t * dy, start.z + t * (end.z - start.z))
                val distances = distancesAt(points, centre, bearing)
                extend(lines, roof, distances.up?.let { Coord3D(centre.x, centre.y, centre.z + it) })
                extend(lines, floor, distances.down?.let { Coord3D(centre.x, centre.y, centre.z - it) })
            }
            if (roof.size >= 2) lines.add(roof.toList())
            if (floor.size >= 2) lines.add(floor.toList())
        }
        return joinNearEnds(lines, ::distance3D)
    }

    /**
     * Whether a wall point found square to one leg lies inside the passage along another: nearer
     * that leg's line than this one's wall is to its own. That is the inside of a bend, where the
     * slice square to one leg runs on past the corner into the next stretch of passage, and drawing
     * it would put a wall across the passage.
     */
    private fun insideAnother(point: Coord3D, offset: Float?, others: List<Pair<Coord3D, Coord3D>>): Boolean {
        val own = offset ?: return false
        return others.any { (a, b) -> planDistanceToSegment(point, a, b) < own - CORNER_TOLERANCE_METRES }
    }

    /**
     * Joins wall lines whose ends nearly meet, as the walls of two legs at the outside of a bend
     * do: each stops square to its own leg, and the rock between them was seen by neither slice.
     * A short straight join is what a surveyor would draw there; a long one would be a guess, and
     * is left as a gap.
     */
    private fun joinNearEnds(
        walls: List<List<Coord3D>>,
        gap: (Coord3D, Coord3D) -> Float,
    ): List<List<Coord3D>> {
        val lines = walls.map { it.toMutableList() }.toMutableList()
        var joined = true
        while (joined) {
            joined = false
            loop@ for (i in lines.indices) {
                for (j in lines.indices) {
                    if (i == j) continue
                    if (gap(lines[i].last(), lines[j].first()) <= JOIN_METRES) {
                        lines[i].addAll(lines[j])
                        lines.removeAt(j)
                        joined = true
                        break@loop
                    }
                }
            }
        }
        return lines
    }

    private fun distance3D(a: Coord3D, b: Coord3D): Float = StationAnchoring.distance(a, b)

    private fun planDistance(a: Coord3D, b: Coord3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun planDistanceToSegment(point: Coord3D, a: Coord3D, b: Coord3D): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        val x = a.x + t * dx - point.x
        val y = a.y + t * dy - point.y
        return sqrt(x * x + y * y)
    }

    /** How much nearer another leg a wall point must be before it counts as inside that passage. */
    private const val CORNER_TOLERANCE_METRES = 0.1f

    /** The longest gap between two walls' ends that is joined rather than left open. */
    private const val JOIN_METRES = 1.5f

    /**
     * The passage's outline round [centre], square to [bearingDegrees], as the cross-section
     * editor's own scan draws it: `PassageScan`'s slice and sectors, fed the placed rock near the
     * station rather than a fresh sweep. Strokes are in the section's own drawing space, metres
     * from the station; empty where too little rock was seen.
     */
    fun sectionAt(points: PointGrid, centre: Coord3D, bearingDegrees: Float): List<List<Coord2D>> {
        val near = mutableListOf<Coord3D>()
        points.forEachNear(centre.x, centre.y, MAXIMUM_RANGE_METRES) { point ->
            near.add(Coord3D(point.x - centre.x, point.y - centre.y, point.z - centre.z))
        }
        if (near.isEmpty()) return emptyList()
        return PassageScan.outlines(near, bearingDegrees)
    }

    /** How many splays a fan has unless asked for another number. */
    const val DEFAULT_FAN_SPLAYS = 12

    /**
     * A fan of splays round [centre], square to [bearingDegrees]: [count] directions evenly round
     * the slice, the first straight to the right of the passage and going round over the roof, and
     * for each the wall at the eighth decile of the rock in its wedge. A direction with too little
     * rock in it gives no splay: a fan with a gap in it is a passage nobody scanned that way.
     *
     * Returned as (distance, azimuth, inclination) readings, as a splay records them.
     */
    fun fanAt(
        points: PointGrid,
        centre: Coord3D,
        bearingDegrees: Float,
        count: Int = DEFAULT_FAN_SPLAYS,
    ): List<Triple<Float, Float, Float>> {
        require(count >= 3) { "a fan needs at least three splays to say anything about a passage" }
        val radians = bearingDegrees.toDouble() * PI / 180
        val sine = sin(radians).toFloat()
        val cosine = cos(radians).toFloat()
        val wedge = 180f / count
        val buckets = List(count) { mutableListOf<Float>() }

        points.forEachNear(centre.x, centre.y, MAXIMUM_RANGE_METRES) { point ->
            val dx = point.x - centre.x
            val dy = point.y - centre.y
            val along = dx * sine + dy * cosine
            if (abs(along) > SLAB_METRES) return@forEachNear
            val across = dx * cosine - dy * sine
            val height = point.z - centre.z
            val distance = sqrt(across * across + height * height)
            if (distance <= 0f || distance > MAXIMUM_RANGE_METRES) return@forEachNear
            val angle = (atan2(height, across) * 180 / PI.toFloat() + 360) % 360
            val index = floor((angle + wedge) / (2 * wedge)).toInt() % count
            buckets[index].add(distance)
        }

        val splays = mutableListOf<Triple<Float, Float, Float>>()
        for ((index, bucket) in buckets.withIndex()) {
            val distance = wall(bucket) ?: continue
            val theta = index * 360.0 / count * PI / 180
            // Right of the passage is (cos b, -sin b) in plan, as `across` measures it.
            val horizontal = cos(theta).toFloat()
            val east = horizontal * cosine
            val north = -horizontal * sine
            val up = sin(theta).toFloat()
            val azimuth = (atan2(east, north) * 180 / PI.toFloat() + 360) % 360
            val inclination = atan2(up, sqrt(east * east + north * north)) * 180 / PI.toFloat()
            splays.add(Triple(distance, if (abs(horizontal) < 1e-4f) bearingDegrees else azimuth, inclination))
        }
        return splays
    }

    /** Below this horizontal length a leg is a pitch, with no sides to speak of. */
    private const val MINIMUM_HORIZONTAL_METRES = 0.3f

    private fun offset(centre: Coord3D, rightX: Float, rightY: Float, metres: Float): Coord3D =
        Coord3D(centre.x + rightX * metres, centre.y + rightY * metres, centre.z)

    /** Adds [point] to [line], or ends the line at a missing sample. */
    private fun extend(walls: MutableList<List<Coord3D>>, line: MutableList<Coord3D>, point: Coord3D?) {
        if (point != null) {
            line.add(point)
            return
        }
        if (line.size >= 2) walls.add(line.toList())
        line.clear()
    }

    private fun wall(distances: MutableList<Float>): Float? {
        if (distances.size < MINIMUM_POINTS) return null
        distances.sort()
        val index = floor(WALL_PERCENTILE * (distances.size - 1)).toInt()
        return distances[index.coerceIn(0, distances.size - 1)]
    }
}

/**
 * Points bucketed into one-metre columns in plan, so that "everything within a few metres of
 * here" visits a few hundred columns rather than every point in a scan.
 */
class PointGrid(points: Iterable<Coord3D>, private val cellMetres: Float = 1f) {

    private val cells = HashMap<Long, MutableList<Coord3D>>()

    init {
        require(cellMetres > 0) { "a column of no width holds nothing" }
        for (point in points) {
            cells.getOrPut(key(cell(point.x), cell(point.y))) { mutableListOf() }.add(point)
        }
    }

    fun forEachNear(east: Float, north: Float, radius: Float, action: (Coord3D) -> Unit) {
        val reach = cell(radius) + 1
        val cx = cell(east)
        val cy = cell(north)
        for (x in cx - reach..cx + reach) {
            for (y in cy - reach..cy + reach) {
                cells[key(x, y)]?.forEach(action)
            }
        }
    }

    private fun cell(metres: Float): Int = floor(metres / cellMetres).toInt()

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
}
