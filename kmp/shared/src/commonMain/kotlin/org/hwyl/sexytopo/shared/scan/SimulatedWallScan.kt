package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A wall scan made up rather than measured: an elliptical passage round a survey's own legs, seen
 * from a phone that walked down the middle of it, in a frame turned and shifted from the survey's
 * by a known amount.
 *
 * Two uses. Tests: a scan whose true placement is known is the only way to check that anchoring
 * and fitting put it back where it came from. And a phone or a computer without a lidar: the
 * simulator, the desktop build and the browser can run the whole of review — placing, smoothing,
 * re-placing, deleting — on something shaped like a real scan, which is otherwise only possible
 * underground with an iPhone Pro.
 *
 * Everything is seeded, so the same survey and the same settings give the same scan every time.
 */
object SimulatedWallScan {

    /** What to make, with defaults that look like a stooping Mendip passage. */
    class Settings(
        val widthMetres: Float = 2f,
        val heightMetres: Float = 1.6f,
        /** How far down the survey to walk from the start station. */
        val lengthMetres: Float = 40f,
        /** Mark the first and last stations passed, as a surveyor in Anchored mode would. */
        val markStations: Boolean = true,
        /** How wrong ARKit's north is: the heading the scan must be turned by to fit. */
        val headingErrorDegrees: Float = 12f,
        /** Where in the scan's frame the survey's origin sits, besides the turn. */
        val offset: Coord3D = Coord3D(3f, -2f, 0.4f),
        /** Lidar noise, in metres, on every point. */
        val noiseMetres: Float = 0.01f,
        val seed: Int = 1,
        /** How high the phone was carried above the legs. */
        val phoneHeightMetres: Float = 0.3f,
        /**
         * How far the middle of the passage is above the legs: nought for stations in the middle
         * of the passage, about half its height for stations on the floor, as most are.
         */
        val passageAboveLegsMetres: Float = 0f,
    )

    /** The scan, and the placement that puts it back exactly where it was made up. */
    class Simulated(val scan: WallScan, val truth: ScanPlacement)

    private const val RING_STEP_METRES = 0.05f
    private const val RING_POINTS = 48
    private const val PATH_STEP_METRES = 0.5f
    private const val PEN_STEP_METRES = 0.1f
    private const val PEN_FLIER_METRES = 0.6f
    private const val PEN_FLIER_EVERY = 23
    private const val MARK_NOISE_METRES = 0.02f

