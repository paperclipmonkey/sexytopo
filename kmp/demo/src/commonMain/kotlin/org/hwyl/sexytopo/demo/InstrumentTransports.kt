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
 * Whether a connection attempt needs to be nudged along from the host's run loop.
 *
 * [org.hwyl.sexytopo.shared.comms.GattSession] holds the timeout policy and has no clock of its
 * own, so something has to call it. On iOS that is this, driven from a Compose effect — which is
 * the plumbing `CoreBluetoothTransport.checkTimeout` was written to wait for, and without which an
 * instrument that is off, flat or out of range leaves the attempt hanging for ever with nothing on
 * screen to say why.
 */
expect fun tickTransport(transport: InstrumentTransport)
