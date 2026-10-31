package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Line
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.simplify
import kotlin.math.sqrt

/**
 * A survey as a place to put scanned points: where its stations are, and how a point in the cave
 * lands on each of the two drawings a surveyor can draw on.
 *
 * The plan is easy, because it is a projection: drop the height. The extended elevation is not,
 * because it is not a projection of the cave but of the cave unrolled, each leg turned to run left
 * or right across the page. So a point is unrolled with the leg it belongs to: found its nearest
 * leg, the point is put the same fraction of the way along that leg's unrolled line, at its own
 * height above or below the leg. How far it was to the side of the leg is lost, as it is for
 * anything drawn on an extended elevation.
 *
 * Built once per survey and asked many times: both transforms walk the whole survey.
 */
class SurveyFrame(survey: Survey) {

    private val real = Projection2D.PLAN.transform(survey)
    private val unrolled = Projection2D.EXTENDED_ELEVATION.transform(survey)

    private val legs: List<Pair<Line<Coord3D>, Line<Coord3D>>> =
        real.legMap.mapNotNull { (leg: Leg, line) ->
            if (!leg.hasDestination()) return@mapNotNull null
            val unrolledLine = unrolled.legMap[leg] ?: return@mapNotNull null
            line to unrolledLine
        }

    /** Every connected leg as a line through the cave, without its unrolled twin. */
    val legLines: List<Line<Coord3D>> get() = legs.map { it.first }

    /** Every station's position, east, north and up from the origin, by name. */
    val stationPositions: Map<String, Coord3D> =
        real.stationMap.entries.associate { (station, coord) -> station.name to coord }

    fun toPlan(point: Coord3D): Coord2D = Projection2D.PLAN.project(point)

    /** Where [point] falls on the extended elevation, or null for a survey with no legs. */
    fun toExtendedElevation(point: Coord3D): Coord2D? {
        val (line, unrolledLine) = nearestLeg(point) ?: return null
        val t = fractionAlong(point, line)
        val start = Projection2D.EXTENDED_ELEVATION.project(unrolledLine.start)
        val end = Projection2D.EXTENDED_ELEVATION.project(unrolledLine.end)
        val legHeight = line.start.z + t * (line.end.z - line.start.z)
        // Unclamped on purpose: a point just past the end of the last leg is drawn just past it,
        // not piled up on the end station.
        val x = start.x + t * (end.x - start.x)
        val y = start.y + t * (end.y - start.y)
        // Sketch space has y downwards, so higher is smaller.
        return Coord2D(x, y - (point.z - legHeight))
    }

    /**
     * A line through the cave as sketch strokes on [projection], broken wherever consecutive
     * points land further apart than [breakMetres].
     *
     * Breaks matter on the extended elevation: where a line passes a corner, consecutive points
     * can belong to legs unrolled to opposite sides of the page, and joining them draws a line
     * across the drawing that is in no cave. On the plan a break is a gap in the data.
     */
    fun toStrokes(
        points: List<Coord3D>,
        projection: Projection2D,
        simplifyMetres: Float = DEFAULT_SIMPLIFY_METRES,
        breakMetres: Float = DEFAULT_BREAK_METRES,
    ): List<List<Coord2D>> {
        require(projection == Projection2D.PLAN || projection == Projection2D.EXTENDED_ELEVATION) {
            "scans are drawn on the two sketches a survey keeps"
        }
        val strokes = mutableListOf<List<Coord2D>>()
        var current = mutableListOf<Coord2D>()
        for (point in points) {
            val projected =
                if (projection == Projection2D.PLAN) toPlan(point) else toExtendedElevation(point)
            if (projected == null) continue
            val previous = current.lastOrNull()
            if (previous != null && (projected - previous).mag() > breakMetres) {
                strokes.add(current)
                current = mutableListOf()
            }
            current.add(projected)
        }
        strokes.add(current)
        return strokes.filter { it.size >= 2 }.map { simplify(it, simplifyMetres) }
    }

    private fun nearestLeg(point: Coord3D): Pair<Line<Coord3D>, Line<Coord3D>>? =
        legs.minByOrNull { (line, _) -> distanceToSegment(point, line) }

    companion object {
        /** A centimetre: below anything a caver draws, and it still thins a pen's hits tenfold. */
        const val DEFAULT_SIMPLIFY_METRES = 0.01f

        /** Two metres between consecutive points is a gap, not a wall. */
        const val DEFAULT_BREAK_METRES = 2f

        /** How far along [line] the closest point to [point] is, 0 at its start and 1 at its end. */
        internal fun fractionAlong(point: Coord3D, line: Line<Coord3D>): Float {
            val dx = line.end.x - line.start.x
            val dy = line.end.y - line.start.y
            val dz = line.end.z - line.start.z
            val lengthSquared = dx * dx + dy * dy + dz * dz
            if (lengthSquared == 0f) return 0f
            return ((point.x - line.start.x) * dx + (point.y - line.start.y) * dy + (point.z - line.start.z) * dz) /
                lengthSquared
        }

        internal fun distanceToSegment(point: Coord3D, line: Line<Coord3D>): Float {
            val t = fractionAlong(point, line).coerceIn(0f, 1f)
            val x = line.start.x + t * (line.end.x - line.start.x) - point.x
            val y = line.start.y + t * (line.end.y - line.start.y) - point.y
            val z = line.start.z + t * (line.end.z - line.start.z) - point.z
            return sqrt(x * x + y * y + z * z)
        }
    }
}
