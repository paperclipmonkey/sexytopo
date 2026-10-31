package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.roundToInt

/**
 * Everything one wall scan brought back, in the scan's own frame: survey-shaped axes (x east,
 * y north, z up, metres) levelled by gravity, with ARKit's idea of north and an origin wherever the
 * phone was when the scan opened.
 *
 * [startStation] is the surveyor's pin: the station they said they were beside when they began.
 * It is what places a scan with no touches on an old survey.
 */
class WallScan(
    val points: List<Coord3D>,
    val path: List<Coord3D>,
    val touches: List<StationTouch>,
    val penLines: List<PenLine>,
    val stamps: List<SymbolStamp>,
    val startStation: String?,
)

/** How a scan was placed, which decides what is drawn from it and what the surveyor is told. */
enum class PlacementMode {
    /** Two or more touched stations, or one and the compass. */
    ANCHORED,

    /** No usable touches: fitted to the centerline by shape, from the pin. */
    FITTED,
}

/** What applying a scan did, for the screen to say. */
class WallScanReport(
    val mode: PlacementMode?,
    val placement: ScanPlacement?,
    val content: DerivedContent,
    val lrudsAdded: Int,
    val message: String,
)

/**
 * Putting a finished wall scan into a survey.
 *
 * Placement first: by the stations touched, if there are enough of them, and otherwise by fitting
 * the scan to the centerline from the surveyor's pin. Then drawing: pen lines and stamped symbols
 * always, and walls generated from the scan's points when asked for, which is the default for a
 * fitted scan, whose whole purpose is passage shape on a survey that never had any. LRUD splays go
 * on stations that have none of their own, so a scan never overwrites what somebody measured.
 */
object WallScanApplier {

    /**
     * ARKit is asked for a world aligned to north, so the scan's frame starts out roughly right:
     * the correction expected is nought, give or take whatever the compass got wrong underground.
     */
    const val PRIOR_HEADING_DEGREES = 0f

    fun apply(
        survey: Survey,
        scan: WallScan,
        generateWalls: Boolean? = null,
        addLruds: Boolean? = null,
        wallColour: Colour = Colour.BLACK,
        previous: DerivedContent = DerivedContent.NONE,
    ): WallScanReport {
        val frame = SurveyFrame(survey)
        val anchored = StationAnchoring.solve(scan.touches, frame.stationPositions, PRIOR_HEADING_DEGREES)

        val placement: ScanPlacement
        val mode: PlacementMode
        val placed: String
        if (anchored != null) {
            placement = anchored.placement
            mode = PlacementMode.ANCHORED
            placed = describe(anchored)
        } else {
            val pin = scan.startStation?.let { frame.stationPositions[it] }
            if (pin == null) {
                return WallScanReport(null, null, previous, 0, NOTHING_TO_PLACE_BY)
            }
            val fitted =
                FittedPlacement.fit(scan.path, scan.points, frame, pin, PRIOR_HEADING_DEGREES)
                    ?: return WallScanReport(null, null, previous, 0, NO_CENTERLINE_NEAR)
            placement = fitted.placement
            mode = PlacementMode.FITTED
            placed = describe(fitted)
        }

        val drawWalls = generateWalls ?: (mode == PlacementMode.FITTED)
        val writeLruds = addLruds ?: (mode == PlacementMode.FITTED)

        val placedPoints = scan.points.map { placement.place(it) }
        val grid = PointGrid(placedPoints)
        val walls = if (drawWalls) ScanProfiles.wallsAlongLegs(frame, survey, grid) else emptyList()

        val content =
            ScanSketchWriter.write(
                survey,
                frame,
                placement,
                scan.penLines,
                scan.stamps,
                walls,
                wallColour,
                previous,
            )

        var lruds = 0
        if (writeLruds) {
            for (station in survey.getAllStations()) {
                if (station.getUnconnectedOnwardLegs().isNotEmpty()) continue
                val distances = ScanProfiles.lrudAt(survey, frame, grid, station) ?: continue
                val splays = ScanProfiles.lrudSplays(survey, station, distances)
                for (splay in splays) SurveyBuilder.addSplay(survey, station, splay)
                if (splays.isNotEmpty()) lruds++
            }
        }

        val drew = buildList {
            if (scan.penLines.isNotEmpty()) add(plural(scan.penLines.size, "pen line"))
            if (scan.stamps.isNotEmpty()) add(plural(scan.stamps.size, "symbol"))
            if (walls.isNotEmpty()) add(plural(walls.size, "wall"))
            if (lruds > 0) add("LRUDs at ${plural(lruds, "station")}")
        }
        val message = placed + if (drew.isEmpty()) " Nothing to draw." else " Drew " + drew.joinToString(", ") + "."
        return WallScanReport(mode, placement, content, lruds, message)
    }

    private fun describe(result: AnchorResult): String {
        val stations = result.residuals.map { it.touch.stationName }.distinct().size
        val heading = if (result.isHeadingFromStations) "" else " Heading from the compass."
        val unmatched =
            if (result.unmatched.isEmpty()) {
                ""
            } else {
                " Not in the survey: " + result.unmatched.joinToString { it.stationName } + "."
            }
        return "Anchored to ${plural(stations, "station")}, worst " +
            "${centimetres(result.worstResidual)} out.$heading$unmatched"
    }

    private fun describe(result: FittedResult): String {
        val slide = if (result.slideMetres >= 1f) " Could be ${metres(result.slideMetres)} either way along the passage." else ""
        val rock =
            if (result.legsThroughRock > 0.2f) {
                " ${(result.legsThroughRock * 100).roundToInt()}% of the centerline runs through scanned rock: check the old survey here."
            } else {
                ""
            }
        return "Fitted to the centerline.$slide$rock"
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private fun centimetres(metres: Float): String = "${(metres * 100).roundToInt()} cm"

    private fun metres(value: Float): String = "${(value * 10).roundToInt() / 10f} m"

    const val NOTHING_TO_PLACE_BY =
        "Not placed: touch two stations during the scan, or choose the station you started beside."

    const val NO_CENTERLINE_NEAR = "Not placed: there is no centerline near the station you started beside."
}
