package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * A browser cannot see the shape of a room.
 *
 * WebXR has a depth-sensing module and it is not this: it is offered to a session already running
 * on a headset or on an ARCore phone through Chrome, needs an immersive session and a permission
 * prompt, and is not implemented at all in the browser most cavers carry. Reporting it as absent is
 * the truthful answer on every browser this build actually runs in.
 *
 * Unlike the camera next door, there is no honest fallback to offer. A file input can stand in for
 * a camera because a photograph is a photograph however it was taken; nothing a browser can be
 * handed is a scan of the passage the surveyor is standing in.
 */
private object NoScanner : PassageScanner {

    override val available = false

    override fun scan() = Unit
}

@Composable
actual fun rememberPassageScanner(onScanned: (List<Coord3D>) -> Unit): PassageScanner = NoScanner

actual fun whyNoScanner(): String =
    "A browser cannot measure the shape of a passage. Scan on an iPhone or iPad with lidar, and " +
        "the cross-section it draws opens here like any other."
