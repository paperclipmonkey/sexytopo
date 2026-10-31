package org.hwyl.sexytopo.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/**
 * Android's own receiver, asked directly rather than through Play services.
 *
 * `LocationManager` and the GPS provider, not `FusedLocationProviderClient`. The fused provider is
 * the better answer in a town — it blends wifi and cell positions in, which is how a phone knows
 * where it is indoors — and it is the wrong one here twice over: what it blends in is exactly the
 * infrastructure a cave entrance has none of, and it lives in Google Play services, which this
 * port does not depend on and which a caver's de-Googled phone may not have at all. The plain
 * provider is in every Android there is and reports satellites and nothing else, which at a cave
 * entrance is all there is to report.
 *
 * ## The permission, and why it is asked for here
 *
 * `ACCESS_FINE_LOCATION` is a runtime permission, so it is declared in the manifest *and* asked
 * for. It is asked for on this screen rather than at launch because this screen is the only thing
 * in the app that wants it: a surveyor who never takes a position is never asked, which is the
 * whole reason the manifest could go so long without the line.
 *
 * Refusal is reported rather than retried. Android stops showing the dialog after two refusals and
 * there is nothing an app can do about that but say where the switch is.
 */
@Composable
actual fun rememberPosition(enabled: Boolean): State<Positioning> {
    val positioning = remember { mutableStateOf(Positioning()) }
    val view = LocalView.current
    val context = view.context

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
            if (!allowed) {
                positioning.value = Positioning(trouble = REFUSED)
            }
        }

    // Asked once, when the screen opens, and never again from here: the launcher's own callback
    // is what changes `granted`, and asking on every recomposition would be a dialog that will
    // not go away.
    LaunchedEffect(enabled, granted) {
        if (enabled && !granted) ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    DisposableEffect(enabled, granted) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (!enabled || !granted || manager == null) {
            if (enabled && manager == null) {
                positioning.value = Positioning(trouble = NO_RECEIVER)
            }
            return@DisposableEffect onDispose {}
        }

        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            positioning.value = Positioning(trouble = SWITCHED_OFF)
        }

        // Every method spelled out rather than left to a lambda, and this is not tidiness. Kotlin
        // would happily convert a lambda here, because the SDK this compiles against declares the
        // other three methods as defaults — they only became defaults in Android 11. On anything
        // older the interface the *device* loads has four abstract methods, and a class that
        // implements one of them throws `AbstractMethodError` the first time the receiver reports
        // a status change. Which is to say: on the older phones, in the field, and never here.
        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    positioning.value = Positioning(reading = readingOf(location))
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) {
                    positioning.value = Positioning(trouble = SWITCHED_OFF)
                }
            }

        // Every second and every metre: the readings are being watched settle, so the interesting
        // ones are the repeats from a phone that has not moved.
        val started =
            runCatching {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_MILLISECONDS,
                    UPDATE_METRES,
                    listener,
                )
            }.isSuccess

        if (!started) positioning.value = Positioning(trouble = NO_RECEIVER)

        onDispose {
            manager.removeUpdates(listener)
            positioning.value = Positioning()
        }
    }

    return positioning
}

/**
 * Nothing, because on Android the answer is never permanent.
 *
 * Every device that runs this has the API; what it may not have is a receiver switched on, a
 * permission granted, or a sky. All three can change while the screen is open and all three are
 * reported through [Positioning.trouble] instead, which is the difference between this and the
 * scanner — a desktop will never grow a lidar, and a phone in a pocket is one tap from a fix.
 */
actual fun whyNoPosition(): String = ""

/**
 * What Android reports, in the units everything above this expects.
 *
 * `hasAltitude` and `hasVerticalAccuracyMeters` are asked rather than assumed: a `Location` with
 * no altitude returns zero from `getAltitude`, which is sea level, and writing that into a survey
 * would put a Yorkshire entrance four hundred metres under the ground it is on.
 *
 * Vertical accuracy arrived in Android 8. Before that the height is reported with no error bar at
 * all rather than with a made-up one, and the export borrows the horizontal figure — which
 * understates it, and says so on `SurvexTherionWriter.georeference`.
 */
private fun readingOf(location: Location): GpsReading =
    GpsReading(
        latitude = location.latitude,
        longitude = location.longitude,
        altitude = if (location.hasAltitude()) location.altitude else null,
        horizontalAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        verticalAccuracy =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters.toDouble()
            } else {
                null
            },
    )

private const val UPDATE_MILLISECONDS = 1000L

private const val UPDATE_METRES = 0f

private const val REFUSED =
    "SexyTopo has not been allowed your location. Turn it on under Permissions in the app's " +
        "settings, or type the position in."

private const val NO_RECEIVER =
    "This device has no satellite receiver, so a position has to be typed in."

private const val SWITCHED_OFF =
    "Location is switched off on this device. Turn it on in Settings, or type the position in."
