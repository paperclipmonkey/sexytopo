package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scanning a passage whose shape is already known.
 *
 * The point of testing this on a build server is that the answer can be stated in advance. A
 * synthetic passage is built here — a tube of a given width and height, running in a given
 * direction, sampled as densely as a lidar samples — and the outline is then required to be that
 * passage back again, to a tolerance a caver would accept. Nothing about that needs a phone, which
 * matters, because the phone is the one thing that cannot be had here.
 *
 * What it cannot answer is whether ARKit hands over the points these tests assume: in the frame,
 * in the density and with the noise assumed below. That is in `PassageScanner.ios.kt` and needs a
 * cave.
 */
class PassageScanTest {

    /** Bearings that are not multiples of ninety, so a rotation cannot be right by accident. */
    private val bearings = listOf(0f, 37f, 90f, 214f, 359f)

    /**
     * A passage as a scanner would see it: points on the wall of a horizontal elliptical tube.
     *
     * [halfWidth] is across the passage and [halfHeight] up and down, so a rift is a tall narrow
     * one and a bedding plane a wide flat one. The tube runs along [bearing], and points are laid
     * along it as well as round it, so the slab test in the scan has something to reject.
     *
     * The ellipse is sampled evenly in angle rather than in arc length, exactly as a phone swept
     * at a steady speed would: the near wall gets more points than the far one, which is the
     * uneven density the sector reduction has to survive.
     */
    private fun tube(
        halfWidth: Float,
        halfHeight: Float,
        bearing: Float,
        aroundSteps: Int = 720,
        alongFrom: Float = -3f,
        alongTo: Float = 3f,
        alongSteps: Int = 60,
        onlyBetween: ClosedFloatingPointRange<Float>? = null,
    ): List<Coord3D> {
        val radians = bearing.toDouble() * PI / 180
        val points = mutableListOf<Coord3D>()

        for (i in 0 until aroundSteps) {
            val angle = i.toDouble() / aroundSteps * 2 * PI
            val degrees = (angle * 180 / PI).toFloat()
            if (onlyBetween != null && degrees !in onlyBetween) continue

            // In the section's own frame: across the passage, and down the drawing.
            val across = halfWidth * cos(angle)
            val down = halfHeight * sin(angle)

            for (j in 0..alongSteps) {
                val along = alongFrom + (alongTo - alongFrom) * j / alongSteps

                // Back into survey axes, which is the inverse of what the scan does: turn
                // (across, along) forwards by the bearing, and height is up where down is down.
                val x = across * cos(radians) + along * sin(radians)
                val y = -across * sin(radians) + along * cos(radians)
                points.add(Coord3D(x.toFloat(), y.toFloat(), -down.toFloat()))
            }
        }

        return points
    }

    /** How far the outline sits from the ellipse it was cut from, at its worst. */
    private fun worstErrorAgainstEllipse(
        strokes: List<List<org.hwyl.sexytopo.shared.model.graph.Coord2D>>,
        halfWidth: Float,
        halfHeight: Float,
    ): Float {
        var worst = 0f
        for (stroke in strokes) {
            for (point in stroke) {
                // A point is on the ellipse when (x/a)^2 + (y/b)^2 is one. Scaling the point onto
                // the curve along its own ray turns that into a distance, which is a number a
                // person can judge: "eight centimetres out" rather than "0.03 in ellipse units".
                val normalised =
                    sqrt(
                        (point.x / halfWidth) * (point.x / halfWidth) +
                            (point.y / halfHeight) * (point.y / halfHeight),
                    )
                if (normalised <= 0f) continue
                val radius = sqrt(point.x * point.x + point.y * point.y)
                worst = max(worst, abs(radius - radius / normalised))
            }
        }
        return worst
    }

    /**
     * A round passage scanned all the way round comes back as one closed stroke of the right size.
     *
     * The headline claim, and the one that would be quietly false if the axes were confused: a
     * two-metre tube must come back two metres across, not two metres in some rotated frame or
     * half that because a radius was taken for a diameter.
     */
    @Test
    fun aRoundPassageScannedAllTheWayRoundComesBackRound() {
        for (bearing in bearings) {
            val strokes = PassageScan.outlines(tube(1.5f, 1.5f, bearing), bearing)

            assertEquals(
                1,
                strokes.size,
                "a passage scanned all the way round on a bearing of $bearing should be one wall",
            )
            assertEquals(
                strokes[0].first(),
                strokes[0].last(),
                "a wall scanned all the way round should join up at $bearing",
            )
            val worst = worstErrorAgainstEllipse(strokes, 1.5f, 1.5f)
            assertTrue(
                worst < 0.05f,
                "the scanned wall is ${worst}m off a 1.5m circle at a bearing of $bearing",
            )
        }
    }

