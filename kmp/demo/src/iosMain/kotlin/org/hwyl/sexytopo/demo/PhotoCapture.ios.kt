package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.math.roundToInt

/**
 * The system camera, presented as a modal view controller.
 *
 * `UIImagePickerController` rather than `AVCaptureSession`, and rather than the
 * `PHPickerViewController` that replaced it. The photo *library* half of this class is deprecated
 * in favour of `PHPickerViewController`, which is what makes it look like the wrong choice — but
 * `PHPickerViewController` cannot take a photograph at all, only choose one that already exists,
 * and the camera source type carries no deprecation. `AVCaptureSession` would mean writing a
 * preview layer, a shutter button and an orientation policy by hand, all of which iOS is already
 * willing to draw.
 *
 * No authorisation is requested here, as none is in `DeviceHeading.ios.kt`. Presenting the picker
 * with the camera source type is itself what makes iOS ask, and a surveyor who says no gets the
 * system's own refusal panel rather than a screen this port would have to write. What that does
 * need is `NSCameraUsageDescription` in `Info.plist`: without it the process is killed on
 * presentation rather than refused, exactly as it is killed for a missing
 * `NSBluetoothAlwaysUsageDescription`. See the comment beside both keys there.
 *
 * ## Status: written on Linux, compiled by CI, never pointed at anything
 *
 * Like `CoreBluetoothTransport` and the compass beside it, this was authored without a Mac and is
 * fed to a compiler only by the macOS runner in `.github/workflows/kmp.yaml`. That checks the
 * selectors, the protocol conformances and the interop opt-ins; it cannot check a single pixel,
 * because CI's only iOS device is the simulator and the simulator has no camera. So
 * `PhotoCapture.available` is false on the very machine that proves this compiles, which is worth
 * remembering before reading a green build as evidence that a photograph was ever taken.
 *
 * What is unverified is everything past the shutter: whether the downscale below lands a portrait
 * photograph the right way up (see [encodeForStorage]), and how long re-encoding a
 * forty-eight-megapixel image takes on the main thread of a phone rather than in theory.
 *
 * The second of those stopped being a tidiness question. It is main-thread work; it now happens in
 * the dismissal's completion block rather than across the transition, which is what
 * [PhotoPickerDelegate] sets out; and if it proves long enough to notice it belongs on a
 * background queue with the bytes handed back to the main one. Worth measuring on a phone before
 * making, because UIKit drawing off the main thread is something this port has never done.
 */
@Composable
actual fun rememberPhotoCapture(onPhoto: (ByteArray) -> Unit): PhotoCapture {
    // The photograph arrives long after the tap that asked for it - the surveyor has been holding
    // the phone up at a passage wall meanwhile - so this keeps the delegate pointed at the current
    // callback rather than at whichever one happened to be passed when it was made. The same
    // reasoning as the poll in `PhotoCapture.wasmJs.kt`: the composition moves on and the camera
    // does not know it.
    val deliver = rememberUpdatedState(onPhoto)

    // Remembered, not made inside capture(): UIKit holds a picker's delegate weakly, exactly as
    // CLLocationManager holds its own in DeviceHeading.ios.kt. A delegate that only capture()'s
    // local referred to would be deallocated the moment that call returned, and the camera would
    // come up, take a photograph and have nothing left to hand it to - which looks like a broken
    // camera rather than like a missing reference.
    val delegate =
        remember {
            PhotoPickerDelegate { image -> encodeForStorage(image)?.let { deliver.value(it) } }
        }

    return remember(delegate) { UIKitPhotoCapture(delegate) }
}

/**
 * Empty on a phone, and a sentence on the simulator, which is the one place this genuinely has no
 * camera.
 *
 * Worth saying rather than leaving blank, because the simulator is where most people will first
 * meet this build: `isSourceTypeAvailable` answers false there, the button would sit inert, and
 * "the photograph button does nothing" is a bug report somebody would otherwise file.
 *
 * The wording is returned from here rather than declared in [Strings] because there is nothing
 * upstream to hold it to: the Android app has never taken photographs, so `strings.xml` has no
 * counterpart to mirror. That is exactly the case `Strings.local` exists to describe, and the
 * sentence differs by platform anyway, which one shared string could not.
 */
actual fun whyNoCamera(): String =
    if (cameraAvailable()) {
        ""
    } else {
        "This device has no camera. The iOS Simulator has none, and is not offered the Mac's own " +
            "webcam, so photographs can only be taken on a real iPhone or iPad."
    }

