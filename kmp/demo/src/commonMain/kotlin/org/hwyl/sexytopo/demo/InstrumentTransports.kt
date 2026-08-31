package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport

/**
 * A radio for this platform, or null if it has none.
 *
 * The one thing that genuinely cannot be shared. Every layer above it is common code — the GATT
 * lifecycle, the device profiles, the decoders, the acknowledgements — and this is where a
 * `CBCentralManager` or a `navigator.bluetooth` has to be reached for.
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
 * It used to be one sentence in `commonMain` - *"Your phone will ask which one to pair with"* -
 * and on iOS that is simply untrue, in a way that costs a surveyor an evening. A BLE instrument is
 * not a paired accessory: it does not appear in the iPhone's own Bluetooth settings, there is no
 * chooser, and nothing to pair. CoreBluetooth scans for the advertised name and connects. Somebody
 * with a BRIC4 switched on beside them, hunting for it in Settings because the app told them their
 * phone would ask, is looking for something that will never be there.
 *
 * On the web it *is* a chooser, because Web Bluetooth requires one: a page may not enumerate
 * devices, only ask the browser to offer some. So the two platforms need two sentences, and this
 * is the seam where a platform fact belongs.
 */
expect fun howConnectingWorks(): String

/**
 * Whether a connection attempt needs to be nudged along from the host's run loop.
 *
 * [org.hwyl.sexytopo.shared.comms.GattSession] holds the timeout policy and has no clock of its
 * own, so something has to call it. On iOS that is this, driven from a Compose effect — which is
 * the plumbing `CoreBluetoothTransport.checkTimeout` was written to wait for, and without which an
 * instrument that is off, flat or out of range leaves the attempt hanging for ever with nothing on
 * screen to say why.
 */
expect fun tickTransport(transport: InstrumentTransport)
