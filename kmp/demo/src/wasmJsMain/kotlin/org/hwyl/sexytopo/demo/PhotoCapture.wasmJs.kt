package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A hidden `<input type="file">` accepting any image, with `capture` set to `environment` — which
 * is the only camera a web page has that does not want a permission prompt and a live video
 * element.
 *
 * (The `accept` value is spelled out in the script below rather than here: a Kotlin block comment
 * nests, so the obvious way of writing it opens a comment inside this one that never closes, and
 * the file stops compiling at its last line with no hint as to why.)
 *
 * The same trick as [pickSurveyFile] in `FilePicker.wasmJs.kt`, one attribute apart. `capture` is
 * what turns a chooser into a camera: given `environment` a phone browser opens the rear camera
 * straight away rather than offering the photo library, which is what a surveyor standing at a
 * station wants and saves them two taps in the wet. A desktop browser has no camera to honour it
 * with and ignores the attribute, so the same element is a plain image chooser there — the honest
 * fallback, and the reason [whyNoCamera] has nothing to say on any browser.
 *
 * ## Status: written without a phone to point at anything
 *
 * This compiles and the boundary-crossing below is exercised by the compiler, but nothing here has
 * met a real camera. What is unverified is everything the phone decides: whether iOS Safari opens
 * the camera directly or still shows its Take Photo / Photo Library sheet, whether a photograph
 * taken in portrait arrives the right way up (see the orientation note in [openCamera]), and how
 * long a large photograph takes to decode and redraw on a phone rather than a laptop.
 *
 * ## Getting the bytes back
 *
 * A Kotlin lambda cannot be passed into a `js("")` body — the Kotlin/Wasm bridge carries numbers,
 * strings and JS references, and there is nothing to hand the `FileReader`'s callback to call back
 * into. So the script parks the finished photograph on a global and this reads it back on a timer,
 * exactly as `DeviceHeading.wasmJs.kt` reads back the compass its listener parks. A base64 string
 * crosses the boundary as a plain string, and [BrowserFileStore] already decodes one of those with
 * `Base64.decode` for imported `.top` files, so it is a road that is already built.
 *
 * The poll runs for the life of the composition rather than only while a photograph is outstanding,
 * because there is no dependable signal that the surveyor backed out: Chrome fires a `cancel` event
 * on a file input that was dismissed, and Safari does not. Reading one global variable four times a
 * second is cheaper than getting that wrong, and a tenth of what the compass costs.
 */
@Composable
actual fun rememberPhotoCapture(onPhoto: (ByteArray) -> Unit): PhotoCapture {
    // The photograph arrives long after the tap that asked for it — the surveyor has been in the
    // camera app meanwhile — and the composition will have moved on. This keeps the loop pointed at
    // the current callback rather than the one that happened to be passed when it started.
    val deliver = rememberUpdatedState(onPhoto)

    LaunchedEffect(Unit) {
        while (true) {
            delay(PHOTO_POLL_MS)
            val encoded = takeCapturedPhoto()
            if (encoded.isNotEmpty()) {
                decodePhoto(encoded)?.let(deliver.value)
            }
        }
    }

    return remember { BrowserPhotoCapture }
}

/**
 * There is nothing to explain: every browser has this control, and on a desktop it is a file
 * chooser rather than a camera, which is a different thing but not a missing one.
 *
 * In particular there is no secure-context caveat to report here. That applies to `getUserMedia`,
 * which this deliberately does not use; a file input needs no permission and works over plain HTTP,
 * so unlike the compass in `DeviceHeading.wasmJs.kt` this cannot be broken by serving the build off
 * a bare IP address.
 *
 * Empty rather than absent because the screen that shows this has to be able to ask. Any wording
 * would be returned from here rather than added to [Strings], as [whyNoInstruments] returns its
 * sentence: there is nothing upstream to hold it to, the Android app having never had a camera.
 */
actual fun whyNoCamera(): String = ""

/**
 * Four reads a second, which is slower than the compass's ten and for a slacker deadline: the
 * surveyor has just spent several seconds in the camera app, so a quarter of a second before the
 * photograph appears on the sketch is not something anybody can perceive as a delay.
 */
private const val PHOTO_POLL_MS = 250L

private object BrowserPhotoCapture : PhotoCapture {

    /**
     * Always. A file input exists in every browser, and where there is no camera behind it the
     * chooser it opens instead is still a way of getting a photograph onto the sketch.
     */
    override val available: Boolean = true

    /**
     * The constants are handed to the script rather than written into it, as `Keyboard.wasmJs.kt`
     * hands over its decoy's id: a number copied into a string of JavaScript is one nothing will
     * ever notice drifting from the common declaration it is supposed to match.
     */
    override fun capture() {
        openCamera(PHOTO_LONGEST_EDGE, PHOTO_JPEG_QUALITY)
    }
}

