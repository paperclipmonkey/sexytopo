package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The section, fitted into the small box a surveyor watches while sweeping the phone.
 *
 * Every fault this is looking for draws something. A wrong scale draws a passage squashed, a centre
 * worked out from the walls alone draws one wall filling the box with the surveyor nowhere on it,
 * and a flipped axis draws the roof underfoot. All three look like a scan in progress, which is why
 * each is asserted here against an answer worked out in advance rather than left to be noticed in a
 * cave.
 */
class ScanPreviewTest {

    private val size = 100f

    private fun assertClose(expected: Float, actual: Float, what: String, tolerance: Float = 0.01f) =
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$what should be $expected and is $actual",
        )

    /** A square passage two metres each way about the station, as four corners. */
    private fun squarePassage(halfWidth: Float = 1f) =
        listOf(
            listOf(
                Coord2D(-halfWidth, -halfWidth),
                Coord2D(halfWidth, -halfWidth),
                Coord2D(halfWidth, halfWidth),
                Coord2D(-halfWidth, halfWidth),
                Coord2D(-halfWidth, -halfWidth),
            ),
        )

    /**
     * A passage round the station fills the box, and the station sits in the middle of it.
     *
     * The base case, and the one that fixes the scale: two metres across a hundred pixels is fifty
     * pixels to the metre, and everything else here is a departure from that.
     */
    @Test
    fun aPassageRoundTheStationFillsTheBoxWithTheStationInTheMiddle() {
        val fitted = ScanPreview.fit(squarePassage(), size)

        assertClose(50f, fitted.pixelsPerMetre, "pixels per metre")
        assertClose(50f, fitted.station.x, "the station's x")
        assertClose(50f, fitted.station.y, "the station's y")

        val drawn = fitted.strokes.single()
        assertClose(0f, drawn[0].x, "the left wall")
        assertClose(100f, drawn[1].x, "the right wall")
    }

    /**
     * Padding is kept clear at every edge, and costs the drawing rather than the box.
     *
     * A preview drawn hard against the edge of its own panel is one whose outermost wall cannot be
     * told from the panel's border, which on a phone in the dark is the difference between a
     * measured wall and the end of the screen.
     */
    @Test
    fun paddingIsKeptClearAndComesOutOfTheDrawing() {
        val fitted = ScanPreview.fit(squarePassage(), size, padding = 10f)

        assertClose(40f, fitted.pixelsPerMetre, "pixels per metre inside the padding")
        val drawn = fitted.strokes.single()
        assertTrue(drawn.all { it.x >= 10f && it.x <= 90f }, "the drawing crossed the padding: $drawn")
        assertClose(50f, fitted.station.x, "the station is still in the middle")
    }

    /**
     * Down in the cave is down on the screen, and the drawing is not flipped on its way here.
     *
     * A cross-section's own axes are across and down — `Projection2D.CROSS_SECTION` builds them
     * that way — and a screen counts y downwards too, so nothing here should flip anything. The
     * assertion exists because "no flip" and "forgot the flip" produce identical code and opposite
     * drawings, and the drawing that comes out of a wrong one is a passage with its roof underfoot:
     * entirely plausible, and wrong.
     */
    @Test
    fun theFloorIsDrawnBelowTheStationAndTheRoofAboveIt() {
        val floor = Coord2D(0f, 2f)
        val roof = Coord2D(0f, -2f)
        val fitted = ScanPreview.fit(listOf(listOf(roof, floor)), size)

        val drawnRoof = fitted.strokes.single()[0]
        val drawnFloor = fitted.strokes.single()[1]

        assertTrue(
            drawnFloor.y > fitted.station.y,
            "the floor came out at ${drawnFloor.y}, above the station at ${fitted.station.y}",
        )
        assertTrue(
            drawnRoof.y < fitted.station.y,
            "the roof came out at ${drawnRoof.y}, below the station at ${fitted.station.y}",
        )
    }

    /**
     * The surveyor is on the drawing even when every wall found so far is off to one side.
     *
     * The half-finished case, which is the case this whole preview exists for. Fitting the walls
     * alone would blow that one wall up to fill the box and leave the station off the edge — a
     * picture of a finished section of a passage that is not there. Including the origin in the
     * extent is what keeps the surveyor in the frame, and what makes the empty side of the box
     * legible as "you have not scanned over there yet".
     */
    @Test
    fun theStationStaysOnTheDrawingWhenOnlyOneWallHasBeenFound() {
        val eastWallOnly = listOf(listOf(Coord2D(3f, -1f), Coord2D(3f, 1f)))

        val fitted = ScanPreview.fit(eastWallOnly, size)

        assertTrue(
            fitted.station.x in 0f..size && fitted.station.y in 0f..size,
            "the station fell off the box at ${fitted.station}",
        )
        val wall = fitted.strokes.single()
        assertTrue(
            wall.all { it.x in 0f..size && it.y in 0f..size },
            "the wall fell off the box: $wall",
        )
        assertTrue(
            wall.all { it.x > fitted.station.x },
            "a wall three metres east was not drawn east of the station",
        )
    }

    /**
     * A rift comes back tall and narrow rather than stretched to a square.
     *
     * One scale for both axes. Fitting each axis to the box separately would draw every passage as
     * the same rectangle, which is the one thing a cross-section is for saying is not true.
     */
    @Test
    fun aTallNarrowRiftKeepsItsShape() {
        val rift = listOf(listOf(Coord2D(-0.5f, -4f), Coord2D(0.5f, -4f), Coord2D(0.5f, 4f), Coord2D(-0.5f, 4f)))

        val fitted = ScanPreview.fit(rift, size)
        val drawn = fitted.strokes.single()
        val width = drawn.maxOf { it.x } - drawn.minOf { it.x }
        val height = drawn.maxOf { it.y } - drawn.minOf { it.y }

        assertClose(100f, height, "the rift's height should fill the box")
        assertClose(12.5f, width, "the rift's width should stay an eighth of its height")
    }

    /**
     * A scan that has found nothing yet draws nothing, and says so rather than dividing by it.
     *
     * The first second of every scan. A scale of zero is the honest answer and is the caller's cue
     * to draw the station and nothing else; the alternative — a scale of infinity, or a crash — is
     * a preview that fails at exactly the moment somebody is looking at it hardest.
     */
    @Test
    fun aScanThatHasFoundNothingDrawsNothingRatherThanFailing() {
        val fitted = ScanPreview.fit(emptyList(), size)

        assertTrue(fitted.strokes.isEmpty(), "something was drawn from nothing")
        assertEquals(0f, fitted.pixelsPerMetre, "an empty scan reported a scale")
        assertClose(50f, fitted.station.x, "the station's x")
        assertClose(50f, fitted.station.y, "the station's y")

        // A wall found exactly at the station is the other way of having no extent, since the
        // origin is always in it: one point anywhere else is an extent, and is magnified to fill
        // the box quite correctly.
        val atTheStation = ScanPreview.fit(listOf(listOf(Coord2D.ORIGIN)), size)
        assertEquals(0f, atTheStation.pixelsPerMetre, "a section with no extent reported a scale")

        val oneMetreOut = ScanPreview.fit(listOf(listOf(Coord2D(1f, 1f))), size)
        assertClose(
            100f,
            oneMetreOut.pixelsPerMetre,
            "one point a metre from the station is a metre of extent and should fill the box",
        )
    }

    /** A box with no room in it is refused rather than drawn into. */
    @Test
    fun aBoxWithNoRoomInItIsRefused() {
        assertFailsWith<IllegalArgumentException> { ScanPreview.fit(squarePassage(), 0f) }
        assertFailsWith<IllegalArgumentException> { ScanPreview.fit(squarePassage(), 10f, padding = 5f) }
        assertFailsWith<IllegalArgumentException> { ScanPreview.fit(squarePassage(), 10f, padding = -1f) }
    }

    /**
     * The count on the screen is of directions measured, not of points gathered.
     *
     * The distinction the preview exists to make. A surveyor sweeping one wall for half a minute
     * and one who has been the whole way round gather points at the same rate; only this tells them
     * apart, and it is the same list of sectors that decides where the drawn strokes break.
     */
    @Test
    fun theCountIsOfDirectionsMeasuredRatherThanPointsGathered() {
        assertEquals(0, ScanPreview.sectorsMeasured(List(60) { null }))
        assertEquals(60, ScanPreview.sectorsMeasured(List(60) { 2f }))
        assertEquals(
            2,
            ScanPreview.sectorsMeasured(listOf(1f, null, null, 3f, null)),
            "only the sectors with a wall in them count",
        )
    }

    /**
     * What a real scan produces goes through it, which the hand-built shapes above do not prove.
     *
     * `PassageScan.outlines` is the only thing that will ever call this, so the join is worth one
     * test: a tube of rock round the station, sliced and reduced by the real thing, comes back as a
     * drawing that fills its box with the surveyor inside it.
     */
    @Test
    fun aRealScanFitsTheBoxWithTheSurveyorInsideThePassage() {
        val tube = buildList {
            for (i in 0 until 720) {
                val angle = i / 720.0 * 2 * kotlin.math.PI
                for (j in -4..4) {
                    add(
                        org.hwyl.sexytopo.shared.model.graph.Coord3D(
                            (1.5 * kotlin.math.cos(angle)).toFloat(),
                            (j * 0.05).toFloat(),
                            (1.5 * kotlin.math.sin(angle)).toFloat(),
                        ),
                    )
                }
            }
        }

        val fitted = ScanPreview.fit(PassageScan.outlines(tube, bearing = 0f), size, padding = 4f)

        assertTrue(fitted.strokes.isNotEmpty(), "a scan of a whole tube drew nothing")
        assertClose(50f, fitted.station.x, "the station's x in a passage round it", tolerance = 2f)
        assertClose(50f, fitted.station.y, "the station's y in a passage round it", tolerance = 2f)
        assertTrue(
            fitted.strokes.flatten().all { it.x in 0f..size && it.y in 0f..size },
            "the fitted section left the box",
        )
    }
}
