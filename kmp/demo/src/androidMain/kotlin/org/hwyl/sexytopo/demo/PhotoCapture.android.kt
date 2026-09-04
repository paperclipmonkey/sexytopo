package org.hwyl.sexytopo.demo

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The camera app, asked for a full-size photograph written to a file this app owns.
 *
 * `ActivityResultContracts.TakePicture` and not `TakePicturePreview`. The preview contract is the
 * shorter road — no file, no provider, a `Bitmap` handed straight back — and it hands back the
 * thumbnail the camera app puts in the result `Intent`, a few hundred pixels across because a
 * binder transaction cannot carry more. A thumbnail of a passage is not a survey record: the
 * photograph is there to show what the rift actually looks like at station 4, and at that size
 * nobody can tell one boulder from another. So the file route, which is the only one that gets the
 * real image.
 *
 * The registration is why this is a composable at all. `rememberLauncherForActivityResult` has to
 * run while the composition is being built — registering an activity result after the host activity
 * has started throws — so the launcher is remembered up front and pressing the button only calls
 * [PhotoCapture.capture].
 *
 * ## Status: written with no Android SDK, and no phone at the end of it
 *
 * Authored in a container with no Android SDK in it. The Kotlin below has been type-checked, but
 * against hand-written stubs of the platform classes it calls rather than against the real ones —
 * which proves the inference and the smart casts and proves nothing at all about whether the
 * signatures match. CI's `:androidApp:assembleDebug` is the first thing to compile it against a
 * genuine `android.jar`.
 *
 * Beyond compiling, everything the camera app decides is unverified: whether it honours the
 * `EXTRA_OUTPUT` uri rather than quietly returning a thumbnail (some manufacturer cameras have
 * done both over the years), whether a portrait photograph arrives rotated in the pixels or only in
 * the EXIF tag [uprightDegrees] reads, and how long the decode below takes on the sort of phone
 * somebody is willing to drop down a pitch.
 */
@Composable
actual fun rememberPhotoCapture(onPhoto: (ByteArray) -> Unit): PhotoCapture {
    val context = AndroidHost.appContext

    // The photograph comes back long after the tap that asked for it, and on Android it may come
    // back to a different composition entirely: the camera app is a foreground activity, and this
    // one can be killed behind it and rebuilt from its saved state when the surveyor comes back.
    // The launcher is re-registered by that rebuild and the result is redelivered to it, so this
    // keeps the delivery pointed at whatever callback the current composition passed rather than
    // at the one that happened to be passed when the camera opened.
    val deliver = rememberUpdatedState(onPhoto)

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val file = context?.let(::photoFile)
            if (taken && file != null) {
                // On the main thread, deliberately. The surveyor has just come back from the
                // camera app and the screen is being rebuilt anyway, and shrinking is bounded by
                // the sampling in [shrinkToJpeg] rather than by the size of the photograph. A
                // background dispatcher would buy a frame or two at the cost of a photograph that
                // can arrive after the sketch it belongs to has gone.
                shrinkToJpeg(file)?.let(deliver.value)
            }
            // Whether or not it worked. The cache holds a full-resolution photograph until this
            // runs, which is the largest single file this app ever writes.
            file?.delete()
        }

    return remember(context, launcher) {
        if (context == null) NoCamera else CameraAppCapture(context, launcher)
    }
}

/**
 * Empty on any phone, which is nearly always the answer here.
 *
 * The wording is typed out rather than taken from [Strings] because there is nothing upstream to
 * hold it to: the Android app has never taken photographs, so `strings.xml` has no counterpart to
 * mirror and the check in `AndroidStringsTest` would have nothing to check against. See the expect
 * declaration.
 *
 * A missing [AndroidHost] context is reported as a missing camera too. It is a different fault —
 * the host forgot to call `attach` — but it is not a difference a surveyor can act on, and the two
 * are the same sentence to whoever is reading the screen.
 */
actual fun whyNoCamera(): String {
    val camera =
        AndroidHost.appContext?.packageManager?.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    return if (camera == true) {
        ""
    } else {
        "This device has no camera, so there is nothing for the button to open."
    }
}

/** What a camera-less tablet gets, and what a host that never attached its context gets. */
private object NoCamera : PhotoCapture {