/**
 * Opens the camera and, when a photograph comes back, shrinks it before Kotlin ever sees it.
 *
 * The shrinking is the point. This build keeps its files in `localStorage` — see [BrowserFileStore]
 * — which holds about five megabytes for the whole origin and holds them base64-encoded at a third
 * more again, so a single untouched phone photograph would fill it and a second would fail the
 * save. Drawing into a canvas at a bounded longest edge and re-encoding at [PHOTO_JPEG_QUALITY]
 * turns a twelve-megapixel photograph into something on the order of a hundred kilobytes, which is
 * the difference between a survey that can carry photographs and one that cannot.
 *
 * The re-encode launders the format too, which matters more here than on Android. An iPhone stores
 * HEIC; `canvas.toDataURL('image/jpeg', ...)` hands back JPEG whatever went in, so the sketch and
 * the exporter only ever have to know about one format.
 *
 * Decoding through an `<img>` rather than `createImageBitmap` is deliberate, and is the trap most
 * likely to be hit here. A phone photograph taken in portrait is very often stored landscape with
 * an EXIF orientation tag saying which way to turn it. CSS `image-orientation` defaults to
 * `from-image`, so an `<img>` reports the turned dimensions and `drawImage` draws it the right way
 * up (Chrome 81 and Safari 13.1 onwards); `createImageBitmap` defaults to ignoring EXIF entirely
 * and would silently store every portrait photograph on its side. Unverified without a phone, which
 * is exactly why the safer of the two is the one used.
 *
 * An object URL rather than a `FileReader`: only the pixels are wanted, so there is no reason to
 * pay for base64-ing the original into a string just to decode it again.
 */
private fun openCamera(edge: Int, quality: Double): Boolean =
    js(
        """{
          try {
            var input = document.createElement('input');
            input.type = 'file';
            input.accept = 'image/*';
            // Set rather than assigned: `capture` is reflected as a property on some browsers and
            // not others, and the attribute is what all of them read.
            input.setAttribute('capture', 'environment');
            input.style.display = 'none';
            input.addEventListener('change', function () {
              var file = input.files && input.files[0];
              if (!file) { input.remove(); return; }
              var url = URL.createObjectURL(file);
              var image = new Image();
              var done = function () {
                URL.revokeObjectURL(url);
                input.remove();
              };
              image.onload = function () {
                try {
                  var longest = Math.max(image.width, image.height);
                  var scale = longest > edge ? edge / longest : 1;
                  var canvas = document.createElement('canvas');
                  // At least one pixel each way: a canvas of zero width throws on toDataURL, and
                  // Math.round of a very thin panorama's short edge can reach zero.
                  canvas.width = Math.max(1, Math.round(image.width * scale));
                  canvas.height = Math.max(1, Math.round(image.height * scale));
                  var context = canvas.getContext('2d');
                  if (!context) { done(); return; }
                  context.drawImage(image, 0, 0, canvas.width, canvas.height);
                  // "data:image/jpeg;base64,<payload>" - only the payload crosses into Kotlin,
                  // the same slice FilePicker.wasmJs.kt takes off a readAsDataURL result.
                  var encoded = canvas.toDataURL('image/jpeg', quality);
                  window.__sexytopoPhoto = encoded.slice(encoded.indexOf(',') + 1);
                } catch (e) {
                  // Out of memory on a very large photograph, or a canvas the browser will not
                  // read back. Nothing is parked, so the sketch simply does not gain a photograph
                  // - the same silence FilePicker.wasmJs.kt leaves behind a failed import.
                }
                done();
              };
              image.onerror = function () { done(); };
              image.src = url;
            });
            document.body.appendChild(input);
            input.click();
            return true;
          } catch (e) {
            return false;
          }
        }""",
    )

/**
 * Takes the parked photograph, if there is one, and leaves the global empty behind it.
 *
 * Cleared on read so that one photograph is delivered once: the poll comes round again a quarter of
 * a second later and must not hand the same picture to the sketch twice.
 */
private fun takeCapturedPhoto(): String =
    js(
        """(function () {
            var parked = window.__sexytopoPhoto;
            window.__sexytopoPhoto = null;
            return typeof parked === 'string' ? parked : '';
        })()""",
    )

/**
 * Base64 in, JPEG bytes out, and null if the string was not base64 after all.
 *
 * Caught rather than thrown for the same reason [BrowserFileStore.readBytes] catches it: a
 * photograph that will not decode is a photograph the surveyor does not get, not a crash in the
 * middle of a survey.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun decodePhoto(encoded: String): ByteArray? =
    runCatching { Base64.decode(encoded) }.getOrNull()
