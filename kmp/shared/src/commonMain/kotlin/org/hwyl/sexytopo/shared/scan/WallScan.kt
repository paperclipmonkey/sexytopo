package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    /**
     * Which samples of [path] were taken while ARKit said its tracking was limited or lost: too
     * dark, too fast, too featureless. The track there is not to be believed, so fitting leaves it
     * out, and review says how much of the scan it was, which is the first thing to know about a
     * scan that came out wrong.
     */
    val poorTracking: Set<Int> = emptySet(),
) {
    /** The track with the samples taken while tracking was poor left out. */
    fun trustedPath(): List<Coord3D> = path.filterIndexed { index, _ -> index !in poorTracking }
}

/** How a scan was placed, which decides what is drawn from it and what the surveyor is told. */
enum class PlacementMode {
    /** Two or more touched stations, or one and the compass. */
    ANCHORED,

    /** No usable touches: fitted to the centerline by shape, from the pin. */
    FITTED,

    /** Placed by the surveyor's own hand in review, or locked where a fit left it. */
    MANUAL,
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

    /**
     * Draws [record] into [survey] as its review now says, replacing whatever it drew before, and
     * notes on the record what was done.
     *
     * Everything the previous rebuild drew is found by the scan's id and taken out first, along
     * with the splays it added, so this is safe to call as often as the surveyor moves a slider: a
     * rebuild never piles one drawing on another, and never touches drawing or splays that are not
     * the scan's own.
     */
    fun rebuild(
        survey: Survey,
        record: WallScanRecord,
        wallColour: Colour = Colour.BLACK,
        /**
         * Fit an unmarked scan to the centreline again even if it has been fitted before. A fit
         * walks every point of the scan several hundred times, so a rebuild for a slider or a
         * switch in review keeps the last one; the first placement, and Automatic in review, ask
         * for a fresh one.
         */
        refit: Boolean = false,
    ): WallScanReport {
        val previous = record.result
        remove(survey, record)
        val scan = record.scan
        val review = record.review
        val frame = SurveyFrame(survey)

        val placement: ScanPlacement
        val mode: PlacementMode
        val placed: String
        var residuals: List<Pair<String, Float>> = emptyList()
        var slide: Float? = null
        val manual = review.placement
        val anchoring = StationAnchoring.solve(record.effectiveTouches(), frame.stationPositions, PRIOR_HEADING_DEGREES)
        val anchored = if (manual == null) anchoring else null
        val fromRockByDefault = anchoring == null
        var throughRock: Float? = null
        if (manual != null) {
            placement = manual
            mode = PlacementMode.MANUAL
            // The marks are still worth checking against a placement made by hand: they are the
            // one independent measure of whether the hand got it right.
            residuals = residualsFor(record, frame, manual)
            placed =
                "Placed by hand." +
                    if (residuals.isEmpty()) "" else " Marks are worst ${centimetres(residuals.maxOf { it.second })} out."
        } else if (anchored != null) {
            placement = anchored.placement
            mode = PlacementMode.ANCHORED
            placed = describe(anchored)
            residuals = anchored.residuals.map { it.touch.stationName to it.metres }
        } else {
            val reusable = previous?.placement?.takeIf { !refit && previous.mode == PlacementMode.FITTED }
            if (reusable != null) {
                placement = reusable
                slide = previous.slideMetres
                throughRock = previous.legsThroughRock
                placed = describeFit(slide ?: 0f, throughRock ?: 0f)
            } else {
                val pin = scan.startStation?.let { frame.stationPositions[it] }
                if (pin == null) {
                    record.result = ScanResult(null, null, NOTHING_TO_PLACE_BY)
                    return WallScanReport(null, null, DerivedContent.NONE, 0, NOTHING_TO_PLACE_BY)
                }
                val fitted = FittedPlacement.fit(fittingPath(scan), scan.points, frame, pin, PRIOR_HEADING_DEGREES)
                if (fitted == null) {
                    record.result = ScanResult(null, null, NO_CENTERLINE_NEAR)
                    return WallScanReport(null, null, DerivedContent.NONE, 0, NO_CENTERLINE_NEAR)
                }
                placement = fitted.placement
                placed = describe(fitted)
                slide = fitted.slideMetres
                throughRock = fitted.legsThroughRock
            }
            mode = PlacementMode.FITTED
        }

        val drawWalls = review.generateWalls ?: fromRockByDefault
        val writeLruds = review.addLruds ?: fromRockByDefault
        val needGrid = drawWalls || writeLruds || review.splayFans || review.crossSections
        val grid = if (needGrid) PointGrid(scan.points.map { placement.place(it) }) else null
        val walls = if (drawWalls && grid != null) ScanProfiles.wallsAlongLegs(frame, survey, grid) else emptyList()
        val roofAndFloor =
            if (drawWalls && grid != null) ScanProfiles.roofAndFloorAlongLegs(frame, survey, grid) else emptyList()

        val lines = record.effectiveLines().map { (index, line) -> record.lineFor(index) to line.colour }
        val stamps = record.effectiveStamps()
        val content =
            ScanSketchWriter.draw(survey, frame, placement, lines, stamps, walls, wallColour, record.id, roofAndFloor)

        val sections =
            if (review.crossSections && grid != null) {
                addCrossSections(survey, frame, grid, placement, record)
            } else {
                0
            }

        val added = mutableListOf<AddedSplay>()
        var lruds = 0
        var fans = 0
        if (grid != null && (writeLruds || review.splayFans)) {
            for (station in survey.getAllStations()) {
                // A station somebody shot splays from keeps its own: a scan adds to a survey and
                // never overrules it.
                if (station.getUnconnectedOnwardLegs().isNotEmpty()) continue
                val centre = frame.stationPositions[station.name] ?: continue
                val splays = mutableListOf<Leg>()
                if (writeLruds) {
                    ScanProfiles.lrudAt(survey, frame, grid, station)?.let { distances ->
                        splays += ScanProfiles.lrudSplays(survey, station, distances)
                    }
                    if (splays.isNotEmpty()) lruds++
                }
                if (review.splayFans) {
                    val bearing = CrossSectioner.angleOfSection(survey, station)
                    val fan = ScanProfiles.fanAt(grid, centre, bearing)
                    for ((distance, azimuth, inclination) in fan) {
                        splays += Leg(distance, azimuth, inclination)
                    }
                    if (fan.isNotEmpty()) fans++
                }
                for (splay in splays) {
                    splay.comment = splayComment(record.id)
                    SurveyBuilder.addSplay(survey, station, splay)
                    added += AddedSplay(station.name, splay.distance, splay.azimuth, splay.inclination)
                }
            }
        }

        val drew = buildList {
            if (lines.isNotEmpty()) add(plural(lines.size, "pen line"))
            if (stamps.isNotEmpty()) add(plural(stamps.size, "symbol"))
            if (walls.isNotEmpty()) add(plural(walls.size, "wall"))
            if (lruds > 0) add("LRUDs at ${plural(lruds, "station")}")
            if (fans > 0) add("splay fans at ${plural(fans, "station")}")
            if (sections > 0) add(plural(sections, "cross-section"))
        }
        val message = placed + if (drew.isEmpty()) " Nothing to draw." else " Drew " + drew.joinToString(", ") + "."
        record.result = ScanResult(mode, placement, message, added, residuals, slide, fromRockByDefault, throughRock)
        return WallScanReport(mode, placement, content, lruds, message)
    }