    /**
     * A scan down the survey from [start], or null when there is no leg to walk from it.
     *
     * The walk follows the first leg on from each station, as far as [Settings.lengthMetres] or
     * the end of the passage, whichever comes first.
     */
    fun around(survey: Survey, start: Station, settings: Settings = Settings()): Simulated? {
        val positions = SurveyFrame(survey).stationPositions
        val chain = walk(start, settings.lengthMetres, positions)
        if (chain.size < 2) return null
        val centreline = chain.mapNotNull { positions[it.name] }
        if (centreline.size < 2) return null

        val random = Random(settings.seed)
        fun noise(scale: Float) = (random.nextFloat() * 2 - 1) * scale

        // The scan's frame is the survey's turned by the heading error and shifted: so the
        // placement that undoes it is that turn and the matching shift, and a scan point is that
        // placement's unplace of a survey point.
        val turn = ScanPlacement(settings.headingErrorDegrees, Coord3D.ORIGIN)
        val turnedOffset = turn.place(settings.offset)
        val truth =
            ScanPlacement(
                settings.headingErrorDegrees,
                Coord3D(-turnedOffset.x, -turnedOffset.y, -turnedOffset.z),
            )
        fun inScan(survey: Coord3D) = truth.unplace(survey)

        val points = mutableListOf<Coord3D>()
        val path = mutableListOf<Coord3D>()
        val pen = PenStroke()
        val halfWidth = settings.widthMetres / 2
        val halfHeight = settings.heightMetres / 2
        var sincePen = 0

        for (i in 0 until centreline.size - 1) {
            val from = centreline[i]
            val to = centreline[i + 1]
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val horizontal = sqrt(dx * dx + dy * dy)
            val length = sqrt(horizontal * horizontal + dz * dz)
            if (length <= 0f) continue
            // Right of the direction of travel, level; a pitch has no sides, so keep the last one.
            val (rightX, rightY) = if (horizontal > 0.05f) (dy / horizontal) to (-dx / horizontal) else 1f to 0f

            var along = 0f
            while (along < length) {
                val t = along / length
                val centre = Coord3D(from.x + t * dx, from.y + t * dy, from.z + t * dz + settings.passageAboveLegsMetres)
                for (k in 0 until RING_POINTS) {
                    val angle = 2 * PI * k / RING_POINTS
                    val across = (cos(angle) * halfWidth).toFloat() + noise(settings.noiseMetres)
                    val up = (sin(angle) * halfHeight).toFloat() + noise(settings.noiseMetres)
                    val rock = Coord3D(centre.x + rightX * across, centre.y + rightY * across, centre.z + up)
                    // At a bend, this leg's ring runs on into the next stretch of passage. There is
                    // no rock there in a real cave, and a made-up wall across the passage is
                    // exactly what fitting is meant to notice, so it is left out.
                    if (insideOtherLeg(rock, i, centreline, halfWidth, halfHeight, settings.passageAboveLegsMetres)) continue
                    points.add(inScan(rock))
                }
                along += RING_STEP_METRES
            }

            along = 0f
            while (along < length) {
                val t = along / length
                path.add(inScan(Coord3D(from.x + t * dx, from.y + t * dy, from.z + t * dz + settings.phoneHeightMetres)))
                along += PATH_STEP_METRES
            }

            // The pen runs along the foot of the left wall, as a surveyor drawing the wall would,
            // with now and then a hit that went past the wall into a gap.
            along = 0f
            while (along < length) {
                val t = along / length
                sincePen++
                val reach = halfWidth * 0.85f + if (sincePen % PEN_FLIER_EVERY == 0) PEN_FLIER_METRES else 0f
                val foot =
                    Coord3D(
                        from.x + t * dx - rightX * reach + noise(settings.noiseMetres),
                        from.y + t * dy - rightY * reach + noise(settings.noiseMetres),
                        from.z + t * dz + settings.passageAboveLegsMetres - halfHeight * 0.5f,
                    )
                pen.add(inScan(foot))
                along += PEN_STEP_METRES
            }
        }
        path.add(inScan(centreline.last().let { Coord3D(it.x, it.y, it.z + settings.phoneHeightMetres) }))

        val touches =
            if (settings.markStations) {
                listOf(chain.first(), chain[chain.size / 2], chain.last()).distinct().mapNotNull { station ->
                    positions[station.name]?.let { at ->
                        StationTouch(
                            station.name,
                            inScan(Coord3D(at.x + noise(MARK_NOISE_METRES), at.y + noise(MARK_NOISE_METRES), at.z)),
                        )
                    }
                }
            } else {
                emptyList()
            }

        // A stalactite in the roof halfway along, and the stream flowing on down the passage.
        val middle = centreline[centreline.size / 2]
        val next = centreline[minOf(centreline.size - 1, centreline.size / 2 + 1)]
        val bearingInSurvey = (atan2(next.x - middle.x, next.y - middle.y) * 180 / PI.toFloat() + 360) % 360
        val stamps =
            listOf(
                SymbolStamp(
                    Symbol.STALACTITE,
                    inScan(Coord3D(middle.x, middle.y, middle.z + settings.passageAboveLegsMetres + halfHeight)),
                ),
                SymbolStamp(
                    Symbol.WATER_FLOW,
                    inScan(Coord3D(middle.x, middle.y, middle.z + settings.passageAboveLegsMetres - halfHeight)),
                    (bearingInSurvey - settings.headingErrorDegrees + 360) % 360,
                ),
            )

        val scan =
            WallScan(
                points = points,
                path = path,
                touches = touches,
                penLines = if (pen.rawHits.size >= 2) listOf(PenLine(pen, Colour.BLACK)) else emptyList(),
                stamps = stamps,
                startStation = start.name,
            )
        return Simulated(scan, truth)
    }

    /** Whether [point] is inside the passage round any leg of [centreline] other than [own]. */
    private fun insideOtherLeg(
        point: Coord3D,
        own: Int,
        centreline: List<Coord3D>,
        halfWidth: Float,
        halfHeight: Float,
        above: Float,
    ): Boolean {
        for (j in 0 until centreline.size - 1) {
            if (j == own) continue
            val a = centreline[j]
            val b = centreline[j + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0f) 0f else (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            val across = sqrt((a.x + t * dx - point.x).let { it * it } + (a.y + t * dy - point.y).let { it * it })
            val up = point.z - (a.z + t * (b.z - a.z) + above)
            val inside = (across / halfWidth).let { it * it } + (up / halfHeight).let { it * it }
            if (inside < INSIDE_ANOTHER) return true
        }
        return false
    }

    /** A little inside the other passage's wall, so the wall itself is kept where two meet. */
    private const val INSIDE_ANOTHER = 0.9f

    /** The stations down the first leg from each, until the length is reached. */
    private fun walk(start: Station, lengthMetres: Float, positions: Map<String, Coord3D>): List<Station> {
        val chain = mutableListOf(start)
        var walked = 0f
        var here = start
        while (walked < lengthMetres) {
            val leg = here.onwardLegs.firstOrNull { it.hasDestination() } ?: break
            val next = leg.destination
            val a = positions[here.name] ?: break
            val b = positions[next.name] ?: break
            walked += StationAnchoring.distance(a, b)
            chain.add(next)
            here = next
        }
        return chain
    }
}
