package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turning a cloud of scanned points into the outline of a passage cross-section.
 *
 * A depth scanner — an iPhone's lidar, through ARKit — hands back tens of thousands of points on
 * the rock around the surveyor. A cross-section is one slice through that: the shape of the
 * passage in the plane at right angles to the way it runs. This is the arithmetic between the two,
 * and it is here rather than in `iosMain` because it is arithmetic: it can be checked on a build
 * server against passages whose true shape is known, which is the only way anybody is going to
 * find out whether it is right before taking a phone underground.
 *
 * ## What comes out
 *
 * Open strokes, not a closed loop, and that is the design rather than a limitation. A surveyor
 * sweeping a phone round a rift sees the two walls and the floor and misses the roof, or stands
 * too close to one wall to see behind themselves. Every direction they did not scan is a direction
 * this knows nothing about, and the honest answer is a gap in the drawing rather than a straight
 * line drawn across it. That also happens to be exactly what the sketch model already holds:
 * `PathDetail` is an open stroke, a wall is normally drawn as several of them, and the eraser
 * splits one into two. A scan that saw the whole way round returns a single stroke that closes on
 * itself.
 *
 * ## Why radial bins
 *
 * A passage seen from a station standing in it is star-shaped about that station nearly always:
 * one wall in each direction. So the reduction that fits the shape is to divide the circle into
 * equal sectors and ask each one how far away the rock is. It survives the things a real scan does
 * — wildly uneven point density, the near wall carrying ten times the points of the far one — in
 * a way that hull-finding or nearest-neighbour chaining does not.
 *
 * It is wrong for a passage that doubles back on itself, a pillar in the middle of a chamber, or a
 * bedding plane seen from inside a crack in its floor. Those want drawing by hand, which is why
 * this adds a stroke to the section's own sketch rather than replacing anything: what it draws can
 * be rubbed out and drawn over like any other stroke.
 */
/**
 * The patches of surface a scan has already seen, so the same rock is not counted twice.
 *
 * A depth scanner does not hand back what is new since the last read: ARKit's `rawFeaturePoints`
 * is the *whole* cloud it is currently holding, re-reported every time it is asked. Keeping all of
 * that on every read counts reads rather than rock, and it does more harm than waste — see
 * [PassageScan] on why duplication disables the noise floor rather than reinforcing it.
 *
 * So space is divided into small boxes and each box counts once. Quantising rather than trusting
 * the scanner's own per-point identifiers is deliberate: ARKit refines a feature's position as it
 * sees more of it, so one identifier arrives at slightly different places over a sweep and
 * identifier-dedup would keep the first and least-refined estimate of each. It also thins a near
 * wall that is tracked far more densely than the far one, which is the difference between a
 * percentile that describes the passage and one that describes whatever the phone was nearest.
 *
 * Here rather than in `PassageScanner.ios.kt` because it is arithmetic, and because the bit-packing
 * below is the kind of thing that is wrong silently: a scan would simply come out sparse. On this
 * side of the line it is tested, and the Android scanner that does not exist yet will want it too.
 */
class SeenSurfaces(private val voxelMetres: Float = DEFAULT_VOXEL_METRES) {

    init {
        require(voxelMetres > 0) { "a box of no size holds nothing" }
    }

    private val seen = mutableSetOf<Long>()

    /** How many distinct patches have been seen, which is what a scan should show a surveyor. */
    val size: Int get() = seen.size

    /**
     * Whether this point is somewhere nothing has been seen before, remembering it either way.
     *
     * Takes three floats rather than a [Coord3D] so that a caller walking a raw buffer — which is
     * what the iOS scanner does, several thousand times a second — need not allocate an object per
     * point just to ask.
     */
    fun isNew(east: Float, north: Float, up: Float): Boolean = seen.add(key(east, north, up))

    private fun key(east: Float, north: Float, up: Float): Long {
        // `floor` and not a cast. A cast truncates towards zero, which folds the box just below
        // the origin onto the box just above it on every axis — so the eight boxes meeting at the
        // surveyor's feet would be one, and points either side of them would wrongly collapse.
        val x = floor(east / voxelMetres).toLong() and AXIS_MASK
        val y = floor(north / voxelMetres).toLong() and AXIS_MASK
        val z = floor(up / voxelMetres).toLong() and AXIS_MASK
        return (x shl (2 * AXIS_BITS)) or (y shl AXIS_BITS) or z
    }