    override val available = false

    override fun capture() = Unit
}

private class CameraAppCapture(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Uri>,
) : PhotoCapture {

    /**
     * Hardware, asked of the package manager, rather than "is there an app that answers
     * `ACTION_IMAGE_CAPTURE`". The second question is the better one and cannot be asked: since
     * Android 11 `resolveActivity` returns null for any package this app has not declared a
     * `queries` element for, so asking it would report no camera on every modern phone and hide
     * the button on all of them. Launching an implicit intent is not filtered that way, so the
     * launch below still reaches the camera app; only the question about it is blocked.
     *
     * `FEATURE_CAMERA_ANY` rather than `FEATURE_CAMERA`, so a tablet with only a front camera
     * still counts. It is a worse photograph of a passage than the rear camera would take, and it
     * is a photograph.
     */
    override val available: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            photoUri(context) != null

    override fun capture() {
        val file = photoFile(context)
        // The camera app opens the file for writing through the provider, which creates the file
        // but will not create the directory above it.
        file.parentFile?.mkdirs()
        // And whatever was left there. The result only says whether the camera app thinks it
        // succeeded; a stale photograph from a capture whose process died before it could be read
        // would otherwise be delivered a second time, as this one's.
        file.delete()
        val uri = photoUri(context) ?: return
        // A camera app that is uninstalled between this object being remembered and the button
        // being pressed throws ActivityNotFoundException. A survey is not worth losing over a
        // photograph that did not happen.
        runCatching { launcher.launch(uri) }
    }
}

/**
 * One fixed name, overwritten each time, in a cache subdirectory of this app's own.
 *
 * Fixed rather than timestamped so that the launch and the result agree on which file they mean
 * without either having to remember it across a process death — and so that a delete that never
 * ran, because the process died in the camera app, leaves one stale photograph behind rather than
 * a growing pile of them.
 */
private fun photoFile(context: Context): File =
    File(File(context.cacheDir, PHOTO_DIRECTORY), PHOTO_FILE_NAME)

/**
 * A `content://` uri the camera app is allowed to write to, or null if the host app has not
 * declared the provider this needs.
 *
 * The same arrangement `SurveyZipSharer` uses to hand a survey zip to another app, one path element
 * apart: a `FileProvider` declared in the manifest, a `cache-path` in `res/xml/file_paths.xml`
 * covering the subdirectory, and `getUriForFile` to translate. A plain `file://` uri would be
 * simpler and has thrown `FileUriExposedException` since Android 7 — handing another app a raw path
 * into this app's storage is exactly what that check exists to stop.
 *
 * The authority is built from the running package name because the manifest declares it as
 * `${applicationId}.fileprovider`, and the two must agree. Neither is a constant anybody can
 * shorten: provider authorities are unique across the device, so a demo that claimed the real
 * SexyTopo's `org.hwyl.sexytopo.fileprovider` could not be installed next to it — which is the one
 * thing the debug build's `applicationIdSuffix` exists to allow.
 *
 * Null rather than a throw: `getUriForFile` raises IllegalArgumentException when no declared path
 * covers the file, so a host app that forgot the provider gets a camera reported as unavailable
 * instead of a crash at the moment somebody presses the button.
 */
private fun photoUri(context: Context): Uri? =
    runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.$FILE_PROVIDER_SUFFIX",
            photoFile(context),
        )
    }.getOrNull()

/**
 * The photograph the camera app wrote, read back as JPEG bytes no longer than
 * [PHOTO_LONGEST_EDGE] on their longest side.
 *
 * Two decodes rather than one, which is the whole reason this is not four lines. A modern phone
 * camera writes twelve megapixels or more, and `BitmapFactory` decodes ARGB_8888: decoding one of
 * those in full costs around fifty megabytes of heap, which on the phone a caver is willing to
 * take underground is an OutOfMemoryError rather than a pause. So the first decode reads nothing
 * but the dimensions — `inJustDecodeBounds` allocates no pixels at all — and the second decodes
 * through [sampleSizeFor], which never holds more than about four times the pixels that survive.
 *
 * `inSampleSize` only halves, so it lands somewhere between one and two times the wanted edge and
 * [scaledToLongestEdge] finishes the job exactly. Sampling alone would leave photographs at
 * whatever power of two happened to fall out of the original's size, which is a different picture
 * size per phone.
 *
 * Null on anything that goes wrong. A photograph that will not decode is a photograph the surveyor
 * does not get, not a crash in the middle of a survey — the same silence `FilePicker.wasmJs.kt`
 * leaves behind a failed import.
 */
