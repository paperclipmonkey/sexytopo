package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport

actual fun platformTransportFor(profile: InstrumentProfile): InstrumentTransport? = null

actual fun whyNoInstruments(): String = "The desktop build has no Bluetooth. Use the simulated instrument, or type readings in."

actual fun tickTransport(transport: InstrumentTransport) = Unit

actual fun howConnectingWorks(): String = "The desktop build cannot connect to an instrument."