    /**
     * Takes everything [record] drew out of [survey]: its strokes and symbols on both sketches,
     * and the splays its last rebuild added. Returns how many things went.
     */
    fun remove(survey: Survey, record: WallScanRecord): Int {
        var removed = ScanSketchWriter.removeScan(survey, record.id)
        // Found by their comment on every station, rather than by the station names the record
        // noted: a station renamed since, or a save of the survey that landed without the record's,
        // would otherwise leave splays behind that every later rebuild then mistakes for measured
        // ones and builds round.
        val comment = splayComment(record.id)
        for (station in survey.getAllStations()) {
            for (leg in station.getUnconnectedOnwardLegs().filter { it.comment == comment }) {
                SurveyUpdater.deleteLeg(survey, station, leg)
                removed++
            }
        }
        return removed
    }

    /**
     * Fits [record] to the centerline again, starting from where it is now rather than from its
     * pin, over a narrower search; and locks the result in, so the next rebuild keeps it.
     *
     * For a scan the automatic fit put in the wrong passage, or a little off: the surveyor drags it
     * roughly into place and asks for it to be fitted from there. Returns false, changing nothing,
     * when there is nothing to fit to.
     */
    fun refit(survey: Survey, record: WallScanRecord): Boolean {
        val start = record.review.placement ?: record.result?.placement ?: return false
        val path = fittingPath(record.scan)
        if (path.isEmpty()) return false
        val frame = SurveyFrame(survey)
        val fitted =
            FittedPlacement.fit(
                path,
                record.scan.points,
                frame,
                start.place(path.first()),
                start.headingDegrees,
                headingRangeDegrees = REFIT_HEADING_DEGREES,
                searchRadiusMetres = REFIT_RADIUS_METRES,
            ) ?: return false
        record.review.placement = fitted.placement
        return true
    }