private fun shrinkToJpeg(file: File): ByteArray? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longest) }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return@runCatching null
        val upright = decoded.scaledToLongestEdge().turnedBy(uprightDegrees(file))

        val stream = ByteArrayOutputStream()
        // Bitmap.compress wants nought to a hundred; the shared constant is on the nought-to-one
        // scale the browser's toBlob takes, so it is scaled here rather than written twice.
        val quality = (PHOTO_JPEG_QUALITY * 100).roundToInt()
        val compressed = upright.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        // Freed here rather than left to the collector: below Android 8 a bitmap's pixels live in
        // the native heap, which the collector has no pressure from and will happily let fill.
        upright.recycle()
        if (compressed) stream.toByteArray() else null
    }.getOrNull()

/**
 * The largest power of two that still leaves the longest edge at or above [PHOTO_LONGEST_EDGE].
 *
 * At or above, not near: sampling below the wanted size and scaling back up would turn a sharp
 * photograph into a soft one to save memory that was already saved.
 *
 * A power of two because that is all `BitmapFactory` honours — it rounds anything else down to
 * one, silently, which is a full-resolution decode dressed as a sampled one.
 */
private fun sampleSizeFor(longestEdge: Int): Int {
    var sample = 1
    while (longestEdge / (sample * 2) >= PHOTO_LONGEST_EDGE) {
        sample *= 2
    }
    return sample
}

/** The photograph at [PHOTO_LONGEST_EDGE], or as it was if it was already smaller. */
private fun Bitmap.scaledToLongestEdge(): Bitmap {
    val longest = max(width, height)
    if (longest <= PHOTO_LONGEST_EDGE) return this
    val scale = PHOTO_LONGEST_EDGE.toFloat() / longest
    // At least one pixel each way: a very thin panorama's short edge can round to zero, and a
    // bitmap of zero width cannot be created.
    val scaled =
        Bitmap.createScaledBitmap(
            this,
            max(1, (width * scale).roundToInt()),
            max(1, (height * scale).roundToInt()),
            true,
        )
    if (scaled !== this) recycle()
    return scaled
}

/** The photograph turned, or as it was when there is nothing to turn. */
private fun Bitmap.turnedBy(degrees: Float): Bitmap {
    if (degrees == 0f) return this
    val matrix = Matrix().apply { postRotate(degrees) }
    val turned = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (turned !== this) recycle()
    return turned
}

/**
 * How far the photograph has to be turned to be the right way up, read from its EXIF.
 *
 * The trap the browser build hit from the other side. A phone held upright very often stores the
 * photograph landscape with an orientation tag saying which way to turn it; `BitmapFactory` reads
 * pixels and ignores that tag, and `Bitmap.compress` writes a JPEG with no EXIF in it at all. So
 * doing nothing here would not merely fail to rotate the photograph — it would throw away the
 * evidence that it needed rotating, and every portrait photograph would be filed on its side with
 * nothing left to say so.
 *
 * The EXIF specification's own numbers rather than `ExifInterface.ORIENTATION_ROTATE_90` and its
 * neighbours: those constants were only added to `android.media.ExifInterface` in API 24, and this
 * module's floor is 23. Naming them would compile against the current SDK and be a missing field
 * on the oldest phone this build supports.
 *
 * Only the three rotations are handled. The four mirrored orientations exist in the specification
 * and no phone camera produces them.
 */
private fun uprightDegrees(file: File): Float =
    runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)

/** Matches the `cache-path` in the host app's `res/xml/file_paths.xml`. */
private const val PHOTO_DIRECTORY = "photos"

private const val PHOTO_FILE_NAME = "capture.jpg"

/** Matches the authority the host app's manifest declares. */
private const val FILE_PROVIDER_SUFFIX = "fileprovider"
