package org.hwyl.sexytopo.shared.demo

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.math.toCartesian
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Line
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.sketch.MIN_CROSS_SECTION_HALF_EXTENT
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.atan2
import kotlin.math.max
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

        // Tracked so the demo can guarantee a cross-section on a side passage, not only the
        // entrance series — see addCrossSections.
        val branchStations = mutableSetOf<Station>()
        repeat(numBranches) {
            val candidates = survey.getAllStations().filter { !survey.isOrigin(it) }
            if (candidates.isEmpty()) return@repeat
            survey.activeStation = candidates[random.nextInt(candidates.size)]
            branchStations += createBranch(survey, 3, random)
        }

        val pitch = addPitch(survey, random)
        pitch?.let { branchStations += it.stations }

        survey.activeStation = survey.origin
        addWallLines(survey, random)
        addCrossSections(survey, random, branchStations)
        addAnnotations(survey, random, pitch)
        survey.trip = exampleTrip()
        return survey
    }

    /**
     * Trip metadata, so the export screen shows the block a real survey carries rather than a bare
     * centreline. The date is fixed rather than read from a clock: the exported text is compared
     * against golden files and rendered into the demo screenshots.
     */
    private fun exampleTrip(): Trip {
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.instrument = "DistoX2"
        trip.copyrightHolder = "Demo Caving Club"
        trip.licence = "CC-BY-SA-4.0"
        trip.comments = "Demo data. Entrance series only; the sump is not surveyed."
        trip.team =
            listOf(
                Trip.TeamEntry("A. Surveyor", listOf(Trip.Role.BOOK)),
                Trip.TeamEntry("B. Caver", listOf(Trip.Role.INSTRUMENTS, Trip.Role.EXPLORATION)),
            )
        return trip
    }

    private fun createBranch(survey: Survey, numStations: Int, random: Random): List<Station> {
        val created = mutableListOf<Station>()
        repeat(numStations) {
            val distance = 5f + random.nextInt(10)
            val azimuth = (40 + random.nextInt(100)).toFloat()
            val inclination = (-20 + random.nextInt(40)).toFloat()
            val newStation =
                SurveyBuilder.updateWithNewStation(survey, Leg(distance, azimuth, inclination))
            addLruds(survey, newStation, azimuth, distance, random)
            created.add(newStation)
        }
        return created
    }

    /**
     * Left/right/up/down splays relative to the passage direction. The Android app derives these
     * through LRUD.createSplay; this is the same idea in miniature. [legDistance] is the shot that
     * just reached [station] - see [addWallDetailSplays] for what it is used for.
     */
    private fun addLruds(
        survey: Survey,
        station: Station,
        passageAzimuth: Float,
        legDistance: Float,
        random: Random,
    ) {
        val left = adjustAngle(passageAzimuth, -90f)
        val right = adjustAngle(passageAzimuth, 90f)
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(3), left, 0f))
        // Between the L and R splays deliberately - see wallPoint, which relies on the leftmost
        // and rightmost horizontal splay in this station's own list being exactly these two.
        addWallDetailSplays(survey, station, passageAzimuth, legDistance, random)
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(3), right, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(2), passageAzimuth, 89f))
        SurveyBuilder.addSplay(survey, station, Leg(1f + random.nextInt(2), passageAzimuth, -89f))
    }

    /**
     * Extra shots along the wall, not just the four cardinal LRUDs — what a surveyor actually
     * does when the next station is far enough away that a bare LRUD box would leave whoever is
     * drawing the passage guessing at everything in between. Scaled by how far the shot that
     * reached this station travelled: a short hop needs nothing extra, a long one gets up to four,
     * angled progressively further from square-on so each reaches further along the wall than the
     * last rather than clustering right by the station the way the cardinal ones do.
     */
    private fun addWallDetailSplays(
        survey: Survey,
        station: Station,
        passageAzimuth: Float,
        legDistance: Float,
        random: Random,
    ) {
        val extraCount = ((legDistance - 5f) / 3f).toInt().coerceIn(0, 4)
        if (extraCount == 0) return
        val side = if (random.nextBoolean()) -1f else 1f
        for (i in 1..extraCount) {
            val bearingOffset = side * (85f - i * 15f)
            val angle = adjustAngle(passageAzimuth, bearingOffset)
            val distance = 1f + random.nextFloat() * (1.5f + i * 0.8f)
            SurveyBuilder.addSplay(survey, station, Leg(distance, angle, 0f))
        }
    }

    /**
     * A pitch down to a short length of further passage, hung off an existing station the way the
     * side branches are - but with its first leg steep enough (see [Projection2D.isLegInPlane])
     * to draw dashed on the plan, which is the only thing a plan drawing has to tell a pitch apart
     * from a crawl.
     */
    private fun addPitch(survey: Survey, random: Random): Pitch? {
        val candidates = survey.getAllStations().filter { !survey.isOrigin(it) }
        if (candidates.isEmpty()) return null
        survey.activeStation = candidates[random.nextInt(candidates.size)]

        val dropMetres = 6 + random.nextInt(5)
        val pitchAzimuth = random.nextInt(360).toFloat()
        val head =
            SurveyBuilder.updateWithNewStation(
                survey,
                Leg(dropMetres.toFloat(), pitchAzimuth, -60f - random.nextInt(16)),
            )
        addLruds(survey, head, pitchAzimuth, dropMetres.toFloat(), random)

        val footDistance = 4 + random.nextInt(6)
        val foot =
            SurveyBuilder.updateWithNewStation(
                survey,
                Leg(footDistance.toFloat(), adjustAngle(pitchAzimuth, 20f), -5f),
            )
        addLruds(survey, foot, pitchAzimuth, footDistance.toFloat(), random)

        return Pitch(listOf(head, foot), head, "$dropMetres m pitch")
    }

    /** [stations] is every station the pitch added, [head] the one straight below the top of it. */
    private class Pitch(val stations: List<Station>, val head: Station, val label: String)

    private fun addCrossSections(survey: Survey, random: Random, branchStations: Set<Station>) {
        val plan = survey.getSketch(Projection2D.PLAN)
        val space = Projection2D.PLAN.project(survey)
        val positions = space.stationMap
        val candidates = survey.getAllStations().filter { !survey.isOrigin(it) }
        if (candidates.isEmpty()) return

        // Drawn larger than life, as the app's cross-section-scale setting does: a passage a
        // couple of metres wide is unreadable at the scale the centreline is drawn at.
        plan.crossSectionScale = 4f

        val count = maxOf(4, candidates.size / 3)
        val chosen = chooseSectionStations(candidates, branchStations, count, random)

        val centroid = centroidOf(positions.values)
        val obstacles = collectObstacles(plan, positions.values, space.legMap.values)

        for (station in chosen) {
            val position = positions[station] ?: continue
            val section = CrossSectioner.section(survey, station)
            val tips = section.getProjection().legMap.values.map { it.end }
            val sectionRadius = max(tips.maxOfOrNull { it.mag() } ?: 0f, MIN_CROSS_SECTION_HALF_EXTENT)

            val offset =
                crossSectionOffset(
                    position,
                    section.angle,
                    sectionRadius,
                    plan.crossSectionScale,
                    obstacles,
                    centroid,
                )
            val sectionCentre = position + offset
            val detail = plan.addCrossSection(section, sectionCentre)
            detail.sketch = outlineAroundLruds(tips, random)

            obstacles.add(Obstacle(sectionCentre, sectionRadius * plan.crossSectionScale))
        }
    }

    /** [count] stations, at least one of which is on a side branch. */
    internal fun chooseSectionStations(
        candidates: List<Station>,
        branchStations: Set<Station>,
        count: Int,
        random: Random,
    ): List<Station> {
        val onABranch = candidates.filter { it in branchStations }.shuffled(random).take(1)
        val rest =
            candidates.filterNot { it in onABranch }
                .shuffled(random)
                .take(maxOf(0, count - onABranch.size))
        return onABranch + rest
    }

    private fun centroidOf(points: Collection<Coord2D>): Coord2D {
        if (points.isEmpty()) return Coord2D.ORIGIN
        var sum = Coord2D.ORIGIN
        for (point in points) sum += point
        return sum.scale(1f / points.size)
    }

    /**
     * Everything a cross-section has to be placed clear of, as a point cloud with a clearance
     * radius each: stations and wall points need none of their own, but a section added by the
     * caller afterwards carries its own footprint so the next one does not overlap it.
     */
    private fun collectObstacles(
        plan: Sketch,
        stationPositions: Collection<Coord2D>,
        legs: Collection<Line<Coord2D>>,
    ): MutableList<Obstacle> {
        val obstacles = mutableListOf<Obstacle>()
        for (position in stationPositions) obstacles.add(Obstacle(position, 0f))
        for (path in plan.pathDetails) {
            for (point in path.path) obstacles.add(Obstacle(point, 0f))
        }
        for (leg in legs) {
            obstacles.add(Obstacle((leg.start + leg.end).scale(0.5f), 0f))
        }
        return obstacles
    }

    private class Obstacle(val point: Coord2D, val radius: Float)

    /**
     * Offset perpendicular to the local passage bearing ([sectionAngle], the same bearing the
     * section is sliced at). Of the two perpendicular sides, the one that actually clears every
     * obstacle wins; if neither does at the first distance, both step further out together until
     * one does.
     */
    private fun crossSectionOffset(
        stationPosition: Coord2D,
        sectionAngle: Float,
        sectionRadius: Float,
        crossSectionScale: Float,
        obstacles: List<Obstacle>,
        centroid: Coord2D,
    ): Coord2D {
        val along = Projection2D.PLAN.project(toCartesian(Coord3D.ORIGIN, Leg(1f, sectionAngle, 0f)))
        val direction = along.normalise()
        val perpendicular = Coord2D(-direction.y, direction.x)
        val footprint = sectionRadius * crossSectionScale
        val step = footprint + CROSS_SECTION_CLEARANCE_METRES
        val sides = listOf(perpendicular, perpendicular.scale(-1f))

        for (multiplier in 1..MAX_CLEARANCE_STEPS) {
            val distance = step * multiplier
            val clear =
                sides.filter { side ->
                    val candidate = stationPosition + side.scale(distance)
                    obstacles.all { obstacle ->
                        getDistance(candidate, obstacle.point) >=
                            footprint + obstacle.radius + CROSS_SECTION_CLEARANCE_METRES
                    }
                }
            if (clear.isNotEmpty()) {
                return furthestFromCentroid(clear, stationPosition, distance, centroid).scale(distance)
            }
        }
        // Nowhere fully clears — a tightly packed corner of the demo cave. Take the least-bad side
        // at the furthest distance tried rather than give up and draw on top of the centreline.
        val distance = step * MAX_CLEARANCE_STEPS
        return furthestFromCentroid(sides, stationPosition, distance, centroid).scale(distance)
    }

    private fun furthestFromCentroid(
        sides: List<Coord2D>,
        stationPosition: Coord2D,
        distance: Float,
        centroid: Coord2D,
    ): Coord2D =
        sides.maxByOrNull { side -> getDistance(stationPosition + side.scale(distance), centroid) }!!

    /** A buffer beyond the strict footprint, so a section reads as clearly apart rather than tangent. */
    private const val CROSS_SECTION_CLEARANCE_METRES = 1.5f

    private const val MAX_CLEARANCE_STEPS = 6

    /**
     * Ordered by angle around the station rather than by which splay is which, because
     * [CrossSection.getProjection] deliberately does not preserve that identity (see its own doc
     * comment) — sorting by angle recovers a walk around the outside without needing it, and works
     * for any number of splays, not just this demo's four.
     */
    private fun outlineAroundLruds(tips: List<Coord2D>, random: Random): Sketch {
        val sketch = Sketch()
        if (tips.size < 3) return sketch

        val ordered = tips.sortedBy { atan2(it.y, it.x) }
        val path = sketch.startNewPath(ordered.first(), Colour.BLACK)
        for (i in ordered.indices) {
            freehandTo(path, ordered[i], ordered[(i + 1) % ordered.size], random)
        }
        return sketch
    }

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
        plan: Sketch,
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

    private fun addAnnotations(survey: Survey, random: Random, pitch: Pitch?) {
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

        pitch?.let { positions[it.head]?.let { at -> plan.addTextDetail(at.add(-2.5f, 2.5f), it.label, 1f, Colour.BLACK) } }

        addFormations(plan, positions, survey, random)

        // A find worth marking on the sketch as much as in the trip comments. There is no UIS
        // symbol for a bone deposit, so - like Entrance and Sump - it is text, not a stamp.
        val boneCandidates = positions.keys.filter { !survey.isOrigin(it) && it !== deepest?.key }
        if (boneCandidates.isNotEmpty()) {
            val station = boneCandidates[random.nextInt(boneCandidates.size)]
            positions[station]?.let { plan.addTextDetail(it.add(1f, 1f), "bones", 1f, Colour.BLACK) }
        }
    }

    /** A few UIS formation symbols, stamped just off stations that have room to spare for one. */
    private fun addFormations(
        plan: Sketch,
        positions: Map<Station, Coord2D>,
        survey: Survey,
        random: Random,
    ) {
        val formations = listOf(Symbol.STALACTITE, Symbol.STALAGMITE, Symbol.COLUMN, Symbol.CURTAIN)
        val candidates = positions.keys.filter { !survey.isOrigin(it) }.shuffled(random)
        for ((symbol, station) in formations.zip(candidates)) {
            val position = positions[station] ?: continue
            val dx = 0.6f - random.nextFloat() * 1.2f
            val dy = 0.6f - random.nextFloat() * 1.2f
            plan.addSymbolDetail(position.add(dx, dy), symbol.therionName, 1f, 0f, Colour.BLACK)
        }
    }
}
