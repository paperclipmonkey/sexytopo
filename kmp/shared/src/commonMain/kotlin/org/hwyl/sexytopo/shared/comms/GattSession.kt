package org.hwyl.sexytopo.shared.comms

/**
 * The lifecycle of one attempt to talk to an instrument, as a state machine: given that callbacks
 * arrive out of order, late, twice, or after the surveyor has already given up, what should happen
 * next.
 *
 * A review of the first draft of the iOS transport found six defects, and every one of them was a
 * lifecycle question rather than a Bluetooth question:
 *
 *  - `connect()` called twice leaked a scanning central manager that `disconnect()` could not
 *    reach, so the phone went on scanning after the surveyor stopped;
 *  - a callback still in flight could report a connection *after* `disconnect()`;
 *  - Bluetooth being off emitted a failure, and then switching it back on silently re-scanned and
 *    reconnected to an instrument nobody had asked for;
 *  - connection was reported once the characteristics were *found*, not once they were
 *    *subscribed*, so a failed subscribe went unnoticed and the instrument recorded nothing;
 *  - a device missing a required characteristic produced no failure at all, just silence;
 *  - and nothing ever timed out, so an instrument that was off, flat or out of range left the app
 *    waiting for ever with no way to tell the surveyor why.
 *
 * The two ideas that make those go away are [generation] and [phase]. Every [start] opens a new
 * generation; a callback carrying an older one is discarded. Every transition is checked against
 * the phase, so nothing can be reported twice or out of order.
 */