/**
 * The camera source type, named once.
 *
 * Once because this is the spelling most likely to be wrong. Kotlin/Native exposes some
 * Objective-C `NS_ENUM`s as enum classes and others as bare top-level constants —
 * `UIDeviceOrientation` next door in `DeviceHeading.ios.kt` is the first,
 * `CBManagerStatePoweredOn` in `CoreBluetoothTransport` is the second — and which one you get is
 * invisible until a macOS runner says so. Both UIKit enums this port already uses came out as
 * enum classes, so that is the bet here; if it turns out to be a constant, this is the single line
 * to change rather than the two places that read it.
 */
private val CAMERA_SOURCE =
    UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera

/** Whether iOS will show a camera at all. False on the simulator, and on very little else. */
private fun cameraAvailable(): Boolean =
    UIImagePickerController.isSourceTypeAvailable(CAMERA_SOURCE)

private class UIKitPhotoCapture(private val delegate: PhotoPickerDelegate) : PhotoCapture {

    /**
     * Asked once. Whether a device has a camera cannot change while the app is running, and the
     * screen that shows this reads it on every recomposition.
     */
    override val available: Boolean = cameraAvailable()

    override fun capture() {
        if (!available) return
        val host = topmostViewController() ?: return

        val picker = UIImagePickerController()
        picker.sourceType = CAMERA_SOURCE
        // False so that the photograph comes back under UIImagePickerControllerOriginalImage. With
        // editing on, iOS puts a square crop box in front of the surveyor and delivers the crop
        // under a different key, and a photograph of a passage wants neither.
        picker.allowsEditing = false
        picker.delegate = delegate
        host.presentViewController(picker, true, null)
    }
}

/**
 * Handed the photograph, or nothing at all when the surveyor backs out.
 *
 * Both protocols, and not one of them: `UIImagePickerController.delegate` is declared in
 * Objective-C as `id<UINavigationControllerDelegate, UIImagePickerControllerDelegate>`, an
 * intersection Kotlin cannot express — cinterop has to type the property as one of the two, and
 * conforming to both is what makes the assignment in `UIKitPhotoCapture.capture` compile whichever
 * one it picked. A named class rather than an anonymous object, for the reason
 * `CoreBluetoothTransport` sets out at length: an Objective-C delegate must be an `NSObject` as
 * well as conform to the protocol, and Kotlin refuses to infer a type with two supertypes for a
 * non-local declaration.
 *
 * The two selectors overridden here have different arities, so unlike the four in
 * `CoreBluetoothTransport` there is no `@ObjCSignatureOverride` collision to reason about. The
 * deprecated `imagePickerController:didFinishPickingImage:editingInfo:` takes three parameters and
 * the one used here takes two, which is enough to keep them apart.
 *
 * ## The encoding waits for the camera to finish leaving, and that is not a preference
 *
 * This used to dismiss the picker and then encode the photograph immediately, on the reasoning
 * that the re-encode runs on the main thread and leaving the camera up while it ran would read as
 * the shutter having stuck. Reasonable, and a good way to wedge an iOS app.
 *
 * `dismissViewControllerAnimated` starts an animation and returns straight away. Blocking the main
 * thread with a several-hundred-millisecond re-encode while that transition is still in flight can
 * leave it never completing, and what is then left on the window is the transition's own
 * full-screen view: invisible, on top of everything, and eating every touch. The app looks
 * perfectly normal and answers nothing, which is what a phone reported — every button dead, and a
 * restart the only way out of it.
 *
 * So the work goes in the completion block, where UIKit has finished and there is no transition
 * left to interrupt. The scanner next door already did it that way, which is the strongest thing
 * that can be said for it: two modals in this port, one written each way, and the one that put its
 * work in the completion block is the one nobody has had to restart the app over.
 *
 * The photograph is taken out of the info dictionary *before* the dismissal rather than inside the
 * completion, because by then the picker is being torn down and the dictionary is the picker's.
 */
@OptIn(BetaInteropApi::class)
private class PhotoPickerDelegate(private val onImage: (UIImage) -> Unit) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true) { image?.let(onImage) }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        // Nothing is reported. Backing out of the camera is not a failure, and the sketch should
        // look exactly as it did before the button was pressed.
        picker.dismissViewControllerAnimated(true, null)
    }
}

