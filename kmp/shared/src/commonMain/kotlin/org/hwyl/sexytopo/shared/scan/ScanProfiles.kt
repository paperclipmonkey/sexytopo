package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
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
                extend(walls, leftLine, distances.left?.let { offset(centre, rightX, rightY, -it) })
                extend(walls, rightLine, distances.right?.let { offset(centre, rightX, rightY, it) })
            }
            if (leftLine.size >= 2) walls.add(leftLine.toList())
            if (rightLine.size >= 2) walls.add(rightLine.toList())
        }
        return walls
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
