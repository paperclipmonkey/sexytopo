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

    /**
     * A retry that comes due while the link is already busy is put off, not spent.
     *
     * Every transport here refuses to start a second attempt over the top of a running one, and
     * refuses silently. Spending the retry anyway meant nothing happened, nothing failed, and so
     * nothing scheduled another: one badly timed tick ended the chase for good.
     */
    @Test
    fun aRetryDueWhileTheLinkIsBusyIsKeptForLater() {
        val policy = policy()
        policy.onUnexpectedDisconnection()

        clock += 3_000L
        assertFalse(policy.retryIsDue(linkIsBusy = true), "connected over a running attempt")

        // Still held, rather than lost: it comes back one interval later.
        assertFalse(policy.retryIsDue(), "the held retry came due before its new time")
        clock += 3_000L
        assertTrue(policy.retryIsDue(), "the held retry was dropped rather than deferred")
    }

    /** And being busy does not extend the window: a link that never comes up is still given up on. */
    @Test
    fun holdingRetriesBackDoesNotPostponeGivingUp() {
        val policy = policy()
        policy.onUnexpectedDisconnection()

        repeat(400) { // twenty minutes at three seconds a go, against a fifteen-minute window
            clock += 3_000L
            policy.retryIsDue(linkIsBusy = true)
        }

        assertEquals(ReconnectionPolicy.Decision.GaveUp, policy.onUnexpectedDisconnection())
    }

    /** What the connection indicator asks: is the app dealing with this, or is it for me to? */
    @Test
    fun aRunOfAttemptsIsVisibleWhileItLasts() {
        val policy = policy()
        assertFalse(policy.isChasing, "chasing before anything had gone")

        policy.onUnexpectedDisconnection()
        assertTrue(policy.isChasing)

        policy.noteReady()
        assertFalse(policy.isChasing, "still chasing an instrument that came back")

        policy.onUnexpectedDisconnection()
        minutes(16)
        policy.onUnexpectedDisconnection()
        assertFalse(policy.isChasing, "still chasing after giving up")
    }
}
