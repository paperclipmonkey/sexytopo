package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable

/**
 * The desktop has no camera, for two reasons, and the second would stand even if the first were
 * solved. Compose Desktop has no camera API at all — Skia draws, it does not capture — so there is
 * nothing to call. And a laptop webcam is pointed at whoever is sitting at the desk: it would file
 * a picture of a face in a room as a record of a passage.
 *
 * So it is reported honestly rather than offered, as the desktop's missing vibrator and missing
 * magnetometer are, and a screen can say so instead of showing a button that opens nothing.
 *
 * [PHOTO_LONGEST_EDGE] and [PHOTO_JPEG_QUALITY] therefore never come up here. The rule that no
 * full-resolution photograph reaches the callback is kept the easy way: nothing reaches it at all.
 *
 * This is also what the headless PNG renderer and the Compose UI tests get, which is what keeps
 * the camera drawn in the same state from one run to the next.
 */
private object NoCamera : PhotoCapture {

    override val available = false

    override fun capture() = Unit
}

@Composable
actual fun rememberPhotoCapture(onPhoto: (ByteArray) -> Unit): PhotoCapture = NoCamera

/**
 * Wording rather than a mirrored resource: the Android app has no camera, so `strings.xml` has no
 * counterpart to hold this to. See the expect declaration.
 */
actual fun whyNoCamera(): String =
    "The desktop build has no camera. Take photographs on the phone build; a webcam is pointed " +
        "at the desk rather than at the passage."
