package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.darwin.NSObject

/**
 * CoreLocation's compass.
 *
 * `CLHeading.magneticHeading` rather than `trueHeading`, for two reasons that happen to agree. A
 * cave survey is booked in magnetic bearings — that is what the instrument in the surveyor's hand
 * reads, and what every leg in the file already holds — so a north arrow that pointed at true
 * north would disagree with the drawing it sits on. And true north needs a position fix to know
 * the local declination, which underground there will never be.
 *
 * No authorisation is asked for. Heading updates are not location updates: nothing here can say
 * where the phone is, only which way it is facing, and this deliberately does not put a location
 * permission prompt in front of somebody who turned on a compass rose. Should a future iOS decide
 * to gate the magnetometer behind one anyway, no heading arrives and the arrow keeps pointing
 * north-up, which is what it did before this existed.
 *
 * ## Status: compiles for iOS, has never seen a magnetometer
 *
 * Like `CoreBluetoothTransport`, this was written on Linux and is compiled by a macOS runner
 * rather than run on a phone. The simulator has no magnetometer, so what CI checks is that the
 * delegate's selector and the enum spellings are right, not that the arrow points north.
 */
@Composable
actual fun rememberDeviceHeading(enabled: Boolean): State<Float?> {
    val heading = remember { mutableStateOf<Float?>(null) }
    // Remembered, not made inside the effect: CoreLocation holds its delegate weakly, and a
    // delegate that only the effect's own local referred to would be collected while the manager
    // was still expecting to call it.
    val delegate = remember { HeadingDelegate { heading.value = it } }

    DisposableEffect(enabled, delegate) {
        if (!enabled || !CLLocationManager.headingAvailable()) {
            heading.value = null
            return@DisposableEffect onDispose {}
        }

        // `orientation` reads as unknown until something asks iOS to keep it up to date.
        UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        val manager = CLLocationManager()
        manager.delegate = delegate
        manager.startUpdatingHeading()

        onDispose {
            manager.stopUpdatingHeading()
            manager.delegate = null
            UIDevice.currentDevice.endGeneratingDeviceOrientationNotifications()
            heading.value = null
        }
    }

    return heading
}

/**
 * Turns each reading into a heading for the top of the screen and hands it on.
 *
 * A named class and not an anonymous object: an Objective-C delegate has to be an `NSObject` as
 * well as conform to the protocol, and an anonymous one cannot be held onto for as long as the
 * manager needs it. Subclassing `NSObject` from Kotlin is what the opt-in is for, the same one
 * `CoreBluetoothTransport` carries for its own delegates.
 */
@OptIn(BetaInteropApi::class)
private class HeadingDelegate(private val onHeading: (Float?) -> Unit) :
    NSObject(), CLLocationManagerDelegateProtocol {

    /**
     * The last quarter turn worth believing.
     *
     * A phone laid flat to read a compass reports its orientation as face-up, which says nothing
     * about which way round the picture is drawn. Holding the last real answer means putting the
     * phone down does not swing the arrow; the alternative is snapping back to portrait at exactly
     * the moment somebody is looking at it.
     */
    private var screenAngleDegrees: Float = 0f

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        val degrees = didUpdateHeading.magneticHeading
        // Negative is CoreLocation's way of saying the reading is not to be trusted yet — the
        // magnetometer is still settling, or is sitting in a magnetic field it cannot see past.
        if (degrees < 0.0) {
            onHeading(null)
            return
        }
        quarterTurnOf(UIDevice.currentDevice.orientation)?.let { screenAngleDegrees = it }
        onHeading(screenHeading(degrees.toFloat(), screenAngleDegrees))
    }
}

/**
 * How far the device has been turned anticlockwise from upright, or null when it is lying flat and
 * there is no answer.
 *
 * Landscape left is the home button on the right, which is the phone turned anticlockwise — the
 * same ninety degrees Android calls rotation 90, so the two platforms can share the correction.
 */
private fun quarterTurnOf(orientation: UIDeviceOrientation): Float? =
    when (orientation) {
        UIDeviceOrientation.UIDeviceOrientationPortrait -> 0f
        UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> 90f
        UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> 180f
        UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> 270f
        else -> null
    }
