package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * A wall scan as the survey keeps it: what was captured, never changed, and what the surveyor has
 * changed about it since, kept beside it.
 *
 * The raw capture is [scan]. Everything a surveyor can do to a scan in review — move it, drop a
 * mistaken station mark, rename one, re-smooth a pen line, throw away a stamp — is a field of
 * [review], and the drawing is rebuilt from the two together. So every edit can be taken back by
 * clearing it, and a scan placed badly today can be placed again tomorrow from the same rock.
 *
 * [result] is what the last rebuild did: how it placed the scan, what it said, and the splays it
 * added. It is kept so that opening a survey does not have to place every scan again before it can
 * say anything about one, and so a rebuild can take out the splays the previous one put in.
 */
class WallScanRecord(
    val id: String,
    val scan: WallScan,
    val review: ScanReview = ScanReview(),
    var result: ScanResult? = null,
) {
    /** The station marks still in play, renamed as the review says. */
    fun effectiveTouches(): List<StationTouch> =
        scan.touches.mapIndexedNotNull { index, touch ->
            if (index in review.removedTouches) {
                null
            } else {
                review.renamedTouches[index]?.let { StationTouch(it, touch.inScan) } ?: touch
            }
        }

    /** The pen lines still in play, each as it should now be drawn, in the scan's frame. */
    fun effectiveLines(): List<Pair<Int, PenLine>> =
        scan.penLines.withIndex().filter { it.index !in review.removedLines }.map { it.index to it.value }

    /** The line to draw for pen line [index]: smoothed again in review, or as it was captured. */
    fun lineFor(index: Int): List<Coord3D> {
        val line = scan.penLines[index]
        val amount = review.lineSmoothing[index] ?: return line.stroke.line()
        return PenSmoothing.smooth(line.stroke.rawHits, amount)
    }

    fun effectiveStamps(): List<SymbolStamp> =
        scan.stamps.filterIndexed { index, _ -> index !in review.removedStamps }
}

/**
 * The surveyor's changes to a scan, applied on top of the capture rather than into it.
 *
 * Indices are into the captured lists, which never change, so an index means the same mark for the
 * life of the scan.
 */
class ScanReview(
    /**
     * A placement set by hand, or one the surveyor has locked so later rebuilds keep it. Null to
     * place the scan automatically every time, by its marks or by fitting.
     */
    var placement: ScanPlacement? = null,
    val removedTouches: MutableSet<Int> = mutableSetOf(),
    val renamedTouches: MutableMap<Int, String> = mutableMapOf(),
    val removedLines: MutableSet<Int> = mutableSetOf(),
    /** A whole-line smoothing amount for a pen line, replacing what the slider showed live. */
    val lineSmoothing: MutableMap<Int, Float> = mutableMapOf(),
    val removedStamps: MutableSet<Int> = mutableSetOf(),
    /** Walls generated along the legs; null for the default, which is on for a fitted scan only. */
    var generateWalls: Boolean? = null,
    /** LRUD splays on stations with none; null for the default, as for [generateWalls]. */
    var addLruds: Boolean? = null,
    /** A fan of splays round each station from the scan, for a survey that wants more than four. */
    var splayFans: Boolean = false,
    /** A cross-section drawn from the scan beside each station it covers that has none. */
    var crossSections: Boolean = false,
) {
    val isEmpty: Boolean
        get() =
            placement == null &&
                removedTouches.isEmpty() &&
                renamedTouches.isEmpty() &&
                removedLines.isEmpty() &&
                lineSmoothing.isEmpty() &&
                removedStamps.isEmpty() &&
                generateWalls == null &&
                addLruds == null &&
                !splayFans &&
                !crossSections

    fun copy(): ScanReview =
        ScanReview(
            placement,
            removedTouches.toMutableSet(),
            renamedTouches.toMutableMap(),
            removedLines.toMutableSet(),
            lineSmoothing.toMutableMap(),
            removedStamps.toMutableSet(),
            generateWalls,
            addLruds,
            splayFans,
            crossSections,
        )
}

/**
 * What the last rebuild of a scan did.
 *
 * [splays] are the legs it added, by station and reading, so the next rebuild — or deleting the
 * scan — can take them out again without touching a splay somebody shot with an instrument.
 */
class ScanResult(
    val mode: PlacementMode?,
    val placement: ScanPlacement?,
    val message: String,
    val splays: List<AddedSplay> = emptyList(),
    /** Station by station, how far each mark ended up from its station; empty unless anchored. */
    val residuals: List<Pair<String, Float>> = emptyList(),
    val slideMetres: Float? = null,
    /**
     * Whether walls and LRUDs are drawn when review has not said: true for a scan its marks cannot
     * anchor, whose whole use is passage shape. Decided by the marks rather than by [mode], so
     * nudging a fitted scan by hand, which makes it MANUAL, does not take its walls away.
     */
    val fromRockByDefault: Boolean = false,
    /** For a fitted scan, the share of centreline through rock, kept to describe a reused fit. */
    val legsThroughRock: Float? = null,
)

/** A splay a scan added: enough to find it again among the station's other splays. */
class AddedSplay(
    val station: String,
    val distance: Float,
    val azimuth: Float,
    val inclination: Float,
)

/**
 * The rock of every placed scan in [records], in survey coordinates, thinned evenly to at most
 * [limit] points: the 3D view's point cloud. A scan not placed yet is left out, since there is
 * nowhere to put it.
 *
 * Thinned by stride rather than at random so the same scans always give the same cloud, and
 * shared between the scans by their size, so a short scan beside a long one is not lost.
 */
fun pointCloudOf(records: List<WallScanRecord>, limit: Int = DEFAULT_CLOUD_POINTS): List<Coord3D> {
    val placed = records.mapNotNull { record ->
        val placement = record.review.placement ?: record.result?.placement ?: return@mapNotNull null
        record to placement
    }
    val total = placed.sumOf { it.first.scan.points.size }
    if (total == 0) return emptyList()
    val stride = maxOf(1, (total + limit - 1) / limit)
    val cloud = ArrayList<Coord3D>(minOf(total, limit))
    for ((record, placement) in placed) {
        val points = record.scan.points
        var i = 0
        while (i < points.size) {
            cloud.add(placement.place(points[i]))
            i += stride
        }
    }
    return cloud
}

/** Enough to see a passage's shape in the 3D view and few enough to turn smoothly on a phone. */
const val DEFAULT_CLOUD_POINTS = 20_000