    /**
     * A rift comes back tall and narrow, and a bedding plane wide and low.
     *
     * The two shapes are checked against each other rather than only against their own numbers,
     * because the failure worth catching is the axes being swapped — which a single symmetrical
     * passage cannot see, and which would draw every rift in the survey lying on its side.
     */
    @Test
    fun aRiftIsTallAndNarrowAndABeddingPlaneIsWideAndLow() {
        val rift = PassageScan.outlines(tube(0.4f, 2.5f, 20f), 20f).flatten()
        val bedding = PassageScan.outlines(tube(3f, 0.5f, 20f), 20f).flatten()

        fun extent(points: List<org.hwyl.sexytopo.shared.model.graph.Coord2D>): Pair<Float, Float> {
            var width = 0f
            var height = 0f
            for (point in points) {
                width = max(width, abs(point.x))
                height = max(height, abs(point.y))
            }
            return width to height
        }

        val (riftWidth, riftHeight) = extent(rift)
        val (beddingWidth, beddingHeight) = extent(bedding)

        assertTrue(
            riftHeight > riftWidth * 3,
            "a rift 0.4m wide and 2.5m tall came out ${riftWidth}m by ${riftHeight}m",
        )
        assertTrue(
            beddingWidth > beddingHeight * 3,
            "a bedding plane 3m wide and 0.5m tall came out ${beddingWidth}m by ${beddingHeight}m",
        )
        assertTrue(
            beddingWidth > riftWidth && riftHeight > beddingHeight,
            "the two passages came out the same way up, so across and down have been swapped",
        )
    }

    /**
     * Rock beyond the slice does not reach the drawing.
     *
     * The whole point of a cross-section is that it is a slice: a scanner sweeping a chamber sees
     * metres of passage in both directions, and a section that averaged all of it would draw the
     * widest part of the cave at every station. A narrow passage is put a long way down the tube
     * from a wide one, and only the wide one is at the station.
     */
    @Test
    fun rockFurtherAlongThePassageIsNotPartOfThisSection() {
        val bearing = 65f
        val atTheStation = tube(2f, 2f, bearing, alongFrom = -0.2f, alongTo = 0.2f, alongSteps = 8)
        val furtherOn = tube(0.3f, 0.3f, bearing, alongFrom = 4f, alongTo = 6f, alongSteps = 40)

        val strokes = PassageScan.outlines(atTheStation + furtherOn, bearing)
        val worst = worstErrorAgainstEllipse(strokes, 2f, 2f)

        assertTrue(
            worst < 0.05f,
            "a 0.3m passage four metres further on pulled the section in by ${worst}m",
        )
    }

    /**
     * A wall nobody scanned is left blank rather than drawn straight across.
     *
     * The honesty check, and the reason this returns open strokes at all. A surveyor who sweeps
     * the two walls and the floor and never points the phone up has not measured the roof, and a
     * cross-section that drew one would be a survey saying something nobody observed.
     */
    @Test
    fun aWallNobodyScannedIsLeftBlank() {
        val bearing = 100f
        // Everything except a ninety-degree bite out of one side.
        val scanned = tube(2f, 2f, bearing, onlyBetween = 0f..270f)

        val strokes = PassageScan.outlines(scanned, bearing)

        assertTrue(strokes.isNotEmpty(), "a three-quarter scan drew nothing at all")
        assertTrue(
            strokes.none { it.first() == it.last() },
            "a passage with a quarter of it unscanned was drawn as a closed loop",
        )
        // Nothing drawn in the unscanned quarter, which in this frame is the sector between 270
        // and 360 degrees: positive across, negative down.
        val invented = strokes.flatten().filter { it.x > 0.3f && it.y < -0.3f }
        assertTrue(
            invented.isEmpty(),
            "the scan drew ${invented.size} points of wall where nothing was scanned",
        )
    }

    /**
     * A handful of stray returns do not drag the wall out with them.
     *
     * Lidar reports depths off nothing at all — mist, water, the far side of a hole too small to
     * matter — and a section that took the farthest point in each direction would follow every one
     * of them. The percentile is what stops that, so a scan is given a scattering of fliers at
     * five times the passage's size and still has to draw the passage.
     */
    @Test
    fun aScatteringOfStrayReturnsDoesNotPullTheWallOut() {
        val bearing = 145f
        val radians = bearing.toDouble() * PI / 180
        val passage = tube(1.2f, 1.2f, bearing, alongFrom = -0.2f, alongTo = 0.2f, alongSteps = 10)

        // One flier in every eighth of the circle, well beyond the wall.
        val fliers = (0 until 8).map { i ->
            val angle = i.toDouble() / 8 * 2 * PI
            val across = 6.0 * cos(angle)
            val down = 6.0 * sin(angle)
            Coord3D(
                (across * cos(radians)).toFloat(),
                (-across * sin(radians)).toFloat(),
                (-down).toFloat(),
            )
        }

        val worst = worstErrorAgainstEllipse(
            PassageScan.outlines(passage + fliers, bearing),
            1.2f,
            1.2f,
        )
        assertTrue(worst < 0.1f, "stray returns at 6m pulled a 1.2m passage out by ${worst}m")
    }

