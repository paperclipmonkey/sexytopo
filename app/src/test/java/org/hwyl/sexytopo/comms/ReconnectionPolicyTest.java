package org.hwyl.sexytopo.comms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * The policy decides whether to chase a lost instrument, so the interesting cases are all about
 * timing: these drive a fake clock and a fake scheduler rather than waiting around.
 */
public class ReconnectionPolicyTest {

    private static final long WINDOW_MS = 15 * 60_000L;

    private FakeScheduler scheduler;
    private FakeSettings settings;
    private FakeClock clock;
    private int reconnectCount;
    private ReconnectionPolicy policy;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
        settings = new FakeSettings();
        clock = new FakeClock();
        reconnectCount = 0;
        policy =
                new ReconnectionPolicy(
                        "Test device", () -> reconnectCount++, scheduler, settings, clock);
    }

    @Test
    public void reconnectsAfterAnUnexpectedDrop() {
        policy.noteUserRequestedConnect();
        policy.noteReady();

        policy.onUnexpectedDisconnection();

        assertTrue(policy.isReconnecting());
        scheduler.runPending();
        assertEquals(1, reconnectCount);
    }

    @Test
    public void doesNothingWhenTheUserAskedToDisconnect() {
        policy.noteUserRequestedConnect();
        policy.noteReady();

        policy.noteUserRequestedDisconnect();
        policy.onUnexpectedDisconnection();

        assertFalse(policy.isReconnecting());
        scheduler.runPending();
        assertEquals(0, reconnectCount);
    }

    @Test
    public void doesNothingWhenAutoReconnectIsOff() {
        settings.autoReconnectOn = false;
        policy.noteUserRequestedConnect();

        policy.onUnexpectedDisconnection();

        assertFalse(policy.isReconnecting());
        scheduler.runPending();
        assertEquals(0, reconnectCount);
    }

    @Test
    public void keepsTryingWithinTheWindow() {
        policy.noteUserRequestedConnect();

        for (int i = 0; i < 10; i++) {
            policy.onUnexpectedDisconnection();
            scheduler.runPending();
            clock.advance(60_000L);
        }

        assertEquals(10, reconnectCount);
        assertTrue(policy.isReconnecting());
    }

    @Test
    public void givesUpOnceTheWindowHasElapsed() {
        policy.noteUserRequestedConnect();

        policy.onUnexpectedDisconnection(); // starts the clock
        scheduler.runPending();

        clock.advance(WINDOW_MS + 1);
        policy.onUnexpectedDisconnection();
        scheduler.runPending();

        assertEquals(1, reconnectCount);
        assertFalse(policy.isReconnecting());
    }

    /**
     * The point of a device that dozes off every ninety seconds: as long as it keeps coming back,
     * we keep chasing it, however long the trip lasts.
     */
    @Test
    public void aSuccessfulReconnectionStartsTheWindowAgain() {
        policy.noteUserRequestedConnect();

        for (int i = 0; i < 40; i++) {
            policy.onUnexpectedDisconnection();
            scheduler.runPending();
            clock.advance(WINDOW_MS - 1000); // nearly out of time...
            policy.noteReady(); // ...but the instrument came back
        }

        clock.advance(WINDOW_MS * 2);
        policy.onUnexpectedDisconnection();
        scheduler.runPending();

        assertEquals(41, reconnectCount);
        assertTrue(policy.isReconnecting());
    }

    /**
     * Communicators route our retry through the same method the Connect switch calls, so the policy
     * has to tell the two apart: otherwise every retry would restart the window and we'd never
     * stop.
     */
    @Test
    public void ourOwnRetryDoesNotRestartTheWindow() {
        ReconnectionPolicy[] self = new ReconnectionPolicy[1];
        self[0] =
                new ReconnectionPolicy(
                        "Test device",
                        () -> {
                            reconnectCount++;
                            self[0].noteUserRequestedConnect(); // as the communicators do
                        },
                        scheduler,
                        settings,
                        clock);

        self[0].noteUserRequestedConnect();
        self[0].onUnexpectedDisconnection(); // the window starts here
        scheduler.runPending();

        clock.advance(WINDOW_MS + 1);
        self[0].onUnexpectedDisconnection();
        scheduler.runPending();

        assertEquals(1, reconnectCount);
        assertFalse(self[0].isReconnecting());
    }

    @Test
    public void cancellingDropsAPendingAttempt() {
        policy.noteUserRequestedConnect();
        policy.onUnexpectedDisconnection();

        policy.cancel();
        scheduler.runPending();

        assertEquals(0, reconnectCount);
        assertFalse(policy.isReconnecting());
    }

    private static class FakeScheduler implements ReconnectionPolicy.Scheduler {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            pending.add(runnable);
        }

        @Override
        public void cancelAll() {
            pending.clear();
        }

        void runPending() {
            List<Runnable> toRun = new ArrayList<>(pending);
            pending.clear();
            for (Runnable runnable : toRun) {
                runnable.run();
            }
        }
    }

    private static class FakeSettings implements ReconnectionPolicy.Settings {

        boolean autoReconnectOn = true;
        long windowMs = WINDOW_MS;

        @Override
        public boolean isAutoReconnectOn() {
            return autoReconnectOn;
        }

        @Override
        public long getWindowMs() {
            return windowMs;
        }
    }

    private static class FakeClock implements ReconnectionPolicy.Clock {

        private long now = 1_000_000L;

        @Override
        public long now() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }
}
