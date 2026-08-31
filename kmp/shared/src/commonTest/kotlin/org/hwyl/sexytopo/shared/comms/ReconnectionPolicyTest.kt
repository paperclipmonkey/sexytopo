package org.hwyl.sexytopo.shared.comms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Java's `ReconnectionPolicy` owns a `Handler` and posts its own retries, so there is no way to
 * make time pass in a test and none of this is covered upstream. Splitting the decision from the
 * scheduling is what makes it checkable — and what it decides is whether a surveyor gets their
 * instrument back after walking round a corner with the phone.
 */
class ReconnectionPolicyTest {

    private var clock = 0L
    private var settings = AutoReconnect(enabled = true, windowMinutes = 15)

    private fun policy() = ReconnectionPolicy(settings = { settings }, now = { clock })

    private fun minutes(count: Int) {
        clock += count * 60_000L
    }

    @Test
    fun withTheSettingOffNothingIsChased() {
        settings = AutoReconnect(enabled = false)

        assertEquals(ReconnectionPolicy.Decision.LeaveItAlone, policy().onUnexpectedDisconnection())
    }

    @Test
    fun aDropIsRetriedThreeSecondsLater() {
        val policy = policy()

        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
        )
        assertFalse(policy.retryIsDue(), "a retry due in three seconds is not due now")
        clock += 2_999L
        assertFalse(policy.retryIsDue())
        clock += 1L
        assertTrue(policy.retryIsDue())
        assertFalse(policy.retryIsDue(), "one scheduled retry is one retry, not a loop")
    }

    /**
     * The window is measured from the *first* failure of a run, not the last.
     *
     * That is the whole design: an instrument left behind at the last station must not keep the
     * radio going all the way out of the cave. Measured from the last failure, three-second retries
     * would push the deadline back for ever.
     */
    @Test
    fun attemptsStopFifteenMinutesAfterTheFirstFailureAndNotTheLast() {
        val policy = policy()
        policy.onUnexpectedDisconnection()

        // Fourteen minutes of failing every few seconds: still trying.
        minutes(14)
        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
            "gave up inside its own window",
        )

        minutes(2)
        assertEquals(ReconnectionPolicy.Decision.GaveUp, policy.onUnexpectedDisconnection())
        clock += 10_000L
        assertFalse(policy.retryIsDue(), "still chasing after giving up")
    }

    /**
     * A run that succeeds resets the clock, so the next bad patch gets its own full window.
     *
     * Without this, an hour of ordinary surveying with one reconnection in it would leave the
     * policy unwilling to try again — which is the state a surveyor would meet exactly when they
     * had stopped thinking about it.
     */
    @Test
    fun anInstrumentThatComesBackStartsTheWindowAgain() {
        val policy = policy()
        policy.onUnexpectedDisconnection()
        policy.noteReady()

        minutes(50)
        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
        )
    }

    /** Putting the instrument away by hand means leaving it alone, not chasing it. */
    @Test
    fun aDisconnectTheSurveyorAskedForIsNotChased() {
        val policy = policy()
        policy.onUnexpectedDisconnection()
        policy.noteUserRequestedDisconnect()

        assertEquals(ReconnectionPolicy.Decision.LeaveItAlone, policy.onUnexpectedDisconnection())
        clock += 10_000L
        assertFalse(policy.retryIsDue(), "a retry survived the surveyor putting the instrument away")
    }

    /**
     * And pressing Connect part way through a losing run gets a fresh window rather than the
     * remains of the old one.
     *
     * This is what the Java's `retrying` flag exists to protect: its own retries go through the
     * same entry point the button does, so it has to tell them apart. Here they are separate
     * entry points and the flag is unnecessary — but the behaviour it was protecting is still
     * worth a test, because it is the behaviour a surveyor relies on when they give up waiting and
     * press the button themselves.
     */
    @Test
    fun pressingConnectByHandStartsTheWindowAgain() {
        val policy = policy()
        policy.onUnexpectedDisconnection()
        minutes(20)

        policy.noteUserRequestedConnect()

        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
            "the surveyor asked, and got the last run's exhausted window",
        )
    }

    /** And a connect by hand after putting it away is chased again. */
    @Test
    fun connectingAgainAfterPuttingItAwayIsChased() {
        val policy = policy()
        policy.noteUserRequestedDisconnect()
        policy.noteUserRequestedConnect()

        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
        )
    }

    /**
     * A window of zero still buys one attempt.
     *
     * The Java sets the deadline on the first failure and only *checks* it on the next one, so the
     * first drop of a run is always retried whatever the window says. Kept deliberately: the
     * commonest drop of all is a single blip, and one attempt fixes it.
     */
    @Test
    fun aWindowOfZeroStillTriesOnce() {
        settings = AutoReconnect(enabled = true, windowMinutes = 0)
        val policy = policy()

        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
        )
        assertEquals(ReconnectionPolicy.Decision.GaveUp, policy.onUnexpectedDisconnection())
    }

    /**
     * Turning the setting on mid-trip works without reconnecting first.
     *
     * The settings are read at each decision rather than captured, because the surveyor who wants
     * this is the one who has just been annoyed by not having it.
     */
    @Test
    fun theSettingIsReadWhenItMattersRatherThanRemembered() {
        settings = AutoReconnect(enabled = false)
        val policy = policy()
        assertEquals(ReconnectionPolicy.Decision.LeaveItAlone, policy.onUnexpectedDisconnection())

        settings = AutoReconnect(enabled = true)
        assertEquals(
            ReconnectionPolicy.Decision.Retry(3_000L),
            policy.onUnexpectedDisconnection(),
        )
    }
}