/**
 * The view controller a modal can actually be presented from.
 *
 * Not simply the root, which is where `MainViewController`'s Compose host sits. UIKit refuses to
 * present on a controller that is already presenting something — it logs the attempt and does
 * nothing, so the camera silently never appears — and Compose Multiplatform presents controllers
 * of its own for things like the text-selection menu. Walking the chain costs two lines and removes
 * a whole class of "the button did nothing".
 *
 * Shared with the passage scanner next door, which needs exactly this and would otherwise carry a
 * worse copy of it: walking the presented chain is what makes a modal open over whatever is already
 * on screen rather than under it.
 *
 * `keyWindow` is deprecated from iOS 13 in favour of asking the scene, and is still right here:
 * this app declares `UIApplicationSupportsMultipleScenes` false in `Info.plist`, so there is
 * exactly one window and the scene walk would have nothing to choose between. The alternative costs
 * `UIWindowScene` and `connectedScenes`, which is more interop surface than a Linux machine should
 * be writing blind for an answer that cannot differ.
 */
internal fun topmostViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (true) {
        controller = controller.presentedViewController ?: return controller
    }
}

/**
 * Shrinks the photograph to [PHOTO_LONGEST_EDGE] and re-encodes it as JPEG, or null if the drawing
 * context could not be had.
 *
 * Null rather than the original on failure, deliberately. Handing back the untouched `UIImage`'s
 * own bytes would satisfy the type and break the contract in `PhotoCapture.kt`: a photograph is
 * meant to travel with the survey to a browser build whose whole `localStorage` is five megabytes,
 * so a full-resolution one reaching the callback is worse than no photograph reaching it.
 *
 * `UIGraphicsBeginImageContextWithOptions` with an explicit scale of 1, and not
 * `UIGraphicsImageRenderer`. The renderer looks like the modern answer and is a trap here: its
 * default format takes its scale from the screen, so asking for 1280 points on a 3x iPhone renders
 * 3840 pixels and the file this exists to shrink comes back three times too big each way. The size
 * passed to the older context is in points too, but a scale of 1 pins a point to a pixel, which is
 * the only thing this function is trying to bound.
 *
 * Drawing through a context also normalises the orientation, which is the trap the browser build
 * meets from the other side. A phone photograph taken in portrait is stored landscape with an EXIF
 * tag saying which way to turn it; `UIImage.size` already reports the turned dimensions and
 * `drawInRect` draws it turned, so what comes out of the context is upright and needs no tag.
 * Encoding the original with `UIImageJPEGRepresentation` instead would have carried the tag along
 * and left every other platform to honour it. Unverified without a phone, which is why the path
 * that depends on nobody honouring anything is the one taken.
 *
 * The re-encode launders the format too. iPhones store HEIC by default; what arrives here is an
 * already-decoded `UIImage`, so JPEG comes out whatever went in and the sketch and the exporters
 * only ever have to know about one format.
 */
@OptIn(ExperimentalForeignApi::class)
private fun encodeForStorage(image: UIImage): ByteArray? {
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null

    val longest = maxOf(width, height)
    val factor = if (longest > PHOTO_LONGEST_EDGE) PHOTO_LONGEST_EDGE / longest else 1.0
    // At least one pixel each way: a context of zero width draws nothing, and rounding a very thin
    // panorama's short edge can reach zero. The same guard the browser build puts on its canvas.
    val targetWidth = (width * factor).roundToInt().coerceAtLeast(1).toDouble()
    val targetHeight = (height * factor).roundToInt().coerceAtLeast(1).toDouble()

    // Opaque, because a photograph has no transparency to preserve and JPEG could not carry it
    // anyway; the draw below covers every pixel of the context, so nothing uninitialised survives.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), true, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    val scaled = UIGraphicsGetImageFromCurrentImageContext()
    // Ended unconditionally, before any decision about what came back: an unbalanced image context
    // leaks its bitmap and leaves the next call drawing into this one.
    UIGraphicsEndImageContext()
    if (scaled == null) return null

    return UIImageJPEGRepresentation(scaled, PHOTO_JPEG_QUALITY)?.toPhotoBytes()
}

/**
 * The JPEG's bytes, copied out of the `NSData` rather than wrapped around it.
 *
 * Lifted from `DocumentsFileStore.readBytes` in `Storage.ios.kt`, which is the one piece of this
 * kind that a macOS runner has actually compiled and the iOS test suite actually runs. Copied and
 * not called: that method is a `FileStore` override that reads a path, and the shared module's own
 * `NSData.toByteArray` is `internal` to `:shared`, so neither is reachable from here.
 *
 * The copy is the point either way. The `ByteArray` outlives this function — it is on its way to a
 * file store and a sketch — and the `NSData`'s buffer is only guaranteed while the `NSData` is.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toPhotoBytes(): ByteArray? {
    val size = length.toInt()
    if (size == 0) return null
    val start = bytes?.reinterpret<ByteVar>() ?: return null
    return ByteArray(size) { index -> start[index] }
}
