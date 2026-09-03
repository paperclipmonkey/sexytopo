package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.android.AndroidBleTransport

/**
 * `android.bluetooth.le`, on the application context [AndroidHost] holds — `SurveyLibrary`'s file
 * store and the clipboard already depend on the same context being attached before either is
 * used, and this is no different.
 *
 * `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are declared in `AndroidManifest.xml` but not requested
 * at runtime anywhere in this demo yet — see [AndroidBleTransport] for what that means on Android
 * 12 and later.
 */
actual fun platformTransportFor(profile: InstrumentProfile): InstrumentTransport? {
    val context = AndroidHost.appContext ?: return null
    return AndroidBleTransport(context, profile)
}

actual fun whyNoInstruments(): String = ""

/**
 * The run loop [AndroidBleTransport.checkTimeout] was written to wait for — see
 * `CoreBluetoothTransport`'s own `tickTransport` for why this is not a `Handler.postDelayed` loop
 * owned by the transport itself: Compose already ticks while the instrument screen is open, so the
 * timeout runs there and stops when it closes.
 */
actual fun tickTransport(transport: InstrumentTransport) {
    (transport as? AndroidBleTransport)?.checkTimeout()
}

actual fun howConnectingWorks(): String =
    "Which instrument have you got? The app looks for one broadcasting that name and connects " +
        "to it. There is nothing to pair from this screen first: a survey instrument does not " +
        "need to appear in the phone's own Bluetooth settings before the app can find it."
