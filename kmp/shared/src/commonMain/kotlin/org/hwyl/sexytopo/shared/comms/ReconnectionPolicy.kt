package org.hwyl.sexytopo.shared.comms

/**
 * Whether to chase a lost instrument, and for how long. `pref_auto_reconnect` and
 * `pref_auto_reconnect_window`.
 *
 * **On** by default, which is where this port parts company with the Android app.
 *
 * Upstream ships it off, and the first field trip with this one said plainly why that is the wrong
 * default underground: a BLE link to a BRIC drops several times an hour, and a surveyor with cold
 * hands and a phone in a bag does not want to reopen the instrument screen at every station. The
 * setting still exists for anybody who would rather the radio stayed quiet, and the window is what
 * stops it chasing an instrument that has genuinely been left behind.
 */
data class AutoReconnect(
    val enabled: Boolean = DEFAULT_ENABLED,
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
) {
    val windowMillis: Long
        get() = windowMinutes.toLong() * 60_000L

    companion object {
        /** Upstream's `pref_auto_reconnect` defaults to false; see the note above. */
        const val DEFAULT_ENABLED = true

        /** `pref_auto_reconnect_window` defaults to 15. */
        const val DEFAULT_WINDOW_MINUTES = 15
    }
}

/**
 * Ported from `comms/ReconnectionPolicy`: whether to try the instrument again, and when.
 *
 * What it should not do is chase an instrument that has been left behind at the
 * last station, which is why the attempts stop after a window measured from the **first** failure
 * in a run rather than from the last.
 *
 * The Java class owns an Android `Handler` and posts the retry itself. Here the policy only
 * *decides* — it takes a clock and returns a [Decision] — and the scheduling belongs to whatever
 * is already ticking.
 *
 * The Java also carries a `retrying` flag so that its own retry, which goes through the same entry
 * point the surveyor's Connect button does, is not mistaken for the surveyor asking again. Nothing
 * here needs it, because the two have separate entry points: [noteUserRequestedConnect] is only
 * called from the button.
 */
class ReconnectionPolicy(
    private val settings: () -> AutoReconnect,
    private val now: () -> Long,
) {

    /** What to do about a link that has just gone. */
    sealed interface Decision {
        data class Retry(val afterMillis: Long) : Decision

        /** The window has run out. Say so once, and stop. */
        data object GaveUp : Decision

        /** Not our business: the surveyor asked to disconnect, or the setting is off. */
        data object LeaveItAlone : Decision
    }

    private var userRequestedDisconnect = false

    /** When the current run of attempts must give up; null when no run is in progress. */
    private var giveUpAt: Long? = null

    /** A retry that is due but has not been performed yet, or null. */
    private var retryAt: Long? = null

    /** Call when the surveyor asks to connect, so a later drop counts as unexpected. */
    fun noteUserRequestedConnect() {
        userRequestedDisconnect = false
        giveUpAt = null
        retryAt = null
    }

    /** Call when the surveyor asks to disconnect, so the instrument is left alone. */
    fun noteUserRequestedDisconnect() {
        userRequestedDisconnect = true
        cancel()
    }

    /**
     * Call once the instrument is properly usable, so the next failure starts a fresh window.
     *
     * The Java's own comment warns against calling this merely on connecting: a link that comes up
     * and immediately drops again would keep resetting the window and the app would never give up.
     * In this port that warning is already satisfied by the layer below —
     * [GattSession.subscriptionConfirmed] withholds `REPORT_CONNECTED` until every characteristic
     * has been found *and* every subscription confirmed — so `onConnected` here means ready, not
     * merely joined.
     */
    fun noteReady() {
        giveUpAt = null
        retryAt = null
    }

    /** Call when the instrument drops out, or an attempt to reach it fails. */
    fun onUnexpectedDisconnection(): Decision {
        val settings = settings()
        if (userRequestedDisconnect || !settings.enabled) return Decision.LeaveItAlone

        val now = now()
        val deadline = giveUpAt
        if (deadline == null) {
            // The first failure of a run starts the clock *and* is retried. So a window of zero
            // still buys one attempt, which is the Java's behaviour.
            giveUpAt = now + settings.windowMillis
        } else if (now >= deadline) {
            giveUpAt = null
            retryAt = null
            return Decision.GaveUp
        }

        retryAt = now + RETRY_INTERVAL_MILLIS
        return Decision.Retry(RETRY_INTERVAL_MILLIS)
    }

    /**
     * Whether the retry scheduled by the last [Decision.Retry] is now due, consuming it if so.
     *
     * Written this way rather than as a callback because the caller already has a tick — the same
     * one that ages out a connection attempt — and a policy that posts its own work is a policy
     * that cannot be tested.
     *
     * [linkIsBusy] puts a due retry off rather than spending it. Every transport in this port
     * refuses to start a second attempt over the top of a running one, and refuses *silently* —
     * so a retry fired at the wrong moment used to be a retry consumed for nothing, and since
     * nothing then failed, nothing scheduled another. One badly timed tick ended the chase for
     * good, which is exactly the shape of "it never came back and I could not get it back".
     */
    fun retryIsDue(linkIsBusy: Boolean = false): Boolean {
        val due = retryAt ?: return false
        if (now() < due) return false
        if (linkIsBusy) {
            retryAt = now() + RETRY_INTERVAL_MILLIS
            return false
        }
        retryAt = null
        return true
    }

    /**
     * Whether a run of attempts is under way, for the surveyor's benefit rather than the radio's.
     *
     * "Reconnecting" and "not connected" want to look different on a screen somebody is watching
     * in the dark to decide whether to walk back for the instrument.
     */
    val isChasing: Boolean
        get() = giveUpAt != null

    /** Call when the link is being torn down, to drop any pending attempt. */
    fun cancel() {
        giveUpAt = null
        retryAt = null
    }

    companion object {
        /** `RETRY_INTERVAL_MS` in the Java. */
        const val RETRY_INTERVAL_MILLIS = 3_000L
    }
}
