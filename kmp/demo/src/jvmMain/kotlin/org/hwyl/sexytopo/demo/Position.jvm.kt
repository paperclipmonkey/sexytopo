package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * A desktop has no satellite receiver, and would be indoors if it had one.
 *
 * The same answer the scanner and the camera give next door. It matters slightly less here than it
 * does for those two, because this is the one platform feature whose screen is still worth opening
 * without it: a position typed off a map is as good as a measured one and often better, and the
 * desk is where somebody has the map.
 *
 * This is also what the headless renderer and the Compose tests get, which is what keeps the
 * screen drawn in one state from run to run.
 */
@Composable
actual fun rememberPosition(enabled: Boolean): State<Positioning> =
    remember { mutableStateOf(Positioning(trouble = whyNoPosition())) }

actual fun whyNoPosition(): String =
    "This computer has no satellite receiver. Type the position in — off the map, or from a " +
        "phone that has one."