    companion object {
        /**
         * Two centimetres: finer than the rock is rough, so two returns off genuinely different
         * bits of wall stay two, and coarse enough that the hundreds of re-reports of one feature
         * collapse to one.
         */
        const val DEFAULT_VOXEL_METRES = 0.02f

        /**
         * Twenty-one bits an axis, which at two centimetres reaches twenty kilometres each way
         * from where the surveyor stood — rather more cave than anybody will sweep a phone round.
         * Three of them fit in a Long with a bit to spare.
         */
        private const val AXIS_BITS = 21

        private const val AXIS_MASK = 0x1FFFFFL
    }
}

object PassageScan {

    /**
     * How thick a slice through the passage counts as the cross-section, either side of the plane.
     *
     * A quarter of a metre each way. Thinner than that and a sweep of the phone catches too few
     * points to make a wall out of; thicker and a passage that is turning has two different shapes
     * averaged into one. `CrossSectioner` in the Android app takes splays within a comparable
     * distance of the station for the same reason.
     */
    const val DEFAULT_SLAB_IN_METRES = 0.25f

    /**
     * How many directions the circle is divided into.
     *
     * Sixty gives a wall point every six degrees, which at three metres is one every 31cm — finer
     * than a caver draws by hand and coarse enough that a sweep of a phone fills most of them.
     */
    const val DEFAULT_SECTORS = 60

    /**
     * How many points a sector needs before it is believed.
     *
     * One point is a scanner artefact as often as it is rock — lidar returns stray depths off wet
     * flowstone, and off nothing at all in the dark. Three in the same six-degree sector at a
     * similar distance is a surface.
     */
    const val DEFAULT_MINIMUM_POINTS_PER_SECTOR = 3

    /**
     * Which of a sector's points is taken to be the wall, as a fraction from nearest to farthest.
     *
     * Not the farthest, which is the single worst choice: a scanner that saw through a gap in the
     * rock, or through a puddle, puts one point far beyond the wall and the outline follows it.
     * Not the middle either, which would draw the passage inside its own walls. Eight points in
     * ten is past the mist and short of the fliers.
     */
    const val DEFAULT_WALL_PERCENTILE = 0.8f

    /**
     * How many empty sectors in a row end a stroke rather than being drawn across.
     *
     * One or two empty sectors in the middle of a scanned wall are the scanner blinking, and
     * joining across them is right. Six in a row — thirty-six degrees — is a part of the passage
     * nobody pointed the phone at, and drawing a wall there would be inventing cave.
     */
    const val DEFAULT_GAP_TOLERANCE = 2

    /**
     * A point on the rock, as the scanner gives it: metres from the station, in survey axes.
     *
     * The caller converts. Every scanner has its own idea of which way is up — ARKit's world
     * space is y-up and z-towards-the-viewer, which is not this — and doing that conversion in the
     * platform half keeps one set of axes in the shared code. See `PassageScanner.ios.kt`.
     *
     * ## [points] must be distinct observations, not a cumulative cloud
     *
     * A hard precondition rather than a preference, and a phone found it being broken. Both
     * defences here count observations: a direction needs [minimumPointsPerSector] before it is
     * believed, and a sector's wall is its [wallPercentile]. Hand the same return in a hundred and
     * fifty times and it clears the minimum on its own and is every value the percentile sorts, so
     * a single stray becomes a confident wall and the section comes out as a star of spikes.
     * Duplication does not reinforce the noise floor, it removes it.
     *
     * Depth scanners invite exactly that mistake, because they hand back everything they hold on
     * every read rather than what is new — so a caller reading on a timer must sieve. [SeenSurfaces]
     * is what does it, and `theSameReturnSentAgainIsNotASecondObservation` pins the property.
     */
    fun outlines(
        points: List<Coord3D>,
        bearing: Float,
        slabInMetres: Float = DEFAULT_SLAB_IN_METRES,
        sectors: Int = DEFAULT_SECTORS,
        minimumPointsPerSector: Int = DEFAULT_MINIMUM_POINTS_PER_SECTOR,
        wallPercentile: Float = DEFAULT_WALL_PERCENTILE,
        gapTolerance: Int = DEFAULT_GAP_TOLERANCE,
    ): List<List<Coord2D>> {
        require(sectors >= 3) { "a circle cut into fewer than three sectors is not an outline" }
        require(slabInMetres > 0) { "a slice of no thickness catches no points" }

        val wall = wallDistances(
            points,
            bearing,
            slabInMetres,
            sectors,
            minimumPointsPerSector,
            wallPercentile,
        )
        return strokesFrom(wall, sectors, gapTolerance)
    }