    /**
     * Too few points in a direction is not a wall.
     *
     * The other half of the noise rule. One stray return in a direction nothing else was seen in
     * would otherwise become a lone spike of wall sticking out of the section, which reads as a
     * measured feature rather than as the artefact it is.
     */
    @Test
    fun oneStrayReturnOnItsOwnIsNotAWall() {
        val wall = PassageScan.wallDistances(
            listOf(Coord3D(2f, 0f, 0f), Coord3D(-2f, 0f, 0f)),
            bearing = 0f,
        )

        assertTrue(
            wall.all { it == null },
            "single points in two directions were taken for walls: ${wall.filterNotNull()}",
        )
    }

    /**
     * The noise rules count observations, so the same return sent twice is not two observations.
     *
     * This is the property a phone caught being violated, and it is worth pinning because it is
     * counter-intuitive: duplication does not merely fail to strengthen the percentile, it removes
     * the noise floor altogether. Three points are needed before a direction is believed and the
     * eightieth percentile of a sector is taken — but one stray return repeated a hundred and fifty
     * times clears the three on its own and is every value the percentile sorts, so it comes out as
     * a confident wall. That is what turned a scanned room into a star of spikes.
     *
     * [SeenSurfaces] is what keeps the promise on the way in. This states the promise, so that
     * anything else that ever feeds this — ARCore on Android, a replayed recording — knows it is
     * expected to hand over distinct observations rather than a cumulative cloud.
     */
    @Test
    fun theSameReturnSentAgainIsNotASecondObservation() {
        val stray = Coord3D(4f, 0f, 0f)

        val once = PassageScan.wallDistances(listOf(stray), bearing = 0f)
        assertTrue(
            once.all { it == null },
            "a single stray return is not a wall, which is the noise floor doing its job",
        )

        // The same one return, as a cumulative scanner would re-report it.
        val repeated = PassageScan.wallDistances(List(150) { stray }, bearing = 0f)
        val believed = repeated.filterNotNull()
        assertEquals(
            1,
            believed.size,
            "one stray repeated should still reach exactly one direction, not spread",
        )
        assertEquals(
            4f,
            believed.single(),
            "this test only means anything while repetition still promotes a stray: if that has " +
                "stopped being true the noise floor now counts something other than observations, " +
                "and this wants rewriting rather than deleting",
        )

        // Sieved as the scanner sieves it, the stray is back to being one observation and ignored.
        val seen = SeenSurfaces()
        val sieved = List(150) { stray }.filter { seen.isNew(it.x, it.y, it.z) }
        assertTrue(
            PassageScan.wallDistances(sieved, bearing = 0f).all { it == null },
            "sieved to distinct observations, one stray return is not a wall again",
        )
    }

    /**
     * Nothing scanned at all draws nothing at all, rather than a point at the surveyor's feet.
     *
     * A scan that is started and abandoned, or run with the phone in a pocket, has to come back
     * with no drawing. The alternative is a cross-section holding a dot at the station, which
     * looks like a measurement of a passage with no room in it.
     */
    @Test
    fun anEmptyScanDrawsNothing() {
        assertEquals(emptyList(), PassageScan.outlines(emptyList(), bearing = 0f))
        assertEquals(
            emptyList(),
            PassageScan.outlines(listOf(Coord3D(0f, 0f, 0f)), bearing = 0f),
            "a point at the station itself has no direction and cannot be wall",
        )
    }

    /**
     * The section is cut at right angles to the passage, so turning the passage turns the section.
     *
     * A tube of a fixed shape is scanned on two bearings ninety degrees apart, and the section
     * comes out the same shape both times — which it only does if the bearing is actually used to
     * pick the plane. A scan that ignored the bearing would give the same answer here too, so the
     * asymmetric passage is what makes the check bite: cut the wrong way, a rift running east is a
     * slice down its length rather than across it.
     */
    @Test
    fun theSliceFollowsThePassageRatherThanTheCompass() {
        val eastward = PassageScan.outlines(tube(0.5f, 2f, 90f), 90f).flatten()
        val northward = PassageScan.outlines(tube(0.5f, 2f, 0f), 0f).flatten()

        fun width(points: List<org.hwyl.sexytopo.shared.model.graph.Coord2D>) =
            points.fold(0f) { widest, point -> max(widest, abs(point.x)) }

        assertTrue(
            abs(width(eastward) - width(northward)) < 0.05f,
            "the same passage came out ${width(eastward)}m across running east and " +
                "${width(northward)}m across running north",
        )

        // And cut along the passage instead of across it, the same points are metres wide rather
        // than half a metre — which is what makes the check above about the bearing and not about
        // the tube being symmetrical.
        val cutWrong = PassageScan.outlines(tube(0.5f, 2f, 90f), 0f).flatten()
        assertTrue(
            width(cutWrong) > width(eastward) * 2,
            "slicing a passage along its length gave ${width(cutWrong)}m, no wider than the " +
                "${width(eastward)}m of slicing it properly across",
        )
    }

