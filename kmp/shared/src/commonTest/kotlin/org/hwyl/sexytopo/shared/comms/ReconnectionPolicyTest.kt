package org.hwyl.sexytopo.shared.comms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Java's `ReconnectionPolicy` owns a `Handler` and posts its own retries, so none of this is
 * covered upstream; splitting the decision from the scheduling is what makes it checkable.
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
     * The window is measured from the *first* failure of a run, not the last: measured from the
     * last, three-second retries would push the deadline back for ever.
     */
    @Test
    fun attemptsStopFifteenMinutesAfterTheFirstFailureAndNotTheLast() {
        val policy = policy()
        policy.onUnexpectedDisconnection()

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
     * A run that succeeds resets the clock, so the next bad patch gets its own full window rather
     * than the remains of an hour-old one.
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
     * Pressing Connect part way through a losing run gets a fresh window rather than the remains
     * of the old one — what the Java's `retrying` flag exists to protect, unnecessary here since
     * a manual connect and a scheduled retry are separate entry points.
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
     * A window of zero still buys one attempt: the Java sets the deadline on the first failure and
     * only *checks* it on the next one, so the first drop of a run is always retried.
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

    /** Settings are read at each decision rather than captured, so turning it on mid-trip works. */
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
