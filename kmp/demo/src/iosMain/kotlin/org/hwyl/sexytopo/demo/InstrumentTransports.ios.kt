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
