package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport

/**
 * A radio for this platform, or null if it has none. The one thing that genuinely cannot be
 * shared: this is where a `CBCentralManager` or a `navigator.bluetooth` has to be reached for.
 */
expect fun platformTransportFor(profile: InstrumentProfile): InstrumentTransport?

/**
 * Why this platform cannot talk to an instrument, in words for a surveyor rather than a developer.
 *
 * Shown in place of the device list, because "no instruments found" would be a lie on a platform
 * that was never going to find one.
 */
expect fun whyNoInstruments(): String

/**
 * How connecting actually works here, which is not the same on two platforms.
 *
 * On iOS a BLE instrument is not a paired accessory: it does not appear in the iPhone's own
 * Bluetooth settings, there is no chooser, and nothing to pair — CoreBluetooth scans for the
 * advertised name and connects. On the web it *is* a chooser, because Web Bluetooth requires one:
 * a page may not enumerate devices, only ask the browser to offer some.
 */
expect fun howConnectingWorks(): String

/**
 * Whether a connection attempt needs to be nudged along from the host's run loop.
 *
 * [org.hwyl.sexytopo.shared.comms.GattSession] holds the timeout policy and has no clock of its
 * own — without this, an instrument that is off, flat or out of range leaves the attempt hanging
 * forever with nothing on screen to say why.
 */
expect fun tickTransport(transport: InstrumentTransport)
