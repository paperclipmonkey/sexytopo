package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import org.hwyl.sexytopo.shared.scan.WallScan

/**
 * The wall scanner: a full-screen camera on which the surveyor touches stations with the phone,
 * draws walls with an AR pen and stamps symbols on the rock, or an honest report that this device
 * has none.
 *
 * Shaped after [PassageScanner], for its reasons: the scan takes over the screen for as long as
 * the surveyor needs, and comes back through a callback when they tap Done.
 */
interface WallScanner {

    /** Whether this platform can scan at all. */
    val available: Boolean

    /**
     * Open the scanner.
     *
     * [stations] are the survey's station names, in the order the surveyor will meet them, for
     * the scanner's station picker. [startStation] is the one the surveyor is beside now, which
     * the picker starts on and which places a scan that touches no stations.
     */
    fun scan(stations: List<String>, startStation: String?)
}

/**
 * Remembers a wall scanner, delivering what it captured to [onScanned]. Everything in the
 * [WallScan] is in the scan's own frame; `WallScanApplier` places it.
 */
@Composable
expect fun rememberWallScanner(onScanned: (WallScan) -> Unit): WallScanner

/** The scanner for platforms without one, which is all of them but iOS. */
internal object NoWallScanner : WallScanner {

    override val available = false

    override fun scan(stations: List<String>, startStation: String?) = Unit
}