    /**
     * A cross-section beside each station the scan's track came near and that has none on the
     * plan: the outline of the rock there, as the section editor's scan would draw it. Placed off
     * to the right of the passage, past its wall, where a surveyor would put one by hand. Returns
     * how many were made.
     */
    private fun addCrossSections(
        survey: Survey,
        frame: SurveyFrame,
        grid: PointGrid,
        placement: ScanPlacement,
        record: WallScanRecord,
    ): Int {
        val plan = survey.getSketch(Projection2D.PLAN)
        val track = record.scan.path.map { placement.place(it) }
        var made = 0
        for (station in survey.getAllStations()) {
            if (plan.crossSectionDetails.any { it.station === station }) continue
            val centre = frame.stationPositions[station.name] ?: continue
            if (track.none { StationAnchoring.distance(it, centre) <= SECTION_REACH_METRES }) continue
            val bearing = CrossSectioner.angleOfSection(survey, station)
            val outlines = ScanProfiles.sectionAt(grid, centre, bearing)
            if (outlines.isEmpty()) continue

            val radians = bearing.toDouble() * PI / 180
            val right = Coord3D(cos(radians).toFloat(), -sin(radians).toFloat(), 0f)
            val besideIt =
                Coord3D(
                    centre.x + right.x * SECTION_OFFSET_METRES,
                    centre.y + right.y * SECTION_OFFSET_METRES,
                    centre.z,
                )
            val drawing = Sketch()
            for (outline in outlines) {
                val path = PathDetail(outline, Colour.BLACK)
                path.scanId = record.id
                drawing.pathDetails.add(path)
            }
            val detail = CrossSectionDetail(frame.toPlan(besideIt), CrossSection(station, bearing), drawing)
            detail.scanId = record.id
            plan.crossSectionDetails.add(detail)
            made++
        }
        return made
    }

    /** A station further than this from the phone's track was not where the surveyor scanned. */
    private const val SECTION_REACH_METRES = 3f

    /** How far to the right of the passage a generated section is put on the plan. */
    private const val SECTION_OFFSET_METRES = 4f

    /**
     * The track to fit by: the part taken while tracking was good, unless that is too little of it
     * to fit anything, in which case all of it, since a doubtful track is better than none.
     */
    private fun fittingPath(scan: WallScan): List<Coord3D> {
        val trusted = scan.trustedPath()
        return if (trusted.size * 2 >= scan.path.size) trusted else scan.path
    }

    /** Where each remaining mark lands under [placement], against its station. */
    private fun residualsFor(
        record: WallScanRecord,
        frame: SurveyFrame,
        placement: ScanPlacement,
    ): List<Pair<String, Float>> =
        record.effectiveTouches().mapNotNull { touch ->
            val station = frame.stationPositions[touch.stationName] ?: return@mapNotNull null
            touch.stationName to StationAnchoring.distance(placement.place(touch.inScan), station)
        }

    /**
     * What a scan's splays say in their comment: visible in the table on either app, and what
     * [remove] checks before deleting one, so a splay with the same reading that somebody shot by
     * hand is never mistaken for the scan's.
     */
    fun splayComment(scanId: String): String = "wall scan $scanId"

    const val REFIT_HEADING_DEGREES = 10f
    const val REFIT_RADIUS_METRES = 2f

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

    private fun describeFit(slideMetres: Float, legsThroughRock: Float): String =
        describe(FittedResult(ScanPlacement.IDENTITY, 0f, slideMetres, legsThroughRock))

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private fun centimetres(metres: Float): String = "${(metres * 100).roundToInt()} cm"

    private fun metres(value: Float): String = "${(value * 10).roundToInt() / 10f} m"

    const val NOTHING_TO_PLACE_BY =
        "Not placed: touch two stations during the scan, or choose the station you started beside."

    const val NO_CENTERLINE_NEAR = "Not placed: there is no centerline near the station you started beside."
}
