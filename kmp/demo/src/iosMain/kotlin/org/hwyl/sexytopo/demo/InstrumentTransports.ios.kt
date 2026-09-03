package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.ios.CoreBluetoothTransport

/**
 * CoreBluetooth, which is the only platform in this port with a real radio behind it.
 *
 * `NSBluetoothAlwaysUsageDescription` is in `Info.plist`; without it, merely constructing a
 * `CBCentralManager` raises `NSInternalInconsistencyException` and the app dies on the spot.
 */
actual fun platformTransportFor(profile: InstrumentProfile): InstrumentTransport? =
    CoreBluetoothTransport(profile)

actual fun whyNoInstruments(): String = ""

/**
 * The run loop `CoreBluetoothTransport.checkTimeout` was written to wait for.
 *
 * Its own comment explains why it does not schedule an `NSTimer` itself: an unbalanced timer keeps
 * the radio awake, which is a worse failure than the one it fixes. Compose already has a ticking
 * effect while the instrument screen is open, so the timeout runs there and stops when it closes.
 */
actual fun tickTransport(transport: InstrumentTransport) {
    (transport as? CoreBluetoothTransport)?.checkTimeout()
}


/**
 * The sentence somebody hunting through Settings for their instrument needs to read.
 *
 * A BLE peripheral is not a paired accessory. It does not appear under Settings > Bluetooth, there
 * is no chooser, and there is nothing to pair: the app scans for the advertised name in the
 * profile and connects to what answers. Saying so here is the difference between "the app is
 * broken" and "press Connect".
 */
actual fun howConnectingWorks(): String =
    "Which instrument have you got? The app looks for one broadcasting that name and connects " +
        "to it. There is nothing to pair: a survey instrument never appears in the iPhone's own " +
        "Bluetooth settings, so do not go looking for it there."