    /**
     * A blink in the scan is drawn through; a hole in it is not.
     *
     * The two look the same in the data — an empty sector — and they must not be treated the same,
     * so the rule is how many in a row. Below the tolerance the wall carries on, because a scanner
     * that missed six degrees of a wall it saw either side of did not find a hole in the rock.
     */
    @Test
    fun aBlinkInTheScanIsDrawnThroughButAHoleIsNot() {
        val bearing = 0f
        val whole = tube(2f, 2f, bearing, alongFrom = -0.2f, alongTo = 0.2f, alongSteps = 10)
        val sectorInDegrees = 360f / PassageScan.DEFAULT_SECTORS

        fun withoutSectors(from: Int, count: Int): List<Coord3D> {
            val start = from * sectorInDegrees
            val end = start + count * sectorInDegrees
            return whole.filter { point ->
                val degrees = degreesOf(point, bearing)
                degrees < start || degrees >= end
            }
        }

        val blink = PassageScan.outlines(withoutSectors(10, 1), bearing)
        assertEquals(
            1,
            blink.size,
            "one missed sector broke the wall into ${blink.size} pieces instead of being drawn " +
                "through",
        )

        val hole = PassageScan.outlines(withoutSectors(10, 8), bearing)
        assertTrue(
            hole.isNotEmpty() && hole.none { it.first() == it.last() },
            "eight missed sectors were drawn across as though the wall had been seen",
        )
    }

    /** Where a point sits round the section, in degrees, for the sector arithmetic above. */
    private fun degreesOf(point: Coord3D, bearing: Float): Float {
        val radians = bearing.toDouble() * PI / 180
        val across = point.x * cos(radians) - point.y * sin(radians)
        val down = -point.z.toDouble()
        val degrees = kotlin.math.atan2(down, across) * 180 / PI
        return ((degrees + 360) % 360).toFloat()
    }

    /**
     * Every sector is reachable, and none is reachable twice.
     *
     * The wrap-around at due right of the station is where an off-by-one lives, and it would show
     * as one direction of every scan in the survey being blank or doubled. Checked by sweeping a
     * point round the whole circle and requiring each sector to be found exactly once.
     */
    @Test
    fun everyDirectionRoundTheStationHasASectorOfItsOwn() {
        val sectors = 36
        val found = mutableSetOf<Int>()

        for (step in 0 until 3600) {
            val angle = step / 3600.0 * 2 * PI
            // Three points at the same spot, so each clears the minimum on its own.
            val at = Coord3D((2 * cos(angle)).toFloat(), 0f, (-2 * sin(angle)).toFloat())
            val wall = PassageScan.wallDistances(listOf(at, at, at), bearing = 0f, sectors = sectors)
            val filled = wall.indices.filter { wall[it] != null }
            assertEquals(1, filled.size, "a single direction filled ${filled.size} sectors")
            found.add(filled.first())
        }

        assertEquals(
            (0 until sectors).toSet(),
            found,
            "sweeping the whole circle never reached every sector",
        )
    }

    /**
     * The outline is in metres about the station, which is the frame a cross-section is drawn in.
     *
     * Stated as a check rather than left to the reader because it is what lets the result be added
     * to `CrossSectionDetail.sketch` untouched: the sub-sketch's own origin is the station, and
     * `CrossSection.getProjection` puts the splays there too. A scan in any other units or about
     * any other point would draw a passage the splays disagreed with.
     */
    @Test
    fun theOutlineIsInMetresAboutTheStation() {
        val strokes = PassageScan.outlines(tube(1f, 1f, 0f), 0f)
        val distances = strokes.flatten().map { sqrt(it.x * it.x + it.y * it.y) }

        assertTrue(distances.isNotEmpty(), "nothing was drawn")
        val nearest = distances.fold(Float.MAX_VALUE) { a, b -> min(a, b) }
        val farthest = distances.fold(0f) { a, b -> max(a, b) }
        assertTrue(
            abs(nearest - 1f) < 0.05f && abs(farthest - 1f) < 0.05f,
            "a passage one metre in radius was drawn between ${nearest}m and ${farthest}m from " +
                "the station",
        )
    }
}
