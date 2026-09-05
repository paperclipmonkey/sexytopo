package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * Not written for Android yet, and saying so rather than pretending.
 *
 * There is a road: ARCore's Depth API gives a depth image on a large number of Android phones, and
 * a handful have a time-of-flight sensor that makes it a real measurement rather than an inference
 * from parallax. Every point of it would feed the same `PassageScan` the iOS half feeds — the
 * arithmetic is shared and tested and has nothing platform-specific in it, which is the whole
 * reason it lives in `shared`. What is missing is this file and a Play Services dependency.
 *
 * It is left undone deliberately rather than half-done. ARCore is a separate SDK with its own
 * install prompt and its own device list, and adding it to prove a point on a platform nobody has
 * asked for it on would cost the demo a dependency it does not otherwise need.
 */
private object NoScanner : PassageScanner {

    override val available = false

    override fun scan() = Unit
}

@Composable
actual fun rememberPassageScanner(onScanned: (List<Coord3D>) -> Unit): PassageScanner = NoScanner

actual fun whyNoScanner(): String =
    "Scanning a passage is not built for Android yet. ARCore's Depth API would feed the same " +
        "cross-section maths this app already uses; it is the sensor half that is missing."
