package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport

actual fun platformTransportFor(profile: InstrumentProfile): InstrumentTransport? =
    WebBluetoothTransport.createIfSupported(profile)

/**
 * The sentence that explains the whole shape of this project.
 *
 * Web Bluetooth exists in Chrome — including on Android — and has never existed in Safari, on any
 * platform. So on an iPhone the browser build can never hear from an instrument, which is why the
 * native iOS build is the one that matters and why typed readings are a first-class path rather
 * than a fallback. It is also GATT only: no browser speaks Bluetooth Classic, so the original
 * DistoX and DistoX2 are out of reach here exactly as they are on iOS.
 */
actual fun whyNoInstruments(): String =
    if (WebBluetoothTransport.isSupported()) {
        ""
    } else {
        "This browser has no Web Bluetooth. Safari has never had it, on any platform, so on an " +
            "iPhone use the native build or type readings in. Chrome, including on Android, does."
    }

actual fun tickTransport(transport: InstrumentTransport) {
    (transport as? WebBluetoothTransport)?.pump()
}