class GattSession(
    val profile: InstrumentProfile,
    /**
     * Fifteen seconds is a judgement rather than a ported constant: long enough for a cold BLE
     * connect and service discovery, short enough for a surveyor to find out something is wrong.
     */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /** Where the attempt has got to. */
    enum class Phase {
        /** The only phase from which [start] does anything. */
        IDLE,
        SCANNING,
        CONNECTING,
        DISCOVERING,
        SUBSCRIBING,
        READY,
        FAILED,
    }

    /** What the transport should do next, returned by each event. */
    enum class Action {
        /** Usually because the event belongs to an abandoned attempt. */
        NONE,
        SCAN,
        CONNECT,
        DISCOVER_SERVICES,
        REPORT_CONNECTED,
        REPORT_FAILURE,
        DISCONNECT_AND_REPORT_FAILURE,
    }

    val link = GattLink(profile)

    var phase: Phase = Phase.IDLE
        private set

    /** Why the session failed, when [phase] is [Phase.FAILED]. */
    var failure: String? = null
        private set

    /** Bumped by every [start] and [stop]. */
    var generation: Int = 0
        private set

    val isConnected: Boolean
        get() = phase == Phase.READY

    /** [phase] as the four states anything above a transport is allowed to reason about. */
    val linkState: LinkState
        get() =
            when (phase) {
                Phase.IDLE -> LinkState.IDLE
                Phase.READY -> LinkState.CONNECTED
                Phase.FAILED -> LinkState.FAILED
                Phase.SCANNING, Phase.CONNECTING, Phase.DISCOVERING, Phase.SUBSCRIBING ->
                    LinkState.CONNECTING
            }

    private var startedAtMillis: Long = 0

    /**
     * Begin an attempt. Idempotent: calling it while one is already running does nothing and
     * returns [Action.NONE], rather than starting a second scan nothing can later stop.
     */
    fun start(nowMillis: Long): Action {
        if (phase != Phase.IDLE && phase != Phase.FAILED) return Action.NONE
        generation++
        phase = Phase.SCANNING
        failure = null
        startedAtMillis = nowMillis
        link.reset()
        // Otherwise the timeout message names devices the radio saw half an hour ago, and once
        // MOST_NAMES_WORTH_REPORTING of them have accumulated it can never name a new one.
        declined.clear()
        return Action.SCAN
    }

    /**
     * Take up a peripheral the platform already knows about, skipping the scan.
     *
     * Both platforms can hand back a peripheral without discovering it: iOS by
     * `retrieveConnectedPeripheralsWithServices` or `retrievePeripheralsWithIdentifiers`, and the
     * first of those is the way out of a genuinely stuck app. A BLE peripheral that is still
     * connected at the system level does not advertise, so a scan cannot find it however long it
     * runs - which is why an instrument that dropped out of the *app* while iOS still held the
     * connection could only be reached again by killing the app, and why the surveyor who reported
     * this was closing and reopening SexyTopo at every station.
     *
     * Only from [Phase.SCANNING], which is where [start] leaves the session: this is an
     * alternative to scanning, not a way in from anywhere.
     */
    fun knownPeripheralOffered(generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.SCANNING) return Action.NONE
        phase = Phase.CONNECTING
        return Action.CONNECT
    }

    /** Always safe to call, whatever the phase. */
    fun stop() {
        generation++
        phase = Phase.IDLE
        failure = null
        link.reset()
    }

    /**
     * An iOS `CBCentralManager` reports its state again whenever Bluetooth is switched off and on;
     * only a session that is actually scanning acts on it, so a disconnected app does not reconnect
     * itself on the next toggle.
     */
    fun radioStateChanged(poweredOn: Boolean, description: String, generation: Int): Action {
        if (!isCurrent(generation)) return Action.NONE
        if (poweredOn) {
            return if (phase == Phase.SCANNING) Action.SCAN else Action.NONE
        }
        // Off, unauthorised or resetting. Only worth reporting if we were trying to use it.
        if (phase == Phase.IDLE) return Action.NONE
        return fail("bluetooth unavailable ($description)")
    }

    fun peripheralDiscovered(advertisedName: String?, generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.SCANNING) return Action.NONE
        if (!link.matches(advertisedName)) {
            noteSeen(advertisedName)
            return Action.NONE
        }
        return knownPeripheralOffered(generation)
    }

    /**
     * Every named device the scan turned down, so the timeout can say what it saw.
     *
     * Bounded, and ordered by first sighting.
     */
    private val declined = LinkedHashSet<String>()

    private fun noteSeen(advertisedName: String?) {
        val name = advertisedName?.trim().orEmpty()
        if (name.isEmpty() || declined.size >= MOST_NAMES_WORTH_REPORTING) return
        declined += name
    }

    /** What the scan saw and turned down, and whether any of it is an instrument this app knows. */
    internal fun whatElseWasSeen(): String? {
        if (declined.isEmpty()) return null
        val recognised =
            declined.mapNotNull { name ->
                InstrumentProfile.forAdvertisedName(name)?.let { "$name (a ${it.name})" }
            }
        return if (recognised.isNotEmpty()) {
            "saw " + recognised.joinToString() + " instead"
        } else {
            "saw " + declined.joinToString() + ", none of which is an instrument this app knows"
        }
    }

    fun peripheralConnected(generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.CONNECTING) return Action.NONE
        phase = Phase.DISCOVERING
        return Action.DISCOVER_SERVICES
    }

    fun connectionFailed(reason: String?, generation: Int): Action {
        if (!isCurrent(generation) || phase == Phase.IDLE) return Action.NONE
        return fail(reason ?: "failed to connect")
    }

    fun characteristicDiscovered(uuid: String, generation: Int): GattLink.Role {
        if (!isCurrent(generation) || phase != Phase.DISCOVERING) return GattLink.Role.IGNORED
        return link.discovered(uuid)
    }

    /** Where a device that is not what it claims to be gets rejected. */
    fun serviceDiscoveryFinished(generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.DISCOVERING) return Action.NONE
        if (!link.hasFoundEverything) {
            return fail("instrument is missing ${link.missing.joinToString()}")
        }
        phase = Phase.SUBSCRIBING
        return Action.NONE
    }

    fun subscriptionConfirmed(uuid: String, error: String?, generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.SUBSCRIBING) return Action.NONE
        if (error != null) {
            return fail("could not subscribe to $uuid: $error")
        }
        link.subscribed(uuid)
        if (!link.isReady) return Action.NONE
        phase = Phase.READY
        return Action.REPORT_CONNECTED
    }

    fun peripheralDisconnected(reason: String?, generation: Int): Boolean {
        if (!isCurrent(generation) || phase == Phase.IDLE) return false
        phase = Phase.IDLE
        link.reset()
        return true
    }

    fun tick(nowMillis: Long): Action {
        if (phase == Phase.IDLE || phase == Phase.READY || phase == Phase.FAILED) return Action.NONE
        if (nowMillis - startedAtMillis < timeoutMillis) return Action.NONE
        return fail(timeoutMessage())
    }

    private fun timeoutMessage(): String =
        when (phase) {
            Phase.SCANNING ->
                listOfNotNull(
                    "no ${profile.name} found",
                    whatElseWasSeen(),
                    if (declined.isEmpty()) "is it switched on and in range?" else null,
                ).joinToString(" - ")
            Phase.CONNECTING -> "${profile.name} found but would not connect"
            else -> "${profile.name} connected but did not finish setting up"
        }

    private fun fail(reason: String): Action {
        val wasConnecting = phase != Phase.SCANNING
        phase = Phase.FAILED
        failure = reason
        link.reset()
        return if (wasConnecting) {
            Action.DISCONNECT_AND_REPORT_FAILURE
        } else {
            Action.REPORT_FAILURE
        }
    }

    private fun isCurrent(generation: Int): Boolean = generation == this.generation

    companion object {
        /** Enough to spot the instrument under the wrong name, few enough to read on a phone. */
        const val MOST_NAMES_WORTH_REPORTING = 6

        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000
    }
}
