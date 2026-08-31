package org.hwyl.sexytopo.shared.comms

/**
 * The lifecycle of one attempt to talk to an instrument, as a state machine.
 *
 * [GattLink] answers "what is this characteristic for". This answers the harder question: given
 * that callbacks arrive out of order, late, twice, or after the surveyor has already given up, what
 * should happen next. Both live here rather than in a platform transport for the same reason — the
 * iOS transport cannot be compiled without a Mac, so logic left inside it is logic nobody can run.
 *
 * A review of the first draft of that transport found six defects, and every one of them was a
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
 * generation; a callback carrying an older one is from an attempt that has been abandoned and is
 * discarded. And every transition is checked against the phase, so nothing can be reported twice or
 * out of order.
 *
 * There is no clock here. The caller passes the time in, which keeps this testable without waiting
 * and keeps `commonMain` free of a platform dependency it does not otherwise need.
 */
class GattSession(
    val profile: InstrumentProfile,
    /**
     * How long to wait, from [start], for a usable link before giving up.
     *
     * Fifteen seconds is a judgement rather than a ported constant: long enough for a cold BLE
     * connect and a service discovery on a busy phone, short enough that a surveyor with a flat
     * instrument finds out while they still have the energy to care. The Android app leans on the
     * Nordic library's own retry policy here and has no single number to copy.
     */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /** Where the attempt has got to. */
    enum class Phase {
        /** Nothing running. The only phase from which [start] does anything. */
        IDLE,

        /** Waiting for the radio to be usable, then for the instrument to advertise. */
        SCANNING,

        /** Found it; waiting for the connection itself. */
        CONNECTING,

        /** Connected; walking the services for the characteristics the profile names. */
        DISCOVERING,

        /** Characteristics found; waiting for the notify subscriptions to be confirmed. */
        SUBSCRIBING,

        /** Everything the profile needs is present and subscribed. Measurements can arrive. */
        READY,

        /** Gave up. [failure] says why. */
        FAILED,
    }

    /** What the transport should do next, returned by each event. */
    enum class Action {
        /** Nothing. Usually because the event belongs to an abandoned attempt. */
        NONE,

        /** Begin scanning for peripherals. */
        SCAN,

        /** Connect to the peripheral just discovered. */
        CONNECT,

        /** Ask the peripheral for [GattLink.servicesToDiscover]. */
        DISCOVER_SERVICES,

        /** Tell the listener the instrument is usable. */
        REPORT_CONNECTED,

        /** Tell the listener it failed, with [failure]. */
        REPORT_FAILURE,

        /** Tear down the peripheral connection, then report the failure. */
        DISCONNECT_AND_REPORT_FAILURE,
    }

    val link = GattLink(profile)

    var phase: Phase = Phase.IDLE
        private set

    /** Why the session failed, when [phase] is [Phase.FAILED]. */
    var failure: String? = null
        private set

    /**
     * Which attempt is current.
     *
     * Bumped by every [start] and [stop]. A platform callback captures the generation it was
     * registered under and passes it back; anything older is from an attempt the surveyor has
     * abandoned, and acting on it is how a disconnected app reconnects itself.
     */
    var generation: Int = 0
        private set

    val isConnected: Boolean
        get() = phase == Phase.READY

    private var startedAtMillis: Long = 0

    // -------------------------------------------------------------------------------------
    // Driving it
    // -------------------------------------------------------------------------------------

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
        return Action.SCAN
    }

    /**
     * Give up, at the surveyor's request. Always safe to call, whatever the phase.
     *
     * Bumping the generation here is what stops a callback that was already on its way from
     * reporting a connection to somebody who has just pressed disconnect.
     */
    fun stop() {
        generation++
        phase = Phase.IDLE
        failure = null
        link.reset()
    }

    /**
     * The radio's availability changed.
     *
     * The subtlety this exists for: an iOS `CBCentralManager` reports its state asynchronously, and
     * reports it *again* whenever Bluetooth is switched off and on. Treating every poweredOn as
     * "start scanning" means an app the surveyor disconnected reconnects itself the next time they
     * toggle Bluetooth. Only a session that is actually scanning acts on it.
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

    /** A peripheral advertised [advertisedName]. Returns [Action.CONNECT] if it is ours. */
    fun peripheralDiscovered(advertisedName: String?, generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.SCANNING) return Action.NONE
        if (!link.matches(advertisedName)) {
            noteSeen(advertisedName)
            return Action.NONE
        }
        phase = Phase.CONNECTING
        return Action.CONNECT
    }

    /**
     * Every named device the scan turned down, so the timeout can say what it saw.
     *
     * Bounded, and ordered by first sighting. A scan in a car park picks up phones, watches, tyre
     * sensors and somebody's headphones, and a failure message is worth reading only if it fits on
     * a phone screen.
     */
    private val declined = LinkedHashSet<String>()

    private fun noteSeen(advertisedName: String?) {
        val name = advertisedName?.trim().orEmpty()
        // Unnamed peripherals are most of a BLE scan and none of them can be identified by a
        // surveyor, so counting them would be noise where a name is signal.
        if (name.isEmpty() || declined.size >= MOST_NAMES_WORTH_REPORTING) return
        declined += name
    }

    /**
     * What the scan saw and turned down, and whether any of it is an instrument this app knows.
     *
     * The second half is the useful half. `InstrumentProfile.forAdvertisedName` is the same
     * matcher the connection screen uses, so if the surveyor has BRIC5 selected and a BRIC4 is
     * what is actually on the table, this says so by name instead of leaving them to guess.
     */
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

    /** A characteristic turned up. The transport acts on the returned role. */
    fun characteristicDiscovered(uuid: String, generation: Int): GattLink.Role {
        if (!isCurrent(generation) || phase != Phase.DISCOVERING) return GattLink.Role.IGNORED
        return link.discovered(uuid)
    }

    /**
     * Every service has reported its characteristics.
     *
     * This is where a device that is not what it claims to be gets rejected. Without it the
     * transport simply waits: no connection, no failure, no explanation.
     */
    fun serviceDiscoveryFinished(generation: Int): Action {
        if (!isCurrent(generation) || phase != Phase.DISCOVERING) return Action.NONE
        if (!link.hasFoundEverything) {
            return fail("instrument is missing ${link.missing.joinToString()}")
        }
        phase = Phase.SUBSCRIBING
        return Action.NONE
    }

    /**
     * A subscribe succeeded or failed.
     *
     * Reporting a connection when the characteristics were merely *found* was the subtlest of the
     * six: everything looks right, the surveyor sees "connected", and not one measurement ever
     * arrives, because the subscribe quietly failed.
     */
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

    /** The peripheral went away by itself — out of range, switched off, battery flat. */
    fun peripheralDisconnected(reason: String?, generation: Int): Boolean {
        if (!isCurrent(generation) || phase == Phase.IDLE) return false
        phase = Phase.IDLE
        link.reset()
        return true
    }

    /**
     * Called periodically. Returns [Action.DISCONNECT_AND_REPORT_FAILURE] once the attempt has
     * taken too long, so an instrument that is off or out of range says so instead of hanging.
     */
    fun tick(nowMillis: Long): Action {
        if (phase == Phase.IDLE || phase == Phase.READY || phase == Phase.FAILED) return Action.NONE
        if (nowMillis - startedAtMillis < timeoutMillis) return Action.NONE
        return fail(timeoutMessage())
    }

    private fun timeoutMessage(): String =
        when (phase) {
            // "Is it switched on and in range?" is the right question only when the answer might
            // be no. An instrument sitting on the table, switched on, advertising under a name
            // this app does not match — a renamed BRIC, a firmware that drops the underscore, the
            // wrong model selected — gives exactly the same silence, and sends the surveyor to
            // check the batteries on a device whose batteries are fine.
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
        /**
         * How many declined names a failure message will list.
         *
         * A scan in a car park sees phones, watches, headphones and tyre sensors. Six is enough
         * to spot the instrument that was there under the wrong name and few enough to read on a
         * phone, in a cave, by head torch.
         */
        const val MOST_NAMES_WORTH_REPORTING = 6

        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000
    }
}
