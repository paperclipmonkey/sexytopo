package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * A depth scanner the surveyor can sweep round the passage, or an honest report that this device
 * has none.
 *
 * Shaped exactly after [PhotoCapture], and for the same reasons: the scan arrives several seconds
 * after the tap that asked for it, having taken over the screen meanwhile, so it comes back through
 * a callback rather than as a return value.
 */
interface PassageScanner {

    /** Whether this platform can scan at all. */
    val available: Boolean

    /**
     * Open the scanner. The points arrive at the callback given to [rememberPassageScanner], in
     * metres and in survey axes, relative to where the surveyor was standing when it opened.
     *
     * [bearing] is the section's own, in degrees, and is passed in for the scanner's own use rather
     * than for the arithmetic afterwards — the caller slices the points itself when they arrive. A
     * scanner that knows it can show the surveyor the section filling in as they sweep, which is
     * the only thing on that screen that says which *directions* are still to do. A count of points
     * rises just as fast for somebody sweeping one wall over and over.
     */
    fun scan(bearing: Float)
}

/**
 * Remembers a way of scanning a passage, delivering the points to [onScanned].
 *
 * Composable for [rememberPhotoCapture]'s reason turned round: nothing here needs registering
 * during composition, but everything here needs *keeping* across one — a scan holds a session that
 * must not be built again on every frame while the surveyor is sweeping the phone about.
 *
 * The points are in survey axes: x east, y north, z up, which is what `toCartesian` builds and what
 * `PassageScan` expects. Each platform converts from whatever its own scanner uses, so the shared
 * code sees one set of axes; see `PassageScanner.ios.kt`, which is the only one that has any.
 */
@Composable
expect fun rememberPassageScanner(onScanned: (List<Coord3D>) -> Unit): PassageScanner

/**
 * Why there is no scanner, for a screen that has to say so. Empty where there is one.
 *
 * [whyNoCamera]'s counterpart, and it earns its keep harder: a camera is a thing every phone has
 * and a depth scanner is not, so this is the ordinary answer on most devices rather than the
 * exception. It differs by platform and by hardware — a desktop has no sensor, an Android phone has
 * no implementation here yet, an iPhone without lidar can still track but sees far less — and each
 * has to say its own sentence.
 */
expect fun whyNoScanner(): String

/**
 * How many points a scan keeps at most.
 *
 * A sweep gathers a few thousand feature points a second, and a cross-section is built from a slice
 * a quarter of a metre thick — so the great majority of what is gathered is thrown away by the
 * slab test anyway. The cap is about the phone rather than about the arithmetic: a list this long
 * crosses from Objective-C into Kotlin as one allocation, and a surveyor's phone is cold, wet and
 * short of memory.
 *
 * Reaching it stops a scan *gathering*; it does not end the scan. There used to be a half-minute
 * cut-off as well, and taking that out on a surveyor's word would have quietly left this as its
 * replacement — a scan ending itself mid-sweep, for a reason nothing on the screen had mentioned.
 * A full scan says so and stays open, and the surveyor ends it.
 */
const val SCAN_POINT_LIMIT = 120_000
