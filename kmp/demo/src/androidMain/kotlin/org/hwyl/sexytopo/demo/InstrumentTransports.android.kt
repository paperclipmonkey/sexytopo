package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport

actual fun platformTransportFor(profile: InstrumentProfile): InstrumentTransport? = null

actual fun whyNoInstruments(): String = """This port has no Android Bluetooth driver yet. The real SexyTopo does — that is the app this one is a port of — so the work is a transport, not a protocol: everything above it is already here and tested."""

actual fun tickTransport(transport: InstrumentTransport) = Unit


/** Likewise: this port has no Android transport yet, so the list is never offered. */
actual fun howConnectingWorks(): String = "This port has no Android Bluetooth driver yet."