    /**
     * The distance to the wall in each sector, or null where nothing was scanned.
     *
     * Split out from [outlines] because it is the half worth looking at on its own: it is what a
     * live preview would draw while the surveyor is still sweeping the phone about, and it is the
     * half a test can state an expected answer for without also reasoning about where the strokes
     * break.
     */
    fun wallDistances(
        points: List<Coord3D>,
        bearing: Float,
        slabInMetres: Float = DEFAULT_SLAB_IN_METRES,
        sectors: Int = DEFAULT_SECTORS,
        minimumPointsPerSector: Int = DEFAULT_MINIMUM_POINTS_PER_SECTOR,
        wallPercentile: Float = DEFAULT_WALL_PERCENTILE,
    ): List<Float?> {
        val radians = bearing.toDouble() * PI / 180
        val cosine = cos(radians)
        val sine = sin(radians)

        val inSector = List(sectors) { mutableListOf<Float>() }

        for (point in points) {
            // The frame `CrossSection.getProjection` puts its splays in, reached the same way it
            // reaches it: turn the world backwards by the section's bearing so that along the
            // passage becomes north, then keep across and down. `Projection2D.CROSS_SECTION`
            // projects (x, -z), which is why height is negated — sketch space has y downwards.
            val across = point.x * cosine - point.y * sine
            val along = point.x * sine + point.y * cosine
            if (along < -slabInMetres || along > slabInMetres) continue

            val down = -point.z.toDouble()
            val distance = sqrt(across * across + down * down)
            // A point at the station itself has no direction to be in, and dividing by its
            // distance below would be a division by zero.
            if (distance <= 0.0) continue

            inSector[sectorOf(atan2(down, across), sectors)].add(distance.toFloat())
        }

        return inSector.map { distances ->
            if (distances.size < minimumPointsPerSector) {
                null
            } else {
                percentile(distances.sorted(), wallPercentile)
            }
        }
    }

    /**
     * Which sector an angle falls in, counting anticlockwise from due right of the station.
     *
     * `atan2` returns from minus pi to pi and the sectors run from zero, so the negative half is
     * carried round first. The modulo is not decoration: an angle of exactly pi lands one past the
     * last sector without it, and the scan then falls over on whichever point happens to be dead
     * ahead.
     */
    private fun sectorOf(angle: Double, sectors: Int): Int {
        val turns = (angle + 2 * PI) % (2 * PI) / (2 * PI)
        return (floor(turns * sectors).toInt()) % sectors
    }

    /**
     * The value at [fraction] of the way through a sorted list, without interpolating.
     *
     * Nearest-rank rather than a weighted average between two neighbours, because these are
     * measurements of a rock surface rather than samples of a distribution: the answer should be a
     * distance something was actually seen at.
     */
    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        val index = floor(fraction * (sorted.size - 1)).toInt()
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }

    /**
     * The strokes through the sectors that saw rock, breaking where too many in a row did not.
     *
     * The wrap-around is the fiddly part and the part worth having: a scan of a whole passage has
     * its first and last sectors adjacent on the drawing, and treating the list as a line rather
     * than a ring would put a break at due right of the station on every complete scan — a nick in
     * the wall exactly where nothing happened.
     */
    private fun strokesFrom(
        wall: List<Float?>,
        sectors: Int,
        gapTolerance: Int,
    ): List<List<Coord2D>> {
        if (wall.all { it == null }) return emptyList()

        // A complete scan is one stroke that comes back to where it started, and has no beginning
        // worth speaking of. Anything else is cut at its gaps, starting from the first sector after
        // a gap so that a stroke is never broken in two halfway along.
        if (wall.all { it != null }) {
            val ring = wall.indices.map { pointAt(it, wall[it]!!, sectors) }
            return listOf(ring + ring.first())
        }

        val start = wall.indices.first { wall[it] == null }
        val strokes = mutableListOf<List<Coord2D>>()
        var current = mutableListOf<Coord2D>()
        var missing = 0

        for (step in 1..sectors) {
            val index = (start + step) % sectors
            val distance = wall[index]
            if (distance == null) {
                missing++
                // Held open across a blink, closed after a hole. The stroke is only ended once the
                // gap is proved to be a real one, so that the points either side of a single empty
                // sector still join up.
                if (missing > gapTolerance && current.isNotEmpty()) {
                    strokes.add(current)
                    current = mutableListOf()
                }
            } else {
                missing = 0
                current.add(pointAt(index, distance, sectors))
            }
        }
        if (current.isNotEmpty()) strokes.add(current)

        // A stroke of one point is a sector that scraped past the minimum on its own with holes
        // both sides. It draws as nothing and it says nothing; two points are the fewest that are
        // a piece of wall.
        return strokes.filter { it.size >= 2 }
    }

    /** The middle of a sector, at the distance the wall was found, in the section's own frame. */
    private fun pointAt(sector: Int, distance: Float, sectors: Int): Coord2D {
        val angle = (sector + 0.5) / sectors * 2 * PI
        return Coord2D((distance * cos(angle)).toFloat(), (distance * sin(angle)).toFloat())
    }
}
