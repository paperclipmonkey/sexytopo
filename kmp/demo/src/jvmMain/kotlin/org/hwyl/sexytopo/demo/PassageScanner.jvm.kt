package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * A desktop has no depth sensor, and would be pointed at a desk if it had one.
 *
 * The same answer [NoCamera] gives next door, for the same two reasons: there is no API to call,
 * and the thing it would measure is not a cave. This is also what the headless renderer and the
 * Compose tests get, which is what keeps the scan button drawn in one state from run to run.
 */
private object NoScanner : PassageScanner {

    override val available = false

    override fun scan() = Unit
}

@Composable
actual fun rememberPassageScanner(onScanned: (List<Coord3D>) -> Unit): PassageScanner = NoScanner

actual fun whyNoScanner(): String =
    "The desktop build has no depth scanner. Scan a passage on an iPhone or iPad with lidar; the " +
        "cross-section it draws can be edited here afterwards."
