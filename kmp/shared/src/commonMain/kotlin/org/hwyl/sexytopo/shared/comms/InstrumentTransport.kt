package org.hwyl.sexytopo.shared.comms

/**
 * A link to an instrument, with no platform types in sight.
 *
 * Implementations are called from the main thread and must deliver every callback on it too;
 * nothing here is synchronised. That is a requirement rather than a convenience: a reading becomes
 * a survey edit, and deleting a station, undo and every other edit happen on the main thread
 * without locking. The Android app delivered readings on the instrument's own thread until 1.14.0,
 * and a reading landing mid-delete could join a station that had just been removed, after which
 * later readings vanished into it (upstream issue #171). Here `AndroidBleTransport` posts every
 * callback to the main looper, `CoreBluetoothTransport` gives its central manager the main queue,
 * and a browser has only the one thread.
 */
interface InstrumentTransport {

    /** Whether the link is currently usable. */
    val isConnected: Boolean

    /**
     * How far along the link is, which [isConnected] cannot express.
     *
     * The difference matters to whoever is chasing a lost instrument: a retry fired while an
     * attempt is still running is a retry thrown away, since every transport here refuses to start
     * a second attempt over the top of the first. It also matters on screen, where "connecting" and
     * "not connected" are different things to a surveyor waiting in the dark.
     *
     * The default suits a transport with nothing in between — the simulator, and the test fakes.
     */
    val linkState: LinkState
        get() = if (isConnected) LinkState.CONNECTED else LinkState.IDLE

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

fun interface TransportSubscription {
    fun cancel()
}

/**
 * Where a link has got to, in the four states anything above the transport needs to tell apart.
 *
 * Deliberately coarser than [GattSession.Phase]: scanning, connecting, discovering and subscribing
 * are all [CONNECTING] here, because nothing outside a transport should be making decisions out of
 * which GATT step is in progress.
 */
enum class LinkState {
    /** Nothing in flight: never started, or cleanly stopped. */
    IDLE,

    /** An attempt is running. Starting another would be thrown away. */
    CONNECTING,

    CONNECTED,

    /** An attempt finished badly. A fresh one may be started from here. */
    FAILED,
}

/**
 * Which logical stream a frame arrived on.
 *
 * Most instruments have exactly one inbound stream ([DEFAULT]). FCL genuinely has two, told apart
 * by characteristic UUID (`...c504` primary, `...c505` extended), so its decoder needs to know
 * which is which. BRIC4 also has three, and `Bric4Manager` notes in a comment that Android gives it
 * no way to tell them apart, so the Android driver cycles blindly through the roles.
 */
enum class FrameChannel {
    DEFAULT,
    PRIMARY,
    EXTENDED,
    TERTIARY,
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
