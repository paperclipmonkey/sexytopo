package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * A desktop has no magnetometer, and a laptop that had one would be reporting which way the desk
 * faces. Null rather than zero, so the arrow is drawn pointing north-up as a label rather than
 * claiming to be tracking anything.
 *
 * This is also what the headless renderer and the Compose UI tests get, which is what keeps the
 * demo PNGs and the golden screenshots identical from one run to the next.
 */
@Composable
actual fun rememberDeviceHeading(enabled: Boolean): State<Float?> =
    remember { mutableStateOf<Float?>(null) }
