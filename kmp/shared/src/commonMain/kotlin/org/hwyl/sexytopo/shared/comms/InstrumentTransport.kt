package org.hwyl.sexytopo.shared.comms

/**
 * A link to an instrument, with no platform types in sight.
 *
 * The Android app fuses transport and protocol: `DistoXThread` owns an RFCOMM socket *and* parses
 * packets; each `*Manager` extends Nordic's `BleManager` *and* decodes characteristics. Splitting
 * them lets the decoders in this package be exercised byte-for-byte in commonTest, and lets an
 * Android `BleManager`, a CoreBluetooth `CBPeripheral` and [SimulatedInstrument] all feed the same
 * code.
 *
 * Implementations are expected to be called from a single thread (the platform's callback thread
 * in practice) and to deliver frames on that same thread; nothing here is synchronised.
 */
interface InstrumentTransport {

    /** Whether the link is currently usable. */
    val isConnected: Boolean

    /**
     * Asks the platform to open the link. Returns immediately; success or failure arrives as
     * [InstrumentTransportListener.onConnected] or [InstrumentTransportListener.onFailure].
     */
    fun connect()

    /** Asks the platform to close the link. [InstrumentTransportListener.onDisconnected] follows. */
    fun disconnect()

    /**
     * Writes one outbound frame exactly as given — this layer never adds framing of its own, so
     * callers pass whatever the instrument expects: a bare command byte for the classic DistoX and
     * for SAP6/FCL, or a `data:`-framed packet from
     * [org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming] for DistoX BLE and Cavway X1.
     */
    fun send(bytes: ByteArray)

    /**
     * Registers [listener] for connection changes and inbound frames. Cancel the returned
     * subscription to stop; cancelling twice is harmless.
     */
    fun observe(listener: InstrumentTransportListener): TransportSubscription
}

/** Handle returned by [InstrumentTransport.observe]. */
fun interface TransportSubscription {
    fun cancel()
}

/**
 * Which logical stream a frame arrived on.
 *
 * Most instruments have exactly one inbound stream ([DEFAULT]). FCL genuinely has two, told apart
 * by characteristic UUID (`...c504` primary, `...c505` extended), so its decoder needs to know
 * which is which. BRIC4 has three characteristics but — as `Bric4Manager` notes in a comment —
 * gives the client no way to tell them apart, so its frames are all [DEFAULT] and the decoder
 * cycles blindly through the three roles.
 */
enum class FrameChannel {
    DEFAULT,
    PRIMARY,
    EXTENDED,
}

/** Callbacks from an [InstrumentTransport]. Every method has a no-op default. */
interface InstrumentTransportListener {
    fun onConnected() {}

    fun onDisconnected(reason: String? = null) {}

    fun onFailure(reason: String) {}

    fun onFrame(channel: FrameChannel, bytes: ByteArray) {}
}

/**
 * Listener bookkeeping shared by every implementation, so a platform transport only has to supply
 * connect/disconnect/send and call the `emit*` helpers from its own callbacks.
 */
abstract class BaseInstrumentTransport : InstrumentTransport {

    private val listeners = mutableListOf<InstrumentTransportListener>()

    override fun observe(listener: InstrumentTransportListener): TransportSubscription {
        listeners += listener
        return TransportSubscription { listeners -= listener }
    }

    protected fun emitConnected() = forEachListener { it.onConnected() }

    protected fun emitDisconnected(reason: String? = null) = forEachListener { it.onDisconnected(reason) }

    protected fun emitFailure(reason: String) = forEachListener { it.onFailure(reason) }

    protected fun emitFrame(bytes: ByteArray, channel: FrameChannel = FrameChannel.DEFAULT) =
        forEachListener { it.onFrame(channel, bytes) }

    /** Iterates a snapshot, so a listener may unsubscribe from inside its own callback. */
    private inline fun forEachListener(action: (InstrumentTransportListener) -> Unit) {
        for (listener in listeners.toList()) action(listener)
    }
}
