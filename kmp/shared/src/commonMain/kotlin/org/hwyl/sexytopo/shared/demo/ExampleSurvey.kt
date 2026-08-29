package org.hwyl.sexytopo.shared.demo

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.math.toCartesian
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.random.Random

/**
 * Builds a plausible branching cave so the demo has something real to draw.
 *
 * Modelled on the Android app's `testutils/ExampleSurveyCreator`, but seeded so that the same
 * survey is produced every run — the demo screenshot and the iOS app should show the same cave.
 *
 * This also stands in for the parts of `SurveyUpdater` a full port would carry across: creating a
 * station from a leg, naming it, and hanging LRUD splays off it.
 */
object ExampleSurvey {

    private const val LEFT = "L"
    private const val RIGHT = "R"

    fun create(seed: Int = 20260829, numStations: Int = 11, numBranches: Int = 4): Survey {
        val random = Random(seed)
        val survey = Survey("Demo Cave")

        createBranch(survey, numStations, random)

        repeat(numBranches) {
            val candidates = survey.getAllStations().filter { !survey.isOrigin(it) }
            if (candidates.isEmpty()) return@repeat
            survey.activeStation = candidates[random.nextInt(candidates.size)]
            createBranch(survey, 3, random)
        }

        survey.activeStation = survey.origin
        addWallLines(survey, random)
        addAnnotations(survey)
        return survey
    }

    // -----------------------------------------------------------------------------------------
    // Centreline
    // -----------------------------------------------------------------------------------------

    private fun createBranch(survey: Survey, numStations: Int, random: Random) {
        repeat(numStations) {
            val distance = 5f + random.nextInt(10)
            val azimuth = (40 + random.nextInt(100)).toFloat()
            val inclination = (-20 + random.nextInt(40)).toFloat()
            val newStation =
                SurveyBuilder.updateWithNewStation(survey, Leg(distance, azimuth, inclination))
            addLruds(survey, newStation, azimuth, random)
        }
    }

    /**
     * Left/right/up/down splays relative to the passage direction. The Android app derives these
     * through LRUD.createSplay; this is the same idea in miniature.
     */
    private fun addLruds(survey: Survey, station: Station, passageAzimuth: Float, random: Random) {
        val left = adjustAngle(passageAzimuth, -90f)
        val right = adjustAngle(passageAzimuth, 90f)
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(3), left, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(3), right, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(2), passageAzimuth, 89f))
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(2), passageAzimuth, -89f))
    }

    // -----------------------------------------------------------------------------------------
    // Sketch
    // -----------------------------------------------------------------------------------------

    /**
     * Draws left- and right-hand passage walls that follow the survey tree, so the plan looks like
     * a cave survey rather than a stick diagram. Each wall is a freehand polyline offset from the
     * centreline by that station's LRUD splay on that side.
     */
    private fun addWallLines(survey: Survey, random: Random) {
        val plan = survey.getSketch(Projection2D.PLAN)
        val positions = Projection2D.PLAN.project(survey).stationMap

        for (side in listOf(LEFT, RIGHT)) {
            val wallPoints = HashMap<Station, Coord2D>()
            val origin = survey.origin
            wallPoints[origin] = wallPoint(positions[origin] ?: Coord2D.ORIGIN, origin, side, random)
            drawWallChain(plan.let { it }, survey, positions, wallPoints, origin, side, null, random)
        }
    }

    private fun drawWallChain(
        plan: org.hwyl.sexytopo.shared.model.sketch.Sketch,
        survey: Survey,
        positions: Map<Station, Coord2D>,
        wallPoints: MutableMap<Station, Coord2D>,
        from: Station,
        side: String,
        currentPath: PathDetail?,
        random: Random,
    ) {
        val onward = from.getConnectedOnwardLegs()
        val fromWall = wallPoints[from] ?: return

        if (onward.size == 1) {
            val child = onward[0].destination
            val childWall =
                wallPoints.getOrPut(child) {
                    wallPoint(positions[child] ?: Coord2D.ORIGIN, child, side, random)
                }
            val path = currentPath ?: plan.startNewPath(fromWall, Colour.BLACK)
            freehandTo(path, fromWall, childWall, random)
            drawWallChain(plan, survey, positions, wallPoints, child, side, path, random)
        } else {
            for (leg in onward) {
                val child = leg.destination
                val childWall =
                    wallPoints.getOrPut(child) {
                        wallPoint(positions[child] ?: Coord2D.ORIGIN, child, side, random)
                    }
                // At a fork every branch restarts from the shared parent point, so the walls meet.
                val branch = plan.startNewPath(fromWall, Colour.BLACK)
                freehandTo(branch, fromWall, childWall, random)
                drawWallChain(plan, survey, positions, wallPoints, child, side, branch, random)
            }
        }
    }

    /** Offsets a station's plan position by its splay on the given side. */
    private fun wallPoint(
        position: Coord2D,
        station: Station,
        side: String,
        random: Random,
    ): Coord2D {
        val splays = station.getUnconnectedOnwardLegs().filter { it.inclination in -45f..45f }
        val splay =
            when {
                splays.isEmpty() -> return position
                side == LEFT -> splays.first()
                else -> splays.last()
            }
        val jitter = (random.nextFloat() - 0.5f) * 0.4f
        val offset =
            Projection2D.PLAN.project(
                toCartesian(Coord3D.ORIGIN, splay.adjustAzimuth(adjustAngle(splay.azimuth, jitter))),
            )
        return position + offset
    }

    /** Breaks a straight run into jittered sub-segments so it reads as hand-drawn. */
    private fun freehandTo(path: PathDetail, from: Coord2D, to: Coord2D, random: Random) {
        val subSegments = 6 + random.nextInt(7)
        val delta = to - from
        val maxOffset = delta.mag() * 0.03f
        for (i in 1 until subSegments) {
            val t = i.toFloat() / subSegments
            val straight = from + delta.scale(t)
            val dx = (random.nextFloat() * 2 - 1) * maxOffset
            val dy = (random.nextFloat() * 2 - 1) * maxOffset
            path.lineTo(straight.add(dx, dy))
        }
        path.lineTo(to)
    }

    private fun addAnnotations(survey: Survey) {
        val plan = survey.getSketch(Projection2D.PLAN)
        val positions = Projection2D.PLAN.project(survey).stationMap
        positions[survey.origin]?.let { plan.addTextDetail(it.add(1f, -1f), "Entrance", 1.2f, Colour.BLUE) }

        // Deepest station, as a second label with some survey meaning.
        val space = Projection2D.PLAN.transform(survey)
        val deepest = space.stationMap.minByOrNull { it.value.z }
        if (deepest != null && deepest.key !== survey.origin) {
            positions[deepest.key]?.let {
                plan.addTextDetail(it.add(1f, -1f), "Sump", 1.2f, Colour.BLUE)
            }
        }
    }
}
