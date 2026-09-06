package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * CoreLocation, asked where it is rather than which way it is pointing.
 *
 * The other half of `DeviceHeading.ios.kt`, and the half that needs permission. A heading is the
 * magnetometer and tells nobody where the phone is; a position is the thing iOS puts behind a
 * prompt, and `NSLocationWhenInUseUsageDescription` in `Info.plist` is what lets that prompt
 * appear at all. Without the key the request is a no-op and no reading ever arrives, which is a
 * silence this cannot tell from a bad sky — hence `IosAssetsTest`, which holds the key in place.
 *
 * ## What it asks for, and what that costs
 *
 * `kCLLocationAccuracyBest` rather than the ten-metre or hundred-metre settings, because ten
 * metres is roughly what a cave entrance needs and asking for it would mean settling for worse.
 * The cost is the receiver running flat out, which is why nothing here starts until the position
 * screen is open and why closing it stops the manager rather than leaving it to a collector.
 *
 * `startUpdatingLocation` rather than `requestLocation`, which takes one reading and stops: the
 * whole method here is to stand still and watch the accuracy improve, and one reading out of a
 * cold receiver is the worst one there will be.
 *
 * ## Two things about what comes back
 *
 * A negative accuracy is iOS saying the number beside it is not a measurement. A negative
 * `verticalAccuracy` means the altitude is meaningless — common indoors and under trees — so it
 * becomes a null altitude here rather than a plausible-looking height nobody should use. A
 * negative `horizontalAccuracy` means the whole fix is invalid, and the reading is dropped.
 *
 * And the altitude that is used is `altitude`, which is metres above sea level, rather than
 * `ellipsoidalAltitude`, which is the other one iOS knows and is about fifty metres different in
 * Britain. `StationFix` sets out why that matters.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun rememberPosition(enabled: Boolean): State<Positioning> {
    val positioning = remember { mutableStateOf(Positioning()) }
    // Remembered rather than made inside the effect, exactly as the compass's delegate is:
    // CoreLocation holds its delegate weakly, and one only the effect's local referred to would
    // be collected while the manager was still expecting to call it.
    val delegate = remember { PositionDelegate { positioning.value = it } }

    DisposableEffect(enabled, delegate) {
        if (!enabled) {
            positioning.value = Positioning()
            return@DisposableEffect onDispose {}
        }

        val manager = CLLocationManager()
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        // Asked again here as well as before the scanner opens, because this is the screen that
        // actually needs it and a surveyor may never have opened the other one.
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()

        onDispose {
            manager.stopUpdatingLocation()
            manager.delegate = null
            positioning.value = Positioning()
        }
    }

    return positioning
}

/**
 * Every iPhone and iPad has a receiver, so there is nothing to say.
 *
 * The simulator has one too — it reports whatever location Xcode is set to simulate, which is
 * usually Apple's own campus. That is worth knowing when a scan taken at a desk comes back in
 * California, and it is not worth refusing to run over: a simulated position is a real answer
 * from the only receiver that machine has.
 */
actual fun whyNoPosition(): String = ""

/**
 * Hands each reading on, and turns what iOS refuses to do into something to say.
 *
 * A named `NSObject` subclass rather than an anonymous object, for the reason `HeadingDelegate`
 * next door is: an Objective-C delegate has to be an `NSObject` as well as conform to the
 * protocol, and it has to outlive the call that registered it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class PositionDelegate(private val onPositioning: (Positioning) -> Unit) :
    NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val horizontal = location.horizontalAccuracy
        // Negative is iOS's way of saying this is not a position at all.
        if (horizontal < 0) return

        val vertical = location.verticalAccuracy
        val (latitude, longitude) =
            location.coordinate.useContents { latitude to longitude }

        onPositioning(
            Positioning(
                reading =
                    GpsReading(
                        latitude = latitude,
                        longitude = longitude,
                        altitude = if (vertical < 0) null else location.altitude,
                        horizontalAccuracy = horizontal,
                        verticalAccuracy = if (vertical < 0) null else vertical,
                    ),
            ),
        )
    }

    /**
     * Failing is how iOS says the permission was refused, as well as how it says the sky is bad.
     *
     * The two are not told apart here on purpose. `CLError` distinguishes them, and reading the
     * code would mean naming an enum whose Kotlin spelling cannot be checked from this side of the
     * build — the trap `PassageScanner.ios.kt` fell into twice. What a surveyor does about either
     * is the same thing: go outside, and check Settings if that does not help.
     */
    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onPositioning(
            Positioning(
                trouble =
                    "No position yet. Step into the open, and check that SexyTopo is allowed " +
                        "your location in Settings.",
            ),
        )
    }
}
