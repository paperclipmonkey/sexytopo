package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable

/** A camera the surveyor can open, or an honest report that this device has none. */
interface PhotoCapture {

    /** Whether this platform can take a photograph at all. */
    val available: Boolean

    /** Open the camera. The photograph arrives at the callback given to rememberPhotoCapture. */
    fun capture()
}

/**
 * Remembers a way to take a photograph, delivering JPEG bytes to [onPhoto].
 *
 * Composable because Android's camera is an activity result, which can only be registered from a
 * composition. `ActivityResultContracts.TakePicture` goes through
 * `rememberLauncherForActivityResult`, and that registration has to happen while the composition
 * is being built rather than when the button is pressed: registering after the host activity has
 * started throws, which is a crash at the moment the surveyor asks for the camera rather than an
 * error at the moment somebody wrote the line. So the launcher is remembered up front and the
 * press only fires [PhotoCapture.capture].
 *
 * That is also why the photograph comes back through [onPhoto] rather than as a return value: an
 * activity result arrives on a later frame, long after whatever call opened the camera returned,
 * and on Android it may arrive after the process has been killed and rebuilt behind the camera app.
 *
 * Nothing here can enforce the downscaling below. [onPhoto] is fed by each platform's own actual,
 * and commonMain never sees an encoder, so a target that forgot would be caught by somebody's
 * storage filling up rather than by a shared test.
 */
@Composable
expect fun rememberPhotoCapture(onPhoto: (ByteArray) -> Unit): PhotoCapture

/**
 * Why the camera is missing, for a screen that has to say so. Empty where it is not.
 *
 * Shaped after [whyNoInstruments], and for the same reason: "no camera found" would be a lie on a
 * platform that was never going to find one.
 *
 * The wording is returned from here rather than declared in [Strings] because there is nothing
 * upstream to hold it to. The Android app has never taken photographs — the only camera anywhere
 * in its source is the viewpoint `SurveyRenderer` moves round the 3D view — so `strings.xml` has
 * no counterpart to mirror, which is exactly the case `Strings.local` exists to describe. It also
 * differs by platform, which one shared string could not: a desktop has no camera API, a browser
 * has one only in a secure context, and each has to say its own sentence.
 */
expect fun whyNoCamera(): String

/**
 * The longest edge a stored photograph is reduced to, and the JPEG quality it is re-encoded at.
 *
 * Not a nicety. The browser build keeps its files in localStorage, which holds about five
 * megabytes for the whole origin and holds them base64-encoded at a third more again, so one
 * untouched phone photograph would fill it. A cave photograph is a record of what a passage looks
 * like rather than a print, so this costs nothing anybody will miss.
 *
 * Every target downscales, not only the browser one. The stores differ but the survey does not: a
 * photograph taken on a phone is meant to travel with the survey to the desktop and back, and a
 * file that only fits on the platform it was taken on is not a record of anything.
 *
 * The quality is on the nought-to-one scale the browser's `canvas.toBlob` takes; Android's
 * `Bitmap.compress` wants nought to a hundred, so its actual scales this up.
 */
const val PHOTO_LONGEST_EDGE = 1280
const val PHOTO_JPEG_QUALITY = 0.72
